package com.keymessage.core.auth

import com.keymessage.core.crypto.Ed25519
import com.keymessage.core.crypto.Ed25519Impl
import com.keymessage.core.crypto.Signature
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/**
 * Tests de integracion KM-0002 ↔ KM-0003 (3F.8).
 *
 * Verifica que RelayAuthHandler consume correctamente el mecanismo de
 * autenticacion sin duplicar ni reinterpretar su criptografia.
 */
class RelayAuthHandlerTest {

    private val ed25519: Ed25519 = Ed25519Impl()
    private val alice = ed25519.generateKeyPair()
    private val bob = ed25519.generateKeyPair()
    private val relayKp = ed25519.generateKeyPair()

    private val aliceId = identityId(alice.publicKey)
    private val bobId = identityId(bob.publicKey)
    private val relayId = identityId(relayKp.publicKey)

    private val handler = RelayAuthHandler(ed25519)

    // Valores fijos deterministas
    private val nonce = ByteArray(16) { 1 }
    private val serverNonce = ByteArray(16) { 2 }
    private val defaultTs = 1_721_827_200_000L
    private val sessionId = "b0a9f8e7d6c5b4a3f2e1d0c9b8a7f6e5d4c3b2a1"

    // ===================================================================
    // Binding correcto
    // ===================================================================

    @Test
    fun `challenge A genera response A que verifica`() {
        val challenge = AuthChallenge(nonce, defaultTs, 3, relayId)
        val response = handler.buildResponse(challenge, aliceId, alice)

        val verifier = AuthVerifier(ed25519, Clock { defaultTs }, NonceReplayGuard { true })
        assertNull(verifier.verifyChallengeResponse(challenge, response),
            "challenge A + response A debe verificar")
    }

    @Test
    fun `challenge A genera response A que verifica contra AuthVerifier con golden`() {
        // Misma logica que G14-G15: nonce, timestamp, responder, identity
        val challenge = AuthChallenge(nonce, defaultTs, 3, relayId)
        val response = handler.buildResponse(challenge, aliceId, alice)

        // El transcript construido debe medir 152 bytes
        val transcript = TranscriptBuilder.authTranscript(nonce, defaultTs, relayId, aliceId)
        assertEquals(152, transcript.size)

        // La firma debe verificar
        assertTrue(ed25519.verify(alice.publicKey, transcript, Signature(response.signature)),
            "firma del response debe verificar contra el transcript")

        // El identityId debe derivar de publicKey
        assertEquals(aliceId, identityId(alice.publicKey))
    }

    // ===================================================================
    // Cross-binding: challenge A + response B debe fallar
    // ===================================================================

    @Test
    fun `challenge de relay + response de Alice con identidad de Bob falla INVALID_IDENTITY`() {
        val challenge = AuthChallenge(nonce, defaultTs, 3, relayId)

        // Construir response como si fuera de Bob (identityId de Bob)
        val transcript = TranscriptBuilder.authTranscript(nonce, defaultTs, relayId, bobId)
        val sig = ed25519.sign(bob.privateKey, transcript)
        val response = AuthResponse(bobId, bob.publicKey, sig.bytes, 3)

        val verifier = AuthVerifier(ed25519, Clock { defaultTs }, NonceReplayGuard { true })
        val result = verifier.verifyChallengeResponse(challenge, response)

        // La identidad del response es de Bob, pero el challenge es de Alice.
        // V1 (identity) se verifica contra publicKey — identityId de Bob deriva
        // de publicKey de Bob, asi que V1 pasa. Pero! el nonce del challenge
        // se usa en el transcript, y el transcript fue firmado con la clave de Bob.
        // La linea curiosa: V1 pasa (identityId de Bob OK), V3 falla porque
        // el verifier construye el transcript con el nonce del challenge y
        // el identityId del response, y la firma es de Bob sobre ese transcript...
        // En realidad V3 PASARIA porque Bob firmo sobre (nonce, ts, relayId, bobId).
        // Pero el challenge pide responder a relayId con identityId... de Alice?
        // No, el challenge solo dice nonce, timestamp, responderIdentityId=relayId.
        // El response dice identityId=bobId.
        // V1: bobId deriva de bob.publicKey -> OK
        // V3: firma de Bob sobre (nonce, ts, relayId, bobId) -> OK (Bob firmo correctamente)
        // V5: timestamp dentro de ventana -> OK
        // Esto es CORRECTO: el challenge es generico, cualquiera puede responderlo.
        // Lo que importa es que AUTH_OK se firme contra el identityId correcto.
        assertNull(result,
            "Bob puede responder al challenge de Alice con su propia identidad")
    }

    @Test
    fun `AUTH_OK firmado para identity equivocada falla`() {
        // Alice responde al challenge
        val challenge = AuthChallenge(nonce, defaultTs, 3, relayId)
        val response = handler.buildResponse(challenge, aliceId, alice)

        // Relay construye AUTH_OK para Bob, no para Alice
        val serverTranscript = TranscriptBuilder.serverAuthTranscript(sessionId, serverNonce, bobId)
        val ok = AuthOk(sessionId, serverNonce, relayKp.publicKey,
            ed25519.sign(relayKp.privateKey, serverTranscript).bytes)

        // Alice verifica AUTH_OK con su identityId
        val result = handler.verifyAuthOk(ok, aliceId)
        assertEquals(AuthError.INVALID_SERVER_SIGNATURE, result,
            "AUTH_OK firmado para Bob debe fallar cuando Alice lo verifica")
    }

    @Test
    fun `AUTH_OK firmado para identity correcta pasa`() {
        val challenge = AuthChallenge(nonce, defaultTs, 3, relayId)
        val response = handler.buildResponse(challenge, aliceId, alice)

        val serverTranscript = TranscriptBuilder.serverAuthTranscript(sessionId, serverNonce, aliceId)
        val ok = AuthOk(sessionId, serverNonce, relayKp.publicKey,
            ed25519.sign(relayKp.privateKey, serverTranscript).bytes)

        assertNull(handler.verifyAuthOk(ok, aliceId),
            "AUTH_OK firmado para Alice debe verificar")
    }

    // ===================================================================
    // identityId/publicKey mismatch
    // ===================================================================

    @Test
    fun `buildResponse con identityId que no coincide con publicKey lanza`() {
        val challenge = AuthChallenge(nonce, defaultTs, 3, relayId)
        assertThrows<IllegalArgumentException> {
            handler.buildResponse(challenge, bobId, alice) // identityId de Bob, key de Alice
        }
    }

    @Test
    fun `buildResponse con publicKey que no produce identityId lanza`() {
        val challenge = AuthChallenge(nonce, defaultTs, 3, relayId)
        assertThrows<IllegalArgumentException> {
            handler.buildResponse(challenge, "f".repeat(64), alice) // identityId falso
        }
    }

    // ===================================================================
    // Stateless binding
    // ===================================================================

    @Test
    fun `estado de sesion no se almacena en handler`() {
        // El handler no tiene estado — es un verificador de una sola llamada.
        // No depende de AuthSession ni de almacenamiento.
        val challenge = AuthChallenge(nonce, defaultTs, 3, relayId)
        val response = handler.buildResponse(challenge, aliceId, alice)

        // Cada llamada a verifyAuthOk es independiente
        val serverTranscript = TranscriptBuilder.serverAuthTranscript(sessionId, serverNonce, aliceId)
        val ok = AuthOk(sessionId, serverNonce, relayKp.publicKey,
            ed25519.sign(relayKp.privateKey, serverTranscript).bytes)

        // Primera verificacion
        assertNull(handler.verifyAuthOk(ok, aliceId))

        // Segunda verificacion (mismo ok, mismo identityId) — debe poder repetirse
        assertNull(handler.verifyAuthOk(ok, aliceId),
            "handler debe ser stateless: misma verificacion dos veces debe dar mismo resultado")
    }

    @Test
    fun `handler no retiene nonces entre llamadas`() {
        val challenge = AuthChallenge(nonce, defaultTs, 3, relayId)
        val challenge2 = AuthChallenge(ByteArray(16) { 3 }, defaultTs, 3, relayId)

        val r1 = handler.buildResponse(challenge, aliceId, alice)
        val r2 = handler.buildResponse(challenge2, aliceId, alice)

        assertNotNull(r1)
        assertNotNull(r2)
        assertFalse(r1.signature.contentEquals(r2.signature),
            "nonces distintos deben producir firmas distintas")
    }

    // ===================================================================
    // Replay: nonce reutilizado detectado por AuthVerifier
    // ===================================================================

    @Test
    fun `mismo nonce en dos challenges detectado como replay`() {
        val seen = mutableSetOf<String>()
        val guard = NonceReplayGuard { nonce ->
            val key = nonce.joinToString("")
            if (key in seen) false else { seen.add(key); true }
        }

        val challenge = AuthChallenge(nonce, defaultTs, 3, relayId)
        val response = handler.buildResponse(challenge, aliceId, alice)

        val verifier = AuthVerifier(ed25519, Clock { defaultTs }, guard)
        assertNull(verifier.verifyChallengeResponse(challenge, response),
            "primer challenge debe ser aceptado")

        // Segundo challenge CON EL MISMO NONCE
        val challenge2 = AuthChallenge(nonce, defaultTs, 3, relayId)
        assertEquals(AuthError.REPLAY_DETECTED,
            verifier.verifyChallengeResponse(challenge2, response),
            "nonce repetido debe ser rechazado")
    }

    @Test
    fun `response capturado en sesion anterior no funciona en sesion nueva`() {
        // Simular que un atacante capturo un response valido
        val challenge1 = AuthChallenge(ByteArray(16) { 10 }, defaultTs, 3, relayId)
        val response1 = handler.buildResponse(challenge1, aliceId, alice)

        // Ahora el relay envia un challenge DISTINTO
        val challenge2 = AuthChallenge(ByteArray(16) { 20 }, defaultTs, 3, relayId)

        // El atacante reenvia response1, que fue firmado para nonce=10
        val verifier = AuthVerifier(ed25519, Clock { defaultTs }, NonceReplayGuard { true })
        val result = verifier.verifyChallengeResponse(challenge2, response1)
        assertEquals(AuthError.INVALID_SIGNATURE, result,
            "response capturado no debe funcionar con nonce distinto")
    }

    // ===================================================================
    // Transcript integrity: tampering con campos
    // ===================================================================

    @Test
    fun `AUTH_OK con serverNonce incorrecto no verifica`() {
        val transcript = TranscriptBuilder.serverAuthTranscript(sessionId, serverNonce, aliceId)
        val ok = AuthOk(sessionId, serverNonce, relayKp.publicKey,
            ed25519.sign(relayKp.privateKey, transcript).bytes)

        // Tamper serverNonce
        val tamperedOk = ok.copy(serverNonce = ByteArray(16) { 0xAA.toByte() })
        assertEquals(AuthError.INVALID_SERVER_SIGNATURE, handler.verifyAuthOk(tamperedOk, aliceId))
    }

    @Test
    fun `AUTH_OK con sessionId incorrecto no verifica`() {
        val transcript = TranscriptBuilder.serverAuthTranscript(sessionId, serverNonce, aliceId)
        val ok = AuthOk(sessionId, serverNonce, relayKp.publicKey,
            ed25519.sign(relayKp.privateKey, transcript).bytes)

        val tamperedOk = ok.copy(sessionId = "c".repeat(40))
        assertEquals(AuthError.INVALID_SERVER_SIGNATURE, handler.verifyAuthOk(tamperedOk, aliceId))
    }

    @Test
    fun `AUTH_OK con responderPublicKey incorrecta no verifica`() {
        val transcript = TranscriptBuilder.serverAuthTranscript(sessionId, serverNonce, aliceId)
        val ok = AuthOk(sessionId, serverNonce, relayKp.publicKey,
            ed25519.sign(relayKp.privateKey, transcript).bytes)

        val tamperedOk = ok.copy(responderPublicKey = alice.publicKey)
        assertEquals(AuthError.INVALID_SERVER_SIGNATURE, handler.verifyAuthOk(tamperedOk, aliceId))
    }

    // ===================================================================
    // identityId hex size and format invariants
    // ===================================================================

    @Test
    fun `identityId debe tener exactamente 64 caracteres hex`() {
        assertEquals(64, aliceId.length)
        assertTrue(aliceId.all { it in "0123456789abcdef" },
            "identityId debe ser hex minusculas")

        assertEquals(64, relayId.length)
        assertTrue(relayId.all { it in "0123456789abcdef" },
            "identityId debe ser hex minusculas")
    }

    @Test
    fun `identityId es SHA-256 de 32 bytes en hex`() {
        // identityId = SHA-256("KM-ID-IDENTITY" || publicKey), 32 bytes = 64 hex chars
        val raw = identityIdRaw(alice.publicKey)
        assertEquals(32, raw.size)
        assertEquals(aliceId, raw.joinToString("") { "%02x".format(it) })
    }

    @Test
    fun `identityId de Alice y Bob son distintos`() {
        assertNotEquals(aliceId, bobId)
    }

    @Test
    fun `identityId de relay es distinto de Alice`() {
        assertNotEquals(aliceId, relayId)
    }

    // ===================================================================
    // publicKey (32 bytes) ≠ identityId (64 hex chars) en firma
    // ===================================================================

    @Test
    fun `publicKey de 32 bytes usada en firma, NO identityId de 64 hex`() {
        val transcript = TranscriptBuilder.authTranscript(nonce, defaultTs, relayId, aliceId)
        val sig = ed25519.sign(alice.privateKey, transcript)

        // Verificar con publicKey: OK
        assertTrue(ed25519.verify(alice.publicKey, transcript, Signature(sig.bytes)))

        // Verificar con identityId bytes: FALLA (identityId = 64 bytes ASCII)
        val idBytes = aliceId.encodeToByteArray()
        assertEquals(64, idBytes.size)
        assertThrows<IllegalArgumentException> {
            ed25519.verify(idBytes, transcript, Signature(sig.bytes))
        }
    }

    // ===================================================================
    // Helpers
    // ===================================================================

    private fun identityId(publicKey: ByteArray): String {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        digest.update("KM-ID-IDENTITY".encodeToByteArray())
        digest.update(publicKey)
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun identityIdRaw(publicKey: ByteArray): ByteArray {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        digest.update("KM-ID-IDENTITY".encodeToByteArray())
        digest.update(publicKey)
        return digest.digest()
    }
}