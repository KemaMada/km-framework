package com.km.node

import com.km.auth.*
import com.km.crypto.Ed25519
import com.km.crypto.Ed25519Impl
import com.km.negotiation.Capability
import com.km.negotiation.CapabilitySet
import com.km.negotiation.KceValue
import com.km.negotiation.Km7Negotiation
import com.km.identity.DeviceRoster
import com.km.identity.KmIds
import com.km.model.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.util.UUID
import kotlin.test.assertContentEquals

/**
 * Integration test del runtime end-to-end.
 *
 * Demuestra el flujo completo:
 *   NodeIdentity → NodeAnnouncement → AuthChallenge → AuthResponse → AUTH_OK
 *   → KM-0007 negotiation → negotiationHash binding
 *
 * Y mutaciones en CADA frontera que demuestran que las invariantes se mantienen.
 */
class RuntimeIntegrationTest {

    private val ed25519: Ed25519 = Ed25519Impl()
    private val aliceKP = ed25519.generateKeyPair()
    private val bobKP = ed25519.generateKeyPair()

    private val fakeCore = object : com.km.api.KeyMessageCore {
        override fun start(): Result<Unit> = Result.success(Unit)
        override fun stop() {}
        override fun createMessage(to: IdentityId, payload: ByteArray): Result<com.km.model.Message> = Result.failure(UnsupportedOperationException())
        override fun sendMessage(message: com.km.model.Message): Result<com.km.protocol.SendResult> = Result.failure(UnsupportedOperationException())
        override fun getMessageState(messageId: MessageId): Result<com.km.model.MessageState> = Result.failure(UnsupportedOperationException())
        override fun getConversation(peerId: IdentityId): Result<List<com.km.model.Message>> = Result.failure(UnsupportedOperationException())
        override fun registerAckHandler(handler: (com.km.model.Ack) -> Unit) {}
        override fun registerMessageHandler(handler: (com.km.model.Message) -> Unit) {}
        override fun registerStateChangeHandler(handler: (MessageId, com.km.model.MessageState) -> Unit) {}
    }

    private val alice = NodeRuntimeImpl.fromKeyPair(
        publicKey = aliceKP.publicKey,
        privateKey = aliceKP.privateKey,
        nodeName = "Alice-Node",
        capabilities = setOf(NodeCapability.CLIENT),
        clientCore = fakeCore,
        ed25519 = ed25519,
    )
    private val bob = NodeRuntimeImpl.fromKeyPair(
        publicKey = bobKP.publicKey,
        privateKey = bobKP.privateKey,
        nodeName = "Bob-Node",
        capabilities = setOf(NodeCapability.CLIENT),
        clientCore = fakeCore,
        ed25519 = ed25519,
    )

    private val defaultTs: Long get() = System.currentTimeMillis() - 1000L // 1 segundo atras, dentro de ventana

    // ===================================================================
    // END-TO-END: flujo completo
    // ===================================================================

    @Test
    fun `NodeAnnouncement encode decode roundtrip preserva identity`() {
        val now = System.currentTimeMillis() - 1000L
        val ann = alice.createNodeAnnouncement(timestamp = now)
        val json = NodeAnnouncementJsonCodec.encode(ann)
        val decoded = NodeAnnouncementJsonCodec.decode(json)
        // Verificar identity
        assertEquals(ann.identity.nodeId.value, decoded.identity.nodeId.value,
            "nodeId debe coincidir")
        assertContentEquals(ann.identity.publicKey, decoded.identity.publicKey,
            "publicKey debe coincidir")
    }

    @Test
    fun `NodeIdentity fromKeyPair garantiza invariante identityId == nodeId`() {
        val nodeId = alice.identity.nodeId
        val expected = KmIds.identityId(aliceKP.publicKey).toHexString()
        assertEquals(expected, nodeId.value, "nodeId debe derivar de publicKey")
    }

    @Test
    fun `createNodeAnnouncement firmado verifica correctamente`() {
        val ann = alice.createNodeAnnouncement(timestamp = defaultTs)
        val signableBytes = NodeAnnouncementJsonCodec.signableJson(ann)
        assertDoesNotThrow {
            ann.verifyWireBytes(signableBytes, now = defaultTs).getOrThrow()
        }
    }

    @Test
    fun `verifyNodeAnnouncement decodifica y verifica en un paso`() {
        val ann = alice.createNodeAnnouncement(timestamp = defaultTs)
        val jsonBytes = NodeAnnouncementJsonCodec.encode(ann)
        val verified = alice.verifyNodeAnnouncement(jsonBytes)
        assertEquals(ann.messageId, verified.messageId)
        assertContentEquals(ann.identity.publicKey, verified.identity.publicKey)
    }

    // ===================================================================
    // Mutaciones por frontera
    // ===================================================================

    @Test
    fun `mutation Alice publicKey alterada falla en verifyNodeAnnouncement`() {
        val ann = alice.createNodeAnnouncement(timestamp = defaultTs)
        val jsonBytes = NodeAnnouncementJsonCodec.encode(ann)
        val jsonStr = String(jsonBytes, Charsets.UTF_8)
        val pubB64 = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(aliceKP.publicKey)
        val otraPub = ed25519.generateKeyPair().publicKey
        val otraB64 = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(otraPub)
        val mutated = jsonStr.replace("\"publicKey\":\"$pubB64\"", "\"publicKey\":\"$otraB64\"")

        // nodeId no coincide con la nueva publicKey
        assertThrows<IllegalArgumentException> {
            alice.verifyNodeAnnouncement(mutated.encodeToByteArray())
        }
    }

    @Test
    fun `mutation identityId como publicKey falla`() {
        val ann = alice.createNodeAnnouncement(timestamp = defaultTs)
        val jsonBytes = NodeAnnouncementJsonCodec.encode(ann)
        val jsonStr = String(jsonBytes, Charsets.UTF_8)
        val pubB64 = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(aliceKP.publicKey)
        // identityId (64 hex chars = 64 ASCII bytes) en base64 son 86 chars
        val identityIdBytes = alice.identity.nodeId.value.encodeToByteArray() // 64 bytes
        val identityIdB64 = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(identityIdBytes)
        val mutated = jsonStr.replace("\"publicKey\":\"$pubB64\"", "\"publicKey\":\"$identityIdB64\"")
        // 86 base64 chars = 64 bytes, esperaba 32 bytes
        assertThrows<NodeAnnouncementCodecException> {
            alice.verifyNodeAnnouncement(mutated.encodeToByteArray())
        }
    }

    @Test
    fun `mutation deviceId como publicKey falla en verificar`() {
        val deviceId = KmIds.deviceId(aliceKP.publicKey)
        val sig = ed25519.sign(aliceKP.privateKey, "test".encodeToByteArray())
        // deviceId NO debe verificar como publicKey
        assertFalse(ed25519.verify(deviceId, "test".encodeToByteArray(), sig),
            "deviceId no debe verificar como publicKey")
    }

    @Test
    fun `mutation AuthTranscript alterado detectado por AuthVerifier`() {
        val challenge = bob.createAuthChallenge(alice.identity.nodeId)
        val session = alice.startAuthSession(bob.identity.nodeId, challenge, bob.identity.nodeId.value)
        val response = alice.buildAuthResponse(bob.identity.nodeId, peerIdentityId = bob.identity.nodeId.value)

        // Construir un transcript diferente al que Alice firmo (con identityId de Bob)
        val differentTranscript = TranscriptBuilder.authTranscript(
            nonce = challenge.nonce,
            timestampMillis = challenge.timestampMillis,
            responderIdentityId = challenge.responderIdentityId,
            identityId = bob.identity.nodeId.value,
        )

        val verifier = AuthVerifier(
            ed25519 = ed25519,
            clock = Clock { challenge.timestampMillis },
            replayGuard = NonceReplayGuard { true },
        )

        // Crear un AuthResponse falso con el transcript incorrecto
        val fakeSig = ed25519.sign(aliceKP.privateKey, differentTranscript)
        val fakeResponse = AuthResponse(
            identityId = bob.identity.nodeId.value,
            publicKey = aliceKP.publicKey,
            signature = fakeSig.bytes,
            protocolVersion = 3,
        )
        // V1: identityId no deriva de publicKey
        assertEquals(AuthError.INVALID_IDENTITY, verifier.verifyChallengeResponse(challenge, fakeResponse))
    }

    @Test
    fun `mutation AUTH_OK sessionId alterado detectado`() {
        val ann = alice.createNodeAnnouncement(timestamp = defaultTs)
        val challenge = bob.createAuthChallenge(alice.identity.nodeId)
        val session = alice.startAuthSession(bob.identity.nodeId, challenge, bob.identity.nodeId.value)
        val response = alice.buildAuthResponse(bob.identity.nodeId, peerIdentityId = bob.identity.nodeId.value)
        alice.markAuthResponseSent(bob.identity.nodeId)
        val authOk = bob.verifyAuthResponseAndBuildOk(alice.identity.nodeId, response, challenge)

        // Mutar sessionId en el AUTH_OK
        val mutatedAuthOk = authOk.copy(sessionId = "ffffffffffffffffffffffffffffffffffffffff")

        assertThrows<Exception> {
            alice.receiveAuthOk(
                peerId = bob.identity.nodeId,
                authOk = mutatedAuthOk,
                expectedPeerIdentityId = bob.identity.nodeId.value,
                announcement = ann,
            )
        }
    }

    @Test
    fun `mutation negotiationHash distinto si capacidades difieren`() {
        // Alice anuncia su identidad y recibe el anuncio de Bob
        val aliceAnn = alice.createNodeAnnouncement(timestamp = defaultTs)
        val bobAnn = bob.createNodeAnnouncement(timestamp = defaultTs)

        val challenge = bob.createAuthChallenge(alice.identity.nodeId)
        val session = alice.startAuthSession(bob.identity.nodeId, challenge, bob.identity.nodeId.value)
        val response = alice.buildAuthResponse(bob.identity.nodeId, bob.identity.nodeId.value)
        alice.markAuthResponseSent(bob.identity.nodeId)
        val authOk = bob.verifyAuthResponseAndBuildOk(alice.identity.nodeId, response, challenge)
        // Alice recibe AUTH_OK: el peer es Bob, su announcement es bobAnn
        alice.receiveAuthOk(bob.identity.nodeId, authOk, alice.identity.nodeId.value, bobAnn)

        // Negociar con capacidades normales
        val caps = CapabilitySet(listOf(
            Capability("serialization", 1, mapOf("format" to KceValue.VString("cbor"))),
        ))
        val ctx = alice.negotiateCapabilities(bob.identity.nodeId, caps, caps)
        val negotiatedHash = ctx.negotiationHash

        // Construir un hash que simula una negociacion diferente
        val differentCaps = CapabilitySet(listOf(
            Capability("serialization", 2, mapOf("format" to KceValue.VString("json"))),
        ))
        val differentResult = Km7Negotiation.negotiate(differentCaps, differentCaps)
        val differentHash = Km7Negotiation.negotiationHash(differentResult)

        assertFalse(negotiatedHash!!.contentEquals(differentHash),
            "el negotiationHash debe vincularse a las capacidades negociadas reales")
    }

    @Test
    fun `mutation NodeAnnouncement bytes alterados falla verifyWireBytes`() {
        val ann = alice.createNodeAnnouncement(timestamp = defaultTs)
        val signableBytes = NodeAnnouncementJsonCodec.signableJson(ann)
        // Mutar un byte del signable payload
        signableBytes[5] = (signableBytes[5].toInt() xor 1).toByte()
        assertThrows<NodeAnnouncementVerificationException> {
            ann.verifyWireBytes(signableBytes, now = defaultTs).getOrThrow()
        }
    }

    @Test
    fun `mutation identityId != publicKey detectado en verifyChallengeResponse`() {
        val challenge = bob.createAuthChallenge(alice.identity.nodeId)
        // Alice firma con su clave pero declara identityId de Bob
        val wrongIdentityId = bob.identity.nodeId.value
        val transcript = TranscriptBuilder.authTranscript(
            nonce = challenge.nonce,
            timestampMillis = challenge.timestampMillis,
            responderIdentityId = challenge.responderIdentityId,
            identityId = wrongIdentityId,
        )
        val sig = ed25519.sign(aliceKP.privateKey, transcript)
        val response = AuthResponse(
            identityId = wrongIdentityId,
            publicKey = aliceKP.publicKey,
            signature = sig.bytes,
            protocolVersion = 3,
        )

        val verifier = AuthVerifier(
            ed25519 = ed25519,
            clock = Clock { challenge.timestampMillis },
            replayGuard = NonceReplayGuard { true },
        )
        // V1: identityId no deriva de publicKey
        assertEquals(AuthError.INVALID_IDENTITY, verifier.verifyChallengeResponse(challenge, response))
    }

    @Test
    fun `mutation estado IDLE no permite buildResponse`() {
        val session = AuthSession.initiator(
            keyPair = aliceKP,
            identityId = alice.identity.nodeId.value,
            ed25519 = ed25519,
        )
        assertEquals(AuthSessionState.IDLE, session.state)

        assertThrows<IllegalStateException> {
            session.buildResponse(bob.identity.nodeId.value)
        }
    }

    @Test
    fun `createNodeAnnouncement sin keyPair lanza IllegalStateException`() {
        val nodeSinClaves = NodeRuntimeImpl(
            identity = NodeIdentity.fromKeyPair(aliceKP.publicKey),
            capabilities = setOf(NodeCapability.CLIENT),
            clientCore = fakeCore,
        )
        val e = assertThrows<IllegalStateException> {
            nodeSinClaves.createNodeAnnouncement()
        }
        assertTrue(e.message!!.contains("KeyPair"))
    }

    @Test
    fun `announcement signableJson produce mismos bytes para creador y verificador`() {
        val ann = alice.createNodeAnnouncement(timestamp = defaultTs)
        val creatorSignable = NodeAnnouncementJsonCodec.signableJson(ann)

        // Verifier recibe JSON, decodea, obtiene signableJson
        val fullJson = NodeAnnouncementJsonCodec.encode(ann)
        val decoded = alice.verifyNodeAnnouncement(fullJson)
        val verifierSignable = NodeAnnouncementJsonCodec.signableJson(decoded)

        assertContentEquals(creatorSignable, verifierSignable,
            "creator y verifier deben producir los mismos signable bytes")
    }

    @Test
    fun `PeerContext requiere sesion AUTHENTICATED`() {
        val ann = alice.createNodeAnnouncement(timestamp = defaultTs)
        val session = AuthSession.initiator(
            keyPair = aliceKP,
            identityId = alice.identity.nodeId.value,
            ed25519 = ed25519,
        )
        // session esta en IDLE, no AUTHENTICATED
        assertThrows<IllegalArgumentException> {
            PeerContext.authenticated(
                remotePeerId = bob.identity.nodeId,
                publicKey = bobKP.publicKey,
                announcement = ann,
                authSession = session,
            )
        }
    }

    @Test
    fun `negotiation categorias obligatorias vacias fallan`() {
        val localCaps = CapabilitySet(listOf(
            Capability("serialization", 1),
        ))
        val peerCaps = CapabilitySet(listOf(
            Capability("compression", 1),
        ))
        val result = Km7Negotiation.negotiate(localCaps, peerCaps, requiredCategories = setOf("cipher"))
        assertTrue(result.isFailure)
        assertTrue(result.failureReason!!.contains("cipher"))
    }

    @Test
    fun `NodeAnnouncement encode decode roundtrip preserva todos los campos`() {
        val limits = RelayLimits()
        val endpoints = listOf(
            NodeEndpoint("websocket", "wss://test.example.com/ws"),
            NodeEndpoint("onion", "test.onion"),
        )
        val caps = setOf(NodeCapability.CLIENT, NodeCapability.RELAY)
        val ann = alice.createNodeAnnouncement(
            endpoints = endpoints,
            nodeCapabilities = caps,
            limits = limits,
            timestamp = defaultTs,
        )
        val json = NodeAnnouncementJsonCodec.encode(ann)
        val decoded = NodeAnnouncementJsonCodec.decode(json)
        assertEquals(ann.messageId, decoded.messageId)
        assertEquals(ann.timestamp, decoded.timestamp)
        assertEquals(ann.endpoints, decoded.endpoints)
        assertEquals(ann.capabilities, decoded.capabilities)
        assertEquals(ann.limits, decoded.limits)
        assertContentEquals(ann.identity.publicKey, decoded.identity.publicKey)
    }
}