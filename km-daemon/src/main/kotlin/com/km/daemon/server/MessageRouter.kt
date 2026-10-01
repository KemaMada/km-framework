package com.km.daemon.server

import com.fasterxml.jackson.databind.JsonNode
import com.km.codec.JsonRelayControlCodec
import com.km.daemon.config.DaemonConfig
import com.km.daemon.util.Base64Standard
import com.km.model.IdentityId
import com.km.model.MessageId
import com.km.model.RelayErrorCode
import com.km.model.RelayErrorNotice
import com.km.model.RelayExpiredNotice
import com.km.model.RelayLimits
import com.km.model.StoredReceipt
import com.km.node.RelayDataEnvelope
import com.km.node.RelayServer
import com.km.node.RelayServerError
import com.km.node.RelayServerResult
import com.km.node.RelaySignalMessage
import org.slf4j.Logger
import java.util.Collections
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Reparte los frames de texto entre el camino correcto y mantiene el estado
 * de las conexiones.
 *
 * Aqui vive el DIALECTO, no la logica de negocio: decidir que hacer con un
 * `type` concreto, y delegar el trabajo en quien ya lo sabe hacer.
 *
 * | `type`      | Camino                                                    |
 * |-------------|-----------------------------------------------------------|
 * | `AUTH_*`    | [AuthAdapter] -> `RelayServer.createChallenge/authenticate` |
 * | `MESSAGE`   | Base64 -> `RelayServer.deliverData` -> Base64            |
 * | `SIGNAL`    | extension -> `RelayServer.handleSignal`                  |
 * | `PING`/`PONG` | heartbeat de aplicacion (§12.2)                         |
 * | `PEER_*`    | NUNCA entrantes: son salida del rele                      |
 * | `STORED`, `RELAY_EXPIRED`, `RELAY_ERROR`, `ACK` | se registran y se descartan |
 * | cualquier otro | se ignora (§7.1.3)                                     |
 *
 * Lo que este objeto NO hace, y es lo importante (§6.1): no mira el contenido
 * de `data`, no lee `messageId`, no descifra, no implementa semantica de
 * `ACK`. `data` entra como Base64, sale de `RelayServer` intacto, y vuelve a
 * salir como Base64. Nada mas.
 */
class MessageRouter(
    private val relayServer: RelayServer,
    private val codec: JsonWireCodec,
    private val authAdapter: AuthAdapter,
    private val relayIdentityId: IdentityId,
    private val config: DaemonConfig,
    private val logger: Logger,
    private val now: () -> Long = System::currentTimeMillis,
) {

    private val controlCodec = JsonRelayControlCodec()

    /** Conexiones vivas, indexadas por socket. */
    private val connections: MutableMap<Any, RelayWebSocket> = ConcurrentHashMap()

    /**
     * Guardia de admision sobre el TEXTO del frame.
     *
     * No es una segunda copia del limite de `RelayServer`: aquel decide en
     * BYTES DE PAYLOAD y corre dentro de `deliverData`. Este va en caracteres
     * de transporte y corre ANTES de parsear el JSON y decodificar el Base64,
     * para no materializar decenas de megabytes que despues se van a
     * rechazar. Es una medida de asignacion, no una politica.
     */
    private val maxFrameChars: Int = Base64Standard.maxEncodedLength(RelayLimits.DEFAULT_MAX_MESSAGE_SIZE.toInt()) +
        FRAME_ENVELOPE_ALLOWANCE

    init {
        // Presencia: el core ya mantiene el modelo, aqui solo se serializa a
        // `PEER_ONLINE` / `PEER_OFFLINE` (§12.1, cierra P8).
        relayServer.onPeerOnline { peerId -> broadcast(codec.peerPresence("PEER_ONLINE", peerId)) }
        relayServer.onPeerOffline { peerId -> broadcast(codec.peerPresence("PEER_OFFLINE", peerId)) }
    }

    // =====================================================================
    // Ciclo de vida de conexiones
    // =====================================================================

    fun register(socket: Any, connection: RelayWebSocket) {
        connections[socket] = connection
        logger.info("conexion abierta ({} activas)", connections.size)
    }

    /**
     * Libera la conexion y su sesion.
     *
     * Solo se llama a `RelayServer.disconnect` si la sesion que el core ve
     * sigue siendo la de ESTA conexion. Una reconexion del mismo peer crea
     * una sesion nueva y deja la vieja cerrada; sin esta comprobacion, el
     * cierre de la conexion vieja se llevaria por delante la nueva.
     */
    fun release(socket: Any, connection: RelayWebSocket) {
        connections.remove(socket)

        val peerId = connection.peerId
        val sessionId = connection.sessionId
        if (peerId != null && sessionId != null) {
            val current = relayServer.getSession(peerId)
            if (current != null && current.isActive && current.sessionId == sessionId) {
                // Orden: primero el handler de datos, porque `disconnect` ya
                // llama a `unregisterPeerHandler` y dispara `PEER_OFFLINE`.
                relayServer.unregisterDataHandler(peerId)
                relayServer.disconnect(peerId)
            } else {
                // Otra conexion de la MISMA identidad toma el relevo con una
                // sesion nueva. Soltar aqui los handlers de esta le
                // descolgaria a la conexion viva, asi que no se toca nada.
                logger.debug("cierre de sesion ya sustituida por otra conexion; RelayServer intacto")
            }
        }
        connection.markClosed()
        logger.info("conexion cerrada ({} activas)", connections.size)
    }

    fun authenticatedConnections(): List<RelayWebSocket> =
        Collections.unmodifiableList(connections.values.filter { it.isAuthenticated })

    fun activeConnectionCount(): Int = connections.size

    // =====================================================================
    // Dispatch
    // =====================================================================

    /**
     * Punto unico de entrada de un frame de texto.
     *
     * Regla de §7.1: un frame ilegible se descarta, la conexion sobrevive.
     * Toda excepcion se absorbe aqui a proposito; el hilo de una conexion no
     * muere por un frame que otroontrol escribio mal.
     */
    fun onText(connection: RelayWebSocket, text: String) {
        try {
            if (text.length > maxFrameChars) {
                logger.warn(
                    "frame descartado: {} caracteres exceden el maximo de {}",
                    text.length, maxFrameChars,
                )
                sendRelayError(connection, RelayErrorCode.MESSAGE_TOO_LARGE, "frame de transporte demasiado grande", null)
                return
            }

            val frame = codec.readObject(text)
            if (frame == null) {
                logger.debug("frame descartado: no es un objeto JSON")
                return
            }
            val type = codec.typeOf(frame)
            if (type == null) {
                logger.debug("frame descartado: falta 'type' o no es string")
                return
            }

            when (type) {
                "AUTH_REQUEST" -> onAuthRequest(connection, frame)
                "AUTH_RESPONSE" -> onAuthResponse(connection, frame)
                "MESSAGE" -> onMessage(connection, frame)
                "SIGNAL" -> onSignal(connection, frame)
                "PING" -> onPing(connection, frame)
                "PONG" -> onPong(connection)
                "STORED", "RELAY_EXPIRED", "RELAY_ERROR" -> onRelayToClient(connection, type)
                "PEER_ONLINE", "PEER_OFFLINE", "AUTH_CHALLENGE", "AUTH_OK", "AUTH_FAIL" ->
                    onRelayToClient(connection, type)

                "ACK" -> onAck(connection)

                else -> logger.debug("tipo desconocido ignorado: {}", type)
            }
        } catch (e: Exception) {
            logger.warn("frame procesado con error: {}", e.message)
        }
    }

    // --- autenticacion ------------------------------------------------------

    private fun onAuthRequest(connection: RelayWebSocket, frame: JsonNode) {
        val request = codec.parseAuthRequest(frame)
        if (request == null) {
            logger.debug("AUTH_REQUEST con campos invalidos")
            connection.send(
                codec.authFail(AuthAdapter.ErrorCode.INVALID_AUTH_FLOW, "AUTH_REQUEST incompleto o mal formado")
            )
            return
        }

        when (val step = authAdapter.begin(request)) {
            is AuthStep.Challenge -> {
                connection.onChallengeIssued(request)
                connection.send(step.wire)
            }

            is AuthStep.Rejected -> {
                logger.info(
                    "AUTH_REQUEST rechazado para {}: {}",
                    request.identityId.value.take(8), step.wire,
                )
                connection.send(step.wire)
            }

            is AuthStep.Accepted -> logger.warn("AUTH_REQUEST no puede aceptarse sin respuesta")
        }
    }

    private fun onAuthResponse(connection: RelayWebSocket, frame: JsonNode) {
        val signature = codec.parseAuthResponseSignature(frame)
        if (signature == null) {
            connection.send(
                codec.authFail(AuthAdapter.ErrorCode.INVALID_AUTH_FLOW, "AUTH_RESPONSE sin firma valida")
            )
            return
        }

        when (val step = authAdapter.complete(connection.pendingAuth, signature)) {
            is AuthStep.Accepted -> {
                val identity = connection.pendingAuth?.identityId
                if (identity == null) {
                    // Inalcanzable: `complete` solo acepta sin `pendingAuth`.
                    connection.send(codec.authFail(AuthAdapter.ErrorCode.INVALID_AUTH_FLOW, "sesion sin identidad"))
                    return
                }
                establishSession(connection, identity, step.sessionId)
                connection.send(step.wire)
                logger.info("peer autenticado: {}", identity.value)
            }

            is AuthStep.Rejected -> {
                logger.info("AUTH_RESPONSE rechazado: {}", step.wire)
                connection.send(step.wire)
            }

            is AuthStep.Challenge -> logger.warn("AUTH_RESPONSE devolvio un challenge inesperado")
        }
    }

    /**
     * Registra los handlers de la identidad ya autenticada.
     *
     * El orden importa: primero el handler de senalizacion, que dispara
     * `PEER_ONLINE` a los demas, y despues el de datos, para que un
     * `MESSAGE` que llegue en el mismo instante no encuentre al destinatario
     * "no conectado" cuando si lo esta.
     */
    private fun establishSession(connection: RelayWebSocket, identity: IdentityId, sessionId: String) {
        // Una reconexion del mismo peer: el core ya cerro la sesion anterior.
        // Se sueltan los handlers viejos ANTES de autenticar, si los hubiera.
        connection.peerId?.let { previous ->
            relayServer.unregisterDataHandler(previous)
            relayServer.unregisterPeerHandler(previous)
        }

        connection.onAuthenticated(identity, sessionId)

        val signaling = relayServer.registerPeerHandler(identity) { signal ->
            deliverSignal(signal)
        }
        if (signaling.isFailure) {
            logger.warn("no se pudo registrar el handler de senalizacion: {}", signaling)
        }

        if (config.relayEnabled) {
            val data = relayServer.registerDataHandler(identity) { envelope -> deliverData(envelope) }
            if (data.isFailure) {
                logger.warn("no se pudo registrar el handler de datos: {}", data)
            }
        }
    }

    // --- datos --------------------------------------------------------------

    /**
     * `MESSAGE` entrante.
     *
     * El camino es: Base64 -> `ByteArray` -> `RelayServer.deliverData`. Del
     * `ByteArray` no se lee ni un bit. El anti-spoofing y el limite de
     * 65 535 B los aplica `deliverData` ANTES de rutear (§15.3): aqui no se
     * duplican.
     */
    private fun onMessage(connection: RelayWebSocket, frame: JsonNode) {
        val peerId = connection.peerId
        if (peerId == null) {
            sendRelayError(connection, RelayErrorCode.RELAY_NOT_AUTHORIZED, "MESSAGE antes de AUTH_OK", null)
            return
        }
        if (!config.relayEnabled) {
            sendRelayError(connection, RelayErrorCode.RELAY_NOT_AUTHORIZED, "camino de datos desactivado", null)
            return
        }

        val message = codec.parseMessage(frame)
        if (message == null) {
            // Base64 invalido o campos obligatorios ausentes: §14.1, el frame
            // se descarta ENTERO. No hay carga parcial, ni reparada, ni
            // reenvio a pelo.
            logger.warn("MESSAGE descartado: carga Base64 invalida o envelope incompleto")
            sendRelayError(connection, RelayErrorCode.RELAY_NOT_AUTHORIZED, "MESSAGE mal formado", null)
            return
        }

        val envelope = RelayDataEnvelope(
            from = message.from,
            to = message.to,
            payload = message.data,
            timestamp = now(),
        )
        when (val result = relayServer.deliverData(peerId, envelope)) {
            is RelayServerResult.Success -> Unit
            is RelayServerResult.Failure -> {
                logger.info("MESSAGE no enrutada: {}", result.message)
                sendRelayError(connection, mapErrorCode(result.error), result.message, null)
            }
        }
    }

    /** `MESSAGE` saliente: el payload vuelve a Base64 sin haber sido tocado. */
    private fun deliverData(envelope: RelayDataEnvelope) {
        val wire = codec.message(envelope.from, envelope.to, envelope.payload)
        withConnection(envelope.to) { it.send(wire) }
    }

    // --- senalizacion (extension) -------------------------------------------

    /**
     * `SIGNAL` entrante -> `RelayServer.handleSignal`.
     *
     * El anti-spoofing lo hace el core: `handleSignal` rechaza con
     * `SPOOFING_DETECTED` toda señal cuyo `from` no sea el peer autenticado
     * (§11). Aqui el `from` lo pone el core, no el cliente, precisamente para
     * que esa comparacion signifique algo.
     */
    private fun onSignal(connection: RelayWebSocket, frame: JsonNode) {
        val peerId = connection.peerId
        if (peerId == null) {
            sendRelayError(connection, RelayErrorCode.RELAY_NOT_AUTHORIZED, "SIGNAL antes de AUTH_OK", null)
            return
        }
        if (!config.signalingEnabled) {
            sendRelayError(connection, RelayErrorCode.RELAY_NOT_AUTHORIZED, "senalizacion desactivada", null)
            return
        }

        val incoming = codec.parseSignal(frame)
        if (incoming == null) {
            logger.debug("SIGNAL descartado: kind desconocido o payload ausente")
            return
        }

        val signal = toRelaySignal(peerId, incoming) ?: return
        when (val result = relayServer.handleSignal(peerId, signal)) {
            is RelayServerResult.Success -> Unit
            is RelayServerResult.Failure -> {
                logger.info("SIGNAL no enrutada: {}", result.message)
                sendRelayError(connection, mapErrorCode(result.error), result.message, null)
            }
        }
    }

    private fun toRelaySignal(from: IdentityId, incoming: IncomingSignal): RelaySignalMessage? {
        val timestamp = now()
        val text = incoming.text
        val bundle = incoming.contactBundle
        return when (incoming.kind) {
            SIGNAL_SDP_OFFER -> if (text == null) null else RelaySignalMessage.SdpOffer(from, incoming.to, text, timestamp)
            SIGNAL_SDP_ANSWER -> if (text == null) null else RelaySignalMessage.SdpAnswer(from, incoming.to, text, timestamp)
            SIGNAL_ICE_CANDIDATE -> if (text == null) null else RelaySignalMessage.IceCandidate(from, incoming.to, text, timestamp)
            SIGNAL_CONTACT_EXCHANGE -> if (bundle == null) null else RelaySignalMessage.ContactExchange(from, incoming.to, bundle, timestamp)
            else -> null
        }
    }

    /** `SIGNAL` saliente hacia el peer destino. */
    private fun deliverSignal(signal: RelaySignalMessage) {
        val fields: SignalFields = when (signal) {
            is RelaySignalMessage.SdpOffer -> SignalFields(SIGNAL_SDP_OFFER, sdp = signal.sdp)
            is RelaySignalMessage.SdpAnswer -> SignalFields(SIGNAL_SDP_ANSWER, sdp = signal.sdp)
            is RelaySignalMessage.IceCandidate -> SignalFields(SIGNAL_ICE_CANDIDATE, candidate = signal.candidate)
            is RelaySignalMessage.ContactExchange -> SignalFields(SIGNAL_CONTACT_EXCHANGE, contactBundle = signal.contactBundle)

            // PeerOnline/PeerOffline NO pasan por aqui: el core los convierte
            // en eventos de presencia, y esos salen por `broadcast`.
            is RelaySignalMessage.PeerOnline, is RelaySignalMessage.PeerOffline -> return
        }
        val wire = codec.signal(
            from = signal.from,
            to = signal.to,
            kind = fields.kind,
            sdp = fields.sdp,
            candidate = fields.candidate,
            contactBundle = fields.contactBundle,
        )
        withConnection(signal.to) { it.send(wire) }
    }

    // --- presencia y heartbeat ----------------------------------------------

    private fun onPing(connection: RelayWebSocket, frame: JsonNode) {
        val original = frame.get("messageId")?.takeIf { it.isTextual }?.asText()
        connection.send(codec.pong(original ?: ""))
    }

    /** El `PONG` solo confirma que el pipe sigue vivo; no navega estado. */
    private fun onPong(connection: RelayWebSocket) {
        logger.debug("PONG de {}", connection)
    }

    private fun onRelayToClient(connection: RelayWebSocket, type: String) {
        logger.debug("frame de rele->cliente recibido por el cliente: {} (descartado)", type)
    }

    /**
     * `ACK` entrante: se registra y se descarta.
     *
     * Deliberado. `ACK` es control de APLICACION (§11) y el dialecto B no le
     * da un campo `data` que transproducir, asi que no hay nada opaco que
     * mover. Implementar su reenvio exigiria que el rele entendiera
     * `originalMessageId` y el ciclo de vida de la sesion de aplicacion, que
     * es exactamente lo que §11.1 le prohibe.
     */
    private fun onAck(connection: RelayWebSocket) {
        logger.debug("ACK entrante de {}: el rele no implementa semantica de ACK (§11)", connection)
    }

    // --- salida a otras conexiones -------------------------------------------

    private fun broadcast(text: String) {
        connections.values.forEach { connection ->
            if (connection.isAuthenticated) connection.send(text)
        }
    }

    private fun withConnection(identity: IdentityId, action: (RelayWebSocket) -> Unit) {
        val target = connections.values.firstOrNull { it.peerId == identity && it.isOpen }
        if (target != null) action(target) else logger.debug("sin conexion para {}", identity.value.take(8))
    }

    // --- control (STORED / RELAY_EXPIRED / RELAY_ERROR) ----------------------

    /**
     * `RELAY_ERROR` al emisor.
     *
     * La codificacion es la de km-core (`JsonRelayControlCodec`), no una
     * reescritura: el mismo `STORED` que el cliente ya sabe leer.
     */
    private fun sendRelayError(
        connection: RelayWebSocket,
        code: RelayErrorCode,
        message: String,
        originalMessageId: MessageId?,
    ) {
        val notice = RelayErrorNotice(
            messageId = MessageId(UUID.randomUUID()),
            timestamp = now(),
            errorCode = code,
            errorMessage = message,
            originalMessageId = originalMessageId,
        )
        controlCodec.encodeError(notice)
            .onSuccess { connection.send(String(it, Charsets.UTF_8)) }
            .onFailure { logger.warn("no se pudo codificar RELAY_ERROR: {}", it.message) }
    }

    /**
     * `STORED` al emisor.
     *
     * Costura disponible, no camino vivo: el store-and-forward sigue sin
     * implementar (§13.3, P4), porque delivering bytes opacos a un
     * destinatario ausente exigiria inventar una clave de almacenamiento y una
     * politica de expiracion que el dialecto no define. El metodo existe
     * porque el codec ya esta y probado, y porque el dia que exista la logica
     * de guardado no habra que reescribir la serializacion.
     */
    fun sendStored(connection: RelayWebSocket, receipt: StoredReceipt) {
        controlCodec.encodeStored(receipt)
            .onSuccess { connection.send(String(it, Charsets.UTF_8)) }
            .onFailure { logger.warn("no se pudo codificar STORED: {}", it.message) }
    }

    /** `RELAY_EXPIRED`. Misma costura que [sendStored]. */
    fun sendRelayExpired(connection: RelayWebSocket, notice: RelayExpiredNotice) {
        controlCodec.encodeRelayExpired(notice)
            .onSuccess { connection.send(String(it, Charsets.UTF_8)) }
            .onFailure { logger.warn("no se pudo codificar RELAY_EXPIRED: {}", it.message) }
    }

    /**
     * `RelayServerError` (interno, nueve valores) -> `RelayErrorCode` (wire).
     *
     * §16.3 declara esta correspondencia como PENDIENTE y prohibe asumir que
     * el rele emite ningun codigo mientras no exista. Como el MVP necesita
     * responder algo cuando el core rechaza un frame, aqui se declara una
     * tabla, y se declara como lo que es: una eleccion de ESTE modulo, no un
     * hecho del protocolo. Es parcial: cubre los errores que el camino de
     * datos y el de senalizacion pueden producir, no los nueve del enum.
     */
    private fun mapErrorCode(error: RelayServerError): RelayErrorCode = when (error) {
        RelayServerError.PEER_NOT_ONLINE -> RelayErrorCode.PEER_NOT_FOUND
        RelayServerError.SESSION_NOT_FOUND,
        RelayServerError.SESSION_CLOSED,
        RelayServerError.SESSION_EXPIRED,
        RelayServerError.DUPLICATE_SESSION,
        RelayServerError.SPOOFING_DETECTED,
        -> RelayErrorCode.RELAY_NOT_AUTHORIZED

        // `deliverData` solo devuelve INVALID_MESSAGE por el limite de tamano
        // (§15.3), que es el unico `RelayErrorCode` de tamaño que existe.
        RelayServerError.INVALID_MESSAGE -> RelayErrorCode.MESSAGE_TOO_LARGE
        RelayServerError.AUTH_FAILED -> RelayErrorCode.RELAY_AUTH_FAILED
        RelayServerError.INTERNAL_ERROR -> RelayErrorCode.RELAY_HANDOFF_FAILED
    }

    private companion object {
        /**
         * Holgura sobre el Base64 para el resto del envelope JSON
         * (`type`, `messageId`, `from`, `to`, `timestamp`, comillas y llaves).
         * 1 KiB es de sobra para un envelope de este dialecto y esta por
         * debajo de lo que haria falta para que el limite mordiera de mas.
         */
        const val FRAME_ENVELOPE_ALLOWANCE: Int = 1024
    }
}

/**
 * Campos de texto de una `SIGNAL` de salida, ya clasificados por `kind`.
 *
 * Existe para que el `when` sobre el tipo sellado sea una EXPRESION con
 * valor, no un `when` sentencia con cuatro variables declaradas fuera: asi
 * el compilador obliga a cubrir cada subtipo y no se puede colar un
 * `PeerOnline` en el camino de senalizacion de datos.
 */
private data class SignalFields(
    val kind: String,
    val sdp: String? = null,
    val candidate: String? = null,
    val contactBundle: ByteArray? = null,
)
