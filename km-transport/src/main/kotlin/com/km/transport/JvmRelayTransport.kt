package com.km.transport

import com.km.auth.Base64Url
import com.km.auth.Dialect
import com.km.auth.DialectCheck
import com.km.auth.DialectNegotiation
import com.km.auth.TranscriptBuilder
import com.km.codec.JsonDecoder
import com.km.codec.JsonRelayControlCodec
import com.km.codec.RelayControlCodec
import com.km.crypto.Ed25519
import com.km.crypto.Ed25519Impl
import com.km.model.IdentityId
import com.km.model.Message
import com.km.model.RelayErrorCode
import com.km.model.RelayErrorNotice
import com.km.model.RelayExpiredNotice
import com.km.model.StoredReceipt
import com.km.node.LegacyInboundFrame
import com.km.node.RelayTransport
import com.km.node.TransportBackend
import com.km.node.TransportError
import com.km.node.TransportEventCallbacks
import com.km.node.TransportResult
import com.km.protocol.ConnectionState
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.websocket.DefaultClientWebSocketSession
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocketSession
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readText
import io.ktor.websocket.send
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.ClosedReceiveChannelException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.min
import kotlin.random.Random

/**
 * Backoff exponencial con tope y jitter completo.
 *
 * El jitter NO es decorativo: sin el, N clientes que pierden la conexion a la
 * vez la reconectan a la vez, y cada oleada vuelve a tumbar al rele que acaba de
 * recuperarse. El tope de 30 s es el de KM-WIRE-RELAY seccion 18.1, I17.
 */
class ReconnectPolicy(
    val initialDelayMs: Long = 1_000,
    val maxDelayMs: Long = 30_000,
    val multiplier: Double = 2.0,
    private val random: Random = Random.Default,
) {
    /** Retardo del reintento numero [attempt], empezando en 0. */
    fun delayFor(attempt: Int): Long {
        require(attempt >= 0) { "el intento no puede ser negativo" }
        val capped = min(initialDelayMs * Math.pow(multiplier, attempt.toDouble()), maxDelayMs.toDouble())
        // Jitter completo: el retardo real cae en [capped/2, capped].
        return (capped / 2.0 + random.nextDouble() * capped / 2.0).toLong()
    }
}

/** Instantanea del transporte para la pantalla de la TUI. */
data class RelayStatus(
    val endpoint: String,
    val state: ConnectionState,
    val identityId: IdentityId,
    val sessionId: String? = null,
    val relayIdentityId: String? = null,
    val reconnectAttempt: Int = 0,
    val nextRetryInMs: Long? = null,
    val lastError: String? = null,
)

/**
 * Transporte de rele sobre Ktor Client WebSocket, hablando KM-WIRE-RELAY.
 *
 * Implementa `com.km.node.RelayTransport` y NO escribe criptografia de
 * protocolo: la firma del `AUTH_RESPONSE` sale de `Ed25519` de `km-core` sobre el
 * transcript canonico que construye `TranscriptBuilder` (seccion 9.5 del
 * documento). Aqui no hay ninguna regla de KM-0002 reescrita.
 *
 * QUE HACE EL RELE Y QUE HACE ESTE
 *
 * El rele es opaco: mueve `data` de un `MESSAGE` de un emisor a un destinatario
 * sin interpretarlo, y comprueba que `from` sea el peer autenticado. Por eso este
 * transporte no reenvia nada en nombre de un tercero: `send` siempre pone
 * `from` = identidad local. Un peer sin autenticar no tiene camino de datos, y por
 * eso un `MESSAGE` recibido antes del `AUTH_OK` no puede entregarse a nadie.
 *
 * ESTADOS
 *
 * ```
 * DISCONNECTED --connect()--> CONNECTING --AUTH_OK--> ONLINE
 *      ^                            |                     |
 *      |                            | fallo / cierre      | cierre
 *      +----------------------------+---------------------+
 *                                    RECONNECTING
 * ```
 *
 * `connect()` es SINCRONO porque `Transport` lo declara asi, y espera al
 * `AUTH_OK` con el limite de [handshakeTimeoutMs]. Despues, el ciclo de vida de
 * la sesion corre en su propio `CoroutineScope`.
 */
class JvmRelayTransport(
    private val identity: IdentityId,
    private val ed25519PublicKey: ByteArray,
    private val ed25519PrivateKey: ByteArray,
    /**
     * Etiqueta del transporte, para diagnostico.
     *
     * `Transport` no la declara; `TransportBackend` si, con un default, y el
     * constructor no puede usar un valor por defecto de una interface. Por eso
     * es `override val`: el MISMO nombre en las dos caras, y el que escribe
     * `RelayClientStack` en un `RELAY_ERROR` es el que sale en un
     * `TransportChain`.
     */
    override val name: String = "relay",
    private val endpoint: String = DEFAULT_ENDPOINT,
    /**
     * Identidad del rele a la que se ANUNCIA, fijada por el operador.
     *
     * Va en el `AUTH_REQUEST` como `responderIdentityId` (seccion 9.2). Es
     * opcional aqui por una razon concreta y comprobada contra el `km-daemon`
     * desplegado: `AuthAdapter.begin` rechaza cuando el campo viene informado y
     * NO coincide, y una cadena vacia CUENTA como informado. O sea, que
     * anunciando "" el rele responde `AUTH_FAIL RELAY_IDENTITY_MISMATCH`.
     *
     * Por eso, si no se fija, el campo se OMITE: es lo unico que un cliente sin
     * informacion previa puede hacer sin fallar. En cuanto llega el
     * `AUTH_CHALLENGE`, su `responderIdentityId` se guarda y las reconexiones
     * siguientes ya lo anuncian, de modo que quedan fijadas desde la segunda.
     */
    private val relayIdentity: IdentityId? = null,
    private val ed25519: Ed25519 = Ed25519Impl(),
    private val codec: RelayDataCodec = RelayDataCodec(),
    private val controlCodec: RelayControlCodec = JsonRelayControlCodec(),
    private val reconnect: ReconnectPolicy = ReconnectPolicy(),
    private val handshakeTimeoutMs: Long = 20_000,
    private val externalScope: CoroutineScope? = null,
    private val clientFactory: () -> HttpClient = ::defaultClient,
    private val log: (String) -> Unit = {},
    private val now: () -> Long = System::currentTimeMillis,
) : RelayTransport, TransportBackend {

    private val stateRef = AtomicReference(ConnectionState.DISCONNECTED)
    /**
     * El MISMO estado que [stateRef], como flujo, para quien necesita ESPERARLO.
     *
     * `stateRef` es la fuente de verdad (lo leen `send`, `isPeerPresent` y la
     * TUI sin suscribirse a nada); este flujo es la cara OBSERVABLE de esa misma
     * verdad y la escribe [setState], que es el UNICO sitio que cambia el
     * estado. Dos escritores distintos volverian a abrir la puerta a un espejo
     * que miente, que es justo lo que este motor ya pago una vez.
     *
     * Existe para [awaitOnline]: esperar un cambio de estado sin sondear y sin
     * perder la transicion intermedia.
     */
    private val stateFlow = MutableStateFlow(ConnectionState.DISCONNECTED)
    /**
     * El cierre como EVENTO observable, para despertar a [awaitOnline].
     *
     * `stopping` es un `AtomicBoolean` porque `connect`/`disconnect` lo leen
     * desde cualquier hilo; un `StateFlow` aparte no duplica esa decision, solo
     * la publica para que un `combine` pueda reaccionar a ella. Sin esto, un
     * `disconnect()` durante una espera dejaria al que espera colgado hasta el
     * timeout aunque el motor ya supiera que no va a haber `ONLINE`.
     */
    private val stopFlow = MutableStateFlow(false)
    private val sessionIdRef = AtomicReference<String?>(null)
    private val relayIdentityRef = AtomicReference<String?>(null)
    /**
     * Dialecto confirmado por el `AUTH_CHALLENGE` de ESTE handshake, si lo
     * hubo. El `AUTH_OK` debe confirmar el mismo; si no, se rechaza. Se limpia
     * al empezar cada handshake nuevo.
     */
    private val challengeDialectRef = AtomicReference<Dialect?>(null)
    private val attemptRef = AtomicInteger(0)
    private val nextRetryRef = AtomicLong(0)
    private val lastErrorRef = AtomicReference<String?>(null)
    private val stopping = AtomicBoolean(false)

    /**
     * Epoca del redial. Invalida los trabajos de reconexion ANTERIORES.
     *
     * ## POR QUE UNA EPOCA Y NO `supervisor.cancel()`
     *
     * `scheduleReconnect` lanza un `delay` dentro de `ownedScope`: cancelar el
     * `supervisor` —que es el job de la sesion— NO cancela ese `delay`. Un
     * redial que se limite a cancelar la sesion deja vivo el reintento
     * diferido del backoff, y a su hora aparece un SEGUNDO handshake compitiendo
     * con el redial. El rele acepta un challenge por identidad y el perdedor
     * devuelve `AUTH_FAILED INVALID_SIGNATURE`.
     *
     * Cada trabajo captura la epoca al lanzarse y, al despertar, aborta si ya
     * no es la vigente. Es la unica invalidacion que un `delay` respeta.
     */
    private val redialEpoch = AtomicLong(0)

    /**
     * Single-flight de la renegociacion FORZADA de la sesion (R3 y D2).
     *
     * `true` desde que [forceRedial] —o [abandonExpiredSession], cuando el rele
     * declara muerta la sesion estando `ONLINE`— toma el mando hasta que el
     * `AUTH_OK` lo consume, o hasta que el intento falla y cede al backoff
     * normal. Mientras esta puesto, un segundo [forceRedial] es un no-op con
     * exito: el evento de red de mas no abre otro socket. Y un segundo
     * `RELAY_ERROR` de la sesion que se esta abandonando tampoco: dos avisos
     * seguidos —dos `SIGNAL` en vuelo, por ejemplo— producen UN `AUTH`, no dos.
     *
     * Y es la guarda que `onSessionClosed` mira: el cierre que provoca la
     * propia renegociacion NO puede forzar `DISCONNECTED` ni encolar otro
     * reintento.
     */
    private val redialing = AtomicBoolean(false)

    private val ownedScope: CoroutineScope = externalScope
        ?: CoroutineScope(SupervisorJob() + Dispatchers.IO + CoroutineName("km-relay"))

    @Volatile
    private var client: HttpClient? = null

    @Volatile
    private var active: SessionRunner? = null

    @Volatile
    private var supervisor: Job? = null

    // Handlers. Todos arrancan como no-op para que una llegada antes de
    // registrarlos no sea una excepcion: el core los registra en su `init`, y
    // un `PEER_ONLINE` puede llegar antes de que la TUI los muestre.
    private var messageHandler: (ByteArray) -> Unit = {}
    private var ackHandler: (ByteArray) -> Unit = {}
    private var storedHandler: (StoredReceipt) -> Unit = {}
    private var relayExpiredHandler: (RelayExpiredNotice) -> Unit = {}
    private var stateHandler: (ConnectionState) -> Unit = {}
    //
    // p4: `onPeerOnline`/`onPeerOffline` pasan a MULTI-suscriptor (lista). Antes
    // eran un unico lambda, de modo que el segundo que se registrara pisaba al
    // primero —y en el escritorio actual eso ya pasaba: `Session.bind` se
    // registra DESPUES de `KeyMessageCoreImpl`, luego el flush por presencia
    // estaba muerto y nadie se enteraba. Con lista, un consumidor mas ya no
    // puede matar a otro, y el test que lo fija son dos suscriptores.
    private val peerOnlineHandlers = java.util.concurrent.CopyOnWriteArrayList<(IdentityId) -> Unit>()
    private val peerOfflineHandlers = java.util.concurrent.CopyOnWriteArrayList<(IdentityId) -> Unit>()

    /**
     * Suscriptores de `SIGNAL` entrante. MULTI-suscriptor, como los de presencia.
     *
     * Una senal no es un mensaje: no la consume el nucleo, la consume quien
     * sepa que hacer con ella (en la app, el registro del contacto). Con un
     * unico lambda, el segundo que se registrara pisaba al primero.
     */
    private val signalHandlers = java.util.concurrent.CopyOnWriteArrayList<(DecodedSignal) -> Unit>()

    /**
     * Suscriptores de `RELAY_ERROR`. MULTI-suscriptor, como los de presencia.
     *
     * El `RELAY_ERROR` es la unica respuesta del rele a una `SIGNAL` que no le
     * gusta, y es la que decide el veredicto de un envio: sin ella, el emisor
     * no puede distinguir "el par estaba offline" de "el rele me rechazo".
     */
    private val relayErrorHandlers = java.util.concurrent.CopyOnWriteArrayList<(RelayErrorNotice) -> Unit>()

    /**
     * Los `RELAY_ERROR` que han llegado y nadie ha recogido todavia.
     *
     * Es una COLA y no un `lastError`: un error de hace un minuto no es el
     * veredicto de la senal de ahora. El sink la vacia ANTES de enviar, de
     * modo que un error viejo no se atribuya a una senal nueva.
     */
    private val relayErrors: java.util.concurrent.ArrayBlockingQueue<RelayErrorNotice> =
        java.util.concurrent.ArrayBlockingQueue(8)

    /**
     * Pares PRESENTES segun el ultimo `PEER_ONLINE`/`PEER_OFFLINE` recibido.
     *
     * Es lo que responde a [onCreateBinding] y lo que dispara
     * [RelayPeerChannelDriver]. Se mantiene como ESTADO y no como Derived de los
     * handlers, porque "que pares hay" es un hecho del rele y no un efecto de
     * quien se suscriba: con un solo handler, un consumidor que se daba de baja
     * se llevaba el estado consigo.
     */
    private val onlinePeers: MutableSet<IdentityId> =
        java.util.Collections.newSetFromMap(java.util.concurrent.ConcurrentHashMap())

    // ------------------------------------------------------------------
    // Transport
    // ------------------------------------------------------------------

    override fun connect(): Result<Unit> {
        if (stateRef.get() == ConnectionState.ONLINE) return Result.success(Unit)

        stopping.set(false)
        stopFlow.value = false
        attemptRef.set(0)
        lastErrorRef.set(null)
        ensureClient()

        return runBlocking {
            when (val outcome = awaitHandshake()) {
                null -> fail("el handshake no completo en ${handshakeTimeoutMs}ms", active)
                else -> outcome
            }
        }
    }

    override fun disconnect() {
        stopping.set(true)
        // Publica el cierre ANTES de tocar el resto: un `awaitOnline` en vuelo
        // despierta por el `false`, no por el timeout.
        stopFlow.value = true
        // Invalida cualquier reintento diferido ANTES de soltar la sesion: si no,
        // un `connect()` posterior rearma `stopping=false` y el trabajo viejo,
        // que ya paso el `delay`, encontraria la puerta abierta y abriria un
        // segundo socket.
        redialEpoch.incrementAndGet()
        redialing.set(false)
        supervisor?.cancel()
        supervisor = null
        active?.close()
        active = null
        client?.close()
        client = null
        sessionIdRef.set(null)
        relayIdentityRef.set(null)
        onlinePeers.clear()
        nextRetryRef.set(0L)
        setState(ConnectionState.DISCONNECTED)
    }

    /**
     * Redial FORZADO por un cambio de red (R1/R2/R3).
     *
     * ## POR QUE NO SIRVE `connect()`
     *
     * `connect()` sale inmediatamente si el estado es `ONLINE` —y ante un
     * cambio de red que NO cierra el socket, el estado ES `ONLINE` aunque el
     * byte no llegue (#349, PROBE-B)—. No hay otra API de "reconectar ya": este
     * metodo es esa puerta.
     *
     * ## QUE HACE, EN ESTE ORDEN
     *
     *  1. Toma el single-flight: con `redialing` ya puesto devuelve exito sin
     *     hacer nada. Dos eventos de red, un `AUTH`.
     *  2. Sube la epoca: cualquier reintento diferido anterior queda invalidado
     *     al despertar.
     *  3. Limpia la vista de presencia de la sesion que se va (R5). El snapshot
     *     del rele la re-anuncia en el `AUTH_OK` nuevo.
     *  4. Pone `RECONNECTING` y CIERRA la sesion activa. El orden importa:
     *     `redialing` se pone ANTES del cierre porque el `finally` del bucle de
     *     lectura llama a `onSessionClosed` en otro hilo, y si lo viera a
     *     `false` forzaria `DISCONNECTED` y encolaria un segundo reintento.
     *  5. Programa el handshake inmediato, que re-hace `AUTH` completo.
     *
     * ## LO QUE NO HACE
     *
     * No toca X3DH/PREKEY ni el ratchet (R2/R12): la sesion E2E es de `km-core`
     * y el redial solo repara el socket de rele. El cifrado se reenvia verbatim.
     *
     * No bloquea: el cierre del socket corre en `ownedScope`. Un socket
     * blackholeado puede tardar en cerrar, y el llamante —el callback de
     * `ConnectivityManager`— no puede quedarse esperandolo.
     *
     * @return fallo si el transporte esta detenido; exito (incluso si ya habia
     *   un redial en vuelo) en cualquier otro caso.
     */
    fun forceRedial(): Result<Unit> {
        if (stopping.get()) {
            return Result.failure(IllegalStateException("el transporte esta parado: no hay redial"))
        }
        if (!redialing.compareAndSet(false, true)) {
            log("redial ya en vuelo: el evento repetido se colapsa")
            return Result.success(Unit)
        }

        lastErrorRef.set(null)
        redialEpoch.incrementAndGet()
        onlinePeers.clear()
        sessionIdRef.set(null)
        attemptRef.set(0)
        nextRetryRef.set(0L)
        setState(ConnectionState.RECONNECTING)

        // Se suelta la referencia ANTES de cerrar: `completeReady` y `sendFrame`
        // miran `active`, y una sesion que se esta yendo no puede recibir ni el
        // `ready` ni un byte de la nueva.
        val previa = active
        active = null
        if (previa != null) {
            ownedScope.launch { previa.closeSuspend("redial") }
        }

        val epoca = redialEpoch.get()
        log("redial forzado por cambio de red (epoca=$epoca)")
        scheduleReconnect(immediate = true, epoch = epoca)
        return Result.success(Unit)
    }

    /**
     * Envia la carga opaca de [data] a [to].
     *
     * `data` es un `Message` ya codificado por `km-core`. El rele no
     * interpreta la carga: la mueve byte a byte (secciones 6.1 y 6.2).
     *
     * Un `Ack` NO viaja por aqui: va por [sendAck], porque este metodo fija el
     * discriminante a `MESSAGE`.
     */
    override fun send(data: ByteArray, to: IdentityId): Result<Unit> = sendFrame(data, to, ack = false)

    /**
     * `ACK` saliente por el frame `ACK`, NO por un `MESSAGE`. F2/D1.
     *
     * Por que es un metodo aparte y no un caso dentro de [send]: [send] fija el
     * discriminante a `MESSAGE`. Un `ACK` que sale por ahi llega al otro
     * extremo disfrazado de mensaje entrante, lo entrega a `messageHandler`, y
     * ese mensaje entra por `ReceiverImpl`, que responde con OTRO `ACK`. El
     * ciclo de amplificacion, ademas de perder el `messageId` original en el
     * envoltorio.
     *
     * El envelope lo pone `RelayDataCodec.encodeAck`, que escribe el
     * discriminante `ACK` y conserva el `originalMessageId` del `Ack` que
     * firmo el receptor.
     */
    override fun sendAck(ackBytes: ByteArray): Result<Unit> = sendFrame(ackBytes, ack = true)

    /**
 * Envia [data] por el frame que le corresponde a [ack].
 *
 * `to` es obligatorio solo para un `MESSAGE`: el `ACK` ya lleva su destinatario
 * dentro del envelope, asi que no hay a quien volver a escribirlo. Por eso es
 * nulable y no tiene valor por defecto — un `send()` sin destinatario tiene que
 * fallar al compilar, no en produccion.
 */
private fun sendFrame(data: ByteArray, to: IdentityId? = null, ack: Boolean): Result<Unit> {
        if (stateRef.get() != ConnectionState.ONLINE) {
            return Result.failure(IllegalStateException("transporte no conectado"))
        }
        if (data.size > MAX_DATA_PAYLOAD_BYTES) {
            // Limite del rele (seccion 15.1). Se comprueba aqui para no gastar
            // un Base64 del triple de tamano en un envio que sera rechazado.
            return Result.failure(
                IllegalArgumentException("payload de ${data.size}B excede $MAX_DATA_PAYLOAD_BYTES B")
            )
        }
        val runner = active
            ?: return Result.failure(IllegalStateException("no hay sesion WebSocket activa"))
        val frame = runCatching {
            if (ack) {
                // El `Ack` llega en el vocabulario de `km-core`, que es donde lo
                // firmo el receptor. Se devuelve al modelo para que el envelope
                // del cable lo escriba `encodeAck` sin reimplementar campos.
                codec.encodeAck(JsonDecoder().decodeAck(data).getOrThrow())
            } else {
                // R1.1: el `messageId` del envelope es el de la carga, no uno
                // nuevo. `encodeMessage` lo lee de la propia carga, asi que
                // pasar `data` es lo unico que hace falta: lo que sale por el
                // cable lleva el identificador contra el que el receptor va a
                // firmar su `ACK`.
                codec.encodeMessage(identity, requireNotNull(to) { "send() exige destinatario" }, data)
            }
        }.getOrElse { return Result.failure(it) }
        return runCatching { runBlocking { runner.sendText(frame) } }
    }

    override fun setMessageHandler(handler: (ByteArray) -> Unit) { messageHandler = handler }
    override fun setAckHandler(handler: (ByteArray) -> Unit) { ackHandler = handler }
    override fun setStoredHandler(handler: (StoredReceipt) -> Unit) { storedHandler = handler }
    override fun setRelayExpiredHandler(handler: (RelayExpiredNotice) -> Unit) { relayExpiredHandler = handler }
    override fun onStateChange(handler: (ConnectionState) -> Unit) { stateHandler = handler }
    override fun onPeerOnline(handler: (IdentityId) -> Unit) { peerOnlineHandlers.add(handler) }
    override fun onPeerOffline(handler: (IdentityId) -> Unit) { peerOfflineHandlers.add(handler) }

    // ------------------------------------------------------------------
    // Senalizacion (extension del dialecto)
    // ------------------------------------------------------------------

    /**
     * Se suscribe a las senales entrantes.
     *
     * MULTI-suscriptor: uno mas no puede matar al otro. El mismo criterio que
     * sostiene [onPeerOnline], y por el mismo motivo: el segundo consumidor se
     * registro DESPUES del primero y lo piso (p4).
     */
    fun onSignal(handler: (DecodedSignal) -> Unit) { signalHandlers.add(handler) }

    /**
     * Da de baja un consumidor de senales.
     *
     * ## Por que existe
     *
     * El motor es UNO por proceso y `signalHandlers` no tenia baja: cada
     * establecimiento P2P (uno por intento, con reintento cada 60 s) dejaba su
     * handler vivo para siempre. Ademas de crecer sin limite, un handler viejo
     * seguia procesando senales de un par ya cerrado. La baja la usa
     * `RelaySignalPort.onSignal`, que devuelve la funcion de cancelacion.
     *
     * Quitar un handler ya ausente es un no-op (`CopyOnWriteArrayList`), de
     * modo que cancelar dos veces —`abort()` es idempotente— no falla.
     */
    fun removeSignalHandler(handler: (DecodedSignal) -> Unit) { signalHandlers.remove(handler) }

    /**
     * Envia una `SIGNAL` a [to].
     *
     * ## EL `from` ES SIEMPRE LA IDENTIDAD LOCAL
     *
     * No es un parametro: es una decision. El rele autentica el `from` contra
     * la sesion y rechaza con `RELAY_NOT_AUTHORIZED` cualquier `from` que no sea
     * el peer conectado, y `RelayServer.handleSignal` compara el `from` con la
     * sesion para el anti-spoofing. Un `from` escribible por el llamante seria
     * una firma falsificable.
     *
     * ## POR QUE NO SE ENVIA A SI MISMO
     *
     * Una `SIGNAL` a la identidad local no la va a leer nadie: el rele la
     * enruta a la sesion del propio emisor, que ya esta autenticada, y su
     * `to` no es un par. Se rechaza con `SIGNAL_TO_SELF` y no se escribe en el
     * cable: un envio que no puede llegar a nadie no debe gastar un intento de
     * la politica de reintentos.
     */
    fun sendSignal(
        to: IdentityId,
        kind: String,
        contactBundle: ByteArray? = null,
        /**
         * SDP de `sdp_offer`/`sdp_answer`.
         *
         * Va DESPUES de [contactBundle] a proposito: las llamadas existentes
         * pasan el portador como tercer argumento posicional, y mover ese
         * parametro romperia a todos los consumidores sin ganar nada. Los
         * llamantes nuevos usan argumentos con nombre.
         */
        sdp: String? = null,
        /** Candidato ICE de `ice_candidate`. */
        candidate: String? = null,
    ): Result<Unit> {
        if (stateRef.get() != ConnectionState.ONLINE) {
            return Result.failure(IllegalStateException("transporte no conectado"))
        }
        if (to == identity) {
            log("SIGNAL descartado: no se envia a la propia identidad (SIGNAL_TO_SELF)")
            return Result.failure(IllegalStateException("una SIGNAL no se envia a la identidad local"))
        }
        val frame = runCatching { codec.encodeSignal(identity, to, kind, contactBundle, sdp, candidate) }
            .getOrElse { return Result.failure(it) }
        val runner = active
            ?: return Result.failure(IllegalStateException("no hay sesion WebSocket activa"))
        return runCatching { runBlocking { runner.sendText(frame) } }
    }

    /**
     * Un `SIGNAL` entrante, YA decodificado.
     *
     * `internal` y no privado a proposito: la regla de R1 —"un `kind` desconocido
     * se ignora y la conexion sigue viva"— es del TRANSPORTE, y se comprueba
     * sobre el transporte, sin abrir un socket. La regla de "el `to` que no es
     * esta identidad se descarta" tambien: es la que impide que una senal
     * dirigida a otro par se entregue aqui.
     */
    internal fun deliverSignal(frame: JsonObject) {
        when (val decoded = codec.decodeSignal(frame)) {
            is DecodedSignal.Invalid -> log("SIGNAL descartado codigo=${decoded.code}")
            is DecodedSignal.UnknownKind -> {
                // R1: se ignora CON RAZON y la conexion sigue viva.
                log("SIGNAL ignorado codigo=${RelayDataCodec.CODE_SIGNAL_UNKNOWN_KIND} kind=${decoded.kind}")
            }
            is DecodedSignal.Known -> {
                if (decoded.to != identity) {
                    log("SIGNAL descartado codigo=SIGNAL_TO_OTHER_IDENTITY")
                    return
                }
                signalHandlers.forEach { handler ->
                    runCatching { handler(decoded) }
                        .onFailure { e -> log("handler de SIGNAL fallo: ${e.message}") }
                }
            }
        }
    }

    /** Identidad de los pares que el rele ha declarado presentes. */
    fun onlinePeerIds(): Set<IdentityId> = onlinePeers.toSet()

    override fun isOnline(): Boolean = stateRef.get() == ConnectionState.ONLINE

    override fun connectionState(): ConnectionState = stateRef.get()

    /**
     * Espera, ACOTADA y CANCELABLE, a que el motor vuelva a `ONLINE`.
     *
     * ## POR QUE ES UNA ESPERA PASIVA
     *
     * Porque el motor YA se renegocia solo cuando el rele declara la sesion
     * muerta ([abandonExpiredSession] con el backoff de [ReconnectPolicy]). Lo
     * unico que falta es que quien tenia una senal en vuelo se ENTERE de que hay
     * sesion nueva. Forzar aqui un redial seria abrir un segundo `AUTH`
     * compitiendo con el que ya esta en camino: el rele acepta un challenge por
     * identidad y el perdedor recibe `AUTH_FAILED INVALID_SIGNATURE`. Por eso
     * este metodo no llama a [forceRedial] ni a [connect].
     *
     * ## QUE DEVUELVE
     *
     *  - `true` si el motor esta `ONLINE` al entrar, o llega a estarlo antes del
     *    plazo.
     *  - `false` si el plazo vence, si [disconnect] ocurre durante la espera, o
     *    si el motor ya estaba parado.
     *
     * Nunca "casi": un estado `RECONNECTING` al vencer el plazo NO es `true`.
     *
     * ## POR QUE UN `combine` Y NO UN SONDEO
     *
     * Un sondeo puede perder la transicion —`RECONNECTING -> ONLINE ->
     * RECONNECTING` entre dos lecturas— y hacer que el llamante concluya que no
     * hubo sesion cuando si la hubo un instante. `first` sobre el flujo observa
     * cada valor. Y la cancelacion del `Job` llamante desuscribe el flujo sola:
     * no queda ninguna continuacion esperando.
     *
     * @param timeoutMs plazo maximo en milisegundos. `<= 0` no espera.
     */
    suspend fun awaitOnline(timeoutMs: Long): Boolean {
        if (stateRef.get() == ConnectionState.ONLINE) return true
        if (stopping.get()) return false
        if (timeoutMs <= 0L) return false
        return withTimeoutOrNull(timeoutMs) {
            combine(stateFlow, stopFlow) { estado, parado -> estado to parado }
                .first { (estado, parado) -> parado || estado == ConnectionState.ONLINE }
            // La condicion que se observo puede haber cambiado entre el `first`
            // y esta linea: lo que se responde es el hecho de AHORA.
            !stopFlow.value && stateRef.get() == ConnectionState.ONLINE
        } ?: false
    }

    /** Instantanea para la pantalla de la TUI. */
    fun status(): RelayStatus = RelayStatus(
        endpoint = endpoint,
        state = stateRef.get(),
        identityId = identity,
        sessionId = sessionIdRef.get(),
        relayIdentityId = relayIdentityRef.get(),
        reconnectAttempt = attemptRef.get(),
        nextRetryInMs = if (nextRetryRef.get() == 0L) null else (nextRetryRef.get() - now()).coerceAtLeast(0),
        lastError = lastErrorRef.get(),
    )

    /** Cierra el transporte. Un scope externo NO se cancela: es de quien lo paso. */
    fun close() {
        disconnect()
        if (externalScope == null) ownedScope.cancel()
    }

    // ------------------------------------------------------------------
    // TransportBackend — LA SEGUNDA CARA, SOBRE EL MISMO OBJETO
    // ------------------------------------------------------------------
    //
    // `RelayTransport` (arriba) y `TransportBackend` (aqui) son DOS interfaces
    // sobre ESTE MISMO objeto, no dos objetos. Es la unica forma de que no
    // puedan divergir: las dos caras leen y escriben [stateRef], [codec],
    // [identity] y [sessionIdRef], y ninguna tiene socket propio. Dos fachadas
    // sobre un motor compartido —la alternativa que se descarto— habrían
    // permitido cablear cada cara a una instancia distinta, con bytes por un
    // socket y presencia por ninguno, sin que nada fallara.
    //
    // `RelayClientStack` exige `backend === transport` justamente para atar esa
    // garantia con un test.

    /**
     * Callbacks de recepcion del backend.
     *
     * NO se reasigna despues de construir el objeto, y NADIE debe hacerlo: es
     * un `var` con semantica de ASIGNACION, de modo que asignar un
     * `TransportEventCallbacks()` fresco borra lo que hubiera —incluido el cable
     * de recepcion que `PeerTransportManager` instala desde su `init`—. Todo el
     * mundo compone con `.copy()`.
     */
    override var eventCallbacks: TransportEventCallbacks = TransportEventCallbacks()

    /**
     * Prepara el cliente HTTP. NO conecta y NO autentica.
     *
     * La distincion es normativa y es la que hace que la doble fachada sea
     * segura: `connect()` —cara [RelayTransport]— es la UNICA entrada que abre
     * un socket y hace `AUTH`. `initialize()` lo llama
     * `PeerTransportManager` en su constructor, es decir ANTES de que ningun
     * consumidor haya decidido salir a la red. Si `initialize()` abriera el
     * socket, construir un manager seria conectar.
     */
    override fun initialize(): TransportResult<Unit> {
        if (client == null) ensureClient()
        return TransportResult.Success(Unit)
    }

    /**
     * El rele no negocia: "crear binding" es que el par este PRESENTE.
     *
     * No es una comprobacion vacia por cortesia. Devolver
     * [TransportError.PEER_NOT_ONLINE_AT_RELAY] cuando el par no esta hace que
     * un `TransportChain` pueda distinguir "este medio no lo alcanza" de "este
     * medio sirve", que es la unica pregunta que `permitsFallback` intenta
     * contestar.
     */
    override fun onCreateBinding(localIdentity: IdentityId, remotePeerId: IdentityId): TransportResult<Unit> =
        if (isPeerPresent(remotePeerId)) {
            TransportResult.Success(Unit)
        } else {
            TransportResult.Failure(
                TransportError.PEER_NOT_ONLINE_AT_RELAY,
                "${remotePeerId.value.take(8)}… no esta presente en el rele",
            )
        }

    /**
     * SDP: no-op DOCUMENTADO, no no-op por descuido.
     *
     * El rele no tiene PeerConnection, no hace oferta/respuesta y no genera
     * candidatos ICE. Lo que si hace el enlazar el binding a `CONNECTED` es
     * [RelayPeerChannelDriver], que recorre el ciclo de estados con SDP vacio
     * porque [PeerTransportBinding.withConnected] —contrato de estado, fijo por
     * `PeerTransportBindingTest`— exige haber pasado por `OFFER_SENT`.
     *
     * Reutilizar estos metodos para simular una negociacion seria inventar un
     * paso que no ocurrio, y `markConnected` ya es el API honesto para eso.
     */
    override fun onLocalSdp(localIdentity: IdentityId, remotePeerId: IdentityId, sdp: String): TransportResult<Unit> =
        TransportResult.Success(Unit)

    /** @see onLocalSdp */
    override fun onRemoteSdp(localIdentity: IdentityId, remotePeerId: IdentityId, sdp: String): TransportResult<Unit> =
        TransportResult.Success(Unit)

    /** @see onLocalSdp */
    override fun onIceCandidate(localIdentity: IdentityId, remotePeerId: IdentityId, candidate: String): TransportResult<Unit> =
        TransportResult.Success(Unit)

    /**
     * Escribe el byte como un `MESSAGE` del dialecto, sin eco.
     *
     * No es un eco. El eco de `PeerTransportManager.sendData` es un hecho del
     * manager: avisa a sus propios `dataHandlers` de lo que ha salido, y de eso
     * viven los arnes que comprueban el camino de salida sin red. Aqui el byte
     * sale y se devuelve si el medio lo acepto.
     */
    override fun sendData(remotePeerId: IdentityId, data: ByteArray): TransportResult<Unit> {
        if (stateRef.get() != ConnectionState.ONLINE) {
            return TransportResult.Failure(
                TransportError.BACKEND_NOT_READY,
                "el motor no esta ONLINE: ${stateRef.get()}",
            )
        }
        return try {
            send(data, remotePeerId)
            TransportResult.Success(Unit)
        } catch (e: Exception) {
            TransportResult.Failure(TransportError.BACKEND_SEND_FAILED, e.message ?: e::class.java.simpleName)
        }
    }

    /** El rele no tiene estado de enlace propio: el cierre lo decide el manager. */
    override fun onCloseBinding(localIdentity: IdentityId, remotePeerId: IdentityId): TransportResult<Unit> =
        TransportResult.Success(Unit)

    /** Cierra el cliente HTTP. La CONEXION la cierra [disconnect]. */
    override fun shutdown(): TransportResult<Unit> {
        disconnect()
        client?.close()
        client = null
        return TransportResult.Success(Unit)
    }

    /**
     * Si el par esta PRESENTE en el rele.
     *
     * Es una consulta al estado que el motor ya mantiene para la TUI, no una
     * llamada de red: el `PEER_ONLINE` del rele mantiene la lista.
     */
    fun isPeerPresent(remotePeerId: IdentityId): Boolean =
        stateRef.get() == ConnectionState.ONLINE && remotePeerId in onlinePeers

    /**
     * Reinyecta un `Message` LEGADO por el camino de `messageHandler`.
     *
     * Es el punto de reinyeccion de [LegacyInboundConductor]: cuando un
     * consumidor baja su compositor, el motor tiene que poder devolver un frame
     * legado al camino por el que llegaba antes de p4. `internal` a proposito —
     * la superficie publica de este modulo es [TransportBackend], y un
     * `deliverLegacyInbound` publico seria una puerta de entrada al camino en
     * claro que nadie necesita desde fuera.
     */
    internal fun deliverLegacyInbound(bytes: ByteArray) {
        messageHandler(bytes)
    }

    /**
     * El codec, para quien tenga que traducir sin duplicarlo.
     *
     * `internal` y no publico: `RelayDataCodec` forma parte de la superficie
     * publica de este modulo (lo usan el escritorio y sus tests), pero la
     * lectura del codec desde dentro no necesita salir.
     */
    /** La identidad con la que este motor se autentica ante el rele. */
    fun relayIdentityId(): IdentityId = identity

    internal fun relayCodec(): RelayDataCodec = codec

    /** El `log` del motor, para que quien componga no tenga que recibir el suyo. */
    internal fun logLine(message: String) = log(message)

    // ------------------------------------------------------------------
    // Sesion
    // ------------------------------------------------------------------

    /**
     * Una conexion WebSocket con su `AUTH_OK` pendiente.
     *
     * `ready` es lo que `connect()` espera. Lo completa el `AUTH_OK`, y lo
     * completa con fallo el propio `AUTH_FAIL` o el cierre antes de tiempo, de
     * modo que `connect()` nunca se queda colgado esperando algo que no va a
     * llegar.
     *
     * El socket se obtiene con `webSocketSession`, cuyo bloque configura la
     * PETICION: la sesion sigue viva DESPUES de que ese bloque termine, que es
     * justo lo que hace falta para que `run()` la lea en su propio hilo.
     */
    private inner class SessionRunner(
        val ready: CompletableDeferred<Result<Unit>>,
        private val http: HttpClient,
        private val url: String,
    ) {
        /**
         * Epoca del redial en el momento de CREAR esta sesion.
         *
         * Una sesion vieja que sigue leyendo mientras el redial ya abrio otra
         * no puede navegar el estado: su `AUTH_OK`, su `AUTH_FAIL` o su cierre
         * pertenecen a un socket que ya no es el del motor. Se descartan.
         */
        val epoch: Long = redialEpoch.get()

        @Volatile
        private var socket: DefaultClientWebSocketSession? = null

        suspend fun run() {
            val session = try {
                http.webSocketSession(url) { }.also { socket = it }
            } catch (e: Throwable) {
                completeReady(this, Result.failure(e))
                throw e
            }
            try {
                sendAuthRequest(session)
                setState(ConnectionState.AUTHENTICATING)
            } catch (e: Throwable) {
                completeReady(this, Result.failure(e))
                throw e
            }
            try {
                // Se itera el canal directamente y no con `consumeAsFlow`: el
                // cierre del socket cierra el canal, y eso hay que distinguirlo
                // de un error de lectura.
                for (frame in session.incoming) onFrame(this, session, frame)
            } catch (_: ClosedReceiveChannelException) {
                log("canal de entrada cerrado")
            } finally {
                // Si la epoca cambio, esta sesion es OBSOLETA: su cierre no
                // puede limpiar la presencia ni el `sessionId` de la sesion
                // nueva, ni forzar `DISCONNECTED` sobre ella.
                if (epoch == redialEpoch.get()) {
                    onSessionClosed()
                } else {
                    log("sesion obsoleta cerrada: epoca $epoch superada")
                }
            }
        }

        suspend fun sendText(text: String) {
            val open = socket ?: throw IllegalStateException("socket no abierto")
            open.send(Frame.Text(text))
        }

        fun close() {
            val open = socket ?: return
            runCatching { runBlocking { open.close(CloseReason(CloseReason.Codes.NORMAL, "cliente")) } }
        }

        /**
         * Cierre suspensible, para el redial.
         *
         * `close()` envuelve el cierre en `runBlocking`, que vale desde
         * `disconnect()` (hilo de control) pero NO desde una corrutina de IO: un
         * socket blackholeado puede tardar en completar el handshake de cierre
         * y bloquearia el hilo. Desde `forceRedial` se usa esta.
         */
        suspend fun closeSuspend(motivo: String) {
            val open = socket ?: return
            runCatching { open.close(CloseReason(CloseReason.Codes.NORMAL, motivo)) }
        }
    }

    private fun ensureClient() {
        if (client == null) client = clientFactory()
    }

    private suspend fun awaitHandshake(): Result<Unit>? {
        supervisor?.cancel()
        val http = client ?: clientFactory().also { client = it }
        val runner = SessionRunner(CompletableDeferred(), http, endpoint)
        active = runner
        supervisor = ownedScope.launch {
            try {
                runner.run()
            } catch (e: Throwable) {
                // El cierre intencionado llega aqui como cancelacion del Job.
                // Informarlo seria ruido: el usuario acaba de pulsar `quit`.
                if (!stopping.get()) {
                    log("sesion terminada: ${e.message}")
                    completeReady(runner, Result.failure(e))
                }
            }
        }
        return withTimeoutOrNull(handshakeTimeoutMs) { runner.ready.await() }
    }

    private suspend fun sendAuthRequest(session: DefaultClientWebSocketSession) {
        // Handshake nuevo: aun no hay dialecto confirmado por el challenge.
        challengeDialectRef.set(null)
        val announced = relayIdentityRef.get() ?: relayIdentity?.value
        session.send(
            Frame.Text(
                buildJsonObject {
                    put("type", TYPE_AUTH_REQUEST)
                    put("messageId", UUID.randomUUID().toString())
                    put("timestamp", now())
                    put("protocolVersion", DIALECT_VERSION)
                    put("identityId", identity.value)
                    put("publicKey", Base64Url.encode(ed25519PublicKey))
                    // Se OMITE cuando no se conoce. Ver la nota de [relayIdentity].
                    if (announced != null) put("responderIdentityId", announced)
                    put(
                        "capabilities",
                        buildJsonObject { put("serialization", buildJsonArray { add("json") }) },
                    )
                }.toString(),
            ),
        )
    }

    // ------------------------------------------------------------------
    // Recepcion de frames
    // ------------------------------------------------------------------

    /**
     * Punto unico de entrada de un frame de texto.
     *
     * Regla de la seccion 7.1: un frame ilegible se descarta y la conexion
     * sobrevive. Toda excepcion se absorbe aqui a proposito.
     */
    private suspend fun onFrame(runner: SessionRunner, session: DefaultClientWebSocketSession, frame: Frame) {
        if (runner.epoch != redialEpoch.get()) {
            // Un frame de la sesion anterior no puede navegar el motor: el
            // redial ya abrio otra. Se descarta, no se procesa a medias.
            log("frame de una sesion obsoleta descartado")
            return
        }
        val text = when (frame) {
            is Frame.Text -> frame.readText()
            // El dialecto es de texto UTF-8 (seccion 7). Un frame binario es un
            // frame que no se entiende: se descarta sin matar la conexion.
            is Frame.Binary -> {
                log("frame binario descartado: el dialecto es de texto UTF-8")
                return
            }
            else -> return
        }

        val obj = codec.parseObject(text)
        if (obj == null) {
            log("frame descartado: no es un objeto JSON")
            return
        }
        val type = codec.typeOf(obj)
        if (type == null) {
            log("frame descartado: falta 'type' o no es string")
            return
        }

        try {
            when (type) {
                TYPE_AUTH_CHALLENGE -> onAuthChallenge(runner, session, obj)
                TYPE_AUTH_OK -> onAuthOk(runner, obj)
                TYPE_AUTH_FAIL -> onAuthFail(runner, obj)
                TYPE_PING -> onPing(session, obj)
                TYPE_PONG -> Unit
                TYPE_MESSAGE -> onMessage(obj)
                TYPE_ACK -> onAck(obj)
                TYPE_SIGNAL -> deliverSignal(obj)
                TYPE_STORED -> onStored(text)
                TYPE_RELAY_EXPIRED -> onRelayExpired(text)
                TYPE_RELAY_ERROR -> onRelayError(text)
                TYPE_PEER_ONLINE -> onPeerPresence(obj, online = true)
                TYPE_PEER_OFFLINE -> onPeerPresence(obj, online = false)
                TYPE_AUTH_SESSION_TIMEOUT -> fail("el rele cerro la sesion por expiracion", runner)
                else -> log("tipo desconocido ignorado: $type")
            }
        } catch (e: Exception) {
            log("frame procesado con error ($type): ${e.message}")
        }
    }

    /**
     * `AUTH_CHALLENGE` -> `AUTH_RESPONSE`.
     *
     * La firma cubre el transcript de 152 B que construye `TranscriptBuilder`, no
     * este JSON: reordenar claves no invalida nada, por construccion (seccion
     * 9.3). El tamano del nonce y la FORMA de los ids se comprueban antes de
     * firmar, porque `TranscriptBuilder` lanza ante un nonce de otro tamano y un
     * fallo de forma no debe llegar a la capa de firma.
     */
    private suspend fun onAuthChallenge(
        runner: SessionRunner,
        session: DefaultClientWebSocketSession,
        frame: JsonObject,
    ) {
        // Dialecto PRIMERO: sin una confirmacion valida no se firma nada.
        // Ausente -> MALFORMED_MESSAGE; desconocido o no implementado ->
        // UNSUPPORTED_PROTOCOL_VERSION. Nunca se adopta el dialecto del rele.
        val dialectWire = frame.string("protocolVersion")
        when (val check = DialectNegotiation.checkChallenge(IMPLEMENTED_DIALECT, dialectWire)) {
            is DialectCheck.Rejected -> {
                fail("dialecto rechazado (${check.error.wireCode}): ${check.detail}", runner)
                return
            }
            is DialectCheck.Accepted -> challengeDialectRef.set(check.dialect)
        }

        val nonce = frame.string("nonce")?.let { runCatching { Base64Url.decode(it) }.getOrNull() }
        if (nonce == null || nonce.size != NONCE_BYTES) {
            fail("AUTH_CHALLENGE sin nonce de $NONCE_BYTES bytes", runner)
            return
        }
        val timestamp = frame.long("timestamp")
        if (timestamp == null || timestamp < 0) {
            fail("AUTH_CHALLENGE sin timestamp utilizable", runner)
            return
        }
        val responder = frame.responderIdentity()
        if (responder == null || responder.length != IDENTITY_ID_HEX) {
            fail("AUTH_CHALLENGE sin responderIdentityId de $IDENTITY_ID_HEX hex", runner)
            return
        }
        relayIdentityRef.set(responder)

        val transcript = TranscriptBuilder.authTranscript(
            nonce = nonce,
            timestampMillis = timestamp,
            responderIdentityId = responder,
            identityId = identity.value,
        )
        val signature = ed25519.sign(ed25519PrivateKey, transcript).bytes
        session.send(
            Frame.Text(
                buildJsonObject {
                    put("type", TYPE_AUTH_RESPONSE)
                    put("messageId", UUID.randomUUID().toString())
                    put("timestamp", now())
                    put("signature", Base64Url.encode(signature))
                }.toString(),
            ),
        )
    }

    /**
     * `AUTH_OK`.
     *
     * Sobre `serverSignature`: NO se verifica todavia, y se dice por que. El
     * relé YA emite el `serverNonce` y el `responderPublicKey` (task 1.2), asi
     * que el material verificable existe; lo que falta es que este cliente
     * rehaga el transcript de 120 B, ancore la clave al pin y verifique la
     * firma. Eso es P6 / task 5.2; aqui no se activa.
     *
     * Lo que SI se comprueba es la FORMA de `sessionId`: 40 hex. Sin ese dato no
     * hay sesion que distinguir de la de otro peer.
     */
    private fun onAuthOk(runner: SessionRunner, frame: JsonObject) {
        // Dialecto antes que cualquier otra cosa: un `AUTH_OK` que no confirme
        // el MISMO dialecto del challenge (o ninguno) no pasa a ONLINE.
        val challengeDialect = challengeDialectRef.get()
        if (challengeDialect == null) {
            fail("AUTH_OK sin AUTH_CHALLENGE previo con dialecto confirmado", runner)
            return
        }
        when (val check = DialectNegotiation.checkAuthOk(IMPLEMENTED_DIALECT, challengeDialect, frame.string("protocolVersion"))) {
            is DialectCheck.Rejected -> {
                fail("dialecto rechazado (${check.error.wireCode}): ${check.detail}", runner)
                return
            }
            is DialectCheck.Accepted -> Unit
        }

        val sessionId = frame.string("sessionId")
        if (sessionId == null || sessionId.length != SESSION_ID_HEX) {
            fail("AUTH_OK con sessionId ausente o de longitud distinta de $SESSION_ID_HEX", runner)
            return
        }
        // OJO: en `AUTH_OK`, `initiatorIdentityId` es el INICIADOR — el
        // cliente — y `responderIdentityId` es el rele (`JsonWireCodec.authOk`
        // los rellena asi). Leer el campo equivocado guarda como identidad del
        // rele la propia, y la siguiente reconexion se anuncia a si misma y
        // recibe `AUTH_FAIL RELAY_IDENTITY_MISMATCH`.
        frame.responderIdentity()?.let { relayIdentityRef.set(it) }
        sessionIdRef.set(sessionId)
        attemptRef.set(0)
        nextRetryRef.set(0L)
        lastErrorRef.set(null)
        // El redial se considera consumado AQUI: hay sesion nueva con `AUTH`
        // nuevo. Sin este reset, el single-flight se quedaria cerrado para
        // siempre y solo habria UN redial por proceso.
        redialing.set(false)
        setState(ConnectionState.ONLINE)
        log("sesion establecida contra $endpoint")
        completeReady(runner, Result.success(Unit))
    }

    private fun onAuthFail(runner: SessionRunner, frame: JsonObject) {
        val code = frame.string("errorCode") ?: "sin codigo"
        val detail = frame.string("errorMessage")?.let { ": $it" } ?: ""
        fail("AUTH_FAIL $code$detail", runner)
    }

    /** `PING` de aplicacion -> `PONG` correlacionado (seccion 12.2). */
    private suspend fun onPing(session: DefaultClientWebSocketSession, frame: JsonObject) {
        val original = frame.string("messageId") ?: ""
        session.send(
            Frame.Text(
                buildJsonObject {
                    put("type", TYPE_PONG)
                    put("messageId", UUID.randomUUID().toString())
                    put("timestamp", now())
                    put("originalMessageId", original)
                }.toString(),
            ),
        )
    }

    /**
     * `MESSAGE` entrante.
     *
     * El rele ya verifico que `from` sea el peer autenticado (seccion 11), asi
     * que aqui no se duplica ese control. Lo que se hace es entregar la carga
     * INTACTA a `km-core`, que es quien entiende los bytes.
     *
     * Y "intacta" incluye el `messageId` de la carga: `toCoreMessageBytes` lo
     * conserva, de modo que el `ACK` que el receptor firme lleva ese
     * identificador y el emisor pueda emparejarlo. Reconstruir el mensaje con un
     * identificador nuevo —que es lo que hacia antes— hacia que la entrega se
     * confirmara contra un mensaje que el emisor no tenia.
     */
    private fun onMessage(frame: JsonObject) {
        val inbound = codec.decodeMessage(frame)
        if (inbound == null) {
            log("MESSAGE descartado: carga Base64 invalida o envelope incompleto")
            return
        }
        if (inbound.to != identity) {
            log("MESSAGE descartado: no va a esta identidad")
            return
        }

        // ------------------------------------------------------------------
        // CLASIFICACION UNICA (p4). Aqui, y no en km-core.
        // ------------------------------------------------------------------
        //
        // El byte crudo solo existe como `inbound.payload` en ESTE punto: el
        // envoltorio que produce `toCoreMessageBytes` lo esconde dentro de un
        // `Message`, y un filtro en km-core tendria que deshacer ese envoltorio
        // para volver a mirar el byte. Ademas "puedo leer esto como MI
        // `Message`?" es una pregunta sobre el DIALECTO de este medio, no sobre
        // `SecureFrame`: el que tiene el codec es quien puede contestarla.
        //
        // La pregunta se hace UNA vez, con la misma lectura que ya hacia
        // `RelayDataCodec.applicationMessageId`, y de ella salen dos caminos.
        val comoLegado = codec.applicationMessageId(inbound.payload) != null
        if (comoLegado) {
            // Carga LEGIBLE en el dialecto de km-core: es la cara legado. Si hay
            // un compositor de frames legados —el `LegacyInboundConductor` del
            // escritorio— se le entrega el MARCO ya decodificado para que el
            // reponga el camino en claro; si NO lo hay, se cae al comportamiento
            // de siempre (`toCoreMessageBytes` -> `messageHandler`). El fallback
            // es lo que mantiene al escritorio bit a bit como estaba.
            val compositor = eventCallbacks.onLegacyInboundFrame
            val mensaje = codec.decodeLegacyMessage(inbound.payload)
            if (compositor == null || mensaje == null) {
                log("MESSAGE legado sin compositor: camino de aplicacion de siempre")
                alCaminoLegado(inbound)
            } else {
                log("MESSAGE legado: lo repone el compositor de frames legados")
                compositor(LegacyInboundFrame(from = inbound.from, encoded = mensaje), inbound.from)
            }
            return
        }

        // Carga opaca: es la cara de par. Si hay un compositor —un
        // `PeerTransportManager` con su cable de B2— el byte va ahi y NO al
        // camino en claro: mezclar los dos seria entregar en claro lo que el
        // destinatario espera descifrado.
        val compositor = eventCallbacks.onDataReceived
        if (compositor == null) {
            log("MESSAGE opaco sin compositor de pares: se entrega por el camino legado")
            alCaminoLegado(inbound)
            return
        }
        compositor(inbound.from, inbound.payload)
    }

    /**
     * El camino LEGADO de siempre: `toCoreMessageBytes` y `messageHandler`.
     *
     * Se conserva bit a bit, con su envoltorio y su `messageId` de envelope.
     * Ese envoltorio es lo que hace que una carga opaca llegue a
     * `KeyMessageCoreImpl` como un `Message` cualquiera en vez de perderse: es
     * la compat que sostiene al escritorio, que no cablea la cara de pares.
     */
    private fun alCaminoLegado(inbound: RelayDataCodec.InboundMessage) {
        val bytes = codec.toCoreMessageBytes(inbound)
        if (bytes == null) {
            log("MESSAGE descartado: no se pudo codificar en el vocabulario de km-core")
            return
        }
        messageHandler(bytes)
    }

    /**
     * `ACK` entrante.
     *
     * El `km-daemon` reenvía el `ACK` verbatim desde el Checkpoint B.1 (§11.1 ya
     * enmendada): sale por aquí la MISMA `String` que el emisor puso en el
     * cable, sin re-serializar. Por eso el campo `signature` que llega es
     * exactamente el que firmó el receptor, y no hay nada que "normalizar"
     * antes de verificarlo.
     *
     * Lo que NO se reescribe tampoco: si `from` no va a esta identidad, o si la
     * firma no cuadra, el `ACK` se descarta. Reparar uno dudoso produciría un
     * `ACK` distinto del firmado, que es peor que perderlo.
     */
    private fun onAck(frame: JsonObject) {
        val ack = codec.decodeAck(frame)
        if (ack == null) {
            log("ACK descartado: campos ausentes o mal formados")
            return
        }
        if (ack.to != identity) {
            log("ACK descartado: no va a esta identidad")
            return
        }
        codec.toCoreAckBytes(ack)?.let { ackHandler(it) }
    }

    /** `STORED`, decodificado por el codec de `km-core` (seccion 13.1). */
    private fun onStored(text: String) {
        controlCodec.decodeStored(text.toByteArray(Charsets.UTF_8))
            .onSuccess { storedHandler(it) }
            .onFailure { log("STORED ilegible: ${it.message}") }
    }

    /** `RELAY_EXPIRED`, decodificado por el codec de `km-core` (seccion 13.2). */
    private fun onRelayExpired(text: String) {
        controlCodec.decodeRelayExpired(text.toByteArray(Charsets.UTF_8))
            .onSuccess { relayExpiredHandler(it) }
            .onFailure { log("RELAY_EXPIRED ilegible: ${it.message}") }
    }

    /** `RELAY_ERROR` (seccion 16.1). */
    private fun onRelayError(text: String) {
        controlCodec.decodeError(text.toByteArray(Charsets.UTF_8))
            .onSuccess(::onRelayErrorNotice)
            .onFailure { log("RELAY_ERROR ilegible: ${it.message}") }
    }

    private fun onRelayErrorNotice(notice: RelayErrorNotice) {
        val detail = notice.errorMessage?.let { ": $it" } ?: ""
        val text = "RELAY_ERROR ${notice.errorCode}$detail"
        lastErrorRef.set(text)
        // La cola se llena y no se puede bloquear la recepcion: si esta llena,
        // el error mas viejo se descarta, porque lo que importa es el MAS
        // RECIENTE (es el veredicto de la senal en vuelo).
        relayErrors.offer(notice)
        while (relayErrors.size > 4) relayErrors.poll()
        relayErrorHandlers.forEach { handler ->
            runCatching { handler(notice) }
                .onFailure { e -> log("handler de RELAY_ERROR fallo: ${e.message}") }
        }
        log(text)

        // D2. El veredicto ya esta en la cola —el `SignalDelivery` que lo
        // espera lo recogera— y ya se ha notificado a los suscriptores. Si ese
        // veredicto dice que la sesion del rele esta muerta MIENTRAS el motor
        // cree estar ONLINE, lo que hay que hacer con el socket es soltarlo.
        // Ver [abandonExpiredSession].
        if (notice.errorCode == RelayErrorCode.RELAY_NOT_AUTHORIZED && stateRef.get() == ConnectionState.ONLINE) {
            abandonExpiredSession()
        }
    }

    /**
     * Suelta una sesion que el rele acaba de declarar muerta y renegocia.
     *
     * ## EL CASO REAL (HONOR/S8+, ~10 min de inactividad)
     *
     * ```
     * 23:38:00.756 [CONTACT] verificado root=... subject=...
     * 23:38:01.124 [RELAY] RELAY_ERROR RELAY_NOT_AUTHORIZED: sesion de ... expirada
     * 23:38:01.437 [SIGNAL] intercambio de contactos NO entregado ... REFUSED
     * 23:38:10.308 [SEND] ... NOT SENT failure=AUTH_FAILED
     * ```
     *
     * El rele expira la sesion a los `sessionExpiryMs` (`RelayServer`) y lo
     * dice por `RELAY_ERROR` SIN cerrar el socket. Por eso el motor seguia
     * `ONLINE` con una sesion que ya no existia: el siguiente envio se volvia a
     * rechazar igual, el intercambio se presentaba como `REFUSED` —que suena a
     * rechazo de negocio— y el estado de la pantalla mentia.
     *
     * ## QUE HACE, EN ESTE ORDEN
     *
     *  1. Toma el single-flight [redialing]. Un segundo aviso de la sesion que
     *     se esta yendo —o un [forceRedial] que llegue a la vez— se colapsa:
     *     dos `AUTH` concurrentes de la MISMA identidad hacen que el rele
     *     cierre la sesion del primero y el perdedor reciba
     *     `AUTH_FAILED INVALID_SIGNATURE`.
     *  2. Sube [redialEpoch]. Cualquier reintento diferido anterior queda
     *     invalidado, y el `finally` de la sesion vieja —que ya no es la
     *     vigente— no fuerza `DISCONNECTED` ni encola un segundo reintento.
     *  3. Pone `RECONNECTING` y limpia la vista de la sesion muerta: `sessionId`
     *     y presencia. El snapshot del rele la re-anuncia en el `AUTH_OK`.
     *  4. Suelta `active` ANTES de cerrar: una sesion que se esta yendo no
     *     puede recibir el `ready` ni un byte de la nueva.
     *  5. Programa el handshake con el BACKOFF EXISTENTE (1 s..30 s con jitter),
     *     NO inmediato: un aviso por `RELAY_NOT_AUTHORIZED` no es un cambio de
     *     red, y el tope del ciclo es el que impide convertir un rele que
     *     rechaza en un bucle caliente de `AUTH`.
     *
     * ## EL CIERRE NO BLOQUEA
     *
     * Se llama desde el hilo que LEE el frame, o sea desde dentro de la propia
     * corrutina de la sesion: un `close()` con `runBlocking` ahi se esperaria a
     * si mismo. Por eso se usa `closeSuspend` en `ownedScope`.
     *
     * ## LAS CUATRO CAUSAS DEL WIRE
     *
     * `RELAY_NOT_AUTHORIZED` agrupa, mientras el wire no las distinga: sin
     * `AUTH_OK`, senalizacion desactivada por el operador, sesion expirada y
     * spoofing. Aqui se tratan todas como sesion muerta. Con la senalizacion
     * desactivada el resultado es un ciclo acotado por el backoff y, para el
     * interlocutor, `CONTACT_EXCHANGE_NOT_CONNECTED` —no un rechazo—; queda
     * documentado como precio de no poder distinguirlas en el cable.
     *
     * ## LO QUE NO HACE
     *
     * No toca X3DH/PREKEY ni el ratchet: repara el socket de rele, no la
     * sesion E2E (R2/R12). No cambia `MESSAGE`/`ACK`/legado.
     */
    private fun abandonExpiredSession() {
        if (stopping.get()) {
            // El transporte se esta parando: `disconnect()` ya cierra la sesion
            // y deja `DISCONNECTED`. Renegociar aqui reabriria lo que se cierra.
            return
        }
        if (!redialing.compareAndSet(false, true)) {
            log("sesion expirada: ya hay una renegociacion en vuelo, el aviso se colapsa")
            return
        }

        // El motivo queda en `lastError` para la pantalla y el diagnostico; el
        // `AUTH_OK` lo limpia, como el de cualquier reconexion.
        val motivo = "RELAY_ERROR RELAY_NOT_AUTHORIZED: la sesion del rele esta muerta; se renegocia"
        lastErrorRef.set(motivo)

        redialEpoch.incrementAndGet()
        onlinePeers.clear()
        sessionIdRef.set(null)
        attemptRef.set(0)
        nextRetryRef.set(0L)
        setState(ConnectionState.RECONNECTING)

        val previa = active
        active = null
        if (previa != null) {
            ownedScope.launch { previa.closeSuspend("sesion expirada") }
        }

        val epoca = redialEpoch.get()
        log("$motivo (epoca=$epoca)")
        scheduleReconnect(immediate = false, epoch = epoca)
    }

    /** Se suscribe a los `RELAY_ERROR` entrantes. */
    fun onRelayError(handler: (RelayErrorNotice) -> Unit) { relayErrorHandlers.add(handler) }

    /**
     * Espera el proximo `RELAY_ERROR`, o `null` si no llega ninguno en
     * [timeoutMs] milisegundos.
     *
     * Es la ventana de veredicto de [SignalDelivery]: el rele no acuse una
     * `SIGNAL`, asi que el unico veredicto posible es un error.
     */
    fun awaitRelayError(timeoutMs: Long): RelayErrorNotice? =
        relayErrors.poll(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS)

    /** Vacia la cola de `RELAY_ERROR` pendientes. */
    fun drainRelayErrors() { relayErrors.clear() }

    /**
     * Presencia de un par, con FAN-OUT AISLADO por suscriptor.
     *
     * `onlinePeers` se muta ANTES de notificar: el motor ya considera al par
     * presente/ausente cuando el consumidor corre, de modo que un handler que
     * consulte `isPeerPresent` durante el aviso ve el hecho, no el aviso
     * anterior.
     *
     * Cada handler va en su propio `runCatching`: antes, uno que lanzara
     * cortaba el `forEach` y los siguientes —incluido el `RelayPeerChannelDriver`
     * de otro par, o el publicador de la UI— no se enteraban, mientras el
     * `onFrame` de mas arriba absorbia la excepcion por frame. Un consumidor
     * roto no puede silenciar a los demas ni tumbar la sesion; su fallo se
     * registra como WARN.
     */
    private fun onPeerPresence(frame: JsonObject, online: Boolean) {
        val peer = frame.identityId("identityId")
        if (peer == null) {
            log("presencia descartada: identityId ausente o de forma invalida")
            return
        }
        if (peer == identity) return
        if (online) onlinePeers.add(peer) else onlinePeers.remove(peer)
        val handlers = if (online) peerOnlineHandlers else peerOfflineHandlers
        handlers.forEach { handler ->
            runCatching { handler(peer) }.onFailure { fallo ->
                log(
                    "un suscriptor de presencia ${if (online) "ONLINE" else "OFFLINE"} lanzo para " +
                        "${peer.value.take(8)}: ${fallo::class.simpleName}: ${fallo.message}",
                )
            }
        }
    }

    // ------------------------------------------------------------------
    // Ciclo de vida y reconexion
    // ------------------------------------------------------------------

    private fun onSessionClosed() {
        sessionIdRef.set(null)
        // R5: la vista de pares muere con la sesion. El snapshot del rele es
        // ADITIVO, asi que sin esta limpieza un par que se fue durante el corte
        // queda "presente" para siempre (#349, PROBE-A: stalePresence=true).
        onlinePeers.clear()
        if (stopping.get()) {
            redialing.set(false)
            setState(ConnectionState.DISCONNECTED)
            return
        }
        if (redialing.get()) {
            // El cierre lo provoco el redial: el reintento YA esta programado y
            // el estado ya es RECONNECTING. Forzar DISCONNECTED aqui mentiria y
            // encolar otro reintento competiria con el de `forceRedial`.
            log("sesion cerrada por el redial: el reintento inmediato ya esta encolado")
            return
        }
        if (stateRef.get() != ConnectionState.ONLINE) {
            // El socket se fue ANTES del handshake. El motivo ya lo ha
            // reportado `fail` o el cierre de la propia sesion; encadenar una
            // reconexion aqui taparia ese error con otro mas generico.
            setState(ConnectionState.DISCONNECTED)
            return
        }
        scheduleReconnect()
    }

    /**
     * Programa un handshake.
     *
     * @param immediate `true` para el redial: se salta el backoff (R1: <=5 s).
     * @param epoch epoca que este trabajo debe seguir viendo vigente al
     *   despertar. Un redial posterior la sube y este trabajo se autodescarta.
     */
    private fun scheduleReconnect(immediate: Boolean = false, epoch: Long = redialEpoch.get()) {
        val attempt = attemptRef.incrementAndGet()
        val wait = if (immediate) 0L else reconnect.delayFor(attempt - 1)
        nextRetryRef.set(now() + wait)
        setState(ConnectionState.RECONNECTING)
        log(if (immediate) "redial inmediato (epoca=$epoch)" else "reconexion $attempt en ${wait}ms")

        ownedScope.launch {
            delay(wait)
            if (stopping.get()) return@launch
            if (redialEpoch.get() != epoch) {
                // Otro redial mando: este reintento es el viejo.
                log("reintento $attempt descartado: epoca $epoch superada por ${redialEpoch.get()}")
                return@launch
            }
            lastErrorRef.set(null)
            val outcome = withTimeoutOrNull(handshakeTimeoutMs) { awaitHandshake() }
            when {
                outcome == null -> {
                    if (redialEpoch.get() != epoch) return@launch
                    // El redial no lo consiguio: cede al backoff normal. Si no
                    // se soltara el flag, un cambio de red posterior quedaria
                    // colapsado contra un redial que ya no existe.
                    redialing.set(false)
                    val reason = "reconexion $attempt: timeout del handshake"
                    lastErrorRef.set(reason)
                    log(reason)
                    scheduleReconnect()
                }
                outcome.isFailure -> {
                    if (redialEpoch.get() != epoch) return@launch
                    redialing.set(false)
                    val reason = "reconexion $attempt: ${outcome.exceptionOrNull()?.message}"
                    lastErrorRef.set(reason)
                    log(reason)
                    scheduleReconnect()
                }
                else -> log("reconexion $attempt correcta")
            }
        }
    }

    private fun fail(reason: String, runner: SessionRunner?): Result<Unit> {
        lastErrorRef.set(reason)
        log(reason)
        if (runner != null) completeReady(runner, Result.failure(IllegalStateException(reason)))
        setState(ConnectionState.DISCONNECTED)
        return Result.failure(IllegalStateException(reason))
    }

    /**
     * Completa el `ready` de [runner] — y SOLO el suyo.
     *
     * Resolverlo por `active` era un fallo de carrera: al cancelar la sesion
     * vieja, su `catch` completaba el `ready` de la sesion NUEVA, el handshake
     * nuevo se daba por fallido y programaba otra reconexion que cancelaba a la
     * siguiente. El resultado era una cascada de `AUTH` (12 en 1,7 s).
     */
    private fun completeReady(runner: SessionRunner, result: Result<Unit>) {
        if (!runner.ready.isCompleted) runner.ready.complete(result)
    }

    private fun setState(next: ConnectionState) {
        val previous = stateRef.getAndSet(next)
        if (previous != next) {
            // La publicacion va ANTES del handler: un consumidor que reaccione
            // al aviso y consulte el flujo tiene que ver el mismo estado que el
            // del aviso, no el anterior.
            stateFlow.value = next
            stateHandler(next)
        }
    }

    companion object {
        /** Rele desplegado. */
        const val DEFAULT_ENDPOINT: String = "wss://keymessage.xyz/km"

        /** Dialecto que anuncia el cliente (seccion 17.1). Fuente unica: `Dialect` (R34). */
        val DIALECT_VERSION: String = Dialect.V2_0.wire

        /**
         * El UNICO dialecto que implementa este cliente (N7). Se declara en el
         * `AUTH_REQUEST` y se EXIGE en `AUTH_CHALLENGE` y `AUTH_OK`: cualquier
         * otra confirmacion, o su ausencia, es un rechazo cerrado.
         */
        val IMPLEMENTED_DIALECT: Dialect = Dialect.V2_0

        /** `RelayServer.maxDataPayloadBytes` (seccion 15.1). */
        const val MAX_DATA_PAYLOAD_BYTES: Int = 65_535

        const val IDENTITY_ID_HEX: Int = RelayDataCodec.IDENTITY_ID_HEX
        const val SESSION_ID_HEX: Int = 40
        const val NONCE_BYTES: Int = 16

        const val TYPE_AUTH_REQUEST: String = "AUTH_REQUEST"
        const val TYPE_AUTH_CHALLENGE: String = "AUTH_CHALLENGE"
        const val TYPE_AUTH_RESPONSE: String = "AUTH_RESPONSE"
        const val TYPE_AUTH_OK: String = "AUTH_OK"
        const val TYPE_AUTH_FAIL: String = "AUTH_FAIL"
        const val TYPE_AUTH_SESSION_TIMEOUT: String = "AUTH_SESSION_TIMEOUT"
        const val TYPE_PING: String = "PING"
        const val TYPE_PONG: String = "PONG"
        const val TYPE_STORED: String = "STORED"
        const val TYPE_RELAY_EXPIRED: String = "RELAY_EXPIRED"
        const val TYPE_RELAY_ERROR: String = "RELAY_ERROR"
        const val TYPE_PEER_ONLINE: String = "PEER_ONLINE"
        const val TYPE_PEER_OFFLINE: String = "PEER_OFFLINE"

        const val TYPE_MESSAGE: String = RelayDataCodec.TYPE_MESSAGE
        const val TYPE_ACK: String = RelayDataCodec.TYPE_ACK
        const val TYPE_SIGNAL: String = RelayDataCodec.TYPE_SIGNAL

        /**
         * Keepalive de RFC 6455 (seccion 12.3), DISTINTO del `PING`/`PONG` de
         * aplicacion que emite el rele. Los dos coexisten.
         */
        const val PING_INTERVAL_MS: Long = 20_000

        /**
         * Motor por defecto: CIO, sin dependencias nativas.
         *
         * El `pingInterval` de WebSockets es el nivel RFC 6455 de la seccion
         * 12.3, DISTINTO del `PING`/`PONG` de aplicacion del mismo apartado.
         * Los dos existen a la vez y este no reemplaza a aquel.
         */
        fun defaultClient(): HttpClient = HttpClient(CIO) {
            install(WebSockets) { pingIntervalMillis = PING_INTERVAL_MS }
            expectSuccess = false
        }
    }
}
