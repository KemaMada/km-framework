package com.example.keymessage.net

import com.km.crypto.Ed25519Impl
import com.km.node.FakeTransportBackend
import com.km.node.PeerTransportManager
import com.example.keymessage.identity.IdentityMaterialFactory
import com.example.keymessage.support.TestWorld
import io.ktor.client.engine.okhttp.OkHttpEngine
import okhttp3.OkHttpClient
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * 0.4 — El motor de p4 se MONTA; no se reescribe.
 *
 * ## QUE AFIRMA
 *
 * 1. `stack.backend === stack.transport`: el motor es a la vez `RelayTransport` y
 *    `TransportBackend`, y el manager usa ESE motor. Un `===` y no un `==`: dos
 *    motores distintos que "funcionan igual" son exactamente el CRITICAL #1 que
 *    cerro p4.
 * 2. La identidad con la que habla el rele es la identidad GUARDADA, no una
 *    derivada en la fabrica.
 * 3. Componer con un backend que NO es el motor LANZA. Es la garantia de que el
 *    ensamblaje mal hecho no se puede presentar como una pila que funciona.
 */
class RelayStackFactoryTest {

    private val material = IdentityMaterialFactory(Ed25519Impl()).fromSeed(ByteArray(32) { (it + 7).toByte() })

    @Test
    fun `la pila usa UN SOLO motor como backend y como transporte`() {
        val stack = RelayStackFactory.stackFor(material, "wss://relay.invalid/km")

        assertSame(
            stack.transport,
            stack.backend,
            "el backend del manager tiene que ser el MISMO motor, no una segunda fachada",
        )
        assertSame(
            stack.transport,
            stack.manager.backend(),
            "y el manager tiene que estar enlazado a ese motor",
        )
        stack.close()
    }

    @Test
    fun `la identidad de la pila es la identidad guardada`() {
        val stack = RelayStackFactory.stackFor(material, "wss://relay.invalid/km")

        assertEquals(
            material.identityId.value,
            stack.identity.value,
            "el rele ve la identidad que la app guardo, no una derivada aqui",
        )
        assertEquals(64, stack.identity.value.length, "un identityId son 64 hex")
        stack.close()
    }

    @Test
    fun `componer con un backend ajeno se RECHAZA`() {
        val motor = RelayStackFactory.engine(material, "wss://relay.invalid/km")
        val managerConOtroBackend = PeerTransportManager(FakeTransportBackend())

        val fallo = assertFailsWith<IllegalArgumentException> {
            RelayStackFactory.compose(motor, managerConOtroBackend)
        }
        assertTrue(
            fallo.message?.contains("backend") == true,
            "el motivo debe decir que el backend no es este motor: ${fallo.message}",
        )
        motor.close()
    }

    @Test
    fun `el endpoint resuelto es el que lleva el motor`() {
        // El endpoint NO se escribe a mano en la app: lo toma el resolvedor, que a
        // su vez cae en el default del motor de p4. Aqui se comprueba que el motor
        // lo acepta y lo refleja en su estado, que es lo que se puede ver sin
        // socket.
        val stack = RelayStackFactory.stackFor(material, "wss://otro-relay.invalid/km")
        val status = stack.transport.status()
        assertEquals("wss://otro-relay.invalid/km", status.endpoint, "el motor expone el endpoint que se le dio")
        assertEquals(material.identityId, status.identityId, "y la identidad del motor")
        stack.close()
    }

    @Test
    fun `el motor OkHttp manda ping cada 20 s - cota de deteccion 40 s`() {
        // R4/S7: el ping es la cota de liveness. OkHttp cierra si no hay PONG
        // dentro de OTRO intervalo, asi que 20 s de periodo son <= 40 s de
        // deteccion. Se afirma el periodo, no una promesa de 20 s.
        assertEquals(20_000, pingDelMotor().pingIntervalMillis, "produccion: ping cada 20 s")
        assertEquals(
            20_000L,
            RelayStackFactory.PING_INTERVAL_MS,
            "el default de la fabrica y el valor afirmado tienen que ser el MISMO",
        )
    }

    @Test
    fun `el intervalo del ping es parametrizable para medir la cota en segundos`() {
        // El test de integracion baja el periodo a 300 ms para medir la MISMA
        // relacion (2x) sin esperar 40 s. Si la fabrica ignorara el argumento,
        // ese test mediria una cota que el dispositivo no tiene.
        assertEquals(300, pingDelMotor(300).pingIntervalMillis)
    }

    @Test
    fun `un ping no positivo se RECHAZA en vez de desactivarlo en silencio`() {
        // pingInterval(0) en OkHttp significa "sin ping": el socket blackholeado
        // volveria a creerse ONLINE para siempre. Un 0 tiene que fallar aqui.
        assertFailsWith<IllegalArgumentException> {
            OkHttpClient.Builder().aplicarPing(0)
        }
    }

    /**
     * El cliente OkHttp REAL que la fabrica entrega al motor Ktor.
     *
     * Se lee del engine y no de un builder aparte a proposito: si la fabrica
     * dejara de aplicar el ping, este test lo veria (`0` ms), no una copia de la
     * configuracion que podria seguir diciendo 20 s.
     */
    private fun pingDelMotor(pingIntervalMs: Long = RelayStackFactory.PING_INTERVAL_MS): OkHttpClient {
        val cliente = RelayStackFactory.androidClient(pingIntervalMs)
        return try {
            requireNotNull((cliente.engine as OkHttpEngine).config.preconfigured) {
                "la fabrica no entrego un cliente OkHttp preconfigurado"
            }
        } finally {
            cliente.close()
        }
    }

    @Test
    fun `control el arnes genera claves distintas por dispositivo`() {
        // El pretesto de todo lo que viene: si la arnes fabricara el mismo par dos
        // veces, "dos identidades distintas" no probaria nada.
        val alice = TestWorld.deviceWithRoot("alice").device
        val bob = TestWorld.deviceWithRoot("bob").device
        assertTrue(!alice.signingKeyPair.publicKey.contentEquals(bob.signingKeyPair.publicKey), "claves de firma distintas")
        assertTrue(!alice.agreementKey.publicKey.contentEquals(bob.agreementKey.publicKey), "claves de acuerdo distintas")
    }
}