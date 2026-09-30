package com.keymessage.core.node

import com.keymessage.core.api.KeyMessageCore
import com.keymessage.core.auth.AuthChallenge
import com.keymessage.core.auth.AuthOk
import com.keymessage.core.auth.AuthResponse
import com.keymessage.core.auth.AuthSession
import com.keymessage.core.auth.AuthSessionState
import com.keymessage.core.auth.Clock
import com.keymessage.core.auth.NonceReplayGuard
import com.keymessage.core.auth.TranscriptBuilder
import com.keymessage.core.codec.Decoder
import com.keymessage.core.codec.Encoder
import com.keymessage.core.codec.JsonDecoder
import com.keymessage.core.codec.JsonEncoder
import com.keymessage.core.codec.JsonRelayControlCodec
import com.keymessage.core.codec.RelayControlCodec
import com.keymessage.core.crypto.Ed25519
import com.keymessage.core.crypto.Ed25519Impl
import com.keymessage.core.crypto.KeyPair
import com.keymessage.core.km7.CapabilitySet
import com.keymessage.core.km7.Km7Negotiation
import com.keymessage.core.km7.NegotiationResult
import com.keymessage.core.model.*
import com.keymessage.core.kmid.KmIds
import java.util.UUID

/**
 * Implementacion del runtime de nodo.
 *
 * INVARIANTES
 *
 *   1. identity se construye SOLO via [NodeIdentity.fromKeyPair], que garantiza:
 *      nodeId == SHA-256("KM-ID-IDENTITY" || publicKey)
 *
 *   2. El KeyPair se conserva para firma y autenticacion; NO se expone en
 *      el modelo de dominio.
 *
 *   3. Peers autenticados se almacenan en [peerContexts] via [PeerContext.authenticated],
 *      que verifica:
 *      - authSession.state == AUTHENTICATED
 *      - peerId == announcement.identity.nodeId
 *
 *   4. identityId != publicKey: identityId es el identificador (64 hex chars),
 *      publicKey es la clave Ed25519 (32 bytes).
 */
class NodeRuntimeImpl(
    override val identity: NodeIdentity,
    override val capabilities: Set<NodeCapability>,
    override val clientCore: KeyMessageCore? = null,
    override val relayService: RelayService? = null,
    private val keyPair: KeyPair? = null,
    private val ed25519: Ed25519 = Ed25519Impl(),
    private val encoder: Encoder = JsonEncoder(),
    private val decoder: Decoder = JsonDecoder(),
    private val controlCodec: RelayControlCodec = JsonRelayControlCodec(),
) : NodeRuntime {

    /**
     * Peer contexts: peers autenticados que han completado el handshake.
     * Solo se añaden via [authenticatePeer].
     */
    private val _peerContexts = mutableMapOf<IdentityId, PeerContext>()
    val peerContexts: Map<IdentityId, PeerContext> get() = _peerContexts.toMap()

    /**
     * Sesiones activas (no necesariamente autenticadas).
     */
    private val activeSessions = mutableMapOf<IdentityId, AuthSession>()

    companion object {
        /**
         * Construye un NodeRuntime desde un par de claves Ed25519.
         *
         * Garantiza el invariante:
         *     nodeId == SHA-256("KM-ID-IDENTITY" || publicKey)
         *
         * @param publicKey Clave publica Ed25519 (32 bytes)
         * @param privateKey Clave privada Ed25519 (se conserva para firma, NO se expone)
         */
        fun fromKeyPair(
            publicKey: ByteArray,
            privateKey: ByteArray,
            nodeName: String? = null,
            capabilities: Set<NodeCapability> = setOf(NodeCapability.CLIENT),
            clientCore: KeyMessageCore? = null,
            relayService: RelayService? = null,
            ed25519: Ed25519 = Ed25519Impl(),
        ): NodeRuntimeImpl {
            val kp = KeyPair(publicKey, privateKey)
            return NodeRuntimeImpl(
                identity = NodeIdentity.fromKeyPair(publicKey, nodeName),
                capabilities = capabilities,
                clientCore = clientCore,
                relayService = relayService,
                keyPair = kp,
                ed25519 = ed25519,
            )
        }
    }

    private var started = false
    private val inboundHandlers = mutableListOf<(Message) -> Unit>()
    private val peerTransports = mutableMapOf<IdentityId, RelayTransport>()
    private val onlinePeers = mutableSetOf<IdentityId>()

    init {
        require(capabilities.isNotEmpty()) { "Node must declare at least one capability" }
        if (NodeCapability.CLIENT in capabilities) {
            require(clientCore != null) { "CLIENT capability requires a KeyMessageCore" }
        }
        if (NodeCapability.RELAY in capabilities || NodeCapability.STORE_AND_FORWARD in capabilities) {
            require(relayService != null) { "RELAY/STORE_AND_FORWARD capability requires a RelayService" }
        }
        relayService?.let { rs ->
            rs.registerStoredHandler { stored -> sendStored(stored) }
            rs.registerAckForwardedHandler { ack -> forwardAck(ack) }
        }
        // Si tenemos keyPair, verificar que identity deriva de el
        if (keyPair != null) {
            val expected = NodeIdentity.fromKeyPair(keyPair.publicKey, identity.nodeName)
            require(identity.nodeId == expected.nodeId) {
                "identity.nodeId no deriva de keyPair.publicKey"
            }
        }
    }

    override fun start(): Result<Unit> = runCatching {
        if (started) return@runCatching
        if (NodeCapability.CLIENT in capabilities) clientCore?.start()?.getOrThrow()
        started = true
    }

    override fun stop() {
        if (!started) return
        if (NodeCapability.CLIENT in capabilities) clientCore?.stop()
        started = false
    }

    override fun isStarted(): Boolean = started

    override fun attachPeerTransport(peerId: IdentityId, transport: RelayTransport) {
        peerTransports[peerId] = transport
        transport.onPeerOnline { p -> peerOnline(p) }
        transport.onPeerOffline { p -> peerOffline(p) }
        transport.setMessageHandler { data ->
            decoder.decodeMessage(data).getOrNull()?.let { handleInboundMessage(it) }
        }
        transport.setAckHandler { data ->
            decoder.decodeAck(data).getOrNull()?.let { handleInboundAck(it) }
        }
        peerOnline(peerId)
    }

    override fun detachPeerTransport(peerId: IdentityId) {
        peerTransports.remove(peerId)
        onlinePeers.remove(peerId)
        activeSessions.remove(peerId)
        _peerContexts.remove(peerId)
    }

    override fun peerOnline(peerId: IdentityId) {
        if (!onlinePeers.add(peerId)) return
        deliverStoredMessages(peerId)
    }

    override fun peerOffline(peerId: IdentityId) {
        onlinePeers.remove(peerId)
    }

    override fun handleInboundMessage(message: Message): Result<RelayDecision> = runCatching {
        val decision = when {
            message.to == identity.nodeId -> {
                if (NodeCapability.CLIENT in capabilities) {
                    inboundHandlers.forEach { it(message) }
                    RelayDecision.Duplicate
                } else {
                    RelayDecision.Rejected(FailureCode.INVALID_MESSAGE)
                }
            }

            NodeCapability.RELAY in capabilities || NodeCapability.STORE_AND_FORWARD in capabilities ->
                relayService?.handleMessage(message, onlinePeers.contains(message.to))?.getOrThrow()
                    ?: RelayDecision.Rejected(FailureCode.PEER_NOT_FOUND)

            else -> RelayDecision.Rejected(FailureCode.PEER_NOT_FOUND)
        }
        when (decision) {
            is RelayDecision.Rejected -> sendRelayError(message.from, message.messageId, decision.code.toRelayError())
            is RelayDecision.Forwarded -> forwardMessage(message)
            else -> {}
        }
        decision
    }

    override fun handleInboundAck(ack: Ack): Result<Unit> {
        if (NodeCapability.RELAY in capabilities || NodeCapability.STORE_AND_FORWARD in capabilities) {
            relayService?.handleAck(ack)?.getOrThrow()
        }
        return Result.success(Unit)
    }

    override fun expireStored(now: Long): Result<List<StoredMessage>> {
        val rs = relayService ?: return Result.success(emptyList())
        val expired = rs.expire(now).getOrThrow()
        expired.forEach { sendRelayExpired(it) }
        return Result.success(expired)
    }

    override fun registerInboundHandler(handler: (Message) -> Unit) {
        inboundHandlers.add(handler)
    }

    // ===================================================================
    // Runtime.2 — NodeAnnouncement
    // ===================================================================

    /**
     * Crea y firma un NodeAnnouncement para este nodo.
     *
     * @throws IllegalStateException si el runtime no tiene keyPair.
     */
    fun createNodeAnnouncement(
        endpoints: List<NodeEndpoint> = emptyList(),
        nodeCapabilities: Set<NodeCapability> = capabilities,
        limits: RelayLimits? = null,
        timestamp: Long = System.currentTimeMillis(),
    ): NodeAnnouncement {
        val kp = keyPair ?: throw IllegalStateException("NodeRuntime sin KeyPair no puede firmar anuncios")
        val partial = NodeAnnouncement(
            messageId = MessageId(UUID.randomUUID()),
            timestamp = timestamp,
            protocolVersion = "1.0",
            identity = identity,
            endpoints = endpoints,
            capabilities = nodeCapabilities,
            limits = limits,
        )
        val signableBytes = NodeAnnouncementJsonCodec.signableJson(partial)
        val sig = ed25519.sign(kp.privateKey, signableBytes)
        return partial.copy(signature = sig.bytes)
    }

    /**
     * Verifica un NodeAnnouncement recibido.
     *
     * @param fullJsonBytes JSON completo recibido (con signature).
     * @return NodeAnnouncement decodificado si verifica.
     * @throws NodeAnnouncementVerificationException si la verificacion falla.
     */
    fun verifyNodeAnnouncement(fullJsonBytes: ByteArray): NodeAnnouncement {
        val announcement = NodeAnnouncementJsonCodec.decode(fullJsonBytes)
        val signableBytes = NodeAnnouncementJsonCodec.signableJson(announcement)
        announcement.verifyWireBytes(signableBytes).getOrThrow()
        return announcement
    }

    // ===================================================================
    // Runtime.3 — Authentication
    // ===================================================================

    /**
     * Inicia una sesion de autenticacion como INITIATOR con un peer.
     *
     * @param peerId identity del peer a autenticar.
     * @param challenge challenge recibido del relay/peer.
     * @param responderIdentityId identityId del responder en el challenge.
     * @return AuthSession en estado CHALLENGE_RECEIVED.
     */
    fun startAuthSession(
        peerId: IdentityId,
        challenge: AuthChallenge,
        responderIdentityId: String,
    ): AuthSession {
        val kp = keyPair ?: throw IllegalStateException("NodeRuntime sin KeyPair no puede autenticar")
        val session = AuthSession.initiator(
            keyPair = kp,
            identityId = identity.nodeId.value,
            ed25519 = ed25519,
        )
        session.receiveChallenge(challenge).getOrThrow()
        activeSessions[peerId] = session
        return session
    }

    /**
     * Construye un AUTH_RESPONSE a partir de una sesion iniciada.
     */
    fun buildAuthResponse(peerId: IdentityId, peerIdentityId: String): AuthResponse {
        val session = activeSessions[peerId]
            ?: throw IllegalStateException("No hay sesion activa para peer $peerId")
        return session.buildResponse(peerIdentityId)
    }

    /**
     * Marca que el response fue enviado.
     */
    fun markAuthResponseSent(peerId: IdentityId) {
        activeSessions[peerId]?.markResponseSent()
    }

    /**
     * Recibe y verifica un AUTH_OK.
     *
     * @return PeerContext con el peer autenticado.
     * @throws IllegalStateException si la autenticacion falla.
     */
    fun receiveAuthOk(
        peerId: IdentityId,
        authOk: AuthOk,
        expectedPeerIdentityId: String,
        announcement: NodeAnnouncement,
    ): PeerContext {
        val session = activeSessions[peerId]
            ?: throw IllegalStateException("No hay sesion activa para peer $peerId")
        session.receiveAuthOk(authOk, expectedPeerIdentityId).getOrThrow()

        val context = PeerContext.authenticated(
            remotePeerId = peerId,
            publicKey = authOk.responderPublicKey,
            announcement = announcement,
            authSession = session,
        )
        _peerContexts[peerId] = context
        return context
    }

    /**
     * Inicia una sesion como RESPONDER y construye el AUTH_CHALLENGE.
     */
    fun createAuthChallenge(peerId: IdentityId): AuthChallenge {
        val kp = keyPair ?: throw IllegalStateException("NodeRuntime sin KeyPair no puede autenticar")
        val nonce = ByteArray(16).also { java.security.SecureRandom().nextBytes(it) }
        val challenge = AuthChallenge(
            nonce = nonce,
            timestampMillis = System.currentTimeMillis(),
            version = 3,
            responderIdentityId = identity.nodeId.value,
        )

        val session = AuthSession.responder(
            keyPair = kp,
            identityId = identity.nodeId.value,
            ed25519 = ed25519,
        )
        session.receiveChallenge(challenge).getOrThrow()
        activeSessions[peerId] = session
        return challenge
    }

    /**
     * Verifica un AuthResponse recibido (V1-V6) y construye AUTH_OK.
     *
     * @return AuthOk listo para enviar al peer.
     */
    fun verifyAuthResponseAndBuildOk(
        peerId: IdentityId,
        response: AuthResponse,
        challenge: AuthChallenge,
    ): AuthOk {
        val kp = keyPair ?: throw IllegalStateException("NodeRuntime sin KeyPair no puede autenticar")

        // V1-V6: verificar el challenge-response
        val verifier = com.keymessage.core.auth.AuthVerifier(
            ed25519 = ed25519,
            clock = Clock { challenge.timestampMillis },
            replayGuard = NonceReplayGuard { true },
        )
        val error = verifier.verifyChallengeResponse(challenge, response)
        require(error == null) { "AuthResponse invalido: $error" }

        // Construir AUTH_OK
        val serverNonce = ByteArray(16).also { java.security.SecureRandom().nextBytes(it) }
        val sessionId = generateSessionId()
        val serverTranscript = TranscriptBuilder.serverAuthTranscript(
            sessionId = sessionId,
            serverNonce = serverNonce,
            initiatorIdentityId = response.identityId,
        )
        val serverSig = ed25519.sign(kp.privateKey, serverTranscript)

        val authOk = AuthOk(
            sessionId = sessionId,
            serverNonce = serverNonce,
            signature = serverSig.bytes,
            responderPublicKey = kp.publicKey,
        )

        // Verificar el AUTH_OK localmente
        val okError = verifier.verifyAuthOk(authOk, response.identityId)
        require(okError == null) { "AUTH_OK generado no verifica: $okError" }

        // Crear sesion como responder
        val session = AuthSession.responder(
            keyPair = kp,
            identityId = identity.nodeId.value,
            ed25519 = ed25519,
            clock = Clock { challenge.timestampMillis },
            replayGuard = NonceReplayGuard { true },
        )
        session.receiveChallenge(challenge).getOrThrow()
        activeSessions[peerId] = session
        return authOk
    }

    // ===================================================================
    // Runtime.4 — KM-0007 Negotiation binding
    // ===================================================================

    /**
     * Negocia capacidades con un peer autenticado.
     *
     * @param peerId identity del peer.
     * @param localCapSet capacidades locales.
     * @param peerCapSet capacidades del peer (desde su anuncio).
     * @param requiredCategories categorias obligatorias.
     * @return PeerContext actualizado con la negociacion vinculada.
     */
    fun negotiateCapabilities(
        peerId: IdentityId,
        localCapSet: CapabilitySet,
        peerCapSet: CapabilitySet,
        requiredCategories: Set<String> = emptySet(),
    ): PeerContext {
        val ctx = _peerContexts[peerId]
            ?: throw IllegalStateException("Peer $peerId no esta autenticado")
        val result = Km7Negotiation.negotiate(localCapSet, peerCapSet, requiredCategories)
        require(!result.isFailure) { "Negociacion fallo: ${result.failureReason}" }
        val updated = ctx.withNegotiation(result)
        _peerContexts[peerId] = updated
        return updated
    }

    /**
     * Obtiene el negotiationHash de un peer autenticado y negociado.
     */
    fun getNegotiationHash(peerId: IdentityId): ByteArray? =
        _peerContexts[peerId]?.negotiationHash

    // ===================================================================
    // Internal
    // ===================================================================

    private fun sendStored(stored: StoredMessage) {
        val receipt = StoredReceipt(
            messageId = MessageId.random(),
            timestamp = stored.storedAt,
            originalMessageId = stored.messageId,
            to = stored.recipientId,
            expiresAt = stored.expiresAt,
            relayNodeId = identity.nodeId
        )
        sendFrame(stored.senderId, controlCodec.encodeStored(receipt))
    }

    private fun forwardAck(ack: Ack) {
        sendFrame(ack.to, encoder.encodeAck(ack))
    }

    private fun sendRelayExpired(stored: StoredMessage) {
        val notice = RelayExpiredNotice(
            messageId = MessageId.random(),
            timestamp = stored.expiresAt,
            originalMessageId = stored.messageId,
            to = stored.senderId,
            reason = "TTL_EXPIRED"
        )
        sendFrame(stored.senderId, controlCodec.encodeRelayExpired(notice))
    }

    private fun sendRelayError(peerId: IdentityId, originalMessageId: MessageId?, code: RelayErrorCode) {
        val error = RelayErrorNotice(
            messageId = MessageId.random(),
            timestamp = System.currentTimeMillis(),
            errorCode = code,
            errorMessage = null,
            originalMessageId = originalMessageId
        )
        sendFrame(peerId, controlCodec.encodeError(error))
    }

    private fun sendFrame(peerId: IdentityId, frame: Result<ByteArray>) {
        val data = frame.getOrNull() ?: return
        peerTransports[peerId]?.send(data, peerId)
    }

    private fun forwardMessage(message: Message) {
        sendFrame(message.to, encoder.encodeMessage(message))
    }

    /** Genera un sessionId aleatorio (40 hex chars = 160 bits). */
    private fun generateSessionId(): String {
        val bytes = ByteArray(20).also { java.security.SecureRandom().nextBytes(it) }
        return bytes.joinToString("") { "%02x".format(it) }
    }

    private fun deliverStoredMessages(peerId: IdentityId) {
        val rs = relayService ?: return
        val stored = rs.relayStore.getForRecipient(peerId).getOrNull().orEmpty()
        stored.forEach { message ->
            sendFrame(peerId, Result.success(message.wireMessage))
        }
    }
}