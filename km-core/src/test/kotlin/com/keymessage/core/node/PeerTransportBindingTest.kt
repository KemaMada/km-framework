package com.keymessage.core.node

import com.keymessage.core.auth.*
import com.keymessage.core.crypto.Ed25519
import com.keymessage.core.crypto.Ed25519Impl
import com.keymessage.core.crypto.KeyPair
import com.keymessage.core.model.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import kotlin.test.assertContentEquals

/**
 * 3K — WebRTC transport integration.
 *
 * AuthSession -> PeerContext -> PeerTransportBinding -> SDP/ICE -> DataChannel
 */
class PeerTransportBindingTest {

    private val ed25519: Ed25519 = Ed25519Impl()
    private val aliceKP = ed25519.generateKeyPair()
    private val bobKP = ed25519.generateKeyPair()
    private val charlieKP = ed25519.generateKeyPair()

    private val aliceId = IdentityId(deriveId(aliceKP.publicKey))
    private val bobId = IdentityId(deriveId(bobKP.publicKey))
    private val charlieId = IdentityId(deriveId(charlieKP.publicKey))

    private val fixedClock: Clock = Clock { 1000L }

    // ===================================================================
    // Helpers
    // ===================================================================

    private fun makePeerContext(
        localKeyPair: KeyPair = aliceKP,
        localId: IdentityId = aliceId,
        peerKeyPair: KeyPair = bobKP,
        remotePeerId: IdentityId = bobId,
    ): PeerContext {
        val session = AuthSession.initiator(localKeyPair, localId.value, ed25519, clock = fixedClock)
        session.receiveChallenge(AuthChallenge(ByteArray(16), 1000L, 3, remotePeerId.value)).getOrThrow()
        session.markResponseSent()
        val ok = makeAuthOk(localKeyPair, localId.value)
        session.receiveAuthOk(ok, localId.value).getOrThrow()
        val ann = makeAnnouncement(peerKeyPair.publicKey, remotePeerId)
        return PeerContext.authenticated(remotePeerId, peerKeyPair.publicKey, ann, session)
    }

    private fun makeAuthOk(keyPair: KeyPair, initiatorId: String): AuthOk {
        val sid = "a".repeat(40)
        val snonce = ByteArray(16) { 3 }
        val t = TranscriptBuilder.serverAuthTranscript(sid, snonce, initiatorId)
        val sig = ed25519.sign(keyPair.privateKey, t)
        return AuthOk(sid, snonce, keyPair.publicKey, sig.bytes)
    }

    private fun makeAnnouncement(pubKey: ByteArray, identityId: IdentityId): NodeAnnouncement {
        val identity = NodeIdentity(identityId, pubKey)
        val partial = NodeAnnouncement(
            messageId = MessageId(java.util.UUID.randomUUID()),
            timestamp = 1000L, protocolVersion = "1.0",
            identity = identity, endpoints = emptyList(),
            capabilities = setOf(NodeCapability.CLIENT),
        )
        val signable = NodeAnnouncementJsonCodec.signableJson(partial)
        val sig = ed25519.sign(ed25519.generateKeyPair().privateKey, signable)
        return partial.copy(signature = sig.bytes)
    }

    private fun manager(): PeerTransportManager = PeerTransportManager(now = { 1000L })

    companion object {
        fun deriveId(publicKey: ByteArray): String {
            val d = java.security.MessageDigest.getInstance("SHA-256")
            d.update("KM-ID-IDENTITY".encodeToByteArray())
            d.update(publicKey)
            return d.digest().joinToString("") { "%02x".format(it) }
        }
    }

    // ===================================================================
    // 3K.1 — PeerConnection lifecycle
    // ===================================================================

    @Test
    fun `K1-01 crear binding desde PeerContext AUTHENTICATED`() {
        val ctx = makePeerContext()
        val mgr = manager()
        val result = mgr.createBinding(aliceId, ctx)
        assertTrue(result.isSuccess)
        val binding = result.getOrThrow()
        assertEquals(PeerTransportState.NEW, binding.state)
        assertEquals(bobId, binding.peerContext.remotePeerId)
    }

    @Test
    fun `K1-02 PeerContext FAILED no puede crear binding`() {
        val ctx = makePeerContext()
        ctx.authSession.receiveAuthFail()
        val mgr = manager()
        val result = mgr.createBinding(aliceId, ctx)
        assertTrue(result.isFailure)
        assertEquals(TransportError.PEER_CONTEXT_NOT_AUTHENTICATED, (result as TransportResult.Failure).error)
    }

    @Test
    fun `K1-03 un solo binding activo por par`() {
        val ctx = makePeerContext()
        val mgr = manager()
        assertTrue(mgr.createBinding(aliceId, ctx).isSuccess)
        val r2 = mgr.createBinding(aliceId, ctx)
        assertTrue(r2.isFailure)
        assertEquals(TransportError.BINDING_ALREADY_EXISTS, (r2 as TransportResult.Failure).error)
    }

    @Test
    fun `K1-04 binding cerrado permite crear nuevo`() {
        val ctx = makePeerContext()
        val mgr = manager()
        mgr.createBinding(aliceId, ctx)
        mgr.closeBinding(aliceId, bobId)
        assertTrue(mgr.createBinding(aliceId, ctx).isSuccess)
    }

    @Test
    fun `K1-05 ciclo completo NEW a CONNECTED`() {
        val ctx = makePeerContext()
        val mgr = manager()
        mgr.createBinding(aliceId, ctx)
        mgr.sendOffer(aliceId, ctx, "offer", bobId)
        mgr.receiveAnswer(aliceId, bobId, "answer")
        mgr.addIceCandidate(aliceId, bobId, "c:1")
        val b = mgr.markConnected(aliceId, ctx).getOrThrow()
        assertEquals(PeerTransportState.CONNECTED, b.state)
    }

    // ===================================================================
    // 3K.2 — SDP offer/answer
    // ===================================================================

    @Test
    fun `K2-01 sendOffer con remotePeerId incorrecto falla`() {
        val ctx = makePeerContext()
        val mgr = manager()
        mgr.createBinding(aliceId, ctx)
        val result = mgr.sendOffer(aliceId, ctx, "offer", charlieId)
        assertTrue(result.isFailure)
        assertEquals(TransportError.PEER_ID_MISMATCH, (result as TransportResult.Failure).error)
    }

    @Test
    fun `K2-02 receiveAnswer de Charlie sin binding falla con BINDING_NOT_FOUND`() {
        val ctx = makePeerContext()
        val mgr = manager()
        mgr.createBinding(aliceId, ctx)
        mgr.sendOffer(aliceId, ctx, "offer", bobId)
        // No hay binding (aliceId, charlieId) porque Charlie no esta en el binding
        val result = mgr.receiveAnswer(aliceId, charlieId, "answer")
        assertTrue(result.isFailure)
        assertEquals(TransportError.BINDING_NOT_FOUND, (result as TransportResult.Failure).error)
    }

    @Test
    fun `K2-03 receiveAnswer sin offer previo`() {
        val ctx = makePeerContext()
        val mgr = manager()
        mgr.createBinding(aliceId, ctx)
        // Binding en NEW, no OFFER_SENT
        val result = mgr.receiveAnswer(aliceId, bobId, "answer")
        assertTrue(result.isFailure)
        assertEquals(TransportError.INVALID_STATE_TRANSITION, (result as TransportResult.Failure).error)
    }

    @Test
    fun `K2-04 SDP exchange completo`() {
        val mgr = manager()
        val ctx = makePeerContext()
        mgr.createBinding(aliceId, ctx)
        val offer = mgr.sendOffer(aliceId, ctx, "alice-offer", bobId).getOrThrow()
        assertEquals(PeerTransportState.OFFER_SENT, offer.state)
        val answer = mgr.receiveAnswer(aliceId, bobId, "bob-answer").getOrThrow()
        assertEquals("bob-answer", answer.sdp?.remoteAnswer)
    }

    // ===================================================================
    // 3K.3 — ICE candidate routing
    // ===================================================================

    @Test
    fun `K3-01 ICE candidate de peer correcto aceptado`() {
        val ctx = makePeerContext()
        val mgr = manager()
        mgr.createBinding(aliceId, ctx)
        mgr.sendOffer(aliceId, ctx, "offer", bobId)
        assertTrue(mgr.addIceCandidate(aliceId, bobId, "c:bob-1").isSuccess)
    }

    @Test
    fun `K3-02 ICE de Charlie sin binding falla BINDING_NOT_FOUND`() {
        val ctx = makePeerContext()
        val mgr = manager()
        mgr.createBinding(aliceId, ctx)
        mgr.sendOffer(aliceId, ctx, "offer", bobId)
        // No hay binding (aliceId, charlieId)
        val result = mgr.addIceCandidate(aliceId, charlieId, "c:charlie")
        assertTrue(result.isFailure)
        assertEquals(TransportError.BINDING_NOT_FOUND, (result as TransportResult.Failure).error)
    }

    @Test
    fun `K3-03 ICE despues de CONNECTED`() {
        val ctx = makePeerContext()
        val mgr = manager()
        mgr.createBinding(aliceId, ctx)
        mgr.sendOffer(aliceId, ctx, "offer", bobId)
        mgr.markConnected(aliceId, ctx)
        val result = mgr.addIceCandidate(aliceId, bobId, "c:late")
        assertTrue(result.isFailure)
        assertEquals(TransportError.INVALID_STATE_TRANSITION, (result as TransportResult.Failure).error)
    }

    @Test
    fun `K3-04 candidatos duplicados permitidos`() {
        val ctx = makePeerContext()
        val mgr = manager()
        mgr.createBinding(aliceId, ctx)
        mgr.sendOffer(aliceId, ctx, "offer", bobId)
        mgr.addIceCandidate(aliceId, bobId, "c:dup")
        val r = mgr.addIceCandidate(aliceId, bobId, "c:dup")
        assertTrue(r.isSuccess)
        assertEquals(2, r.getOrThrow().iceCandidates.size)
    }

    @Test
    fun `K3-05 ICE antes de OFFER`() {
        val ctx = makePeerContext()
        val mgr = manager()
        mgr.createBinding(aliceId, ctx)
        val result = mgr.addIceCandidate(aliceId, bobId, "c:early")
        assertTrue(result.isFailure)
        assertEquals(TransportError.INVALID_STATE_TRANSITION, (result as TransportResult.Failure).error)
    }

    // ===================================================================
    // 3K.4 — DataChannel lifecycle
    // ===================================================================

    @Test
    fun `K4-01 solo CONNECTED permite enviar datos`() {
        val ctx = makePeerContext()
        val mgr = manager()
        mgr.createBinding(aliceId, ctx)
        mgr.sendOffer(aliceId, ctx, "offer", bobId)
        val result = mgr.sendData(aliceId, ctx, "hello".encodeToByteArray())
        assertTrue(result.isFailure)
        assertEquals(TransportError.INVALID_STATE_TRANSITION, (result as TransportResult.Failure).error)
    }

    @Test
    fun `K4-02 CONNECTED permite enviar datos`() {
        val ctx = makePeerContext()
        val mgr = manager()
        mgr.createBinding(aliceId, ctx)
        mgr.sendOffer(aliceId, ctx, "offer", bobId)
        mgr.markConnected(aliceId, ctx)
        assertTrue(mgr.sendData(aliceId, ctx, "hello".encodeToByteArray()).isSuccess)
    }

    @Test
    fun `K4-03 datos contabilizados`() {
        val ctx = makePeerContext()
        val mgr = manager()
        mgr.createBinding(aliceId, ctx)
        mgr.sendOffer(aliceId, ctx, "offer", bobId)
        mgr.markConnected(aliceId, ctx)
        mgr.sendData(aliceId, ctx, "hello".encodeToByteArray())
        assertEquals(5L, mgr.getBindingForPeerContext(aliceId, ctx)?.dataReceived)
    }

    @Test
    fun `K4-04 handler de datos invocado`() {
        val ctx = makePeerContext()
        val mgr = manager()
        mgr.createBinding(aliceId, ctx)
        mgr.sendOffer(aliceId, ctx, "offer", bobId)
        mgr.markConnected(aliceId, ctx)
        var received: ByteArray? = null
        mgr.onData { _, data -> received = data }
        mgr.sendData(aliceId, ctx, "test".encodeToByteArray())
        assertContentEquals("test".encodeToByteArray(), received)
    }

    @Test
    fun `K4-05 handler de estado invocado`() {
        val ctx = makePeerContext()
        val mgr = manager()
        val states = mutableListOf<PeerTransportState>()
        mgr.onStateChange { _, s -> states.add(s) }
        mgr.createBinding(aliceId, ctx)
        mgr.sendOffer(aliceId, ctx, "offer", bobId)
        mgr.markConnected(aliceId, ctx)
        mgr.closeBinding(aliceId, bobId)
        assertTrue(PeerTransportState.OFFER_SENT in states)
        assertTrue(PeerTransportState.CONNECTED in states)
        assertTrue(PeerTransportState.CLOSED in states)
    }

    // ===================================================================
    // 3K.5 — Auth <-> WebRTC binding
    // ===================================================================

    @Test
    fun `K5-01 SDP de Charlie entregado a binding Bob`() {
        val ctxBob = makePeerContext()
        val mgr = manager()
        mgr.createBinding(aliceId, ctxBob)
        mgr.sendOffer(aliceId, ctxBob, "offer-bob", bobId)
        // Charlie no tiene binding con Alice
        val result = mgr.receiveAnswer(aliceId, charlieId, "charlie-answer")
        assertTrue(result.isFailure)
        assertEquals(TransportError.BINDING_NOT_FOUND, (result as TransportResult.Failure).error)
    }

    @Test
    fun `K5-02 ICE de Charlie entregado a binding Bob`() {
        val ctxBob = makePeerContext()
        val mgr = manager()
        mgr.createBinding(aliceId, ctxBob)
        mgr.sendOffer(aliceId, ctxBob, "offer", bobId)
        val result = mgr.addIceCandidate(aliceId, charlieId, "c:charlie")
        assertTrue(result.isFailure)
        assertEquals(TransportError.BINDING_NOT_FOUND, (result as TransportResult.Failure).error)
    }

    @Test
    fun `K5-03 SDP antiguo tras re-auth`() {
        val ctx1 = makePeerContext()
        val mgr = manager()
        mgr.createBinding(aliceId, ctx1)
        mgr.sendOffer(aliceId, ctx1, "old-offer", bobId)
        mgr.invalidateAllForLocalIdentity(aliceId)
        val result = mgr.sendOffer(aliceId, ctx1, "new-offer", bobId)
        assertTrue(result.isFailure)
        assertEquals(TransportError.BINDING_NOT_FOUND, (result as TransportResult.Failure).error)
    }

    @Test
    fun `K5-04 re-auth permite nuevo binding`() {
        val ctx1 = makePeerContext()
        val mgr = manager()
        mgr.createBinding(aliceId, ctx1)
        mgr.invalidateAllForLocalIdentity(aliceId)
        val ctx2 = makePeerContext()
        assertTrue(mgr.createBinding(aliceId, ctx2).isSuccess)
    }

    @Test
    fun `K5-05 remotePeerId cambiado despues de binding`() {
        val ctx = makePeerContext()
        val mgr = manager()
        mgr.createBinding(aliceId, ctx)
        val result = mgr.sendOffer(aliceId, ctx, "offer", charlieId)
        assertTrue(result.isFailure)
        assertEquals(TransportError.PEER_ID_MISMATCH, (result as TransportResult.Failure).error)
    }

    @Test
    fun `K5-06 FAILED no permite enviar`() {
        val ctx = makePeerContext()
        val mgr = manager()
        mgr.createBinding(aliceId, ctx)
        mgr.sendOffer(aliceId, ctx, "offer", bobId)
        mgr.markConnected(aliceId, ctx)
        mgr.failBinding(aliceId, bobId, "lost")
        val result = mgr.sendData(aliceId, ctx, "data".encodeToByteArray())
        assertTrue(result.isFailure)
        assertEquals(TransportError.BINDING_NOT_FOUND, (result as TransportResult.Failure).error)
    }

    // ===================================================================
    // 3K.6 — Reconnect / re-auth / stale-session
    // ===================================================================

    @Test
    fun `K6-01 invalidacion por remotePeerId`() {
        val ctx = makePeerContext()
        val mgr = manager()
        mgr.createBinding(aliceId, ctx)
        mgr.sendOffer(aliceId, ctx, "offer", bobId)
        mgr.invalidateAllForPeerId(bobId)
        val b = mgr.getBindingForPeerContext(aliceId, ctx)
        assertNotNull(b)
        assertFalse(b!!.isActive)
    }

    @Test
    fun `K6-02 invalidacion por identidad local`() {
        val ctx = makePeerContext()
        val mgr = manager()
        mgr.createBinding(aliceId, ctx)
        mgr.sendOffer(aliceId, ctx, "offer", bobId)
        mgr.invalidateAllForLocalIdentity(aliceId)
        // binding existe pero con estado CLOSED
        val b = mgr.getBindingForPeerContext(aliceId, ctx)
        assertNotNull(b)
        assertEquals(PeerTransportState.CLOSED, b!!.state)
    }

    @Test
    fun `K6-03 stale-session no crea binding`() {
        val ctx = makePeerContext()
        ctx.authSession.receiveAuthFail()
        val mgr = manager()
        val result = mgr.createBinding(aliceId, ctx)
        assertTrue(result.isFailure)
        assertEquals(TransportError.PEER_CONTEXT_NOT_AUTHENTICATED, (result as TransportResult.Failure).error)
    }

    // ===================================================================
    // 3K.7 — Mutation + boundary audit
    // ===================================================================

    @Test
    fun `K7-01 mutar sender en SDP`() {
        val ctx = makePeerContext()
        val mgr = manager()
        mgr.createBinding(aliceId, ctx)
        val result = mgr.sendOffer(aliceId, ctx, "offer", charlieId)
        assertEquals(TransportError.PEER_ID_MISMATCH, (result as TransportResult.Failure).error)
    }

    @Test
    fun `K7-02 mutar recipient en ICE`() {
        val ctx = makePeerContext()
        val mgr = manager()
        mgr.createBinding(aliceId, ctx)
        mgr.sendOffer(aliceId, ctx, "offer", bobId)
        val result = mgr.addIceCandidate(aliceId, charlieId, "c:charlie")
        assertTrue(result.isFailure)
        assertEquals(TransportError.BINDING_NOT_FOUND, (result as TransportResult.Failure).error)
    }

    @Test
    fun `K7-03 NEW no permite enviar`() {
        val ctx = makePeerContext()
        val mgr = manager()
        mgr.createBinding(aliceId, ctx)
        val result = mgr.sendData(aliceId, ctx, "data".encodeToByteArray())
        assertEquals(TransportError.INVALID_STATE_TRANSITION, (result as TransportResult.Failure).error)
    }

    @Test
    fun `K7-04 CLOSED no permite enviar`() {
        val ctx = makePeerContext()
        val mgr = manager()
        mgr.createBinding(aliceId, ctx)
        mgr.sendOffer(aliceId, ctx, "offer", bobId)
        mgr.markConnected(aliceId, ctx)
        mgr.closeBinding(aliceId, bobId)
        val result = mgr.sendData(aliceId, ctx, "data".encodeToByteArray())
        assertEquals(TransportError.BINDING_NOT_FOUND, (result as TransportResult.Failure).error)
    }

    @Test
    fun `K7-05 FAILED no permite enviar`() {
        val ctx = makePeerContext()
        val mgr = manager()
        mgr.createBinding(aliceId, ctx)
        mgr.sendOffer(aliceId, ctx, "offer", bobId)
        mgr.markConnected(aliceId, ctx)
        mgr.failBinding(aliceId, bobId, "timeout")
        val result = mgr.sendData(aliceId, ctx, "data".encodeToByteArray())
        assertTrue(result.isFailure)
        assertEquals(TransportError.BINDING_NOT_FOUND, (result as TransportResult.Failure).error)
    }

    @Test
    fun `K7-06 NEW no permite ICE`() {
        val ctx = makePeerContext()
        val mgr = manager()
        mgr.createBinding(aliceId, ctx)
        val result = mgr.addIceCandidate(aliceId, bobId, "c:early")
        assertTrue(result.isFailure)
        assertEquals(TransportError.INVALID_STATE_TRANSITION, (result as TransportResult.Failure).error)
    }

    // ===================================================================
    // Prueba estrella: bindings aislados
    // ===================================================================

    @Test
    fun `A-B y A-C coexisten sin cruzarse`() {
        val mgr = manager()
        val ctxAB = makePeerContext(aliceKP, aliceId, bobKP, bobId)
        val ctxAC = makePeerContext(aliceKP, aliceId, charlieKP, charlieId)

        mgr.createBinding(aliceId, ctxAB)
        mgr.createBinding(aliceId, ctxAC)

        assertEquals(2, mgr.activeBindings().size)
        assertEquals(2, mgr.bindingsForLocalIdentity(aliceId).size)

        mgr.sendOffer(aliceId, ctxAB, "ab-offer", bobId)
        mgr.sendOffer(aliceId, ctxAC, "ac-offer", charlieId)

        mgr.receiveAnswer(aliceId, charlieId, "charlie-answer")

        val bindingAB = mgr.getBinding(aliceId, bobId)!!
        assertEquals(PeerTransportState.OFFER_SENT, bindingAB.state)

        val bindingAC = mgr.getBinding(aliceId, charlieId)!!
        assertEquals(PeerTransportState.ANSWER_RECEIVED, bindingAC.state)
    }

    @Test
    fun `Charlie no intercepta binding A-B`() {
        val mgr = manager()
        val ctxAB = makePeerContext(aliceKP, aliceId, bobKP, bobId)
        mgr.createBinding(aliceId, ctxAB)
        mgr.sendOffer(aliceId, ctxAB, "ab-offer", bobId)

        val charlieAnswer = mgr.receiveAnswer(aliceId, charlieId, "fake-answer")
        assertTrue(charlieAnswer.isFailure)
        assertEquals(TransportError.BINDING_NOT_FOUND, (charlieAnswer as TransportResult.Failure).error)

        val charlieIce = mgr.addIceCandidate(aliceId, charlieId, "c:charlie")
        assertTrue(charlieIce.isFailure)
        assertEquals(TransportError.BINDING_NOT_FOUND, (charlieIce as TransportResult.Failure).error)
    }
}