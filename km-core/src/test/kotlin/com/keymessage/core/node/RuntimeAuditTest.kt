package com.keymessage.core.node

import com.keymessage.core.auth.*
import com.keymessage.core.crypto.Ed25519
import com.keymessage.core.crypto.Ed25519Impl
import com.keymessage.core.km7.Capability
import com.keymessage.core.km7.CapabilitySet
import com.keymessage.core.km7.KceValue
import com.keymessage.core.km7.Km7Negotiation
import com.keymessage.core.kmid.DeviceRoster
import com.keymessage.core.kmid.KmIds
import com.keymessage.core.model.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.test.assertContentEquals

/**
 * Tests de auditoria arquitectonica.
 *
 * A. identityId ≠ publicKey
 * B. deviceId ≠ signingKey (deviceSigningPublicKey)
 * C. Negotiation binding (SUPPORTED ≠ NEGOTIATED)
 * D. Wire bytes verification (NO encode(decode(object)))
 * E. State bypass (NO IDLE → AUTHENTICATED sin AuthSession)
 */
class RuntimeAuditTest {

    private val ed25519: Ed25519 = Ed25519Impl()
    private val aliceKP = ed25519.generateKeyPair()
    private val bobKP = ed25519.generateKeyPair()

    // ===================================================================
    // A. identityId ≠ publicKey
    // ===================================================================

    @Test
    fun `A-01 identityId y publicKey son tipos distintos`() {
        val identityId = KmIds.identityId(aliceKP.publicKey)
        val pubKey = aliceKP.publicKey

        // Ambos son ByteArray de 32 bytes, pero semanticamente diferentes
        assertEquals(32, identityId.size)
        assertEquals(32, pubKey.size)

        // identityId NO verifica como publicKey
        val sig = ed25519.sign(aliceKP.privateKey, "test".encodeToByteArray())
        val identityAsPubKey = ed25519.verify(identityId, "test".encodeToByteArray(), sig)
        assertFalse(identityAsPubKey, "identityId NO debe verificar como publicKey")
    }

    @Test
    fun `A-02 NodeIdentity fromKeyPair garantiza nodeId derivado`() {
        val validIdentity = NodeIdentity.fromKeyPair(aliceKP.publicKey, "test")
        val expected = KmIds.identityId(aliceKP.publicKey).toHexString()
        assertEquals(expected, validIdentity.nodeId.value,
            "fromKeyPair debe derivar nodeId de publicKey")
    }

    @Test
    fun `A-03 AuthResponse identityId debe derivar de publicKey`() {
        val challenge = AuthChallenge(
            nonce = ByteArray(16),
            timestampMillis = 1000L,
            version = 3,
            responderIdentityId = "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb",
        )
        val response = AuthResponse(
            identityId = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
            publicKey = aliceKP.publicKey,
            signature = ByteArray(64),
            protocolVersion = 3,
        )

        val verifier = AuthVerifier(
            ed25519 = ed25519,
            clock = Clock { 1000L },
            replayGuard = NonceReplayGuard { true },
        )
        assertEquals(
            AuthError.INVALID_IDENTITY,
            verifier.verifyChallengeResponse(challenge, response),
            "V1 debe detectar identityId != SHA-256(KM-ID-IDENTITY || publicKey)"
        )
    }

    @Test
    fun `A-04 NodeAnnouncement decode rechaza nodeId no derivado de publicKey`() {
        val pubB64 = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(aliceKP.publicKey)
        val sigB64 = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(64))
        val json = """
            {
                "messageId": "${java.util.UUID.randomUUID()}",
                "timestamp": 123,
                "protocolVersion": "1.0",
                "nodeId": "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                "publicKey": "$pubB64",
                "endpoints": [],
                "capabilities": ["CLIENT"],
                "signature": "$sigB64"
            }
        """.trimIndent()
        assertThrows<IllegalArgumentException> {
            NodeAnnouncementJsonCodec.decode(json.encodeToByteArray())
        }
    }

    // ===================================================================
    // B. deviceId ≠ signingKey
    // ===================================================================

    @Test
    fun `B-01 deviceId deriva de signingKey pero NO es la signingKey`() {
        val signingKey = aliceKP.publicKey
        val deviceId = KmIds.deviceId(signingKey)

        assertFalse(deviceId.contentEquals(signingKey),
            "deviceId NO debe ser igual a signingKey")
        assertEquals(32, deviceId.size, "deviceId debe tener 32 bytes")

        // deviceId NO verifica como publicKey
        val sig = ed25519.sign(aliceKP.privateKey, "test".encodeToByteArray())
        assertFalse(ed25519.verify(deviceId, "test".encodeToByteArray(), sig),
            "deviceId NO debe verificar como clave Ed25519")
    }

    @Test
    fun `B-02 DeviceRoster verifica que deviceId deriva de signingKey`() {
        val devices = listOf(
            DeviceRoster.makeDeviceEntry(
                signingPublicKey = aliceKP.publicKey,
                agreementPublicKey = bobKP.publicKey,
                name = "test-device",
                status = 1L,
            )
        )
        val roster = DeviceRoster.build(
            identityRootPublicKey = aliceKP.publicKey,
            devices = devices,
        )
        // El deviceId en el roster se deriva de signingKey, no es igual a ella
        val deviceId = roster["devices"] as List<Map<String, Any?>>
        val firstDeviceId = deviceId.first()["deviceId"] as ByteArray
        assertFalse(firstDeviceId.contentEquals(aliceKP.publicKey),
            "deviceId en el roster NO debe ser igual a signingKey")
        assertEquals(
            KmIds.deviceId(aliceKP.publicKey).toHexString(),
            firstDeviceId.toHexString(),
            "deviceId debe derivar de SHA-256(KM-ID-DEVICE || signingKey)"
        )
    }

    // ===================================================================
    // C. Negotiation binding
    // ===================================================================

    @Test
    fun `C-01 SUPPORTED ≠ NEGOTIATED`() {
        val local = CapabilitySet(listOf(
            Capability("compression", 1, mapOf("algorithm" to KceValue.VString("none"))),
            Capability("serialization", 2, mapOf("format" to KceValue.VString("cbor"))),
        ))
        val peer = CapabilitySet(listOf(
            Capability("compression", 1, mapOf("algorithm" to KceValue.VString("none"))),
            Capability("serialization", 1, mapOf("format" to KceValue.VString("json"))),
        ))

        val result = Km7Negotiation.negotiate(local, peer)

        // Ambas partes soportan compression v1, pero serialization se negocia a v2
        assertTrue(local.has("compression"))
        assertTrue(peer.has("compression"))
        assertEquals(1, result.capabilities["compression"]?.version,
            "la negociacion debe seleccionar la version comun, no la maxima local")
        assertNotNull(result.capabilities["serialization"],
            "serialization debe negociarse")
    }

    @Test
    fun `C-02 negotiationHash protege contra downgrade`() {
        val capsA = CapabilitySet(listOf(
            Capability("cipher", 2, mapOf("name" to KceValue.VString("xchacha20-poly1305"))),
        ))
        val capsB = CapabilitySet(listOf(
            Capability("cipher", 1, mapOf("name" to KceValue.VString("aes-256-gcm"))),
        ))

        val hashV2 = Km7Negotiation.negotiationHash(Km7Negotiation.negotiate(capsA, capsA))
        val hashV1 = Km7Negotiation.negotiationHash(Km7Negotiation.negotiate(capsA, capsB))

        assertFalse(hashV2.contentEquals(hashV1),
            "diferentes cipher versions deben producir negotiationHash distinto")
    }

    @Test
    fun `C-03 capacidad no negociada no debe usarse`() {
        val local = CapabilitySet(listOf(Capability("a", 1)))
        val peer = CapabilitySet(listOf(Capability("b", 1)))
        val result = Km7Negotiation.negotiate(local, peer)
        // 'a' no esta en peer, 'b' no esta en local
        assertTrue(result.capabilities.isEmpty(),
            "capacidad no soportada por el peer no debe aparecer como negociada")
    }

    @Test
    fun `C-04 negociacion vacia produce hash determinista`() {
        val caps = CapabilitySet(emptyList())
        val result = Km7Negotiation.negotiate(caps, caps)
        val hash1 = Km7Negotiation.negotiationHash(result)
        val hash2 = Km7Negotiation.negotiationHash(result)
        assertContentEquals(hash1, hash2, "la negociacion vacia debe producir hash determinista")
        assertTrue(hash1.isNotEmpty(), "el hash nunca debe estar vacio")
    }

    // ===================================================================
    // D. Wire bytes verification
    // ===================================================================

    @Test
    fun `D-01 verifyWireBytes recibe los mismos bytes que se firmaron`() {
        val ann = NodeAnnouncement(
            messageId = MessageId(java.util.UUID.randomUUID()),
            timestamp = 1000L,
            protocolVersion = "1.0",
            identity = NodeIdentity.fromKeyPair(aliceKP.publicKey, "test"),
            endpoints = listOf(NodeEndpoint("ws", "wss://test/ws")),
            capabilities = setOf(NodeCapability.CLIENT),
        )
        val signableBytes = NodeAnnouncementJsonCodec.signableJson(ann)
        val sig = ed25519.sign(aliceKP.privateKey, signableBytes)
        val signedAnn = ann.copy(signature = sig.bytes)

        // verifyWireBytes recibe LOS MISMOS bytes que se firmaron
        assertDoesNotThrow {
            signedAnn.verifyWireBytes(signableBytes, now = 1000L).getOrThrow()
        }
    }

    @Test
    fun `D-02 signableJson es deterministico independientemente del decode`() {
        val ann = NodeAnnouncement(
            messageId = MessageId(java.util.UUID.randomUUID()),
            timestamp = 1000L,
            protocolVersion = "1.0",
            identity = NodeIdentity.fromKeyPair(aliceKP.publicKey),
            endpoints = emptyList(),
            capabilities = setOf(NodeCapability.CLIENT),
        )
        val signableBytes = NodeAnnouncementJsonCodec.signableJson(ann)
        val sig = ed25519.sign(aliceKP.privateKey, signableBytes)
        val signedAnn = ann.copy(signature = sig.bytes)

        val fullJson = NodeAnnouncementJsonCodec.encode(signedAnn)
        val decoded = NodeAnnouncementJsonCodec.decode(fullJson)
        val reconstructed = NodeAnnouncementJsonCodec.signableJson(decoded)

        assertContentEquals(signableBytes, reconstructed,
            "signableJson debe ser deterministico independientemente del decode")

        assertDoesNotThrow {
            signedAnn.verifyWireBytes(signableBytes, now = 1000L).getOrThrow()
        }
    }

    @Test
    fun `D-03 verify wire usa bytes originales, bytes mutados fallan`() {
        val ann = NodeAnnouncement(
            messageId = MessageId(java.util.UUID.randomUUID()),
            timestamp = 1000L,
            protocolVersion = "1.0",
            identity = NodeIdentity.fromKeyPair(aliceKP.publicKey, "test"),
            endpoints = listOf(NodeEndpoint("ws", "wss://test/ws")),
            capabilities = setOf(NodeCapability.CLIENT),
        )

        val signableBytes = NodeAnnouncementJsonCodec.signableJson(ann)
        val sig = ed25519.sign(aliceKP.privateKey, signableBytes)
        val signedAnn = ann.copy(signature = sig.bytes)

        // Modificar los bytes firmados
        val mutatedBytes = signableBytes.copyOf()
        mutatedBytes[mutatedBytes.size - 1] = (mutatedBytes[mutatedBytes.size - 1].toInt() xor 1).toByte()

        // verifyWireBytes con bytes MUTADOS debe fallar
        val error = assertThrows<NodeAnnouncementVerificationException> {
            signedAnn.verifyWireBytes(mutatedBytes, now = 1000L).getOrThrow()
        }
        assertEquals("INVALID_SIGNATURE", error.code,
            "debe detectar que los bytes mutados NO son los originales firmados")
    }

    // ===================================================================
    // E. State bypass
    // ===================================================================

    @Test
    fun `E-01 no se puede saltar de IDLE a AUTHENTICATED`() {
        val kp = aliceKP
        val session = AuthSession.initiator(
            keyPair = kp,
            identityId = KmIds.identityId(kp.publicKey).toHexString(),
            ed25519 = ed25519,
        )
        assertEquals(AuthSessionState.IDLE, session.state)

        // No se puede construir PeerContext con sesion IDLE
        val ann = NodeAnnouncement(
            messageId = MessageId(java.util.UUID.randomUUID()),
            timestamp = 1000L,
            protocolVersion = "1.0",
            identity = NodeIdentity.fromKeyPair(kp.publicKey),
            endpoints = emptyList(),
            capabilities = setOf(NodeCapability.CLIENT),
        )
        assertThrows<IllegalArgumentException> {
            PeerContext.authenticated(
                remotePeerId = IdentityId("bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"),
                publicKey = bobKP.publicKey,
                announcement = ann,
                authSession = session,
            )
        }
    }

    @Test
    fun `E-02 IDLE no permite buildResponse`() {
        val session = AuthSession.initiator(
            keyPair = aliceKP,
            identityId = KmIds.identityId(aliceKP.publicKey).toHexString(),
            ed25519 = ed25519,
        )
        assertThrows<IllegalStateException> {
            session.buildResponse("bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb")
        }
    }

    @Test
    fun `E-03 CHALLENGE_RECEIVED no permite receiveAuthOk sin RESPONSE_SENT`() {
        val session = AuthSession.initiator(
            keyPair = aliceKP,
            identityId = KmIds.identityId(aliceKP.publicKey).toHexString(),
            ed25519 = ed25519,
        )
        val challenge = AuthChallenge(
            nonce = ByteArray(16),
            timestampMillis = System.currentTimeMillis(),
            version = 3,
            responderIdentityId = KmIds.identityId(bobKP.publicKey).toHexString(),
        )
        session.receiveChallenge(challenge)
        assertEquals(AuthSessionState.CHALLENGE_RECEIVED, session.state)

        // No se puede pasar a AUTHENTICATED sin enviar response primero
        val authOk = AuthOk(
            sessionId = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
            serverNonce = ByteArray(16),
            signature = ByteArray(64),
            responderPublicKey = bobKP.publicKey,
        )
        // receiveAuthOk devuelve Result, obtener la exception con getOrThrow
        assertThrows<IllegalArgumentException> {
            session.receiveAuthOk(authOk, KmIds.identityId(bobKP.publicKey).toHexString()).getOrThrow()
        }
    }

    @Test
    fun `E-04 AuthSession transicion ilegal lanza exception`() {
        val session = AuthSession.initiator(
            keyPair = aliceKP,
            identityId = KmIds.identityId(aliceKP.publicKey).toHexString(),
            ed25519 = ed25519,
        )
        // No se puede marcar RESPONSE_SENT desde IDLE (sin challenge)
        // transition() usa require() que lanza IllegalArgumentException
        assertThrows<IllegalArgumentException> {
            session.markResponseSent()
        }
    }

    @Test
    fun `E-05 AuthVerifier detecta firma invalida en AuthResponse (V3)`() {
        val challenge = AuthChallenge(
            nonce = ByteArray(16),
            timestampMillis = 1000L,
            version = 3,
            responderIdentityId = "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb",
        )
        val response = AuthResponse(
            identityId = KmIds.identityId(aliceKP.publicKey).toHexString(),
            publicKey = aliceKP.publicKey,
            signature = ByteArray(64) { 0x42.toByte() },
            protocolVersion = 3,
        )

        val verifier = AuthVerifier(
            ed25519 = ed25519,
            clock = Clock { 1000L },
            replayGuard = NonceReplayGuard { true },
        )
        val error = verifier.verifyChallengeResponse(challenge, response)
        assertEquals(
            AuthError.INVALID_SIGNATURE,
            error,
            "V3 debe detectar firma invalida"
        )
    }
}

/** Extension para convertir ByteArray a hex string en tests. */
internal fun ByteArray.toHexString(): String = joinToString("") { "%02x".format(it) }