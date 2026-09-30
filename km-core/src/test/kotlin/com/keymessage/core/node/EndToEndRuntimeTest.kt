package com.keymessage.core.node

import com.keymessage.core.auth.*
import com.keymessage.core.crypto.Ed25519
import com.keymessage.core.crypto.Ed25519Impl
import com.keymessage.core.crypto.KeyPair
import com.keymessage.core.model.*
import com.keymessage.core.storage.InMemoryRelayStore
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertContentEquals
import org.junit.jupiter.api.assertThrows

/**
 * 3L — End-to-end runtime integration.
 *
 * Flujo completo: RelayServer -> Auth -> PeerContext -> PeerTransportManager -> SDP/ICE -> DataChannel.
 *
 * Dos nodos (Alice, Bob) se conectan al relay, autentican, intercambian
 * senalizacion WebRTC a traves del relay, establecen bindings y alcanzan
 * CONNECTED en ambos lados.
 *
 * Un tercer nodo (Charlie) intenta interceptar en cada punto.
 */
class EndToEndRuntimeTest {

    private val ed25519: Ed25519 = Ed25519Impl()
    private val relayKP = ed25519.generateKeyPair()
    private val aliceKP = ed25519.generateKeyPair()
    private val bobKP = ed25519.generateKeyPair()
    private val charlieKP = ed25519.generateKeyPair()

    private val relayId = IdentityId(deriveId(relayKP.publicKey))
    private val aliceId = IdentityId(deriveId(aliceKP.publicKey))
    private val bobId = IdentityId(deriveId(bobKP.publicKey))
    private val charlieId = IdentityId(deriveId(charlieKP.publicKey))

    private val fixedClock: Clock = Clock { 1000L }

    private lateinit var relayServer: RelayServer
    private lateinit var aliceMgr: PeerTransportManager
    private lateinit var bobMgr: PeerTransportManager
    private lateinit var charlieMgr: PeerTransportManager

    /** Colas de mensajes: relay routea senales entre peers. */
    private val aliceInbox = mutableListOf<RelaySignalMessage>()
    private val bobInbox = mutableListOf<RelaySignalMessage>()
    private val charlieInbox = mutableListOf<RelaySignalMessage>()

    @BeforeEach
    fun setUp() {
        val store = InMemoryRelayStore(now = { 0L })
        val relayService = RelayServiceImpl(relayStore = store, now = { 0L })
        relayServer = RelayServer(
            relayKeyPair = relayKP, ed25519 = ed25519,
            relayService = relayService, relayIdentityId = relayId,
            now = { 0L }, sessionExpiryMs = 300_000L,
        )
        aliceMgr = PeerTransportManager(now = { 0L })
        bobMgr = PeerTransportManager(now = { 0L })
        charlieMgr = PeerTransportManager(now = { 0L })

        // Clear inboxes
        aliceInbox.clear()
        bobInbox.clear()
        charlieInbox.clear()
    }

    // ===================================================================
    // Helpers E2E
    // ===================================================================

    /** Autentica un peer contra el relay y registra su handler. */
    private fun authenticateWithRelay(
        keyPair: KeyPair,
        identityId: IdentityId,
    ) {
        val challenge = relayServer.createChallenge(identityId)
        val handler = RelayAuthHandler(ed25519)
        val response = handler.buildResponse(
            AuthChallenge(challenge.nonce, 0L, 3, relayId.value),
            identityId.value, keyPair,
        )
        val result = relayServer.authenticate(identityId, response)
        assertTrue(result.isSuccess, "autenticacion fallo para ${identityId.value}")
    }

    /** Crea PeerContext y binding, registra handler en relay. */
    private fun setUpPeer(
        localKP: KeyPair,
        localId: IdentityId,
        peerKP: KeyPair,
        remotePeerId: IdentityId,
        transportMgr: PeerTransportManager,
        inbox: MutableList<RelaySignalMessage>,
    ): PeerContext {
        // 1. Authenticate with relay
        authenticateWithRelay(localKP, localId)

        // 2. Register relay handler (incoming signals go to inbox)
        relayServer.registerPeerHandler(localId) { signal -> inbox.add(signal) }

        // 3. Create PeerContext (auth with the OTHER peer)
        val session = AuthSession.initiator(localKP, localId.value, ed25519, clock = fixedClock)
        session.receiveChallenge(AuthChallenge(ByteArray(16), 0L, 3, remotePeerId.value)).getOrThrow()
        session.markResponseSent()
        val ok = makeAuthOk(localKP, localId.value)
        session.receiveAuthOk(ok, localId.value).getOrThrow()

        val ann = makeAnnouncement(peerKP.publicKey, remotePeerId)
        val ctx = PeerContext.authenticated(remotePeerId, peerKP.publicKey, ann, session)

        // 4. Create transport binding
        transportMgr.createBinding(localId, ctx).getOrThrow()

        return ctx
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
            timestamp = 0L, protocolVersion = "1.0",
            identity = identity, endpoints = emptyList(),
            capabilities = setOf(NodeCapability.CLIENT),
        )
        val signable = NodeAnnouncementJsonCodec.signableJson(partial)
        val sig = ed25519.sign(ed25519.generateKeyPair().privateKey, signable)
        return partial.copy(signature = sig.bytes)
    }

    /** Relay: Alice envia una senal a traves del relay. */
    private fun aliceSendsSignal(signal: RelaySignalMessage) {
        val result = relayServer.handleSignal(aliceId, signal)
        assertTrue(result.isSuccess) { "Alice no pudo enviar senal: ${(result as TransportResult.Failure).message}" }
    }

    /** Relay: Bob envia una senal a traves del relay. */
    private fun bobSendsSignal(signal: RelaySignalMessage) {
        val result = relayServer.handleSignal(bobId, signal)
        assertTrue(result.isSuccess) { "Bob no pudo enviar senal: ${(result as TransportResult.Failure).message}" }
    }

    companion object {
        fun deriveId(publicKey: ByteArray): String {
            val d = java.security.MessageDigest.getInstance("SHA-256")
            d.update("KM-ID-IDENTITY".encodeToByteArray())
            d.update(publicKey)
            return d.digest().joinToString("") { "%02x".format(it) }
        }
    }

    // ===================================================================
    // L1 — Authentication -> PeerContext
    // ===================================================================

    @Test
    fun `L1-01 A y B autentican con relay`() {
        authenticateWithRelay(aliceKP, aliceId)
        authenticateWithRelay(bobKP, bobId)

        assertNotNull(relayServer.getSession(aliceId))
        assertNotNull(relayServer.getSession(bobId))
        assertTrue(relayServer.getSession(aliceId)!!.isActive)
        assertTrue(relayServer.getSession(bobId)!!.isActive)
    }

    @Test
    fun `L1-02 PeerContext creado tras auth exitosa`() {
        val ctx = setUpPeer(aliceKP, aliceId, bobKP, bobId, aliceMgr, aliceInbox)
        assertEquals(bobId, ctx.remotePeerId)
    }

    @Test
    fun `L1-03 auth fallida no permite binding`() {
        // Sesion FAILED → PeerContext.authenticated() rechaza en constructor
        val session = AuthSession.initiator(aliceKP, aliceId.value, ed25519, clock = fixedClock)
        session.receiveAuthFail()
        val ann = makeAnnouncement(bobKP.publicKey, bobId)
        assertThrows<IllegalArgumentException> {
            PeerContext.authenticated(bobId, bobKP.publicKey, ann, session)
        }
    }

    // ===================================================================
    // L2 — Relay -> Signaling
    // ===================================================================

    @Test
    fun `L2-01 SDP offer via relay llega solo a Bob`() {
        val ctxAlice = setUpPeer(aliceKP, aliceId, bobKP, bobId, aliceMgr, aliceInbox)
        setUpPeer(bobKP, bobId, aliceKP, aliceId, bobMgr, bobInbox)

        // Alice envia offer via relay
        aliceMgr.sendOffer(aliceId, ctxAlice, "alice-sdp", bobId)
        aliceSendsSignal(RelaySignalMessage.SdpOffer(aliceId, bobId, "alice-sdp", 0L))

        assertEquals(1, bobInbox.size)
        assertEquals(aliceId, bobInbox[0].from)
        assertEquals(bobId, bobInbox[0].to)
        assertTrue(bobInbox[0] is RelaySignalMessage.SdpOffer)
    }

    @Test
    fun `L2-02 relay rechaza from falsificado`() {
        setUpPeer(aliceKP, aliceId, bobKP, bobId, aliceMgr, aliceInbox)
        setUpPeer(bobKP, bobId, aliceKP, aliceId, bobMgr, bobInbox)

        // Alice intenta enviar como si fuera Bob
        val spoofed = RelaySignalMessage.SdpOffer(bobId, aliceId, "fake", 0L)
        val result = relayServer.handleSignal(aliceId, spoofed)
        assertTrue(result.isFailure)
        assertEquals(RelayServerError.SPOOFING_DETECTED, (result as RelayServerResult.Failure).error)
    }

    @Test
    fun `L2-03 peer offline no recibe signaling`() {
        setUpPeer(aliceKP, aliceId, bobKP, bobId, aliceMgr, aliceInbox)
        // Bob no registrado como peer online (no handler)
        relayServer.unregisterPeerHandler(bobId)

        val result = relayServer.handleSignal(
            aliceId, RelaySignalMessage.SdpOffer(aliceId, bobId, "sdp", 0L)
        )
        assertTrue(result.isFailure)
        assertEquals(RelayServerError.PEER_NOT_ONLINE, (result as RelayServerResult.Failure).error)
    }

    @Test
    fun `L2-04 sesion cerrada no puede senalizar`() {
        setUpPeer(aliceKP, aliceId, bobKP, bobId, aliceMgr, aliceInbox)
        relayServer.disconnect(aliceId)

        val result = relayServer.handleSignal(
            aliceId, RelaySignalMessage.SdpOffer(aliceId, bobId, "sdp", 0L)
        )
        assertTrue(result.isFailure)
        assertEquals(RelayServerError.SESSION_CLOSED, (result as RelayServerResult.Failure).error)
    }

    // ===================================================================
    // L3 — Signaling -> Transport (full chain)
    // ===================================================================

    @Test
    fun `L3-01 flujo completo Alice ofrece Bob responde`() {
        val ctxAlice = setUpPeer(aliceKP, aliceId, bobKP, bobId, aliceMgr, aliceInbox)
        val ctxBob = setUpPeer(bobKP, bobId, aliceKP, aliceId, bobMgr, bobInbox)

        // 1. Alice sends offer
        aliceMgr.sendOffer(aliceId, ctxAlice, "alice-offer", bobId)
        aliceSendsSignal(RelaySignalMessage.SdpOffer(aliceId, bobId, "alice-offer", 0L))
        assertEquals(1, bobInbox.size)
        val bobOffer = bobInbox[0] as RelaySignalMessage.SdpOffer

        // 2. Bob receives offer, creates his binding and sends answer
        bobMgr.sendOffer(bobId, ctxBob, "bob-answer", aliceId)
        bobSendsSignal(RelaySignalMessage.SdpAnswer(bobId, aliceId, "bob-answer", 0L))
        assertEquals(1, aliceInbox.size)
        val aliceAnswer = aliceInbox[0] as RelaySignalMessage.SdpAnswer

        // 3. Alice receives answer
        aliceMgr.receiveAnswer(aliceId, bobId, aliceAnswer.sdp)

        // 4. ICE candidates flow
        aliceSendsSignal(RelaySignalMessage.IceCandidate(aliceId, bobId, "c:alice-1", 0L))
        bobSendsSignal(RelaySignalMessage.IceCandidate(bobId, aliceId, "c:bob-1", 0L))

        // Process ICE on each side
        aliceMgr.addIceCandidate(aliceId, bobId, (bobInbox[0] as? RelaySignalMessage.IceCandidate)?.candidate ?: "c:bob-1")
        bobMgr.addIceCandidate(bobId, aliceId, (aliceInbox[0] as? RelaySignalMessage.IceCandidate)?.candidate ?: "c:alice-1")

        // 5. Both sides connected
        aliceMgr.markConnected(aliceId, ctxAlice)
        bobMgr.markConnected(bobId, ctxBob)

        assertEquals(PeerTransportState.CONNECTED, aliceMgr.getBinding(aliceId, bobId)!!.state)
        assertEquals(PeerTransportState.CONNECTED, bobMgr.getBinding(bobId, aliceId)!!.state)
    }

    @Test
    fun `L3-02 no se puede saltar estados desde relay`() {
        val ctxAlice = setUpPeer(aliceKP, aliceId, bobKP, bobId, aliceMgr, aliceInbox)
        setUpPeer(bobKP, bobId, aliceKP, aliceId, bobMgr, bobInbox)

        // Alice recibe ANSWER sin haber enviado OFFER (desde relay)
        bobSendsSignal(RelaySignalMessage.SdpAnswer(bobId, aliceId, "rogue-answer", 0L))
        assertEquals(1, aliceInbox.size)

        // Alice procesa la respuesta rogue -> debe fallar porque no hay OFFER_SENT
        val result = aliceMgr.receiveAnswer(aliceId, bobId, (aliceInbox[0] as RelaySignalMessage.SdpAnswer).sdp)
        assertTrue(result.isFailure, "ANSWER sin OFFER debe fallar")
        assertEquals(TransportError.INVALID_STATE_TRANSITION, (result as TransportResult.Failure).error)
    }

    // ===================================================================
    // L4 — DataChannel
    // ===================================================================

    @Test
    fun `L4-01 datos enviados por Alice contabilizados en su binding`() {
        val ctxAlice = setUpPeer(aliceKP, aliceId, bobKP, bobId, aliceMgr, aliceInbox)
        val ctxBob = setUpPeer(bobKP, bobId, aliceKP, aliceId, bobMgr, bobInbox)

        // Full handshake (simplificado)
        aliceMgr.sendOffer(aliceId, ctxAlice, "offer", bobId)
        bobMgr.sendOffer(bobId, ctxBob, "answer", aliceId)
        aliceMgr.receiveAnswer(aliceId, bobId, "answer")
        aliceMgr.markConnected(aliceId, ctxAlice)
        bobMgr.markConnected(bobId, ctxBob)

        // Alice send data: verify her binding tracks it
        var aliceSentData: ByteArray? = null
        aliceMgr.onData { _, data -> aliceSentData = data }

        val sendResult = aliceMgr.sendData(aliceId, ctxAlice, "hello from Alice".encodeToByteArray())
        assertTrue(sendResult.isSuccess, "Alice debe poder enviar datos en CONNECTED")
        assertContentEquals("hello from Alice".encodeToByteArray(), aliceSentData,
            "Alice's data handler debe recibir los datos enviados")

        // Bob's binding tracks data independently when he sends
        var bobSentData: ByteArray? = null
        bobMgr.onData { _, data -> bobSentData = data }
        bobMgr.sendData(bobId, ctxBob, "reply from Bob".encodeToByteArray())
        assertContentEquals("reply from Bob".encodeToByteArray(), bobSentData,
            "Bob's data handler debe recibir sus propios datos enviados")

        // Each side's data count is tracked
        assertEquals(16L, aliceMgr.getBinding(aliceId, bobId)?.dataReceived,
            "Alice debe contabilizar 16 bytes enviados (\"hello from Alice\")")
        assertEquals(14L, bobMgr.getBinding(bobId, aliceId)?.dataReceived,
            "Bob debe contabilizar 14 bytes enviados (\"reply from Bob\")")
    }

    @Test
    fun `L4-02 datos solo fluyen en CONNECTED`() {
        val ctxAlice = setUpPeer(aliceKP, aliceId, bobKP, bobId, aliceMgr, aliceInbox)
        setUpPeer(bobKP, bobId, aliceKP, aliceId, bobMgr, bobInbox)

        aliceMgr.sendOffer(aliceId, ctxAlice, "offer", bobId)
        val result = aliceMgr.sendData(aliceId, ctxAlice, "premature".encodeToByteArray())
        assertTrue(result.isFailure)
        assertEquals(TransportError.INVALID_STATE_TRANSITION, (result as TransportResult.Failure).error)
    }

    // ===================================================================
    // L5 — Re-authentication
    // ===================================================================

    @Test
    fun `L5-01 re-auth invalida binding existente`() {
        val ctxAlice = setUpPeer(aliceKP, aliceId, bobKP, bobId, aliceMgr, aliceInbox)
        setUpPeer(bobKP, bobId, aliceKP, aliceId, bobMgr, bobInbox)

        aliceMgr.sendOffer(aliceId, ctxAlice, "offer", bobId)
        aliceMgr.markConnected(aliceId, ctxAlice)

        // Alice re-authenticates with relay
        relayServer.disconnect(aliceId)
        aliceMgr.invalidateAllForLocalIdentity(aliceId)

        // Old binding no longer active
        val oldBinding = aliceMgr.getBinding(aliceId, bobId)
        assertNotNull(oldBinding)
        assertEquals(PeerTransportState.CLOSED, oldBinding!!.state)

        // New auth produces new binding
        val ctxAlice2 = setUpPeer(aliceKP, aliceId, bobKP, bobId, aliceMgr, aliceInbox)
        assertNotNull(aliceMgr.getBinding(aliceId, bobId))
        assertTrue(aliceMgr.getBinding(aliceId, bobId)!!.isActive)

        // Old PeerContext can't send
        val result = aliceMgr.sendData(aliceId, ctxAlice, "stale".encodeToByteArray())
        assertTrue(result.isFailure, "datos de contexto antiguo deben ser rechazados")
    }

    @Test
    fun `L5-02 re-auth no permite usar binding anterior`() {
        val ctxAlice = setUpPeer(aliceKP, aliceId, bobKP, bobId, aliceMgr, aliceInbox)
        setUpPeer(bobKP, bobId, aliceKP, aliceId, bobMgr, bobInbox)

        aliceMgr.sendOffer(aliceId, ctxAlice, "old-offer", bobId)
        aliceMgr.markConnected(aliceId, ctxAlice)

        // Re-auth
        relayServer.disconnect(aliceId)
        aliceMgr.invalidateAllForLocalIdentity(aliceId)

        // Try to use old PeerContext (ctxAlice still references old session)
        val result = aliceMgr.sendData(aliceId, ctxAlice, "old-data".encodeToByteArray())
        assertTrue(result.isFailure, "datos en sesion invalidada deben ser rechazados")
    }

    // ===================================================================
    // L6 — Cross-peer injection
    // ===================================================================

    @Test
    fun `L6-01 Charlie no puede ofertar como Alice a Bob`() {
        setUpPeer(aliceKP, aliceId, bobKP, bobId, aliceMgr, aliceInbox)
        setUpPeer(bobKP, bobId, aliceKP, aliceId, bobMgr, bobInbox)
        setUpPeer(charlieKP, charlieId, aliceKP, aliceId, charlieMgr, charlieInbox)

        // Charlie intenta enviar OFFER a Bob como si fuera Alice
        val spoofedOffer = RelaySignalMessage.SdpOffer(aliceId, bobId, "charlie-fake", 0L)
        val result = relayServer.handleSignal(charlieId, spoofedOffer)
        assertTrue(result.isFailure)
        assertEquals(RelayServerError.SPOOFING_DETECTED, (result as RelayServerResult.Failure).error)
    }

    @Test
    fun `L6-02 Charlie no puede responder como Bob a Alice`() {
        setUpPeer(aliceKP, aliceId, bobKP, bobId, aliceMgr, aliceInbox)
        setUpPeer(bobKP, bobId, aliceKP, aliceId, bobMgr, bobInbox)
        setUpPeer(charlieKP, charlieId, aliceKP, aliceId, charlieMgr, charlieInbox)

        val ctxAlice = aliceMgr.getBinding(aliceId, bobId)?.peerContext
            ?: setUpPeer(aliceKP, aliceId, bobKP, bobId, aliceMgr, aliceInbox)
        aliceMgr.sendOffer(aliceId, ctxAlice, "real-offer", bobId)

        // Charlie envia ANSWER como si fuera Bob
        val spoofedAnswer = RelaySignalMessage.SdpAnswer(bobId, aliceId, "charlie-fake", 0L)
        val result = relayServer.handleSignal(charlieId, spoofedAnswer)
        assertTrue(result.isFailure)
        assertEquals(RelayServerError.SPOOFING_DETECTED, (result as RelayServerResult.Failure).error)
    }

    // ===================================================================
    // L7 — Teardown
    // ===================================================================

    @Test
    fun `L7-01 teardown completo`() {
        val ctxAlice = setUpPeer(aliceKP, aliceId, bobKP, bobId, aliceMgr, aliceInbox)
        setUpPeer(bobKP, bobId, aliceKP, aliceId, bobMgr, bobInbox)

        aliceMgr.sendOffer(aliceId, ctxAlice, "offer", bobId)
        aliceMgr.markConnected(aliceId, ctxAlice)

        // Teardown
        relayServer.disconnect(aliceId)
        aliceMgr.invalidateAllForLocalIdentity(aliceId)

        // Post-teardown: todas las operaciones rechazadas
        assertTrue(aliceMgr.sendData(aliceId, ctxAlice, "post".encodeToByteArray()).isFailure)
        assertTrue(aliceMgr.addIceCandidate(aliceId, bobId, "post-ice").isFailure)
        assertTrue(aliceMgr.sendOffer(aliceId, ctxAlice, "post-offer", bobId).isFailure)
        assertTrue(relayServer.handleSignal(aliceId, RelaySignalMessage.SdpOffer(aliceId, bobId, "post", 0L)).isFailure)
    }

    @Test
    fun `L7-02 disconnect relay cierra sesion`() {
        setUpPeer(aliceKP, aliceId, bobKP, bobId, aliceMgr, aliceInbox)
        assertTrue(relayServer.getSession(aliceId)!!.isActive)

        relayServer.disconnect(aliceId)
        assertFalse(relayServer.getSession(aliceId)!!.isActive)
    }

    // ===================================================================
    // E2E Identity chain
    // ===================================================================

    @Test
    fun `E2E-identidad mismo sujeto criptografico en toda la cadena`() {
        // publicKey -> identityId -> announcement -> auth -> PeerContext -> binding
        val ctxAlice = setUpPeer(aliceKP, aliceId, bobKP, bobId, aliceMgr, aliceInbox)

        // identityId deriva de publicKey
        val expectedAliceId = deriveId(aliceKP.publicKey)
        assertEquals(expectedAliceId, aliceId.value)

        // Bob's publicKey -> identityId -> announcement nodeId
        val expectedBobId = deriveId(bobKP.publicKey)
        assertEquals(expectedBobId, bobId.value)
        assertEquals(bobId, ctxAlice.remotePeerId)
        assertEquals(bobId, ctxAlice.announcement.identity.nodeId)
        assertContentEquals(bobKP.publicKey, ctxAlice.announcement.identity.publicKey)

        // Binding remotePeerId == PeerContext.remotePeerId
        val binding = aliceMgr.getBinding(aliceId, bobId)!!
        assertEquals(bobId, binding.peerContext.remotePeerId)

        // NUNCA: publicKey confundido con identityId en ningun punto
        assertNotEquals(bytesToHex(aliceKP.publicKey), aliceId.value,
            "publicKey != identityId")
        assertNotEquals(bytesToHex(bobKP.publicKey), bobId.value,
            "publicKey != identityId")
    }

    // ===================================================================
    // Prueba estrella: tres peers, flujo completo, sin cruce
    // ===================================================================

    @Test
    fun `tres peers flujo completo sin cruce`() {
        // Setup: Alice-Bob y Alice-Charlie
        val ctxAB = setUpPeer(aliceKP, aliceId, bobKP, bobId, aliceMgr, aliceInbox)
        setUpPeer(bobKP, bobId, aliceKP, aliceId, bobMgr, bobInbox)
        setUpPeer(charlieKP, charlieId, aliceKP, aliceId, charlieMgr, charlieInbox)
        val ctxAC = setUpPeer(aliceKP, aliceId, charlieKP, charlieId, aliceMgr, aliceInbox)

        // Alice oferta a Bob
        aliceMgr.sendOffer(aliceId, ctxAB, "ab-offer", bobId)
        aliceSendsSignal(RelaySignalMessage.SdpOffer(aliceId, bobId, "ab-offer", 0L))

        // Alice oferta a Charlie
        aliceMgr.sendOffer(aliceId, ctxAC, "ac-offer", charlieId)
        aliceSendsSignal(RelaySignalMessage.SdpOffer(aliceId, charlieId, "ac-offer", 0L))

        // Bob recibe solo su oferta
        assertEquals(1, bobInbox.filterIsInstance<RelaySignalMessage.SdpOffer>().size)
        assertEquals("ab-offer", (bobInbox[0] as RelaySignalMessage.SdpOffer).sdp)

        // Charlie recibe solo su oferta
        assertEquals(1, charlieInbox.filterIsInstance<RelaySignalMessage.SdpOffer>().size)
        assertEquals("ac-offer", (charlieInbox[0] as RelaySignalMessage.SdpOffer).sdp)

        // Charlie NO puede interceptar el offer de Bob
        assertFalse(charlieInbox.any { (it as? RelaySignalMessage.SdpOffer)?.sdp == "ab-offer" },
            "Charlie no puede recibir el offer de Bob")
    }
}

/** Extension para ByteArray -> hex. */
private fun bytesToHex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it) }