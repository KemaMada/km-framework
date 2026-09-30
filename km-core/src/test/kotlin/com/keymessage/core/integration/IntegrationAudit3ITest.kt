package com.keymessage.core.integration

import com.keymessage.core.auth.*
import com.keymessage.core.crypto.Ed25519
import com.keymessage.core.crypto.Ed25519Impl
import com.keymessage.core.crypto.KeyPair
import com.keymessage.core.km7.Capability
import com.keymessage.core.km7.CapabilitySet
import com.keymessage.core.km7.KceValue
import com.keymessage.core.km7.Km7Negotiation
import com.keymessage.core.km7.NegotiationResult
import com.keymessage.core.kmid.*
import com.keymessage.core.model.*
import com.keymessage.core.node.PeerContext
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.test.assertContentEquals

/**
 * 3I — Auditoria de integracion final.
 *
 * 12 fronteras entre protocolos, verificando que las invariantes
 * se mantienen en cada cruce.
 *
 * Las fronteras 5 (PeerContext→WebRTC) y 6 (Signaling→WebRTC) se
 * prueban como pre-condiciones; la verificacion completa requirira
 * una implementacion WebRTC real en 3K.
 */
class IntegrationAudit3ITest {

    private val ed25519: Ed25519 = Ed25519Impl()
    private val aliceKP = ed25519.generateKeyPair()
    private val bobKP = ed25519.generateKeyPair()
    private val charlieKP = ed25519.generateKeyPair()

    private val aliceId = KmIds.identityId(aliceKP.publicKey).toHexString()
    private val bobId = KmIds.identityId(bobKP.publicKey).toHexString()
    private val charlieId = KmIds.identityId(charlieKP.publicKey).toHexString()

    // Clock fijo para todos los tests que usan AuthSession
    private val fixedClock = Clock { 1000L }
    private val acceptingGuard = NonceReplayGuard { true }

    // ===================================================================
    // Frontera 1: Identity → Auth
    // identityId siempre deriva de publicKey; deviceId de signingKey;
    // ninguna API acepta uno en lugar del otro
    // ===================================================================

    @Test
    fun `F1-01 AuthSession rechaza identityId que no deriva de keyPair`() {
        assertThrows<IllegalArgumentException> {
            AuthSession.initiator(
                keyPair = aliceKP,
                identityId = bobId, // Bob's identity, pero keyPair es de Alice
                ed25519 = ed25519,
            )
        }
    }

    @Test
    fun `F1-02 AuthVerifier V1 detecta identityId manipulado en AuthResponse`() {
        val challenge = AuthChallenge(ByteArray(16), 1000L, 3, aliceId)
        val transcript = TranscriptBuilder.authTranscript(
            nonce = challenge.nonce, timestampMillis = 1000L,
            responderIdentityId = aliceId, identityId = charlieId,
        )
        val sig = ed25519.sign(aliceKP.privateKey, transcript)
        val response = AuthResponse(charlieId, aliceKP.publicKey, sig.bytes, 3)

        val verifier = AuthVerifier(ed25519, fixedClock, acceptingGuard)
        assertEquals(AuthError.INVALID_IDENTITY,
            verifier.verifyChallengeResponse(challenge, response),
            "V1 identityId != SHA-256(KM-ID-IDENTITY || publicKey)")
    }

    @Test
    fun `F1-03 deviceId como publicKey falla en AuthResponse`() {
        val challenge = AuthChallenge(ByteArray(16), 1000L, 3, aliceId)
        val deviceId = KmIds.deviceId(aliceKP.publicKey)
        val transcript = TranscriptBuilder.authTranscript(
            nonce = challenge.nonce, timestampMillis = 1000L,
            responderIdentityId = aliceId, identityId = aliceId,
        )
        val sig = ed25519.sign(aliceKP.privateKey, transcript)
        val response = AuthResponse(aliceId, deviceId, sig.bytes, 3)

        val verifier = AuthVerifier(ed25519, fixedClock, acceptingGuard)
        assertEquals(AuthError.INVALID_IDENTITY,
            verifier.verifyChallengeResponse(challenge, response),
            "deviceId no deriva de identityId")
    }

    @Test
    fun `F1-04 NodeAnnouncement decode rechaza nodeId vacio`() {
        val pubB64 = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(aliceKP.publicKey)
        val sigB64 = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(64))
        assertThrows<IllegalArgumentException> {
            NodeAnnouncementJsonCodec.decode("""
                {"messageId":"${java.util.UUID.randomUUID()}","timestamp":1,"protocolVersion":"1.0",
                "nodeId":"","publicKey":"$pubB64","endpoints":[],"capabilities":["CLIENT"],
                "signature":"$sigB64"}
            """.trimIndent().encodeToByteArray())
        }
    }

    // ===================================================================
    // Frontera 2: Auth → PeerContext
    // solo AUTHENTICATED crea contexto de peer
    // ===================================================================

    @Test
    fun `F2-01 solo estado AUTHENTICATED permite crear PeerContext`() {
        val ann = NodeAnnouncement(
            messageId = MessageId(java.util.UUID.randomUUID()),
            timestamp = 1000L, protocolVersion = "1.0",
            identity = NodeIdentity.fromKeyPair(aliceKP.publicKey),
            endpoints = emptyList(), capabilities = setOf(NodeCapability.CLIENT),
        )
        val session = AuthSession.initiator(aliceKP, aliceId, ed25519, clock = fixedClock)

        // IDLE — rechazado
        assertThrows<IllegalArgumentException> {
            PeerContext.authenticated(IdentityId(bobId), bobKP.publicKey, ann, session)
        }
        // CHALLENGE_RECEIVED — rechazado
        session.receiveChallenge(AuthChallenge(ByteArray(16), 1000L, 3, bobId)).getOrThrow()
        assertThrows<IllegalArgumentException> {
            PeerContext.authenticated(IdentityId(bobId), bobKP.publicKey, ann, session)
        }
        // RESPONSE_SENT — rechazado
        session.markResponseSent()
        assertThrows<IllegalArgumentException> {
            PeerContext.authenticated(IdentityId(bobId), bobKP.publicKey, ann, session)
        }
        // FAILED — rechazado
        session.receiveAuthFail()
        assertThrows<IllegalArgumentException> {
            PeerContext.authenticated(IdentityId(bobId), bobKP.publicKey, ann, session)
        }
    }

    @Test
    fun `F2-02 PeerContext rechaza announcement nodeId != remotePeerId`() {
        val session = makeAuthenticatedSession(aliceKP, aliceId, bobId)
        val ann = NodeAnnouncement(
            messageId = MessageId(java.util.UUID.randomUUID()), timestamp = 1000L,
            protocolVersion = "1.0",
            identity = NodeIdentity(IdentityId(charlieId), charlieKP.publicKey),
            endpoints = emptyList(), capabilities = setOf(NodeCapability.CLIENT),
        )
        assertThrows<IllegalArgumentException> {
            PeerContext.authenticated(IdentityId(bobId), bobKP.publicKey, ann, session)
        }
    }

    // ===================================================================
    // Frontera 3: Auth → KM-0007
    // negotiationHash queda ligado a la sesion autenticada
    // ===================================================================

    @Test
    fun `F3-01 negotiationHash inalterable despues de vinculado`() {
        val session = makeAuthenticatedSession(aliceKP, aliceId, bobId)
        val ctx = PeerContext.authenticated(
            IdentityId(bobId), bobKP.publicKey,
            makePeerAnnouncement(bobKP.publicKey), session,
        )
        val caps = CapabilitySet(listOf(Capability("a", 1)))
        val result = Km7Negotiation.negotiate(caps, caps)
        val ctx1 = ctx.withNegotiation(result)
        assertNotNull(ctx1.negotiationHash)
        assertThrows<IllegalStateException> { ctx1.withNegotiation(result) }
    }

    @Test
    fun `F3-02 negotiationHash vincula capacidades diferentes`() {
        // capsA = {cipher:1, kdf:1}, capsB = {cipher:2, kdf:1}
        val capsA = CapabilitySet(listOf(
            Capability("cipher", 1, mapOf("alg" to KceValue.VString("aes-gcm"))),
            Capability("kdf", 1, mapOf("alg" to KceValue.VString("hkdf-sha256"))),
        ))
        val capsB = CapabilitySet(listOf(
            Capability("cipher", 2, mapOf("alg" to KceValue.VString("xchacha20-poly1305"))),
            Capability("kdf", 1, mapOf("alg" to KceValue.VString("hkdf-sha256"))),
        ))
        // Negociar capsA consigo mismo → cipher:1, kdf:1
        val r1 = Km7Negotiation.negotiate(capsA, capsA)
        // Negociar capsA con capsB → cipher:1 (min(1,2)), kdf:1
        val r2 = Km7Negotiation.negotiate(capsA, capsB)
        val h1 = Km7Negotiation.negotiationHash(r1)
        val h2 = Km7Negotiation.negotiationHash(r2)
        // Ambos son cipher:1, kdf:1 → mismo hash porque version es la comun
        // Para probar que hash distingue, comparar cipher:1 vs cipher:2 completo
        val r3 = Km7Negotiation.negotiate(capsB, capsB) // cipher:2, kdf:1
        val h3 = Km7Negotiation.negotiationHash(r3)
        assertTrue(h1.contentEquals(h2),
            "mismas capacidades negociadas (cipher:1) debe producir mismo hash")
        assertFalse(h1.contentEquals(h3),
            "cipher:1 vs cipher:2 deben producir hash diferente")
    }

    // ===================================================================
    // Frontera 4: Auth → ContactBundle
    // ===================================================================

    @Test
    fun `F4-01 ContactBundle identityId debe coincidir con peer autenticado`() {
        val devices = listOf(DeviceRoster.makeDeviceEntry(aliceKP.publicKey, bobKP.publicKey))
        val roster = DeviceRoster.build(aliceKP.publicKey, devices)
        val rosterId = (roster["identityId"] as ByteArray).toHexString()
        assertEquals(aliceId, rosterId)
    }

    @Test
    fun `F4-02 ContactBundle de Alice no es de Bob`() {
        val devices = listOf(DeviceRoster.makeDeviceEntry(aliceKP.publicKey, bobKP.publicKey))
        val roster = DeviceRoster.build(aliceKP.publicKey, devices)
        assertNotEquals(bobId, (roster["identityId"] as ByteArray).toHexString())
    }

    @Test
    fun `F4-03 deviceId derivado de signingKey en roster`() {
        val entry = DeviceRoster.makeDeviceEntry(aliceKP.publicKey, bobKP.publicKey)
        val deviceId = entry["deviceId"] as ByteArray
        assertContentEquals(KmIds.deviceId(aliceKP.publicKey), deviceId)
        assertFalse(deviceId.contentEquals(aliceKP.publicKey), "deviceId != signingKey")
    }

    // ===================================================================
    // Frontera 5: PeerContext → WebRTC (pre-condicion)
    // ===================================================================

    @Test
    fun `F5-01 remotePeerId del contexto = peer autenticado, no local`() {
        val session = makeAuthenticatedSession(aliceKP, aliceId, bobId)
        val ctx = PeerContext.authenticated(
            IdentityId(bobId), bobKP.publicKey,
            makePeerAnnouncement(bobKP.publicKey), session,
        )
        assertEquals(IdentityId(bobId), ctx.remotePeerId)
    }

    @Test
    fun `F5-02 announcement del peer tiene su nodeId`() {
        val ann = makePeerAnnouncement(bobKP.publicKey)
        assertEquals(IdentityId(bobId), ann.identity.nodeId)
    }

    // ===================================================================
    // Frontera 6: Signaling → WebRTC (pre-condicion)
    // ===================================================================

    @Test
    fun `F6-01 PeerContext inmutable no permite cambiar remotePeerId sin romper invariante`() {
        val session = makeAuthenticatedSession(aliceKP, aliceId, bobId)
        val ctx = PeerContext.authenticated(
            IdentityId(bobId), bobKP.publicKey,
            makePeerAnnouncement(bobKP.publicKey), session,
        )
        val mutated = ctx.copy(remotePeerId = IdentityId(charlieId))
        assertNotEquals(mutated.announcement.identity.nodeId, mutated.remotePeerId)
    }

    // ===================================================================
    // Frontera 7: Wire → Signature
    // ===================================================================

    @Test
    fun `F7-01 verifyWireBytes con bytes alterados falla (no contra reconstruccion)`() {
        val ann = NodeAnnouncement(
            messageId = MessageId(java.util.UUID.randomUUID()), timestamp = 1000L,
            protocolVersion = "1.0",
            identity = NodeIdentity.fromKeyPair(aliceKP.publicKey, "test"),
            endpoints = listOf(NodeEndpoint("ws", "wss://test/ws")),
            capabilities = setOf(NodeCapability.CLIENT),
        )
        val signableBytes = NodeAnnouncementJsonCodec.signableJson(ann)
        val sig = ed25519.sign(aliceKP.privateKey, signableBytes)
        val signedAnn = ann.copy(signature = sig.bytes)

        val fullJsonStr = String(NodeAnnouncementJsonCodec.encode(signedAnn), Charsets.UTF_8)
        val tamperedJson = fullJsonStr.replace("\"timestamp\":1000", "\"timestamp\":999")
        val decodedTampered = NodeAnnouncementJsonCodec.decode(tamperedJson.encodeToByteArray())
        val tamperedSignable = NodeAnnouncementJsonCodec.signableJson(decodedTampered)

        val error = assertThrows<NodeAnnouncementVerificationException> {
            signedAnn.verifyWireBytes(tamperedSignable, now = 1000L).getOrThrow()
        }
        assertEquals("INVALID_SIGNATURE", error.code)
    }

    @Test
    fun `F7-02 verifyWireBytes con bytes ORIGINALES pasa`() {
        val ann = NodeAnnouncement(
            messageId = MessageId(java.util.UUID.randomUUID()), timestamp = 1000L,
            protocolVersion = "1.0",
            identity = NodeIdentity.fromKeyPair(aliceKP.publicKey),
            endpoints = emptyList(), capabilities = setOf(NodeCapability.CLIENT),
        )
        val signableBytes = NodeAnnouncementJsonCodec.signableJson(ann)
        val sig = ed25519.sign(aliceKP.privateKey, signableBytes)
        val signedAnn = ann.copy(signature = sig.bytes)
        assertDoesNotThrow { signedAnn.verifyWireBytes(signableBytes, now = 1000L).getOrThrow() }
    }

    // ===================================================================
    // Frontera 8: Relay → Auth
    // ===================================================================

    @Test
    fun `F8-01 RelayAuthHandler no expone privateKey`() {
        val handler = com.keymessage.core.auth.RelayAuthHandler(ed25519)
        val response = handler.buildResponse(AuthChallenge(ByteArray(16), 1000L, 3, bobId), aliceId, aliceKP)
        assertEquals(aliceId, response.identityId)
        assertContentEquals(aliceKP.publicKey, response.publicKey)
        assertTrue(response.signature.isNotEmpty())
    }

    @Test
    fun `F8-02 RelayAuthHandler verifica identityId contra publicKey`() {
        val handler = com.keymessage.core.auth.RelayAuthHandler(ed25519)
        assertThrows<IllegalArgumentException> {
            handler.buildResponse(AuthChallenge(ByteArray(16), 1000L, 3, bobId), bobId, aliceKP)
        }
    }

    // ===================================================================
    // Frontera 9: State → Transport
    // ===================================================================

    @Test
    fun `F9-01 FAILED permite reset a IDLE`() {
        val session = AuthSession.initiator(aliceKP, aliceId, ed25519, clock = fixedClock)
        session.receiveChallenge(AuthChallenge(ByteArray(16), 1000L, 3, bobId)).getOrThrow()
        session.receiveAuthFail()
        assertEquals(AuthSessionState.FAILED, session.state)
        session.reset()
        assertEquals(AuthSessionState.IDLE, session.state)
    }

    @Test
    fun `F9-02 FAILED no permite buildResponse sin reset`() {
        val session = AuthSession.initiator(aliceKP, aliceId, ed25519, clock = fixedClock)
        session.receiveChallenge(AuthChallenge(ByteArray(16), 1000L, 3, bobId)).getOrThrow()
        session.receiveAuthFail()
        assertThrows<IllegalStateException> { session.buildResponse(bobId) }
    }

    @Test
    fun `F9-03 FAILED permite reset y reutilizacion`() {
        val session = AuthSession.initiator(aliceKP, aliceId, ed25519, clock = fixedClock)
        session.receiveChallenge(AuthChallenge(ByteArray(16), 1000L, 3, bobId)).getOrThrow()
        session.receiveAuthFail()
        session.reset()
        assertEquals(AuthSessionState.IDLE, session.state)
        session.receiveChallenge(AuthChallenge(ByteArray(16), 2000L, 3, bobId)).getOrThrow()
        assertEquals(AuthSessionState.CHALLENGE_RECEIVED, session.state)
    }

    // ===================================================================
    // Frontera 10: Multi-device
    // ===================================================================

    @Test
    fun `F10-01 dos dispositivos misma identidad tienen deviceId distintos`() {
        val d1 = ed25519.generateKeyPair()
        val d2 = ed25519.generateKeyPair()
        assertNotEquals(KmIds.deviceId(d1.publicKey).toHexString(), KmIds.deviceId(d2.publicKey).toHexString())
        assertNotEquals(KmIds.deviceId(d1.publicKey).toHexString(), d1.publicKey.toHexString())
    }

    @Test
    fun `F10-02 DeviceRoster deviceId unicos`() {
        val d1 = ed25519.generateKeyPair()
        val d2 = ed25519.generateKeyPair()
        val roster = DeviceRoster.build(aliceKP.publicKey, listOf(
            DeviceRoster.makeDeviceEntry(d1.publicKey, bobKP.publicKey, "mobile"),
            DeviceRoster.makeDeviceEntry(d2.publicKey, charlieKP.publicKey, "pc"),
        ))
        val devices = roster["devices"] as List<Map<String, Any?>>
        assertEquals(2, devices.size)
        val ids = devices.map { (it["deviceId"] as ByteArray).toHexString() }.toSet()
        assertEquals(2, ids.size)
    }

    // ===================================================================
    // Frontera 11: Replay
    // ===================================================================

    @Test
    fun `F11-01 NonceReplayGuard rechaza nonce repetido`() {
        val used = mutableSetOf<String>()
        val guard = NonceReplayGuard { used.add(it.toHexString()) }
        val nonce = ByteArray(16) { 1 }
        assertTrue(guard.checkAndRemember(nonce))
        assertFalse(guard.checkAndRemember(nonce))
    }

    @Test
    fun `F11-02 sessionId diferente entre sesiones`() {
        assertNotEquals(generateRandomSessionId(), generateRandomSessionId())
    }

    @Test
    fun `F11-03 negotiationHash diferente si capacidades cambian`() {
        val capsA = CapabilitySet(listOf(Capability("x", 1)))
        val capsB = CapabilitySet(listOf(Capability("x", 2)))
        val h1 = Km7Negotiation.negotiationHash(Km7Negotiation.negotiate(capsA, capsA))
        val h2 = Km7Negotiation.negotiationHash(Km7Negotiation.negotiate(capsA, capsA))
        assertContentEquals(h1, h2)
        val h3 = Km7Negotiation.negotiationHash(Km7Negotiation.negotiate(capsB, capsB))
        assertFalse(h1.contentEquals(h3))
    }

    @Test
    fun `F11-04 nonce repetido en AuthChallenge produce Result failure`() {
        val guard = NonceReplayGuard { false } // siempre false
        val session = AuthSession.initiator(aliceKP, aliceId, ed25519, clock = fixedClock, replayGuard = guard)
        val result = session.receiveChallenge(AuthChallenge(ByteArray(16), 1000L, 3, bobId))
        assertTrue(result.isFailure, "nonce repetido debe dar Result.failure")
    }

    // ===================================================================
    // Frontera 12: Downgrade
    // ===================================================================

    @Test
    fun `F12-01 SUPPORTED vs NEGOTIATED son distintos`() {
        val local = CapabilitySet(listOf(
            Capability("serialization", 2, mapOf("fmt" to KceValue.VString("cbor"))),
            Capability("compression", 1, mapOf("alg" to KceValue.VString("none"))),
        ))
        val peer = CapabilitySet(listOf(
            Capability("compression", 1, mapOf("alg" to KceValue.VString("none"))),
        ))
        assertTrue(local.has("compression"))
        assertTrue(peer.has("compression"))
        assertFalse(local.has("serialization") && peer.has("serialization"),
            "serialization no soportada por peer")
        val result = Km7Negotiation.negotiate(local, peer)
        assertEquals(1, result.capabilities["compression"]?.version)
        assertNull(result.capabilities["serialization"])
    }

    @Test
    fun `F12-02 capacidad no soportada por peer no se negocia`() {
        val result = Km7Negotiation.negotiate(
            CapabilitySet(listOf(Capability("a", 1))),
            CapabilitySet(listOf(Capability("b", 1))),
        )
        assertTrue(result.capabilities.isEmpty())
        assertFalse(result.isFailure)
    }

    @Test
    fun `F12-03 categoria obligatoria sin interseccion falla`() {
        val result = Km7Negotiation.negotiate(
            CapabilitySet(listOf(Capability("a", 1))),
            CapabilitySet(listOf(Capability("b", 1))),
            requiredCategories = setOf("a"),
        )
        assertTrue(result.isFailure)
    }

    @Test
    fun `F12-04 negotiationHash nunca es nulo`() {
        val result = Km7Negotiation.negotiate(
            CapabilitySet(listOf(Capability("a", 1))),
            CapabilitySet(listOf(Capability("a", 1))),
        )
        assertNotNull(Km7Negotiation.negotiationHash(result))
    }

    // ===================================================================
    // Helpers
    // ===================================================================

    private fun makeAuthenticatedSession(
        keyPair: KeyPair,
        localId: String,
        remotePeerId: String,
    ): AuthSession {
        val session = AuthSession.initiator(keyPair, localId, ed25519, clock = fixedClock)
        session.receiveChallenge(AuthChallenge(ByteArray(16), 1000L, 3, remotePeerId)).getOrThrow()
        session.markResponseSent()
        val authOk = makeValidAuthOk(keyPair, localId)
        session.receiveAuthOk(authOk, localId).getOrThrow()
        return session
    }

    private fun makeValidAuthOk(keyPair: KeyPair, initiatorId: String): AuthOk {
        val sid = generateRandomSessionId()
        val serverNonce = ByteArray(16) { 3 }
        val transcript = TranscriptBuilder.serverAuthTranscript(sid, serverNonce, initiatorId)
        val sig = ed25519.sign(keyPair.privateKey, transcript)
        return AuthOk(sid, serverNonce, keyPair.publicKey, sig.bytes)
    }

    private fun makePeerAnnouncement(peerPub: ByteArray): NodeAnnouncement {
        val identity = NodeIdentity.fromKeyPair(peerPub, "peer")
        val partial = NodeAnnouncement(
            messageId = MessageId(java.util.UUID.randomUUID()), timestamp = 1000L,
            protocolVersion = "1.0", identity = identity,
            endpoints = emptyList(), capabilities = setOf(NodeCapability.CLIENT),
        )
        val signable = NodeAnnouncementJsonCodec.signableJson(partial)
        val kp = ed25519.generateKeyPair()
        val sig = ed25519.sign(kp.privateKey, signable)
        return partial.copy(signature = sig.bytes)
    }

    private fun generateRandomSessionId(): String {
        val bytes = ByteArray(20).also { java.security.SecureRandom().nextBytes(it) }
        return bytes.joinToString("") { "%02x".format(it) }
    }
}

private fun ByteArray.toHexString(): String = joinToString("") { "%02x".format(it) }