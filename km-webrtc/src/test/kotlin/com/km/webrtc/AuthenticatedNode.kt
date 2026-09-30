package com.km.webrtc

import com.km.auth.AuthChallenge
import com.km.auth.AuthOk
import com.km.auth.AuthSession
import com.km.auth.Clock
import com.km.auth.RelayAuthHandler
import com.km.auth.TranscriptBuilder
import com.km.crypto.Ed25519
import com.km.crypto.Ed25519Impl
import com.km.crypto.KeyPair
import com.km.model.IdentityId
import com.km.model.MessageId
import com.km.model.NodeAnnouncement
import com.km.model.NodeAnnouncementJsonCodec
import com.km.model.NodeCapability
import com.km.model.NodeIdentity
import com.km.node.PeerContext
import com.km.node.PeerTransportManager
import com.km.node.RelayServer
import com.km.node.RelaySignalMessage
import java.security.MessageDigest
import java.util.UUID

/**
 * Fixture 3N.4 — nodos con identidad y autenticacion REAL de km-core.
 *
 * Reproduce la cadena completa hasta PeerContext AUTENTICATED:
 *   KeyPair -> identityId -> NodeAnnouncement -> AuthSession -> PeerContext
 *
 * No usa atajos: la identidad se deriva de la clave publica (KM-ID-0001)
 * y la autenticacion usa el transcript real de KM-0002.
 */
class AuthenticatedNode(
    val ed25519: Ed25519,
    val label: String,
) {
    val keyPair: KeyPair = ed25519.generateKeyPair()
    val identityId: IdentityId = IdentityId(deriveId(keyPair.publicKey))

    /** Transporte real. El manager DEBE operar sobre este backend. */
    val transport = RealWebRtcTransport()

    /**
     * El manager de km-core gobierna el transporte real.
     * Esta es la integracion clave de 3N.4: la autoridad de km-core
     * opera directamente sobre libwebrtc.
     */
    val manager = PeerTransportManager(transportBackend = transport, now = { 0L })

    private val clock: Clock = Clock { 1_000L }

    /** Crea un PeerContext AUTENTICADO hacia el peer indicado. */
    fun authenticatedContextTowards(peer: AuthenticatedNode): PeerContext {
        val session = AuthSession.initiator(
            keyPair = keyPair,
            identityId = identityId.value,
            ed25519 = ed25519,
            clock = clock,
        )
        session.receiveChallenge(
            AuthChallenge(ByteArray(16) { 7 }, 0L, 3, peer.identityId.value)
        ).getOrThrow()
        session.markResponseSent()
        // AUTH_OK firmado por el par remoto (simula al peer respondiendo).
        val ok = makeAuthOk(peer, session)
        session.receiveAuthOk(ok, identityId.value).getOrThrow()
        // El anuncio pertenece al peer remoto.
        val announcement = peer.announcement()
        return PeerContext.authenticated(
            remotePeerId = peer.identityId,
            publicKey = peer.keyPair.publicKey,
            announcement = announcement,
            authSession = session,
        )
    }

    /** NodeAnnouncement firmado por esta node. */
    fun announcement(): NodeAnnouncement {
        val identity = NodeIdentity(identityId, keyPair.publicKey)
        val partial = NodeAnnouncement(
            messageId = MessageId(UUID.randomUUID()),
            timestamp = 0L,
            protocolVersion = "1.0",
            identity = identity,
            endpoints = emptyList(),
            capabilities = setOf(NodeCapability.CLIENT),
        )
        val signable = NodeAnnouncementJsonCodec.signableJson(partial)
        val sig = ed25519.sign(keyPair.privateKey, signable)
        return partial.copy(signature = sig.bytes)
    }

    /** Crea un conductor (ManagedWebRtcPeer) hacia el peer indicado. */
    fun peerTowards(peer: AuthenticatedNode, context: PeerContext): ManagedWebRtcPeer =
        ManagedWebRtcPeer(
            localIdentity = identityId,
            peerContext = context,
            manager = manager,
            transport = transport,
        )

    fun dispose() {
        manager.clear()
        transport.shutdown()
        transport.disposeCallbacks()
    }

    /**
     * Autentica esta node contra el RelayServer real (KM-0002) y registra
     * su handler de senales entrantes.
     *
     * @param onSignal invocado con cada senal que el relay enruta a esta node.
     */
    fun connectToRelay(
        relay: RelayServer,
        onSignal: (RelaySignalMessage) -> Unit,
    ) {
        // 1. Autenticacion real contra el relay.
        val challenge = relay.createChallenge(identityId)
        val handler = RelayAuthHandler(ed25519)
        val response = handler.buildResponse(
            AuthChallenge(
                nonce = challenge.nonce,
                timestampMillis = challenge.timestampMillis,
                version = challenge.version,
                responderIdentityId = challenge.responderIdentityId,
            ),
            identityId.value,
            keyPair,
        )
        val result = relay.authenticate(identityId, response)
        check(result.isSuccess) { "autenticacion relay fallo: $result" }

        // 2. Registrar handler: el relay entregara aqui las senales dirigidas
        //    a esta identidad.
        val registered = relay.registerPeerHandler(identityId, onSignal)
        check(registered.isSuccess) { "registro relay fallo: $registered" }
    }

    /** Desconecta del relay cerrando la sesion. */
    fun disconnectFromRelay(relay: RelayServer) {
        relay.disconnect(identityId)
    }

    private fun makeAuthOk(
        peer: AuthenticatedNode,
        session: AuthSession,
    ): AuthOk {
        // sessionId: 40 caracteres hex (160 bits) segun KM-0002.
        val sid = MessageDigest.getInstance("SHA-256")
            .digest("km-session-$label".toByteArray())
            .joinToString("") { "%02x".format(it) }
            .take(40)
        val serverNonce = ByteArray(16) { 9 }
        val transcript = TranscriptBuilder.serverAuthTranscript(
            sid, serverNonce, session.localIdentityId
        )
        val sig = peer.ed25519.sign(peer.keyPair.privateKey, transcript)
        return AuthOk(sid, serverNonce, peer.keyPair.publicKey, sig.bytes)
    }

    companion object {
        fun deriveId(publicKey: ByteArray): String {
            val digest = MessageDigest.getInstance("SHA-256")
            digest.update("KM-ID-IDENTITY".toByteArray())
            digest.update(publicKey)
            return digest.digest().joinToString("") { "%02x".format(it) }
        }
    }
}

/**
 * Establece la senalizacion bidireccional entre dos conductores.
 *
 * El enlace entrega SDP/ICE al par correcto segun el rol de cada uno:
 * - la oferta del offerer la consume el answerer (acceptOffer)
 * - la respuesta del answerer la consume el offerer (applyAnswer)
 */
class SignalingPair(
    val offerer: ManagedWebRtcPeer,
    val answerer: ManagedWebRtcPeer,
) {
    private val toAnswerer = DirectSignaling().apply { expectedRemotePeer = offerer.remotePeerId }
    private val toOfferer = DirectSignaling().apply { expectedRemotePeer = answerer.remotePeerId }

    init {
        offerer.signaling = toAnswerer
        answerer.signaling = toOfferer

        // Offerer recibe la respuesta.
        toOfferer.onRemoteSdp = { answer -> offerer.applyAnswer(answer) }
        // Answerer recibe la oferta.
        toAnswerer.onRemoteSdp = { offer -> answerer.acceptOffer(offer) }

        // ICE en ambos sentidos.
        toOfferer.onRemoteIce = { ice -> offerer.deliverIce(ice) }
        toAnswerer.onRemoteIce = { ice -> answerer.deliverIce(ice) }
    }

    /** Candidatos ICE que el offerer recibio del answerer. */
    val offererIce = toOfferer.iceReceived
    val answererIce = toAnswerer.iceReceived

    /** SDP entregadas a cada lado (para diagnostico). */
    val sdpsToOfferer = toOfferer.sdpsReceived
    val sdpsToAnswerer = toAnswerer.sdpsReceived

    /** Ejecuta el handshake completo hasta que ambos queden CONNECTED. */
    fun negotiate(timeoutMs: Long = 20_000L) {
        offerer.startOffering(timeoutMs)
    }

    /** Espera a que ambos managers confirmen CONNECTED. */
    fun awaitBothConnected(timeoutMs: Long = 20_000L) {
        offerer.awaitConnected(timeoutMs)
        answerer.awaitConnected(timeoutMs)
    }
}

/** Factory compartida de tests 3N.4. */
object WebRtcTestEnv {
    val ed25519: Ed25519 = Ed25519Impl()
}
