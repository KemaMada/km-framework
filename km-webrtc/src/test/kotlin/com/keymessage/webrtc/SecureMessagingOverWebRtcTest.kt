package com.keymessage.webrtc

import com.keymessage.core.auth.AuthChallenge
import com.keymessage.core.auth.AuthOk
import com.keymessage.core.auth.AuthSession
import com.keymessage.core.auth.Clock
import com.keymessage.core.auth.TranscriptBuilder
import com.keymessage.core.crypto.Ed25519
import com.keymessage.core.crypto.Ed25519Impl
import com.keymessage.core.crypto.KeyPair
import com.keymessage.core.crypto.X25519
import com.keymessage.core.crypto.provider.BcChaCha20Poly1305
import com.keymessage.core.crypto.provider.BcHkdfSha256
import com.keymessage.core.crypto.provider.BcX25519
import com.keymessage.core.messaging.ReceiveResult
import com.keymessage.core.messaging.SecureMessagingSession
import com.keymessage.core.messaging.SendResult
import com.keymessage.core.model.IdentityId
import com.keymessage.core.model.MessageId
import com.keymessage.core.model.NodeAnnouncement
import com.keymessage.core.model.NodeAnnouncementJsonCodec
import com.keymessage.core.model.NodeCapability
import com.keymessage.core.model.NodeIdentity
import com.keymessage.core.node.PeerContext
import com.keymessage.core.node.PeerTransportManager
import com.keymessage.core.node.PeerTransportState
import com.keymessage.core.ratchet.DoubleRatchetSession
import com.keymessage.core.ratchet.RatchetSessionBootstrap
import com.keymessage.core.sf.SecureFrameProtector
import com.keymessage.core.sf.SecureRatchetProtocol
import com.keymessage.core.x3dh.BootstrapPrekeys
import com.keymessage.core.x3dh.InitiatorKeyMaterial
import com.keymessage.core.x3dh.X3dh
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.assertContentEquals

/**
 * 3P — Cadena criptografica completa sobre WebRTC REAL.
 *
 *   X3DH real + Double Ratchet real + SecureFrame real + WebRTC real
 *
 * INVARIANTE DEL CHECKPOINT:
 * Este test vive en `km-webrtc` y NO requiere modificar `km-core`. La
 * dependencia va en una sola direccion: km-webrtc -> km-core.
 *
 * Lo que se demuestra:
 * 1. El mensaje de Alice llega a Bob descifrado tras un DataChannel real.
 * 2. El transporte solo ve bytes opacos: ni `F`, ni plaintext, ni claves.
 * 3. Un frame manipulado sobre el DataChannel no entrega nada a la app.
 */
class SecureMessagingOverWebRtcTest {

    private val ed25519: Ed25519 = Ed25519Impl()
    private lateinit var x25519: X25519
    private lateinit var kdf: BcHkdfSha256
    private lateinit var protector: SecureFrameProtector
    private lateinit var x3dh: X3dh

    private val random = SecureRandom()
    private val nodes = mutableListOf<WebRtcCryptoNode>()

    @BeforeEach
    fun setUp() {
        x25519 = BcX25519()
        kdf = BcHkdfSha256()
        protector = SecureFrameProtector(BcChaCha20Poly1305(), kdf)
        x3dh = X3dh(x25519, kdf)
    }

    @AfterEach
    fun tearDown() {
        nodes.forEach { it.dispose() }
        nodes.clear()
    }

    /**
     * Nodo con identidad real, ratchet real y transporte WebRTC real.
     *
     * El manager opera sobre [RealWebRtcTransport]: es la integracion que
     * 3N.4 demostro a nivel de transporte y 3P completa a nivel de mensaje.
     */
    private inner class WebRtcCryptoNode(label: String) {
        val keyPair: KeyPair = ed25519.generateKeyPair()
        val identityId: IdentityId = IdentityId(deriveId(keyPair.publicKey))
        val dh = x25519.generateKeyPair()
        val spk = x25519.generateKeyPair()
        var opk: com.keymessage.core.crypto.X25519KeyPair? = null
        val label = label

        /** Transporte REAL (km-webrtc). */
        val transport = RealWebRtcTransport()

        /** El manager de km-core gobierna el transporte real. */
        val manager = PeerTransportManager(transportBackend = transport, now = { 0L })

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

        fun contextTowards(other: WebRtcCryptoNode): PeerContext {
            val session = AuthSession.initiator(
                keyPair = keyPair,
                identityId = identityId.value,
                ed25519 = ed25519,
                clock = Clock { 1_000L },
            )
            session.receiveChallenge(
                AuthChallenge(ByteArray(16) { 7 }, 0L, 3, other.identityId.value)
            ).getOrThrow()
            session.markResponseSent()
            session.receiveAuthOk(authOkFor(other, session), identityId.value).getOrThrow()
            return PeerContext.authenticated(
                remotePeerId = other.identityId,
                publicKey = other.keyPair.publicKey,
                announcement = other.announcement(),
                authSession = session,
            )
        }

        private fun authOkFor(other: WebRtcCryptoNode, session: AuthSession): AuthOk {
            val sid = MessageDigest.getInstance("SHA-256")
                .digest("sid-$label".toByteArray())
                .joinToString("") { "%02x".format(it) }
                .take(40)
            val nonce = ByteArray(16) { 9 }
            val transcript = TranscriptBuilder.serverAuthTranscript(
                sid, nonce, session.localIdentityId,
            )
            return AuthOk(sid, nonce, other.keyPair.publicKey, ed25519.sign(other.keyPair.privateKey, transcript).bytes)
        }

        fun prekeys() = BootstrapPrekeys(
            deviceId = ByteArray(32) { it.toByte() },
            identityAgreementKey = dh.publicKey,
            signedPreKey = spk.publicKey,
            signedPreKeyId = 1L,
            oneTimePreKey = opk?.publicKey,
            oneTimePreKeyId = if (opk != null) 5L else null,
        )

        fun responderMaterial() = com.keymessage.core.x3dh.ResponderKeyMaterial(
            deviceId = ByteArray(32) { it.toByte() },
            identityAgreementKey = dh,
            signedPreKey = spk,
            oneTimePreKey = opk,
            oneTimePreKeyId = if (opk != null) 5L else null,
        )

        fun peerTowards(peer: WebRtcCryptoNode, context: PeerContext) = ManagedWebRtcPeer(
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
    }

    private fun newNode(label: String): WebRtcCryptoNode =
        WebRtcCryptoNode(label).also { nodes.add(it) }

    /**
     * Levanta dos nodos completos: X3DH, ratchet, SecureFrame y WebRTC real.
     */
    private class Established(
        val alice: SecureMessagingSession,
        val bob: SecureMessagingSession,
        val aliceTransport: RealWebRtcTransport,
        val bobTransport: RealWebRtcTransport,
        val bootstrapValue: ByteArray,
    )

    private fun establish(): Established {
        val alice = newNode("alice")
        val bob = newNode("bob")
        bob.opk = x25519.generateKeyPair()

        // --- Estado 1: bootstrap X3DH real ---
        val f = ByteArray(32).also { random.nextBytes(it) }
        val init = x3dh.initiate(
            InitiatorKeyMaterial(ByteArray(32), alice.dh),
            bob.prekeys(),
            f,
        )
        val prep = x3dh.respond(bob.responderMaterial(), alice.prekeys(), init.ephemeralPublic)
        assertContentEquals(init.sharedKey, prep.sharedKey, "X3DH debe derivar el mismo SK")
        prep.commit()

        // --- Estado 2: establishment ---
        val aliceProtocol = RatchetSessionBootstrap.initiatorProtocol(
            bootstrapValue = f,
            ephemeral = init.ephemeral,
            remoteSignedPreKey = bob.spk.publicKey,
            signedPreKeyPrivate = bob.spk.privateKey,
            x25519 = x25519,
            kdf = kdf,
            protector = protector,
        )
        val bobProtocol = SecureRatchetProtocol(
            DoubleRatchetSession(
                rootKey = RatchetSessionBootstrap.rootKeyFrom(f, kdf),
                dhSelf = bob.spk,          // el par SPK, el mismo que Alice uso
                dhRemote = null,           // el DH ratchet ocurre en el primer receive
                sendChainKey = ByteArray(32),
                receiveChainKey = ByteArray(32),
                x25519 = x25519,
                kdf = kdf,
            ),
            protector,
        )

        // --- Estado 3: mensajeria sobre WebRTC real ---
        val aliceCtx = alice.contextTowards(bob)
        val bobCtx = bob.contextTowards(alice)
        assertTrue(alice.transport.initialize().isSuccess)
        assertTrue(bob.transport.initialize().isSuccess)

        val aliceSession = SecureMessagingSession(
            alice.identityId, aliceCtx, aliceProtocol, alice.manager,
        )
        val bobSession = SecureMessagingSession(bob.identityId, bobCtx, bobProtocol, bob.manager)

        assertTrue(alice.manager.createBinding(alice.identityId, aliceCtx).isSuccess)
        assertTrue(bob.manager.createBinding(bob.identityId, bobCtx).isSuccess)

        // Los bytes crudos del DataChannel llegan a la sesion de mensajeria.
        // Este es el UNICO cableado de transporte, y vive en km-webrtc.
        val alicePeer = alice.peerTowards(bob, aliceCtx)
        val bobPeer = bob.peerTowards(alice, bobCtx)
        alicePeer.onTransportData = { bytes -> bobSession.onTransportBytes(bytes) }
        bobPeer.onTransportData = { bytes -> aliceSession.onTransportBytes(bytes) }

        return Established(aliceSession, bobSession, alice.transport, bob.transport, f)
    }

    /** Negocia SDP/ICE reales entre ambos nodos. */
    private fun connect(a: Established, aPeer: ManagedWebRtcPeer, bPeer: ManagedWebRtcPeer) {
        // `expectedRemotePeer` es, desde la perspectiva de quien envia, el
        // peer REMOTO. El canal de Alice espera a Bob (aPeer.remotePeerId), y el de
        // Bob espera a Alice (bPeer.remotePeerId).
        val toB = DirectSignaling().apply { expectedRemotePeer = aPeer.remotePeerId }
        val toA = DirectSignaling().apply { expectedRemotePeer = bPeer.remotePeerId }
        aPeer.signaling = toB
        bPeer.signaling = toA
        toA.onRemoteSdp = { answer -> aPeer.applyAnswer(answer) }
        toB.onRemoteSdp = { offer -> bPeer.acceptOffer(offer) }
        toA.onRemoteIce = { ice -> aPeer.deliverIce(ice) }
        toB.onRemoteIce = { ice -> bPeer.deliverIce(ice) }
        aPeer.startOffering()
    }

    // ===================================================================
    // 3P — El experimento minimo
    // ===================================================================

    @Test
    @DisplayName("3P-01 mensaje cifrado viaja por WebRTC real y llega descifrado")
    fun `3P-01 mensaje E2E sobre WebRTC real`() {
        val est = establish()
        val aliceNode = nodes[0]
        val bobNode = nodes[1]
        val aCtx = aliceNode.contextTowards(bobNode)
        val bCtx = bobNode.contextTowards(aliceNode)
        val aPeer = aliceNode.peerTowards(bobNode, aCtx)
        val bPeer = bobNode.peerTowards(aliceNode, bCtx)
        // El binding ya lo creo el manager de km-core; aqui solo se cablean
        // los eventos de WebRTC.
        aPeer.attach()
        bPeer.attach()
        aPeer.onTransportData = { bytes -> est.alice.onTransportBytes(bytes) }
        bPeer.onTransportData = { bytes -> est.bob.onTransportBytes(bytes) }

        connect(est, aPeer, bPeer)
        assertTrue(aPeer.awaitConnected(20_000L), "Alice debe llegar a CONNECTED")
        assertTrue(bPeer.awaitConnected(20_000L), "Bob debe llegar a CONNECTED")
        assertEquals(PeerTransportState.CONNECTED, aPeer.bindingState())
        assertTrue(aliceNode.transport.awaitDataChannelOpen(aliceNode.identityId, bobNode.identityId, 10_000L), "DataChannel de Alice")
        assertTrue(bobNode.transport.awaitDataChannelOpen(bobNode.identityId, aliceNode.identityId, 10_000L), "DataChannel de Bob")

        val delivered = CountDownLatch(1)
        est.bob.onPlaintext { delivered.countDown() }

        val sent = est.alice.send("hello".toByteArray())
        assertTrue(sent is SendResult.Ok, "send debe funcionar sobre WebRTC real: $sent")

        assertTrue(delivered.await(15, TimeUnit.SECONDS), "Bob debe recibir y descifrar")
        val plaintext = est.bob.drainOutbound().first()
        assertContentEquals("hello".toByteArray(), plaintext, "el plaintext debe llegar intacto")
    }

    @Test
    @DisplayName("3P-02 el transporte WebRTC solo ve bytes opacos")
    fun `3P-02 el transporte no ve plaintext ni bootstrap`() {
        val est = establish()
        val aliceNode = nodes[0]
        val bobNode = nodes[1]
        val aCtx = aliceNode.contextTowards(bobNode)
        val bCtx = bobNode.contextTowards(aliceNode)
        val aPeer = aliceNode.peerTowards(bobNode, aCtx)
        val bPeer = bobNode.peerTowards(aliceNode, bCtx)
        // El binding ya lo creo el manager de km-core; aqui solo se cablean
        // los eventos de WebRTC.
        aPeer.attach()
        bPeer.attach()
        aPeer.onTransportData = { bytes -> est.alice.onTransportBytes(bytes) }
        bPeer.onTransportData = { bytes -> est.bob.onTransportBytes(bytes) }
        connect(est, aPeer, bPeer)
        aPeer.awaitConnected(20_000L)
        bPeer.awaitConnected(20_000L)
        aliceNode.transport.awaitDataChannelOpen(aliceNode.identityId, bobNode.identityId, 10_000L)
        bobNode.transport.awaitDataChannelOpen(bobNode.identityId, aliceNode.identityId, 10_000L)

        val captured = CopyOnWriteArrayList<ByteArray>()
        val prev = bPeer.transport.eventCallbacks
        bPeer.transport.eventCallbacks = prev.copy(
            onDataReceived = { from, data ->
                captured.add(data)
                prev.onDataReceived?.invoke(from, data)
            }
        )
        val secret = "contenido-que-no-debe-ver-el-transporte".toByteArray()
        est.alice.send(secret)
        Thread.sleep(1_000)

        val wire = captured.firstOrNull()
        assertNotNull(wire, "debe capturarse algo por el DataChannel")
        assertFalse(indexOf(wire!!, secret) >= 0, "el transporte no debe ver plaintext")
        // Tampoco el bootstrap value F.
        assertFalse(indexOf(wire, est.bootstrapValue) >= 0, "F no debe viajar en claro")
    }

    @Test
    @DisplayName("3P-03 frame manipulado sobre WebRTC no entrega nada")
    fun `3P-03 frame manipulado rechazado`() {
        val est = establish()
        val aliceNode = nodes[0]
        val bobNode = nodes[1]
        val aCtx = aliceNode.contextTowards(bobNode)
        val bCtx = bobNode.contextTowards(aliceNode)
        val aPeer = aliceNode.peerTowards(bobNode, aCtx)
        val bPeer = bobNode.peerTowards(aliceNode, bCtx)
        // El binding ya lo creo el manager de km-core; aqui solo se cablean
        // los eventos de WebRTC.
        aPeer.attach()
        bPeer.attach()
        // Bob NO descifra automaticamente: el test controla la entrega.
        aPeer.onTransportData = { bytes -> est.alice.onTransportBytes(bytes) }
        connect(est, aPeer, bPeer)
        aPeer.awaitConnected(20_000L)
        bPeer.awaitConnected(20_000L)
        aliceNode.transport.awaitDataChannelOpen(aliceNode.identityId, bobNode.identityId, 10_000L)
        bobNode.transport.awaitDataChannelOpen(bobNode.identityId, aliceNode.identityId, 10_000L)

        val captured = CopyOnWriteArrayList<ByteArray>()
        val prev = bPeer.transport.eventCallbacks
        bPeer.transport.eventCallbacks = prev.copy(
            onDataReceived = { from, data ->
                captured.add(data)
                prev.onDataReceived?.invoke(from, data)
            }
        )
        val before = est.bob.ratchetFingerprint()
        est.alice.send("secreto".toByteArray())
        Thread.sleep(1_000)

        val wire = captured.first()
        val tampered = wire.copyOf()
        tampered[tampered.size - 1] = (tampered[tampered.size - 1] + 1).toByte()
        val result = est.bob.receive(tampered)
        assertTrue(result is ReceiveResult.Rejected, "un frame manipulado no debe entregar nada: $result")
        assertContentEquals(before, est.bob.ratchetFingerprint(), "no debe mutar el estado")
    }

    // ===================================================================
    // AISLAMIENTO DEL CHECKPOINT
    // ===================================================================

    @Test
    @DisplayName("3P-04 km-core no importa WebRTC: la dependencia es de una sola direccion")
    fun `3P-04 km-core sin WebRTC`() {
        val coreDir = java.io.File("../km-core/src/main/kotlin")
        assertTrue(coreDir.exists(), "debe existir km-core/src/main/kotlin")
        val offenders = coreDir.walkTopDown().filter { it.extension == "kt" }
            .filter { f ->
                f.readLines()
                    .filterNot { val t = it.trimStart(); t.startsWith("*") || t.startsWith("//") || t.startsWith("/*") }
                    .any { it.contains("dev.onvoid") || it.contains("WebRtc") || it.contains("webrtc") }
            }.map { it.name }.toList()
        assertTrue(offenders.isEmpty(), "km-core no debe referenciar WebRTC en codigo: $offenders")
    }

    // ------------------------------------------------------------------

    private fun deriveId(publicKey: ByteArray): String =
        MessageDigest.getInstance("SHA-256").let { d ->
            d.update("KM-ID-IDENTITY".toByteArray())
            d.update(publicKey)
            d.digest().joinToString("") { "%02x".format(it) }
        }

    private fun indexOf(h: ByteArray, n: ByteArray): Int {
        if (n.isEmpty() || n.size > h.size) return -1
        outer@ for (i in 0..h.size - n.size) {
            for (j in n.indices) if (h[i + j] != n[j]) continue@outer
            return i
        }
        return -1
    }


}
