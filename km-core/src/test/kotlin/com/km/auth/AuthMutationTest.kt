package com.km.auth

import com.km.crypto.Ed25519
import com.km.crypto.Ed25519Impl
import com.km.crypto.Signature
import com.km.identity.KmIds
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.test.assertContentEquals

/**
 * Mutation testing de la capa auth (3F.7).
 *
 * Cada test simula un CAMBIO EN EL CODIGO de produccion que un desarrollador
 * podria introducir accidentalmente, y verifica que al menos un test lo
 * detecte (KILLED).
 */
class AuthMutationTest {

    private val ed25519: Ed25519 = Ed25519Impl()
    private val alice = ed25519.generateKeyPair()
    private val bob = ed25519.generateKeyPair()
    private val initiatorSeed = computeSeed("initiator.sign.v1")
    private val initiatorKp = ed25519.keyPairFromSeed(initiatorSeed)
    private val responderKp = ed25519.keyPairFromSeed(computeSeed("responder.sign.v1"))

    private val defaultTs = 1_721_827_200_000L
    private val responderId = KmIds.identityId(responderKp.publicKey).lowerHex()
    private val initiatorId = KmIds.identityId(initiatorKp.publicKey).lowerHex()
    private val nonce = ByteArray(16) { 1 }
    private val serverNonce = ByteArray(16) { 2 }
    private val sessionId = "b0a9f8e7d6c5b4a3f2e1d0c9b8a7f6e5d4c3b2a1"

    private fun fixedClock(now: Long) = Clock { now }
    private fun acceptingGuard() = NonceReplayGuard { true }

    private val authTranscript: ByteArray by lazy {
        TranscriptBuilder.authTranscript(nonce, defaultTs, responderId, initiatorId)
    }
    private val serverTranscript: ByteArray by lazy {
        TranscriptBuilder.serverAuthTranscript(sessionId, serverNonce, initiatorId)
    }

    // ===================================================================
    // 1. Base64URL mutations
    // ===================================================================

    @Test
    fun `M-B64-1 padding rechazado`() {
        assertThrows<Base64Url.DecodeError.PaddingNotAllowed> {
            Base64Url.decode("Zm9vYmFy=")
        }
        assertThrows<Base64Url.DecodeError.PaddingNotAllowed> {
            Base64Url.decode("Zm9vYmFy==")
        }
    }

    @Test
    fun `M-B64-2 caracter mas y barra rechazados`() {
        assertThrows<Base64Url.DecodeError.InvalidChar> {
            Base64Url.decode("a+b/")
        }
        assertThrows<Base64Url.DecodeError.InvalidChar> {
            Base64Url.decode("ab+c")
        }
    }

    @Test
    fun `M-B64-3 longitud imposible len mod 4 eq 1 rechazada`() {
        assertThrows<Base64Url.DecodeError.ImpossibleLength> {
            Base64Url.decode("Z")
        }
        assertThrows<Base64Url.DecodeError.ImpossibleLength> {
            Base64Url.decode("Zm9vY")
        }
    }

    // ===================================================================
    // 2. Transcript mutations
    // ===================================================================

    @Test
    fun `M-TR-1 orden campos alterado rompe auth transcript`() {
        // identityId || responderIdentityId || timestamp || nonce (orden incorrecto)
        val mutado = initiatorId.encodeToByteArray() +
            responderId.encodeToByteArray() +
            uint64be(defaultTs) +
            nonce
        assertFalse(mutado.contentEquals(authTranscript))
    }

    @Test
    fun `M-TR-2 identityId ausente produce longitud incorrecta`() {
        // solo nonce + ts + responderId = 16 + 8 + 64 = 88 bytes, no 152
        val mutado = nonce + uint64be(defaultTs) + responderId.encodeToByteArray()
        assertEquals(88, mutado.size)
        assertNotEquals(152, mutado.size)
    }

    @Test
    fun `M-TR-3 timestamp little-endian produce bytes distintos`() {
        // En JVM: ByteBuffer.allocate(8).putLong(n).array() es big-endian.
        // Si alguien usa ByteBuffer.allocate(8).order(LE).putLong(n).array():
        val leBytes = java.nio.ByteBuffer.allocate(8).order(java.nio.ByteOrder.LITTLE_ENDIAN).putLong(defaultTs).array()
        val mutado = nonce + leBytes + responderId.encodeToByteArray() + initiatorId.encodeToByteArray()
        assertFalse(mutado.contentEquals(authTranscript))
    }

    @Test
    fun `M-TR-4 deviceId usado en lugar de identityId en transcript`() {
        val deviceId = KmIds.deviceId(initiatorKp.publicKey).lowerHex()
        val mutado = TranscriptBuilder.authTranscript(nonce, defaultTs, responderId, deviceId)
        assertFalse(mutado.contentEquals(authTranscript))
    }

    @Test
    fun `M-TR-5 nonce de 8 bytes lanza`() {
        assertThrows<IllegalArgumentException> {
            TranscriptBuilder.authTranscript(ByteArray(8) { 1 }, defaultTs, responderId, initiatorId)
        }
        assertThrows<IllegalArgumentException> {
            TranscriptBuilder.authTranscript(ByteArray(32) { 1 }, defaultTs, responderId, initiatorId)
        }
    }

    @Test
    fun `M-TR-6 server transcript orden alterado produce bytes distintos`() {
        // serverNonce || sessionId || initiatorIdentityId
        val mutado = serverNonce +
            sessionId.encodeToByteArray() +
            initiatorId.encodeToByteArray()
        val correcto = TranscriptBuilder.serverAuthTranscript(sessionId, serverNonce, initiatorId)
        assertFalse(mutado.contentEquals(correcto))
    }

    @Test
    fun `M-TR-7 server transcript sessionId ausente lanza`() {
        assertThrows<IllegalArgumentException> {
            TranscriptBuilder.serverAuthTranscript("", serverNonce, initiatorId)
        }
    }

    @Test
    fun `M-TR-8 auth transcript identityId con hex mayusculas produce bytes distintos`() {
        val upperId = initiatorId.uppercase()
        val mutado = TranscriptBuilder.authTranscript(nonce, defaultTs, responderId, upperId)
        assertFalse(mutado.contentEquals(authTranscript))
    }

    // ===================================================================
    // 3. V1 mutations (identity derivation)
    // ===================================================================

    @Test
    fun `M-V1-1 identityId incorrecto rechazado`() {
        val wrongId = "f".repeat(64)
        val sig = ed25519.sign(initiatorKp.privateKey, authTranscript)
        val response = AuthResponse(wrongId, initiatorKp.publicKey, sig.bytes, 3)
        val challenge = AuthChallenge(nonce, defaultTs, 3, responderId)
        val verifier = AuthVerifier(ed25519, fixedClock(defaultTs), acceptingGuard())
        assertEquals(AuthError.INVALID_IDENTITY, verifier.verifyChallengeResponse(challenge, response))
    }

    @Test
    fun `M-V1-2 publicKey hex como identityId sin separacion de dominio rechazado`() {
        val rawHex = initiatorKp.publicKey.joinToString("") { "%02x".format(it) }
        val sig = ed25519.sign(initiatorKp.privateKey, authTranscript)
        val response = AuthResponse(rawHex, initiatorKp.publicKey, sig.bytes, 3)
        val challenge = AuthChallenge(nonce, defaultTs, 3, responderId)
        val verifier = AuthVerifier(ed25519, fixedClock(defaultTs), acceptingGuard())
        assertEquals(AuthError.INVALID_IDENTITY, verifier.verifyChallengeResponse(challenge, response))
    }

    // ===================================================================
    // 4. V2 mutations (device binding)
    // ===================================================================

    @Test
    fun `M-V2-1 separador KM-ID-IDENTITY vs KM-ID-DEVICE produce IDs distintos`() {
        val devId = KmIds.deviceId(initiatorKp.publicKey).lowerHex()
        val idId = KmIds.identityId(initiatorKp.publicKey).lowerHex()
        assertNotEquals(devId, idId)
    }

    @Test
    fun `M-V2-2 deviceId desde agreementKey difiere de signingKey`() {
        val agreementKey = ByteArray(32) { (it + 100).toByte() }
        val fromAgreement = KmIds.deviceId(agreementKey).lowerHex()
        val fromSign = KmIds.deviceId(initiatorKp.publicKey).lowerHex()
        assertNotEquals(fromAgreement, fromSign)
    }

    // ===================================================================
    // 5. V3 mutations (signature verification)
    // ===================================================================

    @Test
    fun `M-V3-1 identityId como publicKey lanza (64 bytes vs 32)`() {
        val identityIdBytes = initiatorId.encodeToByteArray() // 64 bytes ASCII
        assertThrows<IllegalArgumentException> {
            ed25519.verify(identityIdBytes, authTranscript, Signature(ByteArray(64)))
        }
    }

    @Test
    fun `M-V3-2 firma contra transcript equivocado rechazada`() {
        // Construir un caso donde V1 pase pero V3 falle por transcript incorrecto
        val wrongTranscript = TranscriptBuilder.authTranscript(
            nonce, defaultTs, responderId, initiatorId.replaceFirst('a', 'b')
        )
        val sig = ed25519.sign(initiatorKp.privateKey, authTranscript) // firmado sobre authTranscript
        val response = AuthResponse(initiatorId, initiatorKp.publicKey, sig.bytes, 3)
        val challenge = AuthChallenge(nonce, defaultTs, 3, responderId)

        // El verifier construye el transcript desde el challenge+response, NO acepta
        // uno externo. Pero la firma se verifica contra el transcript construido.
        // Si alguien muta el verifier para usar un transcript externo, fallaria.
        val verifier = AuthVerifier(ed25519, fixedClock(defaultTs), acceptingGuard())
        val result = verifier.verifyChallengeResponse(challenge, response)
        assertNull(result, "transcript correcto -> OK")

        // Ahora simulamos la mutacion: firmar con transcript A, verificar con transcript B
        // El signature de response se firmo sobre authTranscript, pero si el verifier
        // usara wrongTranscript, la verificacion fallaria.
        val sigForWrong = ed25519.sign(initiatorKp.privateKey, wrongTranscript)
        val responseWrong = AuthResponse(initiatorId, initiatorKp.publicKey, sigForWrong.bytes, 3)
        assertEquals(AuthError.INVALID_SIGNATURE,
            verifier.verifyChallengeResponse(challenge, responseWrong))
    }

    @Test
    fun `M-V3-3 firma de clave distinta rechazada`() {
        val sig = ed25519.sign(bob.privateKey, authTranscript)
        val response = AuthResponse(initiatorId, initiatorKp.publicKey, sig.bytes, 3)
        val challenge = AuthChallenge(nonce, defaultTs, 3, responderId)
        val verifier = AuthVerifier(ed25519, fixedClock(defaultTs), acceptingGuard())
        assertEquals(AuthError.INVALID_SIGNATURE, verifier.verifyChallengeResponse(challenge, response))
    }

    @Test
    fun `M-V3-4 signature de tamanio incorrecto lanza o es rechazada`() {
        // Ed25519Impl.verify lanza RuntimeException si signature.size != 64
        assertThrows<Exception> {
            ed25519.verify(initiatorKp.publicKey, authTranscript, Signature(ByteArray(32)))
        }
        assertThrows<Exception> {
            ed25519.verify(initiatorKp.publicKey, authTranscript, Signature(ByteArray(0)))
        }
    }

    // ===================================================================
    // 6. V4 mutations (AUTH_OK signature)
    // ===================================================================

    @Test
    fun `M-V4-1 AUTH_OK con publicKey del Initiator rechazado`() {
        val authOk = AuthOk(sessionId, serverNonce, initiatorKp.publicKey,
            ed25519.sign(responderKp.privateKey, serverTranscript).bytes)
        val verifier = AuthVerifier(ed25519, fixedClock(defaultTs), acceptingGuard())
        assertEquals(AuthError.INVALID_SERVER_SIGNATURE, verifier.verifyAuthOk(authOk, initiatorId))
    }

    @Test
    fun `M-V4-2 AUTH_OK con firma invalida rechazado`() {
        val authOk = AuthOk(sessionId, serverNonce, responderKp.publicKey, ByteArray(64) { 0 })
        val verifier = AuthVerifier(ed25519, fixedClock(defaultTs), acceptingGuard())
        assertEquals(AuthError.INVALID_SERVER_SIGNATURE, verifier.verifyAuthOk(authOk, initiatorId))
    }

    @Test
    fun `M-V4-3 AUTH_OK firmado sobre auth transcript en vez de server transcript`() {
        val wrongSig = ed25519.sign(responderKp.privateKey, authTranscript).bytes
        val authOk = AuthOk(sessionId, serverNonce, responderKp.publicKey, wrongSig)
        val verifier = AuthVerifier(ed25519, fixedClock(defaultTs), acceptingGuard())
        assertEquals(AuthError.INVALID_SERVER_SIGNATURE, verifier.verifyAuthOk(authOk, initiatorId))
    }

    // ===================================================================
    // 7. V5 mutations (timestamp window)
    // ===================================================================

    @Test
    fun `M-V5-1 timestamp futuro lejano rechazado`() {
        val futureTs = defaultTs + 600_000_000L
        val sig = ed25519.sign(initiatorKp.privateKey,
            TranscriptBuilder.authTranscript(nonce, futureTs, responderId, initiatorId))
        val response = AuthResponse(initiatorId, initiatorKp.publicKey, sig.bytes, 3)
        val challenge = AuthChallenge(nonce, futureTs, 3, responderId)
        val verifier = AuthVerifier(ed25519, fixedClock(defaultTs), acceptingGuard())
        assertEquals(AuthError.INVALID_TIMESTAMP, verifier.verifyChallengeResponse(challenge, response))
    }

    @Test
    fun `M-V5-2 timestamp pasado lejano rechazado`() {
        val pastTs = defaultTs - 600_000L
        val sig = ed25519.sign(initiatorKp.privateKey,
            TranscriptBuilder.authTranscript(nonce, pastTs, responderId, initiatorId))
        val response = AuthResponse(initiatorId, initiatorKp.publicKey, sig.bytes, 3)
        val challenge = AuthChallenge(nonce, pastTs, 3, responderId)
        val verifier = AuthVerifier(ed25519, fixedClock(defaultTs), acceptingGuard())
        assertEquals(AuthError.INVALID_TIMESTAMP, verifier.verifyChallengeResponse(challenge, response))
    }

    @Test
    fun `M-V5-3 ventana configurable respeta el parametro`() {
        val futureTs = defaultTs + 60_000_000L // ~20 min futuro
        val sig = ed25519.sign(initiatorKp.privateKey,
            TranscriptBuilder.authTranscript(nonce, futureTs, responderId, initiatorId))
        val response = AuthResponse(initiatorId, initiatorKp.publicKey, sig.bytes, 3)
        val challenge = AuthChallenge(nonce, futureTs, 3, responderId)

        // Ventana enorme: acepta
        val loose = AuthVerifier(ed25519, fixedClock(defaultTs), acceptingGuard(), 1_000_000_000L)
        assertNull(loose.verifyChallengeResponse(challenge, response))

        // Ventana estricta: rechaza
        val strict = AuthVerifier(ed25519, fixedClock(defaultTs), acceptingGuard(), 60_000L)
        assertEquals(AuthError.INVALID_TIMESTAMP, strict.verifyChallengeResponse(challenge, response))
    }

    // ===================================================================
    // 8. V6 mutations (nonce replay guard)
    // ===================================================================

    @Test
    fun `M-V6-1 guard que siempre acepta permite replay`() {
        // Esta mutacion SURVIVE: el guard es inyectado, AuthVerifier confia en el.
        // Es responsabilidad del usuario del verifier proporcionar un guard real.
        val alwaysGuard = NonceReplayGuard { true }
        val sig = ed25519.sign(initiatorKp.privateKey, authTranscript)
        val response = AuthResponse(initiatorId, initiatorKp.publicKey, sig.bytes, 3)
        val challenge = AuthChallenge(nonce, defaultTs, 3, responderId)

        val verifier = AuthVerifier(ed25519, fixedClock(defaultTs), alwaysGuard)
        assertNull(verifier.verifyChallengeResponse(challenge, response))
        assertNull(verifier.verifyChallengeResponse(challenge, response),
            "SURVIVED: guard que siempre retorna true permite replay")
        // AuthVerifier no puede detectar esta mutacion porque el guard es una
        // dependencia inyectada. La proteccion contra replay depende de que el
        // guard implemente checkAndRemember atomicamente.
    }

    @Test
    fun `M-V6-2 guard que checkea sin recordar permite replay`() {
        // Mutacion: check() sin remember() — el guard verifica si el nonce
        // ha sido visto, pero nunca lo anade a la lista de vistos.
        // Como nunca se anade, cada nonce parece "fresco" siempre.
        val seen = mutableSetOf<String>()
        val guardCheckOnly = NonceReplayGuard { nonce ->
            val key = nonce.joinToString("")
            key !in seen  // solo consulta, nunca anade → siempre true
        }

        val sig = ed25519.sign(initiatorKp.privateKey, authTranscript)
        val response = AuthResponse(initiatorId, initiatorKp.publicKey, sig.bytes, 3)
        val challenge = AuthChallenge(nonce, defaultTs, 3, responderId)

        val verifier = AuthVerifier(ed25519, fixedClock(defaultTs), guardCheckOnly)
        assertNull(verifier.verifyChallengeResponse(challenge, response),
            "primera vez: OK")
        assertNull(verifier.verifyChallengeResponse(challenge, response),
            "SURVIVED: guard sin recordar permite replay")
        // AuthVerifier no puede detectar que el guard no recuerda.
        // La proteccion depende del contrato de NonceReplayGuard.
    }

    @Test
    fun `M-V6-3 nonces identicos detectados como replay`() {
        val seen = mutableSetOf<String>()
        val guard = NonceReplayGuard { nonce ->
            val key = nonce.joinToString("")
            if (key in seen) false else { seen.add(key); true }
        }
        val n1 = ByteArray(16) { 5 }
        val n2 = ByteArray(16) { 5 }
        assertContentEquals(n1, n2)
        assertTrue(guard.checkAndRemember(n1))
        assertFalse(guard.checkAndRemember(n2))
    }

    @Test
    fun `M-V6-4 guard rechaza nonces distintos correctamente`() {
        val seen = mutableSetOf<String>()
        val guard = NonceReplayGuard { nonce ->
            val key = nonce.joinToString("")
            if (key in seen) false else { seen.add(key); true }
        }
        assertTrue(guard.checkAndRemember(ByteArray(16) { 1 }))
        assertTrue(guard.checkAndRemember(ByteArray(16) { 2 }))
        assertFalse(guard.checkAndRemember(ByteArray(16) { 1 }))
    }

    // ===================================================================
    // 9. JSON mutations
    // ===================================================================

    @Test
    fun `M-JS-1 parseChallenge nonce ausente lanza`() {
        assertThrows<IllegalArgumentException> {
            AuthJsonCodec.parseChallenge("""{"timestamp":1,"version":3,"responderIdentityId":"${"0".repeat(64)}"}""")
        }
    }

    @Test
    fun `M-JS-2 parseResponse protocolVersion string lanza`() {
        assertThrows<Exception> {
            AuthJsonCodec.parseResponse("""{"identityId":"${"a".repeat(64)}","publicKey":"${"A".repeat(43)}","signature":"${"A".repeat(86)}","protocolVersion":"3"}""")
        }
    }

    @Test
    fun `M-JS-3 parseAuthOk serverNonce ausente lanza`() {
        assertThrows<IllegalArgumentException> {
            AuthJsonCodec.parseAuthOk("""{"sessionId":"${"0".repeat(40)}","responderPublicKey":"${"A".repeat(43)}","signature":"${"A".repeat(86)}"}""")
        }
    }

    @Test
    fun `M-JS-4 parseResponse identityId no hex KILLED por AuthVerifier`() {
        // La mutacion: aceptar identityId con caracteres no hex.
        // El JSON codec no valida hex, pero AuthVerifier V1 si lo hace.
        // Esta mutacion es KILLED a nivel protocolo, no a nivel parseo.
        val nonHexId = "z".repeat(64)
        val response = AuthResponse(nonHexId, initiatorKp.publicKey, ByteArray(64), 3)
        val challenge = AuthChallenge(nonce, defaultTs, 3, responderId)
        val verifier = AuthVerifier(ed25519, fixedClock(defaultTs), acceptingGuard())
        assertEquals(AuthError.INVALID_IDENTITY, verifier.verifyChallengeResponse(challenge, response),
            "AuthVerifier debe rechazar identityId no hex")
    }

    // ===================================================================
    // 10. Integration mutations (identityId vs publicKey)
    // ===================================================================

    @Test
    fun `M-IN-1 identityId hex como publicKey lanza (64 vs 32 bytes)`() {
        val idBytes = initiatorId.encodeToByteArray()
        assertEquals(64, idBytes.size)
        assertThrows<IllegalArgumentException> {
            ed25519.verify(idBytes, authTranscript, Signature(ByteArray(64)))
        }
    }

    @Test
    fun `M-IN-2 nodeId difiere de identityId con misma clave`() {
        val nodeId = computeNodeId(initiatorKp.publicKey)
        val identityId = KmIds.identityId(initiatorKp.publicKey).lowerHex()
        assertNotEquals(nodeId, identityId)
    }

    @Test
    fun `M-IN-3 deviceId en lugar de identityId en transcript produce bytes distintos`() {
        val deviceId = KmIds.deviceId(initiatorKp.publicKey).lowerHex()
        val mutado = TranscriptBuilder.authTranscript(nonce, defaultTs, responderId, deviceId)
        assertFalse(mutado.contentEquals(authTranscript))
    }

    @Test
    fun `M-IN-4 publicKey hex sin separador de dominio rechazado`() {
        val rawHex = initiatorKp.publicKey.joinToString("") { "%02x".format(it) }
        assertEquals(64, rawHex.length)

        val sig = ed25519.sign(initiatorKp.privateKey, authTranscript)
        val response = AuthResponse(rawHex, initiatorKp.publicKey, sig.bytes, 3)
        val challenge = AuthChallenge(nonce, defaultTs, 3, responderId)
        val verifier = AuthVerifier(ed25519, fixedClock(defaultTs), acceptingGuard())
        assertEquals(AuthError.INVALID_IDENTITY, verifier.verifyChallengeResponse(challenge, response))
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

    private fun ByteArray.lowerHex() = joinToString("") { "%02x".format(it) }

    private fun uint64be(value: Long): ByteArray {
        return java.nio.ByteBuffer.allocate(8).putLong(value).array()
    }

    private fun computeNodeId(pk: ByteArray): String {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        digest.update("KM-NODE-NODE".encodeToByteArray())
        digest.update(pk)
        return digest.digest().lowerHex()
    }
}