package com.km.messaging

import com.km.auth.AuthChallenge
import com.km.auth.AuthOk
import com.km.auth.AuthSession
import com.km.auth.Clock
import com.km.auth.TranscriptBuilder
import com.km.crypto.Ed25519
import com.km.crypto.Ed25519Impl
import com.km.crypto.KeyPair
import com.km.crypto.X25519
import com.km.crypto.provider.BcChaCha20Poly1305
import com.km.crypto.provider.BcHkdfSha256
import com.km.crypto.provider.BcX25519
import com.km.model.IdentityId
import com.km.model.MessageId
import com.km.model.NodeAnnouncement
import com.km.model.NodeAnnouncementJsonCodec
import com.km.model.NodeCapability
import com.km.model.NodeIdentity
import com.km.node.FakeTransportBackend
import com.km.node.PeerContext
import com.km.node.PeerTransportManager
import com.km.node.PeerTransportState
import com.km.ratchet.RatchetSessionBootstrap
import com.km.frame.SecureFrameProtector
import com.km.frame.SecureRatchetProtocol
import com.km.x3dh.X3dh
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.assertContentEquals

/**
 * 3O.5.1 — SecureMessagingSession sobre un transporte EN MEMORIA.
 *
 * PRUEBA ARQUITECTONICA CENTRAL: esta suite corre sin WebRTC, sin relay, sin
 * Tor y sin red. Usa `FakeTransportBackend`, que ya existe en km-core. Eso
 * demuestra que la capa criptografica esta correctamente aislada del
 * transporte antes de introducir conectividad P2P.
 *
 * Cubre los TRES estados, sin mezclarlos:
 *   1. Bootstrap X3DH   : ContactBundle-equivalente -> SK, K_prekey, F
 *   2. Establishment    : F -> RootKey -> estado del ratchet
 *   3. Mensajeria       : plaintext <-> ratchet <-> SecureFrame <-> transporte
 */
class SecureMessagingSessionTest {

    private val ed25519: Ed25519 = Ed25519Impl()
    private lateinit var x25519: X25519
    private lateinit var protector: SecureFrameProtector
    private lateinit var x3dh: X3dh

    private val random = SecureRandom()

    @BeforeEach
    fun setUp() {
        x25519 = BcX25519()
        protector = SecureFrameProtector(BcChaCha20Poly1305(), BcHkdfSha256())
        x3dh = X3dh(x25519, BcHkdfSha256())
    }

    // ===================================================================
    // Nodo con identidad y PeerContext AUTENTICADO reales
    // ===================================================================

    private inner class Node(label: String) {
        val keyPair: KeyPair = ed25519.generateKeyPair()
        val identityId: IdentityId = IdentityId(deriveId(keyPair.publicKey))
        val dh: com.km.crypto.X25519KeyPair = x25519.generateKeyPair()
        val spk: com.km.crypto.X25519KeyPair = x25519.generateKeyPair()
        var opk: com.km.crypto.X25519KeyPair? = null
        val label = label

        // TRANSPORTE EN MEMORIA: sin WebRTC, sin relay, sin red.
        val manager = PeerTransportManager(
            transportBackend = FakeTransportBackend(),
            now = { 0L },
        )

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

        /** PeerContext AUTENTICADO hacia el nodo indicado (KM-0002 real). */
        fun contextTowards(other: Node): PeerContext {
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
            val ok = authOkFor(other, session)
            session.receiveAuthOk(ok, identityId.value).getOrThrow()
            return PeerContext.authenticated(
                remotePeerId = other.identityId,
                publicKey = other.keyPair.publicKey,
                announcement = other.announcement(),
                authSession = session,
            )
        }

        private fun authOkFor(other: Node, session: AuthSession): AuthOk {
            val sid = MessageDigest.getInstance("SHA-256")
                .digest("sid-$label".toByteArray())
                .joinToString("") { "%02x".format(it) }
                .take(40)
            val nonce = ByteArray(16) { 9 }
            val transcript = TranscriptBuilder.serverAuthTranscript(
                sid, nonce, session.localIdentityId,
            )
            val sig = other.ed25519Sign(transcript)
            return AuthOk(sid, nonce, other.keyPair.publicKey, sig)
        }

        fun ed25519Sign(data: ByteArray): ByteArray =
            ed25519.sign(keyPair.privateKey, data).bytes

        fun prekeys() = com.km.x3dh.BootstrapPrekeys(
            deviceId = ByteArray(32) { it.toByte() },
            identityAgreementKey = dh.publicKey,
            signedPreKey = spk.publicKey,
            signedPreKeyId = 1L,
            oneTimePreKey = opk?.publicKey,
            oneTimePreKeyId = if (opk != null) 5L else null,
        )

        fun responderMaterial() = com.km.x3dh.ResponderKeyMaterial(
            deviceId = ByteArray(32) { it.toByte() },
            identityAgreementKey = dh,
            signedPreKey = spk,
            oneTimePreKey = opk,
            oneTimePreKeyId = if (opk != null) 5L else null,
        )
    }

    // ===================================================================
    // Bootstrap de los tres estados
    // ===================================================================

    /** Ejecuta los estados 1 y 2 para ambos lados y devuelve las sesiones. */
    private fun bootstrapPair(
        withOneTimePrekey: Boolean = true,
        driveConnected: Boolean = true,
        routeToPeer: Boolean = true,
    ): Pair<SecureMessagingSession, SecureMessagingSession> {
        val alice = Node("alice")
        val bob = Node("bob")
        if (withOneTimePrekey) {
            bob.opk = x25519.generateKeyPair()
        }

        // --- Estado 1: bootstrap X3DH ---
        val f = ByteArray(32).also { random.nextBytes(it) }
        val init = x3dh.initiate(
            initiator = com.km.x3dh.InitiatorKeyMaterial(alice.identityId.value.let { ByteArray(32) }, alice.dh),
            prekeys = bob.prekeys(),
            bootstrapValue = f,
        )
        val prep = x3dh.respond(
            responder = bob.responderMaterial(),
            remotePrekeys = alice.prekeys(),
            ephemeralPublic = init.ephemeralPublic,
        )
        // Ambos derivan el mismo SK.
        assertContentEquals(init.sharedKey, prep.sharedKey, "SK debe coincidir")
        val session = prep.commit()

        // --- Estado 2: establishment F -> RootKey ---
        val aliceProtocol = RatchetSessionBootstrap.initiatorProtocol(
            bootstrapValue = f,
            // La MISMA clave efimera que X3DH genero: es la que el ratchet
            // necesita para continuar la sesion.
            ephemeral = init.ephemeral,
            remoteSignedPreKey = bob.spk.publicKey,
            signedPreKeyPrivate = bob.spk.privateKey,
            x25519 = x25519,
            kdf = BcHkdfSha256(),
            protector = protector,
        )
        val bobProtocol = SecureRatchetProtocol(
            session = com.km.ratchet.DoubleRatchetSession(
                rootKey = RatchetSessionBootstrap.rootKeyFrom(f, BcHkdfSha256()),
                // CRITICO: el DH propio de Bob debe ser su par SPK, que es
                // exactamente la clave que Alice uso como remoteSignedPreKey.
                // Con una clave distinta, DH(SPK_B.priv, EK_A.pub) no
                // coincidiria con DH(EK_A.priv, SPK_B.pub).
                dhSelf = bob.spk,
                // dhRemote debe ser null: el DH ratchet ocurre en la PRIMERA
                // recepcion. Poner la clave de Alice aqui haria que el
                // receptor creyera que la epoch ya existe y se saltaria el ratchet.
                dhRemote = null,
                sendChainKey = ByteArray(32),
                receiveChainKey = ByteArray(32),
                x25519 = x25519,
                kdf = BcHkdfSha256(),
            ),
            protector = protector,
        )

        // --- Estado 3: mensajeria ---
        val aliceSession = SecureMessagingSession(
            localIdentity = alice.identityId,
            peerContext = alice.contextTowards(bob),
            protocol = aliceProtocol,
            transport = alice.manager,
        )
        val bobSession = SecureMessagingSession(
            localIdentity = bob.identityId,
            peerContext = bob.contextTowards(alice),
            protocol = bobProtocol,
            transport = bob.manager,
        )
        // Abrir bindings: el manager exige PeerContext AUTHENTICATED.
        alice.manager.createBinding(alice.identityId, aliceSession.peerContext).getOrThrow()
        bob.manager.createBinding(bob.identityId, bobSession.peerContext).getOrThrow()
        // Conducir los bindings a CONNECTED por la maquina de estados de km-core,
        // que es lo que un transporte real reportaria. El ratchet no hace
        // senalizacion: eso es responsabilidad del transporte.
        if (driveConnected) {
            driveToConnected(aliceSession)
            driveToConnected(bobSession)
        }
        // El transporte en memoria enruta bytes entre sesiones. Puede
        // desactivarse para exert donde el test controla la entrega exacta.
        if (routeToPeer) {
            alice.manager.onData { from, data -> deliver(bobSession, from, data) }
            bob.manager.onData { from, data -> deliver(aliceSession, from, data) }
        }

        aliceNode = alice
        bobNode = bob
        return aliceSession to bobSession
    }

    private var aliceNode: Node? = null
    private var bobNode: Node? = null

    /**
     * Conduce un binding a CONNECTED usando solo el API del manager.
     *
     * Simula lo que haria el transporte real (SDP offer/answer e ICE), sin
     * introducir WebRTC en la suite.
     */
    private fun driveToConnected(session: SecureMessagingSession) {
        val m = session.transportManager
        val ctx = session.peerContext
        m.sendOffer(session.localIdentity, ctx, "sdp-offer", session.remotePeerId).getOrThrow()
        m.receiveAnswer(session.localIdentity, session.remotePeerId, "sdp-answer").getOrThrow()
        m.markConnected(session.localIdentity, ctx).getOrThrow()
    }

    /**
     * Entrega bytes a la sesion destinataria.
     *
     * `PeerTransportManager.sendData` invoca los handlers con el remotePeerId del
     * DESTINATARIO, de modo que la sesion correcta se identifica por su
     * `localIdentity`.
     */
    private fun deliver(target: SecureMessagingSession, to: IdentityId, data: ByteArray) {
        if (target.localIdentity == to) target.onTransportBytes(data)
    }

    // ===================================================================
    // E2E sobre transporte en memoria
    // ===================================================================

    @Test
    fun `MSG-01 Alice envia a Bob y Bob recibe plaintext`() {
        val (alice, bob) = bootstrapPair()
        val sent = alice.send("hola Bob".toByteArray())
        assertTrue(sent is SendResult.Ok, "send debe funcionar: $sent")
        // El mensaje llega como bytes opacos; la sesion de Bob los descifra.
        val received = bob.drainOutbound().firstOrNull()
        assertNotNull(received, "Bob debe descifrar y entregar plaintext")
        assertContentEquals("hola Bob".toByteArray(), received!!)
    }

    @Test
    fun `MSG-02 el transporte solo ve bytes opacos, no plaintext`() {
        val (alice, _) = bootstrapPair()
        val plaintext = "contenido-secreto".toByteArray()
        val captured = CopyOnWriteArrayList<ByteArray>()
        alice.transportManager.onData { _, data -> captured.add(data) }
        alice.send(plaintext)
        val wire = captured.first()
        // El wire NO contiene el plaintext.
        assertFalse(indexOf(wire, plaintext) >= 0, "el transporte no debe ver plaintext")
        // Y no es igual al plaintext.
        assertFalse(wire.contentEquals(plaintext))
    }

    @Test
    fun `MSG-03 varios mensajes en orden`() {
        val (alice, bob) = bootstrapPair()
        for (i in 0..4) {
            alice.send("m$i".toByteArray())
        }
        val out = bob.drainOutbound().map { String(it) }
        assertEquals(listOf("m0", "m1", "m2", "m3", "m4"), out)
    }

    @Test
    fun `MSG-04 bidireccional`() {
        val (alice, bob) = bootstrapPair()
        alice.send("a->b".toByteArray())
        assertContentEquals("a->b".toByteArray(), bob.drainOutbound().first())
        bob.send("b->a".toByteArray())
        assertContentEquals("b->a".toByteArray(), alice.drainOutbound().first())
    }

    @Test
    fun `MSG-05 bytes binarios preservados exactamente`() {
        val (alice, bob) = bootstrapPair()
        val payload = byteArrayOf(0x00, 0x01, 0x7F, 0x80.toByte(), 0xFF.toByte())
        alice.send(payload)
        assertContentEquals(payload, bob.drainOutbound().first())
    }

    // ===================================================================
    // Seguridad: lo que la sesion debe impedir
    // ===================================================================

    @Test
    fun `MSG-06 un frame manipulado no entrega plaintext`() {
        // routeToPeer=false: el frame NO se entrega solo, de modo que el
        // unico intento de descifrado es el manipulado.
        val (alice, bob) = bootstrapPair(routeToPeer = false)
        val before = bob.ratchetFingerprint()
        val captured = CopyOnWriteArrayList<ByteArray>()
        alice.transportManager.onData { _, data -> captured.add(data) }
        alice.send("secreto".toByteArray())
        val tampered = captured.first().copyOf()
        tampered[tampered.size - 1] = (tampered[tampered.size - 1] + 1).toByte()

        val result = bob.receive(tampered)
        assertTrue(
            result is ReceiveResult.Rejected,
            "un frame manipulado no debe entregar plaintext, fue $result",
        )
        assertContentEquals(before, bob.ratchetFingerprint(), "no debe mutar el estado")
    }

    @Test
    fun `MSG-07 PeerContext no autenticado no puede enviar`() {
        val (alice, _) = bootstrapPair()
        val ctx = alice.peerContext
        ctx.authSession.receiveAuthFail()
        val result = alice.send("x".toByteArray())
        assertTrue(result is SendResult.Rejected, "debe rechazar: $result")
        assertEquals(SendReject.NOT_AUTHENTICATED, (result as SendResult.Rejected).reason)
    }

    @Test
    fun `MSG-08 el remotePeerId viene del PeerContext`() {
        val (alice, bob) = bootstrapPair()
        assertEquals(bobNode!!.identityId, alice.remotePeerId)
        assertEquals(aliceNode!!.identityId, bob.remotePeerId)
        assertNotEquals(aliceNode!!.identityId, alice.remotePeerId)
    }

    @Test
    fun `MSG-09 no se envia antes de CONNECTED`() {
        val (alice, _) = bootstrapPair(driveConnected = false)
        assertEquals(PeerTransportState.NEW, alice.bindingState())
        val result = alice.send("prematuro".toByteArray())
        assertTrue(result is SendResult.Rejected, "sin CONNECTED no debe enviar")
    }

    @Test
    fun `MSG-10 sesion cerrada rechaza send y receive`() {
        val (alice, bob) = bootstrapPair()
        alice.close()
        assertTrue(alice.isClosed)
        assertTrue(alice.send("x".toByteArray()) is SendResult.Rejected)
        assertTrue(alice.receive(byteArrayOf(1, 2, 3)) is ReceiveResult.Rejected)
    }

    // ===================================================================
    // AISLAMIENTO ARQUITECTONICO
    // ===================================================================

    @Test
    fun `MSG-11 la sesion no menciona ninguna implementacion de transporte`() {
        val src = java.io.File("src/main/kotlin/com/km/messaging")
        assertTrue(src.exists(), "debe existir el paquete messaging")
        val offenders = src.walkTopDown().filter { it.extension == "kt" }
            .filter { f ->
                f.readLines()
                    .filterNot { val t = it.trimStart(); t.startsWith("*") || t.startsWith("//") || t.startsWith("/*") }
                    .any {
                        it.contains("WebRtc") || it.contains("dev.onvoid") ||
                            it.contains("is Tor") || it.contains("RelayServer")
                    }
            }.map { it.name }.toList()
        assertTrue(offenders.isEmpty(), "la sesion no debe conocer transportes: $offenders")
    }

    @Test
    fun `MSG-12 la sesion solo depende del API de PeerTransportManager`() {
        val fields = SecureMessagingSession::class.java.declaredFields.map { it.type.simpleName }
        assertTrue(fields.contains("PeerTransportManager"), "debe usar PeerTransportManager: $fields")
        // Y no debe retener ningun backend concreto.
        assertFalse(fields.any { it.contains("Backend", ignoreCase = true) }, "$fields")
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
