package com.keymessage.core.node

import com.keymessage.core.auth.*
import com.keymessage.core.crypto.Ed25519
import com.keymessage.core.crypto.KeyPair
import com.keymessage.core.model.FailureCode
import com.keymessage.core.model.IdentityId
import com.keymessage.core.model.RelayErrorNotice
import com.keymessage.core.model.RelayErrorCode
import com.keymessage.core.model.MessageId
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Error codes for RelayServer operations.
 */
enum class RelayServerError {
    SESSION_NOT_FOUND,
    SESSION_CLOSED,
    SESSION_EXPIRED,
    PEER_NOT_ONLINE,
    SPOOFING_DETECTED,
    AUTH_FAILED,
    INVALID_MESSAGE,
    DUPLICATE_SESSION,
    INTERNAL_ERROR,
}

/**
 * Result type for RelayServer operations.
 */
sealed class RelayServerResult<out T> {
    data class Success<T>(val value: T) : RelayServerResult<T>()
    data class Failure(val error: RelayServerError, val message: String) : RelayServerResult<Nothing>()

    val isSuccess: Boolean get() = this is Success
    val isFailure: Boolean get() = this is Failure

    fun getOrThrow(): T = when (this) {
        is Success -> value
        is Failure -> throw IllegalStateException("$error: $message")
    }

    fun <R> map(transform: (T) -> R): RelayServerResult<R> = when (this) {
        is Success -> Success(transform(value))
        is Failure -> this
    }
}

/**
 * Servidor relay con autenticacion KM-0002, sesiones y routing verificado.
 *
 * KM-0003: el relay es un intermediario de senalizacion, NO una autoridad
 * de identidad peer-to-peer. La autenticacion cliente↔relay (KM-0002) es
 * independiente de la autenticacion peer↔peer.
 *
 * INVARIANTES:
 * - `sender` en cada mensaje DEBE coincidir con `session.identityId`.
 * - Un mensaje con `from` falsificado es rechazado (SPOOFING_DETECTED).
 * - `sessionId` es opaco y vinculado a la conexion, no intercambiable.
 * - Solo sesiones AUTHENTICATED pueden enviar/recibir senales.
 *
 * USAGE:
 *   val server = RelayServer(relayKeyPair, ed25519, relayService, clock)
 *
 *   // Auth flow
 *   val challenge = server.createChallenge(aliceId)
 *   // ... client builds and sends AuthResponse ...
 *   val session = server.authenticate(aliceId, authResponse).getOrThrow()
 *
 *   // Peer B connects
 *   val sessionB = server.authenticate(bobId, bobResponse).getOrThrow()
 *
 *   // Register peer handler (called by transport when message arrives)
 *   server.registerPeerHandler(bobId) { signal ->
 *       // transport sends signal to Bob's WebSocket
 *   }
 *
 *   // Alice sends signal to Bob
 *   server.handleSignal(aliceId, SdpOffer(aliceId, bobId, sdp, now))
 *
 *   // Disconnect
 *   server.disconnect(aliceId)
 */
class RelayServer(
    private val relayKeyPair: KeyPair,
    private val ed25519: Ed25519,
    private val relayService: RelayService,
    private val relayIdentityId: IdentityId = IdentityId(
        deriveIdentityId(relayKeyPair.publicKey)
    ),
    private val now: () -> Long = System::currentTimeMillis,
    private val sessionExpiryMs: Long = 300_000L,
    private val timestampWindowMs: Long = 300_000L,
    private val nonceSize: Int = 16,
    /**
     * Tope del payload de datos. Evita que el relay se use como amplificador
     * de memoria con un unico emisor: lo que cabe es lo que un `SecureFrame`
     * puede transportar, no mas.
     */
    private val maxDataPayloadBytes: Int = 65_535,
) {
    // Sesiones activas: identityId -> RelaySession
    private val sessions = ConcurrentHashMap<IdentityId, RelaySession>()

    // Handlers de peers conectados: identityId -> handler para senales entrantes
    private val peerHandlers = ConcurrentHashMap<IdentityId, (RelaySignalMessage) -> Unit>()
    private val dataHandlers = ConcurrentHashMap<IdentityId, (RelayDataEnvelope) -> Unit>()

    // Handlers de eventos de presencia
    private val onlineHandlers = mutableListOf<(IdentityId) -> Unit>()
    private val offlineHandlers = mutableListOf<(IdentityId) -> Unit>()

    // Challenges pendientes: identityId -> AuthChallenge
    private val pendingChallenges = ConcurrentHashMap<IdentityId, AuthChallenge>()

    // ===================================================================
    // Autenticacion (KM-0002)
    // ===================================================================

    /**
     * Crea un challenge de autenticacion para el peer.
     *
     * El challenge queda asociado al peer hasta que se complete
     * la autenticacion o expire.
     */
    fun createChallenge(clientIdentityId: IdentityId): AuthChallenge {
        val nonce = ByteArray(nonceSize).also { java.security.SecureRandom().nextBytes(it) }
        val challenge = AuthChallenge(
            nonce = nonce,
            timestampMillis = now(),
            version = 3,
            responderIdentityId = relayIdentityId.value,
        )
        pendingChallenges[clientIdentityId] = challenge
        return challenge
    }

    /**
     * Verifica el AuthResponse y crea una sesion autenticada.
     *
     * Pasos:
     * 1. Verifica que el challenge existe.
     * 2. Verifica el AuthResponse contra AuthVerifier (V1-V6).
     * 3. Crea RelaySession con sessionId generado por el relay.
     * 4. Genera y devuelve AuthOk firmado por el relay.
     */
    fun authenticate(
        clientIdentityId: IdentityId,
        response: AuthResponse,
    ): RelayServerResult<Pair<RelaySession, AuthOk>> {
        // 1. Challenge exists
        val challenge = pendingChallenges[clientIdentityId]
            ?: return RelayServerResult.Failure(
                RelayServerError.AUTH_FAILED,
                "no hay challenge pendiente para ${clientIdentityId.value}"
            )

        // 2. Verify AuthResponse
        val verifier = AuthVerifier(
            ed25519 = ed25519,
            clock = Clock { now() },
            replayGuard = NonceReplayGuard { true },
        )
        val authError = verifier.verifyChallengeResponse(challenge, response)
        if (authError != null) {
            pendingChallenges.remove(clientIdentityId)
            return RelayServerResult.Failure(
                RelayServerError.AUTH_FAILED,
                "verificacion de challenge fallo: $authError"
            )
        }

        // 3. Verify identityId matches publicKey
        val expectedId = deriveIdentityId(response.publicKey)
        if (!clientIdentityId.value.equals(expectedId, ignoreCase = true)) {
            pendingChallenges.remove(clientIdentityId)
            return RelayServerResult.Failure(
                RelayServerError.AUTH_FAILED,
                "identityId ${clientIdentityId.value} no deriva de publicKey"
            )
        }

        pendingChallenges.remove(clientIdentityId)

        // 4. Check for existing session
        val existing = sessions[clientIdentityId]
        if (existing != null && existing.isActive) {
            // Session exists - this is a re-auth. Close old session.
            sessions[clientIdentityId] = existing.close()
        }

        // 5. Create session and AuthOk
        val sessionId = generateSessionId()
        val session = RelaySession(
            sessionId = sessionId,
            identityId = clientIdentityId,
            authenticatedAt = now(),
        )
        val serverNonce = ByteArray(16).also { java.security.SecureRandom().nextBytes(it) }
        val serverTranscript = TranscriptBuilder.serverAuthTranscript(
            sessionId = sessionId,
            serverNonce = serverNonce,
            initiatorIdentityId = clientIdentityId.value,
        )
        val sig = ed25519.sign(relayKeyPair.privateKey, serverTranscript)
        val authOk = AuthOk(
            sessionId = sessionId,
            serverNonce = serverNonce,
            responderPublicKey = relayKeyPair.publicKey,
            signature = sig.bytes,
        )

        sessions[clientIdentityId] = session
        return RelayServerResult.Success(session to authOk)
    }

    // ===================================================================
    // Routing de senales
    // ===================================================================

    /**
     * Maneja una senal entrante de un peer autenticado.
     *
     * INVARIANTE CRITICA: verifica que `signal.from == peerId` (contexto
     * autenticado). Si no coinciden, rechaza con SPOOFING_DETECTED.
     */
    fun handleSignal(
        peerId: IdentityId,
        signal: RelaySignalMessage,
    ): RelayServerResult<Unit> {
        // 1. Verify session exists and is active
        val session = sessions[peerId]
            ?: return RelayServerResult.Failure(
                RelayServerError.SESSION_NOT_FOUND,
                "no hay sesion para ${peerId.value}"
            )
        if (!session.isActive) {
            return RelayServerResult.Failure(
                RelayServerError.SESSION_CLOSED,
                "sesion de ${peerId.value} esta cerrada"
            )
        }
        if (now() - session.authenticatedAt > sessionExpiryMs) {
            sessions[peerId] = session.close()
            return RelayServerResult.Failure(
                RelayServerError.SESSION_EXPIRED,
                "sesion de ${peerId.value} expirada"
            )
        }

        // 2. Anti-spoofing: signal.from DEBE coincidir con peerId
        if (signal.from != peerId) {
            return RelayServerResult.Failure(
                RelayServerError.SPOOFING_DETECTED,
                "spoofing: sender '${signal.from.value}' != autenticado '${peerId.value}'"
            )
        }

        // 3. Route to recipient
        return routeSignal(signal)
    }

    /**
     * Rutea una senal verificada al destinatario.
     */
    private fun routeSignal(signal: RelaySignalMessage): RelayServerResult<Unit> {
        val recipientHandler = peerHandlers[signal.to]

        return when (signal) {
            is RelaySignalMessage.PeerOnline,
            is RelaySignalMessage.PeerOffline -> {
                // Presence notifications are broadcast to online handlers
                when (signal) {
                    is RelaySignalMessage.PeerOnline -> {
                        onlineHandlers.forEach { it(signal.to) }
                    }
                    is RelaySignalMessage.PeerOffline -> {
                        offlineHandlers.forEach { it(signal.to) }
                    }
                    else -> {} // unreachable
                }
                RelayServerResult.Success(Unit)
            }

            is RelaySignalMessage.SdpOffer,
            is RelaySignalMessage.SdpAnswer,
            is RelaySignalMessage.IceCandidate,
            is RelaySignalMessage.ContactExchange -> {
                // Signaling messages require recipient to be online
                if (recipientHandler == null) {
                    RelayServerResult.Failure(
                        RelayServerError.PEER_NOT_ONLINE,
                        "${signal.to.value} no esta conectado al relay"
                    )
                } else {
                    recipientHandler(signal)
                    RelayServerResult.Success(Unit)
                }
            }
        }
    }

    // ===================================================================
    // Camino de DATOS (3Q.3)
    // ===================================================================

    /**
     * Entrega bytes opacos a un peer autenticado.
     *
     * Misma disciplina anti-spoofing que [handleSignal]: `envelope.from` DEBE
     * coincidir con la identidad de la sesion autenticada. Un `from`
     * suplantado se rechaza ANTES de rutear, nunca despues.
     *
     * El relay NO interpreta `payload`. Solo lo mueve. No existe forma de que
     * este camino exponga el contenido: el `payload` es ciphertext de
     * `SecureFrame` y el relay no posee ninguna clave de sesion.
     */
    fun deliverData(peerId: IdentityId, envelope: RelayDataEnvelope): RelayServerResult<Unit> {
        val session = sessions[peerId]
            ?: return RelayServerResult.Failure(
                RelayServerError.SESSION_NOT_FOUND,
                "no hay sesion para ${peerId.value}",
            )
        if (!session.isActive) {
            return RelayServerResult.Failure(
                RelayServerError.SESSION_CLOSED,
                "sesion de ${peerId.value} esta cerrada",
            )
        }
        if (envelope.payload.size > maxDataPayloadBytes) {
            return RelayServerResult.Failure(
                RelayServerError.INVALID_MESSAGE,
                "payload de ${envelope.payload.size}B excede el maximo de ${maxDataPayloadBytes}B",
            )
        }
        // Anti-spoofing: identico al de las senales.
        if (envelope.from != peerId) {
            return RelayServerResult.Failure(
                RelayServerError.SPOOFING_DETECTED,
                "spoofing: sender '${envelope.from.value}' != autenticado '${peerId.value}'",
            )
        }
        val recipient = dataHandlers[envelope.to]
            ?: return RelayServerResult.Failure(
                RelayServerError.PEER_NOT_ONLINE,
                "${envelope.to.value} no esta conectado al relay",
            )
        recipient(envelope)
        return RelayServerResult.Success(Unit)
    }

    /**
     * Registra el handler de DATOS de un peer.
     *
     * Separado de [registerPeerHandler] a proposito: uno es senalizacion
     * (que el relay interpreta lo minimo) y otro son bytes opacos (que el
     * relay no puede interpretar). Mezclarlos invites a que el camino de datos
     * herede semantica que no le corresponde.
     */
    fun registerDataHandler(peerId: IdentityId, handler: (RelayDataEnvelope) -> Unit): RelayServerResult<Unit> {
        if (!sessions.containsKey(peerId)) {
            return RelayServerResult.Failure(
                RelayServerError.SESSION_NOT_FOUND,
                "no hay sesion para ${peerId.value}",
            )
        }
        dataHandlers[peerId] = handler
        return RelayServerResult.Success(Unit)
    }

    fun unregisterDataHandler(peerId: IdentityId) {
        dataHandlers.remove(peerId)
    }

    fun dataHandlerRegistered(peerId: IdentityId): Boolean = dataHandlers.containsKey(peerId)

    // ===================================================================
    // Presencia de peers
    // ===================================================================

    /**
     * Registra un handler para recibir senales destinadas a un peer.
     * Llamado por el transporte cuando el peer se conecta.
     */
    fun registerPeerHandler(peerId: IdentityId, handler: (RelaySignalMessage) -> Unit): RelayServerResult<Unit> {
        val session = sessions[peerId]
            ?: return RelayServerResult.Failure(
                RelayServerError.SESSION_NOT_FOUND,
                "no hay sesion para ${peerId.value}"
            )
        if (!session.isActive) {
            return RelayServerResult.Failure(
                RelayServerError.SESSION_CLOSED,
                "sesion de ${peerId.value} esta cerrada"
            )
        }
        peerHandlers[peerId] = handler
        // Notificar online
        val online = RelaySignalMessage.PeerOnline(peerId, peerId, now())
        onlineHandlers.forEach { it(peerId) }
        return RelayServerResult.Success(Unit)
    }

    /**
     * Elimina el handler de un peer (desconexion o fallo).
     */
    fun unregisterPeerHandler(peerId: IdentityId) {
        peerHandlers.remove(peerId)
        // Notificar offline
        offlineHandlers.forEach { it(peerId) }
    }

    /**
     * Verifica si un peer esta conectado (tiene handler registrado).
     */
    fun isPeerOnline(peerId: IdentityId): Boolean = peerHandlers.containsKey(peerId)

    /**
     * Retorna la lista de peers actualmente conectados.
     */
    fun onlinePeers(): List<IdentityId> = peerHandlers.keys.toList()

    /**
     * Registra handler para notificaciones de peer online.
     */
    fun onPeerOnline(handler: (IdentityId) -> Unit) {
        onlineHandlers.add(handler)
    }

    /**
     * Registra handler para notificaciones de peer offline.
     */
    fun onPeerOffline(handler: (IdentityId) -> Unit) {
        offlineHandlers.add(handler)
    }

    // ===================================================================
    // Ciclo de vida de sesion
    // ===================================================================

    /**
     * Obtiene la sesion de un peer.
     */
    fun getSession(peerId: IdentityId): RelaySession? = sessions[peerId]

    /**
     * Cierra la sesion de un peer.
     */
    fun disconnect(peerId: IdentityId): RelayServerResult<Unit> {
        val session = sessions[peerId]
            ?: return RelayServerResult.Failure(
                RelayServerError.SESSION_NOT_FOUND,
                "no hay sesion para ${peerId.value}"
            )
        sessions[peerId] = session.close()
        unregisterPeerHandler(peerId)
        return RelayServerResult.Success(Unit)
    }

    /**
     * Cierra todas las sesiones activas.
     */
    fun disconnectAll() {
        val allPeers = sessions.keys.toList()
        allPeers.forEach { disconnect(it) }
    }

    /**
     * Obtiene el numero de sesiones activas.
     */
    fun activeSessionCount(): Int = sessions.count { it.value.isActive }

    // ===================================================================
    // Internal
    // ===================================================================

    private fun generateSessionId(): String {
        val bytes = ByteArray(20).also { java.security.SecureRandom().nextBytes(it) }
        return bytes.joinToString("") { "%02x".format(it) }
    }

    companion object {
        fun deriveIdentityId(publicKey: ByteArray): String {
            val digest = java.security.MessageDigest.getInstance("SHA-256")
            digest.update("KM-ID-IDENTITY".encodeToByteArray())
            digest.update(publicKey)
            return digest.digest().joinToString("") { "%02x".format(it) }
        }
    }
}