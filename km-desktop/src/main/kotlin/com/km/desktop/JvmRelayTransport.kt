package com.km.desktop

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
import com.km.model.RelayErrorNotice
import com.km.model.RelayExpiredNotice
import com.km.model.StoredReceipt
import com.km.node.RelayTransport
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
    /** Etiqueta del transporte. `Transport` no declara `name`: es informativo. */
    val name: String = "relay",
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
) : RelayTransport {

    private val stateRef = AtomicReference(ConnectionState.DISCONNECTED)
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
    private var peerOnlineHandler: (IdentityId) -> Unit = {}
    private var peerOfflineHandler: (IdentityId) -> Unit = {}

    // ------------------------------------------------------------------
    // Transport
    // ------------------------------------------------------------------

    override fun connect(): Result<Unit> {
        if (stateRef.get() == ConnectionState.ONLINE) return Result.success(Unit)

        stopping.set(false)
        attemptRef.set(0)
        lastErrorRef.set(null)
        ensureClient()

        return runBlocking {
            when (val outcome = awaitHandshake()) {
                null -> fail("el handshake no completo en ${handshakeTimeoutMs}ms")
                else -> outcome
            }
        }
    }

    override fun disconnect() {
        stopping.set(true)
        supervisor?.cancel()
        supervisor = null
        active?.close()
        active = null
        client?.close()
        client = null
        sessionIdRef.set(null)
        relayIdentityRef.set(null)
        nextRetryRef.set(0L)
        setState(ConnectionState.DISCONNECTED)
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
    override fun onPeerOnline(handler: (IdentityId) -> Unit) { peerOnlineHandler = handler }
    override fun onPeerOffline(handler: (IdentityId) -> Unit) { peerOfflineHandler = handler }

    override fun isOnline(): Boolean = stateRef.get() == ConnectionState.ONLINE

    override fun connectionState(): ConnectionState = stateRef.get()

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
        @Volatile
        private var socket: DefaultClientWebSocketSession? = null

        suspend fun run() {
            val session = try {
                http.webSocketSession(url) { }.also { socket = it }
            } catch (e: Throwable) {
                completeReady(Result.failure(e))
                throw e
            }
            try {
                sendAuthRequest(session)
                setState(ConnectionState.AUTHENTICATING)
            } catch (e: Throwable) {
                completeReady(Result.failure(e))
                throw e
            }
            try {
                // Se itera el canal directamente y no con `consumeAsFlow`: el
                // cierre del socket cierra el canal, y eso hay que distinguirlo
                // de un error de lectura.
                for (frame in session.incoming) onFrame(session, frame)
            } catch (_: ClosedReceiveChannelException) {
                log("canal de entrada cerrado")
            } finally {
                onSessionClosed()
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
                    completeReady(Result.failure(e))
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
    private suspend fun onFrame(session: DefaultClientWebSocketSession, frame: Frame) {
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
                TYPE_AUTH_CHALLENGE -> onAuthChallenge(session, obj)
                TYPE_AUTH_OK -> onAuthOk(obj)
                TYPE_AUTH_FAIL -> onAuthFail(obj)
                TYPE_PING -> onPing(session, obj)
                TYPE_PONG -> Unit
                TYPE_MESSAGE -> onMessage(obj)
                TYPE_ACK -> onAck(obj)
                TYPE_STORED -> onStored(text)
                TYPE_RELAY_EXPIRED -> onRelayExpired(text)
                TYPE_RELAY_ERROR -> onRelayError(text)
                TYPE_PEER_ONLINE -> onPeerPresence(obj, online = true)
                TYPE_PEER_OFFLINE -> onPeerPresence(obj, online = false)
                TYPE_AUTH_SESSION_TIMEOUT -> fail("el rele cerro la sesion por expiracion")
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
    private suspend fun onAuthChallenge(session: DefaultClientWebSocketSession, frame: JsonObject) {
        // Dialecto PRIMERO: sin una confirmacion valida no se firma nada.
        // Ausente -> MALFORMED_MESSAGE; desconocido o no implementado ->
        // UNSUPPORTED_PROTOCOL_VERSION. Nunca se adopta el dialecto del rele.
        val dialectWire = frame.string("protocolVersion")
        when (val check = DialectNegotiation.checkChallenge(IMPLEMENTED_DIALECT, dialectWire)) {
            is DialectCheck.Rejected -> {
                fail("dialecto rechazado (${check.error.wireCode}): ${check.detail}")
                return
            }
            is DialectCheck.Accepted -> challengeDialectRef.set(check.dialect)
        }

        val nonce = frame.string("nonce")?.let { runCatching { Base64Url.decode(it) }.getOrNull() }
        if (nonce == null || nonce.size != NONCE_BYTES) {
            fail("AUTH_CHALLENGE sin nonce de $NONCE_BYTES bytes")
            return
        }
        val timestamp = frame.long("timestamp")
        if (timestamp == null || timestamp < 0) {
            fail("AUTH_CHALLENGE sin timestamp utilizable")
            return
        }
        val responder = frame.responderIdentity()
        if (responder == null || responder.length != IDENTITY_ID_HEX) {
            fail("AUTH_CHALLENGE sin responderIdentityId de $IDENTITY_ID_HEX hex")
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
     * Sobre `serverSignature`: NO se verifica, y se dice por que. El transcript
     * de 120 B necesita el `serverNonce`, que la tabla de campos de la seccion
     * 9.6 no lista, asi que la firma llega sin material verificable. Es la
     * divergencia P6 del propio documento, no una omision de este modulo: el
     * cliente Android tampoco la verifica.
     *
     * Lo que SI se comprueba es la FORMA de `sessionId`: 40 hex. Sin ese dato no
     * hay sesion que distinguir de la de otro peer.
     */
    private fun onAuthOk(frame: JsonObject) {
        // Dialecto antes que cualquier otra cosa: un `AUTH_OK` que no confirme
        // el MISMO dialecto del challenge (o ninguno) no pasa a ONLINE.
        val challengeDialect = challengeDialectRef.get()
        if (challengeDialect == null) {
            fail("AUTH_OK sin AUTH_CHALLENGE previo con dialecto confirmado")
            return
        }
        when (val check = DialectNegotiation.checkAuthOk(IMPLEMENTED_DIALECT, challengeDialect, frame.string("protocolVersion"))) {
            is DialectCheck.Rejected -> {
                fail("dialecto rechazado (${check.error.wireCode}): ${check.detail}")
                return
            }
            is DialectCheck.Accepted -> Unit
        }

        val sessionId = frame.string("sessionId")
        if (sessionId == null || sessionId.length != SESSION_ID_HEX) {
            fail("AUTH_OK con sessionId ausente o de longitud distinta de $SESSION_ID_HEX")
            return
        }
        // OJO: en `AUTH_OK`, `identityId` es el INICIADOR — el cliente — y
        // `responderIdentityId` es el rele (`JsonWireCodec.authOk` los rellena
        // asi). Leer el campo equivocado guarda como identidad del rele la
        // propia, y la siguiente reconexion se anuncia a si misma y recibe
        // `AUTH_FAIL RELAY_IDENTITY_MISMATCH`.
        frame.responderIdentity()?.let { relayIdentityRef.set(it) }
        sessionIdRef.set(sessionId)
        attemptRef.set(0)
        nextRetryRef.set(0L)
        lastErrorRef.set(null)
        setState(ConnectionState.ONLINE)
        log("sesion establecida contra $endpoint")
        completeReady(Result.success(Unit))
    }

    private fun onAuthFail(frame: JsonObject) {
        val code = frame.string("errorCode") ?: "sin codigo"
        val detail = frame.string("errorMessage")?.let { ": $it" } ?: ""
        fail("AUTH_FAIL $code$detail")
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
        log(text)
    }

    private fun onPeerPresence(frame: JsonObject, online: Boolean) {
        val peer = frame.identityId("identityId")
        if (peer == null) {
            log("presencia descartada: identityId ausente o de forma invalida")
            return
        }
        if (peer == identity) return
        if (online) peerOnlineHandler(peer) else peerOfflineHandler(peer)
    }

    // ------------------------------------------------------------------
    // Ciclo de vida y reconexion
    // ------------------------------------------------------------------

    private fun onSessionClosed() {
        sessionIdRef.set(null)
        val wasOnline = stateRef.get() == ConnectionState.ONLINE
        if (stopping.get()) {
            setState(ConnectionState.DISCONNECTED)
            return
        }
        if (!wasOnline) {
            // El socket se fue ANTES del handshake. El motivo ya lo ha
            // reportado `fail` o el cierre de la propia sesion; encadenar una
            // reconexion aqui taparia ese error con otro mas generico.
            setState(ConnectionState.DISCONNECTED)
            return
        }
        scheduleReconnect()
    }

    private fun scheduleReconnect() {
        val attempt = attemptRef.incrementAndGet()
        val wait = reconnect.delayFor(attempt - 1)
        nextRetryRef.set(now() + wait)
        setState(ConnectionState.RECONNECTING)
        log("reconexion $attempt en ${wait}ms")

        ownedScope.launch {
            delay(wait)
            if (stopping.get()) return@launch
            lastErrorRef.set(null)
            val outcome = withTimeoutOrNull(handshakeTimeoutMs) { awaitHandshake() }
            when {
                outcome == null -> {
                    val reason = "reconexion $attempt: timeout del handshake"
                    lastErrorRef.set(reason)
                    log(reason)
                    scheduleReconnect()
                }
                outcome.isFailure -> {
                    val reason = "reconexion $attempt: ${outcome.exceptionOrNull()?.message}"
                    lastErrorRef.set(reason)
                    log(reason)
                    scheduleReconnect()
                }
                else -> log("reconexion $attempt correcta")
            }
        }
    }

    private fun fail(reason: String): Result<Unit> {
        lastErrorRef.set(reason)
        log(reason)
        completeReady(Result.failure(IllegalStateException(reason)))
        setState(ConnectionState.DISCONNECTED)
        return Result.failure(IllegalStateException(reason))
    }

    private fun completeReady(result: Result<Unit>) {
        active?.let { runner -> if (!runner.ready.isCompleted) runner.ready.complete(result) }
    }

    private fun setState(next: ConnectionState) {
        val previous = stateRef.getAndSet(next)
        if (previous != next) stateHandler(next)
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
