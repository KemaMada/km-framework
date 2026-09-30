package com.km.auth

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.km.crypto.Ed25519Impl
import com.km.crypto.Signature
import com.km.identity.KmIds
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.io.File
import java.nio.file.Paths
import kotlin.test.assertContentEquals

/**
 * Tests de vectores dorados G14-G19 de KM-0002 (3F.5).
 *
 * Cada vector se genera en Python (km-id-reference/auth_generate.py) y
 * Kotlin verifica byte-for-byte: transcript, firma, Base64URL, y el
 * resultado completo de AuthVerifier.
 *
 * PRINCIPIO: la referencia Python es la autoridad. Kotlin NO recalcula
 * los vectores; los carga y verifica.
 */
class GoldenAuthVectorsTest {

    private val ed25519 = Ed25519Impl()
    private val mapper = ObjectMapper()

    /** Directorio raiz de los vectores congelados (km-id-reference/). */
    private val vectorsRoot: File by lazy {
        // Busca desde varias ubicaciones
        val candidates = listOf(
            Paths.get("km-id-reference/vectors").toFile(),
            Paths.get("../km-id-reference/vectors").toFile(),
            Paths.get("../../km-id-reference/vectors").toFile(),
            Paths.get("../../../km-id-reference/vectors").toFile(),
        )
        candidates.firstOrNull { it.isDirectory() && File(it, "G14").isDirectory() }
            ?: error("no se encuentra km-id-reference/vectors/ (busque en: $candidates)")
    }

    private val invalidDir: File by lazy {
        val candidates = listOf(
            Paths.get("km-id-reference/invalid").toFile(),
            Paths.get("../km-id-reference/invalid").toFile(),
            Paths.get("../../km-id-reference/invalid").toFile(),
            Paths.get("../../../km-id-reference/invalid").toFile(),
        )
        candidates.firstOrNull { it.isDirectory() }
            ?: error("no se encuentra km-id-reference/invalid/")
    }

    private val g14Dir get() = File(vectorsRoot, "G14")
    private val g15Dir get() = File(vectorsRoot, "G15")
    private val g16Dir get() = File(vectorsRoot, "G16")
    private val g17Dir get() = File(vectorsRoot, "G17")
    private val g18Dir get() = File(vectorsRoot, "G18")
    private val g19Dir get() = File(vectorsRoot, "G19")

    // ===================================================================
    // Helpers
    // ===================================================================

    private fun readBytes(file: File): ByteArray = file.readBytes()

    private fun readJson(file: File): Map<String, Any?> =
        mapper.readValue(file.readText())

    private fun hexOfBytes(file: File): String = readBytes(file).joinToString("") { "%02x".format(it) }

    // ===================================================================
    // G14: AUTH_CHALLENGE + 152-byte transcript
    // ===================================================================

    @Test
    fun `G14 transcript mide exactamente 152 bytes`() {
        val transcript = readBytes(File(g14Dir, "transcript.bin"))
        assertEquals(152, transcript.size)
    }

    @Test
    fun `G14 transcript nonce en offsets 0-15`() {
        val transcript = readBytes(File(g14Dir, "transcript.bin"))
        val challenge = readJson(File(g14Dir, "challenge.json"))

        val nonceFromJson = Base64Url.decode(challenge["nonce"] as String)
        val nonceFromTranscript = transcript.copyOfRange(0, 16)

        assertContentEquals(nonceFromJson, nonceFromTranscript)
    }

    @Test
    fun `G14 transcript timestamp en offsets 16-23`() {
        val transcript = readBytes(File(g14Dir, "transcript.bin"))
        val challenge = readJson(File(g14Dir, "challenge.json"))

        val ts = (challenge["timestamp"] as Number).toLong()
        val tsBytes = ByteArray(8)
        for (i in 0 until 8) {
            tsBytes[i] = (ts ushr (8 * (7 - i))).toByte()
        }
        assertContentEquals(tsBytes, transcript.copyOfRange(16, 24))
    }

    @Test
    fun `G14 transcript responderIdentityId en offsets 24-87`() {
        val transcript = readBytes(File(g14Dir, "transcript.bin"))
        val challenge = readJson(File(g14Dir, "challenge.json"))

        val responderId = (challenge["responderIdentityId"] as String).encodeToByteArray()
        assertContentEquals(responderId, transcript.copyOfRange(24, 88))
    }

    @Test
    fun `G14 transcript identityId en offsets 88-151`() {
        val transcript = readBytes(File(g14Dir, "transcript.bin"))
        val vectorJson = mapper.readValue<Map<String, Any?>>(File(g14Dir, "vector.json").readText())
        val identityId = (vectorJson["inputs"] as Map<String, Any?>)["identityId"] as String

        assertContentEquals(identityId.encodeToByteArray(), transcript.copyOfRange(88, 152))
    }

    @Test
    fun `G14 challenge JSON field types correctos`() {
        val ch = readJson(File(g14Dir, "challenge.json"))

        assertTrue(ch.containsKey("nonce"))
        assertTrue(ch.containsKey("timestamp"))
        assertTrue(ch.containsKey("version"))
        assertTrue(ch.containsKey("responderIdentityId"))

        // Valores de tipos correctos
        assertTrue(ch["timestamp"] is Number)
        assertTrue(ch["version"] is Int)
        assertTrue(ch["responderIdentityId"] is String)
    }

    // ===================================================================
    // G15: AUTH_RESPONSE + identidad + firma
    // ===================================================================

    @Test
    fun `G15 transcript mide 152 bytes`() {
        val transcript = readBytes(File(g15Dir, "transcript.bin"))
        assertEquals(152, transcript.size)
    }

    @Test
    fun `G15 response signature verifica contra transcript`() {
        val response = readJson(File(g15Dir, "response.json"))
        val transcript = readBytes(File(g15Dir, "transcript.bin"))

        val publicKey = Base64Url.decode(response["publicKey"] as String)
        val signature = Base64Url.decode(response["signature"] as String)

        assertTrue(ed25519.verify(publicKey, transcript, Signature(signature)),
            "La firma del response debe verificar contra el transcript")
    }

    @Test
    fun `G15 identityId deriva correctamente de publicKey`() {
        val response = readJson(File(g15Dir, "response.json"))
        val publicKey = Base64Url.decode(response["publicKey"] as String)
        val actualIdentityId = response["identityId"] as String

        val expectedIdentityId = KmIds.identityId(publicKey).lowerHex()
        assertEquals(expectedIdentityId, actualIdentityId)
    }

    @Test
    fun `G15 response JSON completo`() {
        val resp = readJson(File(g15Dir, "response.json"))
        assertTrue(resp.containsKey("identityId"))
        assertTrue(resp.containsKey("publicKey"))
        assertTrue(resp.containsKey("signature"))
        assertTrue(resp.containsKey("protocolVersion"))
    }

    // ===================================================================
    // G16: AUTH_OK + 120-byte server transcript
    // ===================================================================

    @Test
    fun `G16 transcript mide exactamente 120 bytes`() {
        val transcript = readBytes(File(g16Dir, "transcript.bin"))
        assertEquals(120, transcript.size)
    }

    @Test
    fun `G16 transcript sessionId en offsets 0-39`() {
        val transcript = readBytes(File(g16Dir, "transcript.bin"))
        val authOk = readJson(File(g16Dir, "auth_ok.json"))
        val sessionId = (authOk["sessionId"] as String).encodeToByteArray()
        assertContentEquals(sessionId, transcript.copyOfRange(0, 40))
    }

    @Test
    fun `G16 transcript serverNonce en offsets 40-55`() {
        val transcript = readBytes(File(g16Dir, "transcript.bin"))
        val authOk = readJson(File(g16Dir, "auth_ok.json"))
        val serverNonce = Base64Url.decode(authOk["serverNonce"] as String)
        assertContentEquals(serverNonce, transcript.copyOfRange(40, 56))
    }

    @Test
    fun `G16 transcript initiatorIdentityId en offsets 56-119`() {
        val transcript = readBytes(File(g16Dir, "transcript.bin"))
        val vectorJson = mapper.readValue<Map<String, Any?>>(File(g16Dir, "vector.json").readText())
        val initiatorId = (vectorJson["inputs"] as Map<String, Any?>)["initiatorIdentityId"] as String
        assertContentEquals(initiatorId.encodeToByteArray(), transcript.copyOfRange(56, 120))
    }

    @Test
    fun `G16 AUTH_OK signature verifica contra server transcript`() {
        val authOk = readJson(File(g16Dir, "auth_ok.json"))
        val transcript = readBytes(File(g16Dir, "transcript.bin"))

        val responderKey = Base64Url.decode(authOk["responderPublicKey"] as String)
        val signature = Base64Url.decode(authOk["signature"] as String)

        assertTrue(ed25519.verify(responderKey, transcript, Signature(signature)),
            "La firma del AUTH_OK debe verificar contra el server transcript")
    }

    // ===================================================================
    // G17: Flujo completo Challenge -> Response
    // ===================================================================

    @Test
    fun `G17 challenge y response tienen el mismo transcript`() {
        val challengeJson = readJson(File(g17Dir, "challenge.json"))
        val responseJson = readJson(File(g17Dir, "response.json"))

        val nonce = Base64Url.decode(challengeJson["nonce"] as String)
        val ts = (challengeJson["timestamp"] as Number).toLong()
        val responderId = challengeJson["responderIdentityId"] as String
        val identityId = responseJson["identityId"] as String

        val reconstructed = TranscriptBuilder.authTranscript(nonce, ts, responderId, identityId)

        val loadedTranscript = readBytes(File(g17Dir, "transcript.bin"))
        assertContentEquals(loadedTranscript, reconstructed,
            "El transcript reconstruido en Kotlin debe coincidir byte-for-byte con el vector")
    }

    @Test
    fun `G17 response signature verifica con AuthVerifier`() {
        val challengeJson = readJson(File(g17Dir, "challenge.json"))
        val responseJson = readJson(File(g17Dir, "response.json"))

        val challenge = AuthChallenge(
            nonce = Base64Url.decode(challengeJson["nonce"] as String),
            timestampMillis = (challengeJson["timestamp"] as Number).toLong(),
            version = (challengeJson["version"] as Number).toInt(),
            responderIdentityId = challengeJson["responderIdentityId"] as String,
        )
        val response = AuthResponse(
            identityId = responseJson["identityId"] as String,
            publicKey = Base64Url.decode(responseJson["publicKey"] as String),
            signature = Base64Url.decode(responseJson["signature"] as String),
            protocolVersion = (responseJson["protocolVersion"] as Number).toInt(),
        )

        val verifier = AuthVerifier(
            ed25519 = ed25519,
            clock = Clock { challenge.timestampMillis },
            replayGuard = NonceReplayGuard { true },
        )

        val result = verifier.verifyChallengeResponse(challenge, response)
        assertNull(result, "El flujo completo debe verificar sin errores")
    }

    // ===================================================================
    // G18: Flujo completo Challenge -> Response -> AuthOk
    // ===================================================================

    @Test
    fun `G18 todos los mensajes estan presentes`() {
        assertTrue(File(g18Dir, "challenge.json").isFile)
        assertTrue(File(g18Dir, "response.json").isFile)
        assertTrue(File(g18Dir, "auth_ok.json").isFile)
    }

    @Test
    fun `G18 AuthOk verifica con AuthVerifier`() {
        val authOkJson = readJson(File(g18Dir, "auth_ok.json"))
        val responseJson = readJson(File(g18Dir, "response.json"))
        val identityId = responseJson["identityId"] as String

        val authOk = AuthOk(
            sessionId = authOkJson["sessionId"] as String,
            serverNonce = Base64Url.decode(authOkJson["serverNonce"] as String),
            responderPublicKey = Base64Url.decode(authOkJson["responderPublicKey"] as String),
            signature = Base64Url.decode(authOkJson["signature"] as String),
        )

        val verifier = AuthVerifier(
            ed25519 = ed25519,
            clock = Clock { 0L },
            replayGuard = NonceReplayGuard { true },
        )

        val result = verifier.verifyAuthOk(authOk, identityId)
        assertNull(result, "AUTH_OK debe verificar sin errores")
    }

    // ===================================================================
    // G19: JSON wire + Base64URL + verificación
    // ===================================================================

    @Test
    fun `G19 todos los campos Base64URL son validos y sin padding`() {
        val challenge = readJson(File(g19Dir, "challenge.json"))
        val response = readJson(File(g19Dir, "response.json"))
        val authOk = readJson(File(g19Dir, "auth_ok.json"))

        for (field in listOf("nonce")) {
            val v = challenge[field] as String
            assertTrue(Base64Url.isValid(v), "challenge.$field debe ser Base64URL valido")
            assertFalse(v.contains('='), "challenge.$field no debe contener padding")
        }

        for (field in listOf("publicKey", "signature")) {
            val v = response[field] as String
            assertTrue(Base64Url.isValid(v), "response.$field debe ser Base64URL valido")
            assertFalse(v.contains('='), "response.$field no debe contener padding")
        }

        for (field in listOf("serverNonce", "responderPublicKey", "signature")) {
            val v = authOk[field] as String
            assertTrue(Base64Url.isValid(v), "auth_ok.$field debe ser Base64URL valido")
            assertFalse(v.contains('='), "auth_ok.$field no debe contener padding")
        }
    }

    @Test
    fun `G19 transcript construido por TranscriptBuilder coincide con golden`() {
        val challenge = readJson(File(g19Dir, "challenge.json"))
        val response = readJson(File(g19Dir, "response.json"))

        val nonce = Base64Url.decode(challenge["nonce"] as String)
        val ts = (challenge["timestamp"] as Number).toLong()
        val responderId = challenge["responderIdentityId"] as String
        val identityId = response["identityId"] as String

        val authTranscript = TranscriptBuilder.authTranscript(nonce, ts, responderId, identityId)
        assertTrue(authTranscript.size == 152)

        // Verificar contra el verification.json
        val verification = readJson(File(g19Dir, "verification.json"))
        val expectedHex = verification["authTranscriptHex"] as String
        assertEquals(expectedHex, authTranscript.joinToString("") { "%02x".format(it) })
    }

    @Test
    fun `G19 server transcript coincide con golden`() {
        val authOk = readJson(File(g19Dir, "auth_ok.json"))
        val verification = readJson(File(g19Dir, "verification.json"))

        val sessionId = authOk["sessionId"] as String
        val serverNonce = Base64Url.decode(authOk["serverNonce"] as String)
        val initiatorId = verification["initiatorIdentityId"] as String

        val serverTranscript = TranscriptBuilder.serverAuthTranscript(sessionId, serverNonce, initiatorId)
        assertTrue(serverTranscript.size == 120)

        val expectedHex = verification["serverTranscriptHex"] as String
        assertEquals(expectedHex, serverTranscript.joinToString("") { "%02x".format(it) })
    }

    @Test
    fun `G19 initiator signature verifica`() {
        val response = readJson(File(g19Dir, "response.json"))
        val verification = readJson(File(g19Dir, "verification.json"))

        val publicKey = Base64Url.decode(response["publicKey"] as String)
        val signature = Base64Url.decode(response["signature"] as String)
        val authTranscriptHex = verification["authTranscriptHex"] as String
        val transcript = hexStringToBytes(authTranscriptHex)

        assertTrue(ed25519.verify(publicKey, transcript, Signature(signature)),
            "La firma del Initiator debe verificar")
    }

    @Test
    fun `G19 responder signature verifica`() {
        val authOk = readJson(File(g19Dir, "auth_ok.json"))
        val verification = readJson(File(g19Dir, "verification.json"))

        val responderKey = Base64Url.decode(authOk["responderPublicKey"] as String)
        val signature = Base64Url.decode(authOk["signature"] as String)
        val serverTranscriptHex = verification["serverTranscriptHex"] as String
        val transcript = hexStringToBytes(serverTranscriptHex)

        assertTrue(ed25519.verify(responderKey, transcript, Signature(signature)),
            "La firma del Responder debe verificar")
    }

    // ===================================================================
    // Invalid fixtures I1-I5
    // ===================================================================

    @Test
    fun `I1 wrong identityId devuelve INVALID_IDENTITY`() {
        val resp = readJson(File(invalidDir, "I1-wrong-identity.json"))
        val ch = readJson(File(g14Dir, "challenge.json")) // challenge valido

        val response = AuthResponse(
            identityId = resp["identityId"] as String,
            publicKey = Base64Url.decode(resp["publicKey"] as String),
            signature = Base64Url.decode(resp["signature"] as String),
            protocolVersion = 3,
        )
        val challenge = AuthChallenge(
            nonce = Base64Url.decode(ch["nonce"] as String),
            timestampMillis = (ch["timestamp"] as Number).toLong(),
            version = 3,
            responderIdentityId = ch["responderIdentityId"] as String,
        )

        val verifier = AuthVerifier(ed25519, Clock { 0L }, NonceReplayGuard { true })
        val result = verifier.verifyChallengeResponse(challenge, response)
        assertEquals(AuthError.INVALID_IDENTITY, result)
    }

    @Test
    fun `I2 wrong device key devuelve INVALID_IDENTITY`() {
        val resp = readJson(File(invalidDir, "I2-wrong-device.json"))
        val ch = readJson(File(g14Dir, "challenge.json"))

        val response = AuthResponse(
            identityId = resp["identityId"] as String,
            publicKey = Base64Url.decode(resp["publicKey"] as String),
            signature = Base64Url.decode(resp["signature"] as String),
            protocolVersion = 3,
        )
        val challenge = AuthChallenge(
            nonce = Base64Url.decode(ch["nonce"] as String),
            timestampMillis = (ch["timestamp"] as Number).toLong(),
            version = 3,
            responderIdentityId = ch["responderIdentityId"] as String,
        )

        val verifier = AuthVerifier(ed25519, Clock { 0L }, NonceReplayGuard { true })
        val result = verifier.verifyChallengeResponse(challenge, response)
        assertEquals(AuthError.INVALID_IDENTITY, result)
    }

    @Test
    fun `I3 bad signature devuelve INVALID_SIGNATURE`() {
        val resp = readJson(File(invalidDir, "I3-bad-signature.json"))
        val ch = readJson(File(g14Dir, "challenge.json"))

        val response = AuthResponse(
            identityId = resp["identityId"] as String,
            publicKey = Base64Url.decode(resp["publicKey"] as String),
            signature = Base64Url.decode(resp["signature"] as String),
            protocolVersion = 3,
        )
        val challenge = AuthChallenge(
            nonce = Base64Url.decode(ch["nonce"] as String),
            timestampMillis = (ch["timestamp"] as Number).toLong(),
            version = 3,
            responderIdentityId = ch["responderIdentityId"] as String,
        )

        val verifier = AuthVerifier(ed25519, Clock { challenge.timestampMillis }, NonceReplayGuard { true })
        val result = verifier.verifyChallengeResponse(challenge, response)
        assertEquals(AuthError.INVALID_SIGNATURE, result)
    }

    @Test
    fun `I4 bad timestamp devuelve INVALID_TIMESTAMP`() {
        val ch = readJson(File(invalidDir, "I4-bad-timestamp.json"))

        val challenge = AuthChallenge(
            nonce = Base64Url.decode(ch["nonce"] as String),
            timestampMillis = (ch["timestamp"] as Number).toLong(),
            version = 3,
            responderIdentityId = ch["responderIdentityId"] as String,
        )

        // Necesitamos una respuesta VALIDA (identity + signature) porque V5 se
        // verifica DESPUES de V1-V3. Construimos la respuesta con la semilla
        // determinista de auth_generate.py.
        val initiatorSeed = computeSeed("initiator.sign.v1")
        val initiatorKp = ed25519.keyPairFromSeed(initiatorSeed)
        val identityId = KmIds.identityId(initiatorKp.publicKey).lowerHex()

        // Firmar el transcript de I4 (con el timestamp reducido)
        val transcript = TranscriptBuilder.authTranscript(
            nonce = challenge.nonce,
            timestampMillis = challenge.timestampMillis,
            responderIdentityId = challenge.responderIdentityId,
            identityId = identityId,
        )
        val sig = ed25519.sign(initiatorKp.privateKey, transcript)

        val response = AuthResponse(
            identityId = identityId,
            publicKey = initiatorKp.publicKey,
            signature = sig.bytes,
            protocolVersion = 3,
        )

        // Clock fijo en el timestamp DEFAULT (posterior a I4)
        val verifier = AuthVerifier(ed25519, Clock { 1_721_827_200_000L }, NonceReplayGuard { true })
        val result = verifier.verifyChallengeResponse(challenge, response)
        assertEquals(AuthError.INVALID_TIMESTAMP, result)
    }

    @Test
    fun `I5 replay devuelve REPLAY_DETECTED`() {
        val ch = readJson(File(invalidDir, "I5-replay.json"))
        val g15resp = readJson(File(g15Dir, "response.json"))

        val challenge = AuthChallenge(
            nonce = Base64Url.decode(ch["nonce"] as String),
            timestampMillis = (ch["timestamp"] as Number).toLong(),
            version = 3,
            responderIdentityId = ch["responderIdentityId"] as String,
        )

        // I5 tiene el mismo nonce y timestamp que G14, asi que la respuesta de
        // G15 verifica correctamente (pasando V1-V3)
        val response = AuthResponse(
            identityId = g15resp["identityId"] as String,
            publicKey = Base64Url.decode(g15resp["publicKey"] as String),
            signature = Base64Url.decode(g15resp["signature"] as String),
            protocolVersion = 3,
        )

        val seen = mutableSetOf<String>()
        val guard = NonceReplayGuard { nonce ->
            val key = nonce.joinToString("")
            if (key in seen) false else { seen.add(key); true }
        }

        // Registrar el nonce primero (el mismo nonce de I5)
        guard.checkAndRemember(challenge.nonce)

        val verifier = AuthVerifier(ed25519, Clock { challenge.timestampMillis }, guard)
        val result = verifier.verifyChallengeResponse(challenge, response)
        assertEquals(AuthError.REPLAY_DETECTED, result)
    }

    // ===================================================================
    // Helpers
    // ===================================================================

    private fun computeSeed(label: String): ByteArray {
        val prefix = "KM-AUTH-0002/"
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        digest.update((prefix + label).encodeToByteArray())
        return digest.digest()
    }

    // ===================================================================
    // Invariante arquitectonica: publicKey nunca se confunde con identityId
    // ===================================================================

    @Test
    fun `architectural invariant identityId no se pasa como publicKey a verify`() {
        val transcript = readBytes(File(g14Dir, "transcript.bin"))
        val challenge = readJson(File(g14Dir, "challenge.json"))
        val responseJson = readJson(File(g15Dir, "response.json"))

        val identityIdStr = responseJson["identityId"] as String

        // Intentar pasar identityId como publicKey debe fallar
        val identityIdBytes = identityIdStr.encodeToByteArray() // 64 bytes ASCII != 32 bytes Ed25519
        val exception = assertThrows<IllegalArgumentException> {
            ed25519.verify(identityIdBytes, transcript, Signature(ByteArray(64)))
        }
        assertTrue(exception.message?.contains("publicKey.size") == true ||
            exception.message?.contains("32") == true ||
            exception.message?.contains("tamano") == true,
            "Debe rechazar identityId como publicKey: ${exception.message}")
    }

    @Test
    fun `architectural invariant deviceId no se pasa como publicKey a verify`() {
        // deviceId = SHA-256("KM-ID-DEVICE" || signingKey), 32 bytes raw, pero en
        // el wire se transporta como 64 hex ASCII, NO como clave Ed25519
        val responseJson = readJson(File(g15Dir, "response.json"))
        val publicKey = Base64Url.decode(responseJson["publicKey"] as String)

        // Verificar que publicKey SÍ tiene 32 bytes (no identityId de 64 ASCII)
        assertEquals(32, publicKey.size,
            "publicKey debe tener 32 bytes, no 64 como identityId")
    }

    // ===================================================================
    // Helpers
    // ===================================================================

    private fun hexStringToBytes(hex: String): ByteArray {
        val clean = hex.replace(" ", "").replace("\n", "")
        require(clean.length % 2 == 0) { "hex debe tener longitud par: $clean" }
        return clean.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    }

    private fun ByteArray.lowerHex() = joinToString("") { "%02x".format(it) }
}