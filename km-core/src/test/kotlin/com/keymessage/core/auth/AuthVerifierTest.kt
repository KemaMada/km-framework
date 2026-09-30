package com.keymessage.core.auth

import com.keymessage.core.crypto.Ed25519
import com.keymessage.core.crypto.Ed25519Impl
import com.keymessage.core.crypto.KeyPair
import com.keymessage.core.kmid.KmIds
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

/**
 * Tests de AuthVerifier (3F.3).
 *
 * V1: INVALID_IDENTITY  — identityId no deriva de publicKey
 * V2: INVALID_DEVICE    — (reservado, no implementado en 3F)
 * V3: INVALID_SIGNATURE — firma del transcript incorrecta
 * V4: INVALID_SERVER_SIGNATURE — firma del AUTH_OK incorrecta
 * V5: INVALID_TIMESTAMP — fuera de ventana
 * V6: REPLAY_DETECTED   — nonce repetido
 */
class AuthVerifierTest {

    private val ed25519: Ed25519 = Ed25519Impl()
    private val alice = ed25519.generateKeyPair()
    private val bob = ed25519.generateKeyPair()

    /** Clock fijo para tests deterministas. */
    private fun fixedClock(now: Long) = Clock { now }

    private val defaultTs = 1_721_827_200_000L

    /** NonceReplayGuard que acepta todos los nonces. */
    private fun acceptingGuard() = NonceReplayGuard { true }

    /** NonceReplayGuard que rechaza todos los nonces. */
    private fun rejectingGuard() = NonceReplayGuard { false }

    /** AuthChallenge valido de Bob (responder) para Alice (initiator). */
    private fun challenge(
        nonce: ByteArray = ByteArray(16) { 1 },
        timestamp: Long = defaultTs,
        responderId: String = bobIdentityHex(),
    ) = AuthChallenge(
        nonce = nonce,
        timestampMillis = timestamp,
        version = 3,
        responderIdentityId = responderId,
    )

    /** AuthResponse valido de Alice para Bob. */
    private fun response(identityId: String = aliceIdentityHex(), keyPair: KeyPair = alice): AuthResponse {
        val ch = challenge()
        val transcript = TranscriptBuilder.authTranscript(
            nonce = ch.nonce,
            timestampMillis = ch.timestampMillis,
            responderIdentityId = ch.responderIdentityId,
            identityId = identityId,
        )
        val sig = ed25519.sign(keyPair.privateKey, transcript)
        return AuthResponse(
            identityId = identityId,
            publicKey = keyPair.publicKey,
            signature = sig.bytes,
            protocolVersion = 3,
        )
    }

    /** AuthOk valido de Bob para Alice. */
    private fun authOk(
        responderKeyPair: KeyPair = bob,
        initiatorId: String = aliceIdentityHex(),
        sessionId: String = "b0a9f8e7d6c5b4a3f2e1d0c9b8a7f6e5d4c3b2a1",
    ): AuthOk {
        val serverNonce = ByteArray(16) { 2 }
        val transcript = TranscriptBuilder.serverAuthTranscript(sessionId, serverNonce, initiatorId)
        val sig = ed25519.sign(responderKeyPair.privateKey, transcript)
        return AuthOk(
            sessionId = sessionId,
            serverNonce = serverNonce,
            responderPublicKey = responderKeyPair.publicKey,
            signature = sig.bytes,
        )
    }

    private fun aliceIdentityHex() = KmIds.identityId(alice.publicKey).lowerHex()
    private fun bobIdentityHex() = KmIds.identityId(bob.publicKey).lowerHex()
    private fun ByteArray.lowerHex() = joinToString("") { "%02x".format(it) }

    @Test
    fun `challenge-response valido devuelve null`() {
        val verifier = AuthVerifier(ed25519, fixedClock(defaultTs), acceptingGuard())
        val result = verifier.verifyChallengeResponse(challenge(), response())
        assertNull(result)
    }

    @Test
    fun `V1 identityId incorrecto devuelve INVALID_IDENTITY`() {
        val verifier = AuthVerifier(ed25519, fixedClock(defaultTs), acceptingGuard())
        val wrongId = "ffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff"
        val result = verifier.verifyChallengeResponse(challenge(), response(identityId = wrongId))
        assertEquals(AuthError.INVALID_IDENTITY, result)
    }

    @Test
    fun `V1 identityId con longitud incorrecta devuelve INVALID_IDENTITY`() {
        val verifier = AuthVerifier(ed25519, fixedClock(defaultTs), acceptingGuard())
        val result = verifier.verifyChallengeResponse(
            challenge(),
            AuthResponse(
                identityId = "a1b2c3", // demasiado corto
                publicKey = alice.publicKey,
                signature = ByteArray(64),
                protocolVersion = 3,
            )
        )
        assertEquals(AuthError.INVALID_IDENTITY, result)
    }

    @Test
    fun `V3 firma invalida devuelve INVALID_SIGNATURE`() {
        val verifier = AuthVerifier(ed25519, fixedClock(defaultTs), acceptingGuard())
        // Firmar con la clave de Bob, pero declarar identityId de Alice
        val ch = challenge()
        val transcript = TranscriptBuilder.authTranscript(
            nonce = ch.nonce, timestampMillis = ch.timestampMillis,
            responderIdentityId = ch.responderIdentityId,
            identityId = aliceIdentityHex(),
        )
        val sig = ed25519.sign(bob.privateKey, transcript) // ← firma de Bob, no de Alice
        val badResp = AuthResponse(
            identityId = aliceIdentityHex(),
            publicKey = alice.publicKey, // identityId de Alice, publicKey de Alice
            signature = sig.bytes, // pero firma de Bob
            protocolVersion = 3,
        )
        val result = verifier.verifyChallengeResponse(ch, badResp)
        assertEquals(AuthError.INVALID_SIGNATURE, result)
    }

    @Test
    fun `V5 timestamp futuro devuelve INVALID_TIMESTAMP`() {
        // Clock a 0, timestamp muy futuro
        val verifier = AuthVerifier(ed25519, fixedClock(0L), acceptingGuard())
        val result = verifier.verifyChallengeResponse(
            challenge(timestamp = defaultTs),
            response()
        )
        assertEquals(AuthError.INVALID_TIMESTAMP, result)
    }

    @Test
    fun `V5 timestamp pasado devuelve INVALID_TIMESTAMP`() {
        // Clock muy futuro
        val verifier = AuthVerifier(ed25519, fixedClock(defaultTs + 600_000L), acceptingGuard())
        val result = verifier.verifyChallengeResponse(
            challenge(timestamp = defaultTs),
            response()
        )
        assertEquals(AuthError.INVALID_TIMESTAMP, result)
    }

    @Test
    fun `V6 nonce repetido devuelve REPLAY_DETECTED`() {
        val verifier = AuthVerifier(ed25519, fixedClock(defaultTs), rejectingGuard())
        val result = verifier.verifyChallengeResponse(challenge(), response())
        assertEquals(AuthError.REPLAY_DETECTED, result)
    }

    @Test
    fun `V6 primer nonce aceptado, segundo rechazado`() {
        val seen = mutableSetOf<String>()
        val guard = NonceReplayGuard { nonce ->
            val key = nonce.joinToString("")
            if (key in seen) false else { seen.add(key); true }
        }
        val verifier = AuthVerifier(ed25519, fixedClock(defaultTs), guard)

        val nonce1 = ByteArray(16) { 1 }
        val nonce2 = ByteArray(16) { 2 }
        val ch1 = challenge(nonce = nonce1)
        val ch2 = challenge(nonce = nonce2)

        val resp1 = responseForChallenge(ch1)
        assertNull(verifier.verifyChallengeResponse(ch1, resp1), "primer nonce debe ser aceptado")

        val resp2 = responseForChallenge(ch2)
        assertNull(verifier.verifyChallengeResponse(ch2, resp2), "segundo nonce (distinto) debe ser aceptado")

        val replay = verifier.verifyChallengeResponse(ch1, resp1) // nonce1 repetido
        assertEquals(AuthError.REPLAY_DETECTED, replay, "nonce repetido debe ser rechazado")
    }

    /** Construye un AuthResponse que firma correctamente para el challenge dado. */
    private fun responseForChallenge(
        ch: AuthChallenge,
        identityId: String = aliceIdentityHex(),
        keyPair: KeyPair = alice,
    ): AuthResponse {
        val transcript = TranscriptBuilder.authTranscript(
            nonce = ch.nonce,
            timestampMillis = ch.timestampMillis,
            responderIdentityId = ch.responderIdentityId,
            identityId = identityId,
        )
        val sig = ed25519.sign(keyPair.privateKey, transcript)
        return AuthResponse(
            identityId = identityId,
            publicKey = keyPair.publicKey,
            signature = sig.bytes,
            protocolVersion = 3,
        )
    }

    // ===================================================================
    // Server signature (AUTH_OK)
    // ===================================================================

    @Test
    fun `AUTH_OK valido devuelve null`() {
        val verifier = AuthVerifier(ed25519, fixedClock(defaultTs), acceptingGuard())
        val result = verifier.verifyAuthOk(authOk(), aliceIdentityHex())
        assertNull(result)
    }

    @Test
    fun `V4 AUTH_OK con firma incorrecta devuelve INVALID_SERVER_SIGNATURE`() {
        val verifier = AuthVerifier(ed25519, fixedClock(defaultTs), acceptingGuard())
        val ok = authOk().copy(signature = ByteArray(64) { 0xAA.toByte() })
        val result = verifier.verifyAuthOk(ok, aliceIdentityHex())
        assertEquals(AuthError.INVALID_SERVER_SIGNATURE, result)
    }

    @Test
    fun `V4 AUTH_OK con sessionId incorrecto devuelve INVALID_SERVER_SIGNATURE`() {
        val verifier = AuthVerifier(ed25519, fixedClock(defaultTs), acceptingGuard())
        val ok = AuthOk(
            sessionId = "a1", // muy corto
            serverNonce = ByteArray(16) { 2 },
            responderPublicKey = bob.publicKey,
            signature = ByteArray(64),
        )
        val result = verifier.verifyAuthOk(ok, aliceIdentityHex())
        assertEquals(AuthError.INVALID_SERVER_SIGNATURE, result)
    }

    @Test
    fun `V4 AUTH_OK con publicKey incorrecta devuelve INVALID_SERVER_SIGNATURE`() {
        val verifier = AuthVerifier(ed25519, fixedClock(defaultTs), acceptingGuard())
        val ok = authOk().copy(responderPublicKey = alice.publicKey) // clave de Alice, firma de Bob
        val result = verifier.verifyAuthOk(ok, aliceIdentityHex())
        assertEquals(AuthError.INVALID_SERVER_SIGNATURE, result)
    }
}