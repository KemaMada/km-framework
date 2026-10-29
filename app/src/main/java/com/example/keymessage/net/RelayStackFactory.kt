package com.example.keymessage.net

import com.km.model.IdentityId
import com.km.node.PeerTransportManager
import com.km.transport.JvmRelayTransport
import com.km.transport.RelayClientStack
import com.example.keymessage.identity.IdentityMaterial
import com.example.keymessage.util.KMLog
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.websocket.WebSockets
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/**
 * 0.4 — El motor de rele de p4, MONTADO. No reescrito.
 *
 * ## LA REGLA QUE ESTE FICHERO ENTIENE
 *
 * **La app no escribe su propio cliente de rele.** `JvmRelayTransport` es de
 * `km-transport` y es a la vez `RelayTransport` y `TransportBackend`;
 * `RelayClientStack.compose` es lo que ata el motor al `PeerTransportManager` y
 * rechaza un manager cuyo backend no sea ESTE motor. La app aporta tres cosas y
 * ninguna mas:
 *
 *  1. las claves de la identidad que ya tiene guardadas,
 *  2. el endpoint que ha resuelto la configuracion de arranque,
 *  3. el motor Ktor que se usa —OkHttp en Android, donde CIO no es el motor
 *     natural—, inyectado por [JvmRelayTransport.clientFactory].
 *
 * Reimplementar el cliente de rele fue el CRITICAL #1 que cerro p4: dos motores
 * de wire significan que los bytes y la presencia viajan por sitios distintos y
 * la divergencia es silenciosa. Este fichero no puede crecer por debajo de eso,
 * y `RelayStackFactoryTest` ata que `stack.backend === stack.transport`.
 */
object RelayStackFactory {

    /**
     * Motor Ktor de la app.
     *
     * `OkHttp` y no el `CIO` que trae km-transport por defecto: CIO esta pensado
     * para escritorio y en Android el motor natural es OkHttp, que ademas es el
     * que ya esta en el dispositivo. Se inyecta por `clientFactory`, que es el
     * seam que el motor deja para esto.
     *
     * ## LIVENESS: EL PING ES LA COTA (R4/S7)
     *
     * `pingInterval` es el ping RFC 6455 del motor. OkHttp manda un `PING` cada
     * [pingIntervalMs] y cierra el socket si no ve el `PONG` dentro de OTRO
     * intervalo: la deteccion de un socket blackholeado cae, como maximo, en
     * **2 x [pingIntervalMs]** (con el default de [PING_INTERVAL_MS], <= 40 s).
     * No se promete 20 s: el intervalo es el periodo de envio, no el plazo de
     * espera.
     *
     * La ventana residual se declara en la app: un mensaje enviado entre el
     * ultimo `ACK` y la deteccion puede perderse, porque el socket viejo ya no
     * entrega y el transporte todavia cree que si. El redial abre un socket
     * nuevo y el reintento del mensaje lo cubre; lo que no se puede es fingir
     * que esa ventana no existe.
     *
     * @param pingIntervalMs periodo del ping, en milisegundos. El test lo baja a
     *   300 ms para medir la MISMA relacion (2x) en segundos; produccion usa
     *   [PING_INTERVAL_MS].
     */
    fun androidClient(pingIntervalMs: Long = PING_INTERVAL_MS): HttpClient {
        // El cliente OkHttp se construye AQUI y se entrega como `preconfigured`:
        // asi el ping es una propiedad del cliente que el test puede leer desde
        // `engine.config.preconfigured`, en vez de una lambda opaca. Sin esa
        // superficie, "el ping esta puesto" no seria afirmable en JVM.
        val cliente = OkHttpClient.Builder().aplicarPing(pingIntervalMs).build()
        return HttpClient(OkHttp) {
            install(WebSockets)
            engine {
                preconfigured = cliente
            }
        }
    }

    /**
     * Periodo del ping en produccion.
     *
     * 20 s es el mismo valor que el default de `JvmRelayTransport`, y deja
     * la cota de deteccion en 2x = 40 s. Subirlo alarga la ventana en la
     * que un socket muerto se cree vivo; bajarlo gasta radio en una red
     * movil sin mejorar el redial, que ya existe.
     */
    const val PING_INTERVAL_MS: Long = 20_000L
    /**
     * Motor de rele de la identidad [material], hablando con [endpoint].
     *
     * @param log donde van las lineas del motor. Por defecto, al canal `[RELAY]`,
     *   que es donde la etapa ADB las espera.
     */
    fun engine(
        material: IdentityMaterial,
        endpoint: String,
        log: (String) -> Unit = { KMLog.event("[RELAY]", it) },
    ): JvmRelayTransport {
        KMLog.event("[RELAY]", "motor hacia $endpoint como ${KMLog.truncar(material.identityId.value)}")
        return JvmRelayTransport(
            identity = material.identityId,
            ed25519PublicKey = material.keyPair.publicKey,
            ed25519PrivateKey = material.keyPair.privateKey,
            endpoint = endpoint,
            clientFactory = { androidClient() },
            log = log,
        )
    }

    /**
     * Compone motor + manager + drivers en la pila de p4.
     *
     * No hay una variante que acepte un backend distinto: esa es la condicion
     * que `RelayClientStack.compose` impone, y envolverla aqui para "poder
     * probar" seria dejar una puerta que el framework ya cerro.
     */
    fun compose(transport: JvmRelayTransport, manager: PeerTransportManager): RelayClientStack =
        RelayClientStack.compose(transport, manager)

    /** Pila completa para una identidad y un endpoint. */
    fun stackFor(
        material: IdentityMaterial,
        endpoint: String,
        log: (String) -> Unit = { KMLog.event("[RELAY]", it) },
    ): RelayClientStack {
        val transport = engine(material, endpoint, log)
        return compose(transport, PeerTransportManager(transport))
    }

    /** La identidad con la que este motor habla con el rele. */
    fun identityOf(stack: RelayClientStack): IdentityId = stack.identity
}

/**
 * Aplica el ping RFC 6455 al builder de OkHttp.
 *
 * Es una funcion aparte porque un `OkHttpClient` CONSTRUIDO es lo unico que
 * expone `pingIntervalMillis`, y sin esa superficie "el ping esta puesto" no se
 * puede afirmar en un test de JVM. [RelayStackFactory.androidClient] la usa en
 * `engine.config`, de modo que el valor que se afirma es el que se aplica.
 */
internal fun OkHttpClient.Builder.aplicarPing(
    pingIntervalMs: Long = RelayStackFactory.PING_INTERVAL_MS,
): OkHttpClient.Builder = apply {
    require(pingIntervalMs > 0) { "el ping tiene que ser positivo: $pingIntervalMs" }
    pingInterval(pingIntervalMs, TimeUnit.MILLISECONDS)
}