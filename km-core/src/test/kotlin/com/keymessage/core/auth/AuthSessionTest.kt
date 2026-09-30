package com.keymessage.core.auth

import com.keymessage.core.crypto.Ed25519
import com.keymessage.core.crypto.Ed25519Impl
import com.keymessage.core.crypto.Signature
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/**
 * Tests de AuthSession — maquina de estados de autenticacion KM-0002 (3G).
 *
 * 3G.1 — State model: transiciones legales/ilegales
 * 3G.2 — Initiator: challenge -> response -> OK
 * 3G.3 — Responder: challenge recibido -> response -> authenticated
 * 3G.4 — Failure/timeout
 */
class AuthSessionTest {

    private val ed25519: Ed25519 = Ed25519Impl()
    private val alice = ed25519.generateKeyPair()
    private val relay = ed25519.generateKeyPair()

    private val aliceId = identityId(alice.publicKey)
    private val relayId = identityId(relay.publicKey)

    private val nonce = ByteArray(16) { 1 }
    private val serverNonce = ByteArray(16) { 2 }
    private val defaultTs = 1_721_827_200_000L
    private val sessionId = "b0a9f8e7d6c5b4a3f2e1d0c9b8a7f6e5d4c3b2a1"

    private fun fixedClock(now: Long) = Clock { now }
    private fun acceptingGuard() = NonceReplayGuard { true }

    private fun challenge(): AuthChallenge =
        AuthChallenge(nonce, defaultTs, 3, relayId)

    private fun identityId(pk: ByteArray): String {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        digest.update("KM-ID-IDENTITY".encodeToByteArray())
        digest.update(pk)
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    // ===================================================================
    // 3G.1 — State model: transitions
    // ===================================================================

    @Test
    fun `IDLE to CHALLENGE_RECEIVED es legal`() {
        val session = AuthSession.initiator(alice, aliceId, ed25519, fixedClock(defaultTs), acceptingGuard())
        assertEquals(AuthSessionState.IDLE, session.state)
        session.receiveChallenge(challenge())
        assertEquals(AuthSessionState.CHALLENGE_RECEIVED, session.state)
    }

    @Test
    fun `CHALLENGE_RECEIVED to RESPONSE_SENT es legal`() {
        val session = AuthSession.initiator(alice, aliceId, ed25519, fixedClock(defaultTs), acceptingGuard())
        session.receiveChallenge(challenge())
        session.markResponseSent()
        assertEquals(AuthSessionState.RESPONSE_SENT, session.state)
    }

    @Test
    fun `RESPONSE_SENT_a_AUTHENTICATED_con_AUTH_OK_valido`() {
        val session = AuthSession.initiator(alice, aliceId, ed25519, fixedClock(defaultTs), acceptingGuard())
        session.receiveChallenge(challenge())
        session.buildResponse(relayId) // build response pero no cambia estado
        session.markResponseSent()

        val serverTranscript = TranscriptBuilder.serverAuthTranscript(sessionId, serverNonce, aliceId)
        val ok = AuthOk(sessionId, serverNonce, relay.publicKey,
            ed25519.sign(relay.privateKey, serverTranscript).bytes)
        session.receiveAuthOk(ok, aliceId)
        assertEquals(AuthSessionState.AUTHENTICATED, session.state)
    }

    @Test
    fun `RESPONSE_SENT to FAILED con AUTH_FAIL`() {
        val session = AuthSession.initiator(alice, aliceId, ed25519, fixedClock(defaultTs), acceptingGuard())
        session.receiveChallenge(challenge())
        session.buildResponse(relayId)
        session.markResponseSent()
        session.receiveAuthFail()
        assertEquals(AuthSessionState.FAILED, session.state)
    }

    @Test
    fun `RESPONSE_SENT to FAILED con timeout`() {
        val session = AuthSession.initiator(alice, aliceId, ed25519, fixedClock(defaultTs), acceptingGuard())
        session.receiveChallenge(challenge())
        session.buildResponse(relayId)
        session.markResponseSent()
        session.handleTimeout()
        assertEquals(AuthSessionState.FAILED, session.state)
    }

    @Test
    fun `FAILED to IDLE con reset`() {
        val session = AuthSession.initiator(alice, aliceId, ed25519, fixedClock(defaultTs), acceptingGuard())
        session.receiveChallenge(challenge())
        session.buildResponse(relayId)
        session.markResponseSent()
        session.receiveAuthFail()
        assertEquals(AuthSessionState.FAILED, session.state)

        session.reset()
        assertEquals(AuthSessionState.IDLE, session.state)
    }

    // ===================================================================
    // 3G.1 — Illegal transitions
    // ===================================================================

    @Test
    fun `IDLE to RESPONSE_SENT es ilegal`() {
        val session = AuthSession.initiator(alice, aliceId, ed25519, fixedClock(defaultTs), acceptingGuard())
        assertThrows<IllegalArgumentException> { session.markResponseSent() }
    }

    @Test
    fun `IDLE to AUTHENTICATED es ilegal`() {
        val session = AuthSession.initiator(alice, aliceId, ed25519, fixedClock(defaultTs), acceptingGuard())
        val ok = AuthOk(sessionId, serverNonce, relay.publicKey, ByteArray(64))
        val result = session.receiveAuthOk(ok, aliceId)
        assertTrue(result.isFailure)
    }

    @Test
    fun `CHALLENGE_RECEIVED a AUTHENTICATED es ilegal (falta response)`() {
        val session = AuthSession.initiator(alice, aliceId, ed25519, fixedClock(defaultTs), acceptingGuard())
        session.receiveChallenge(challenge())
        val ok = AuthOk(sessionId, serverNonce, relay.publicKey, ByteArray(64))
        val result = session.receiveAuthOk(ok, aliceId)
        assertTrue(result.isFailure)
    }

    @Test
    fun `AUTHENTICATED a CHALLENGE_RECEIVED es ilegal`() {
        val session = AuthSession.initiator(alice, aliceId, ed25519, fixedClock(defaultTs), acceptingGuard())
        session.receiveChallenge(challenge())
        session.buildResponse(relayId)
        session.markResponseSent()
        val serverTranscript = TranscriptBuilder.serverAuthTranscript(sessionId, serverNonce, aliceId)
        val ok = AuthOk(sessionId, serverNonce, relay.publicKey,
            ed25519.sign(relay.privateKey, serverTranscript).bytes)
        session.receiveAuthOk(ok, aliceId)
        assertEquals(AuthSessionState.AUTHENTICATED, session.state)

        // Recibir otro challenge desde AUTHENTICATED debe fallar (retorna isFailure)
        val result = session.receiveChallenge(challenge())
        assertTrue(result.isFailure)
    }

    @Test
    fun `AUTHENTICATED a RESPONSE_SENT es ilegal`() {
        val session = AuthSession.initiator(alice, aliceId, ed25519, fixedClock(defaultTs), acceptingGuard())
        session.receiveChallenge(challenge())
        session.buildResponse(relayId)
        session.markResponseSent()
        val serverTranscript = TranscriptBuilder.serverAuthTranscript(sessionId, serverNonce, aliceId)
        val ok = AuthOk(sessionId, serverNonce, relay.publicKey,
            ed25519.sign(relay.privateKey, serverTranscript).bytes)
        session.receiveAuthOk(ok, aliceId)
        assertEquals(AuthSessionState.AUTHENTICATED, session.state)

        assertThrows<IllegalArgumentException> { session.markResponseSent() }
    }

    // ===================================================================
    // 3G.2 — Initiator flow
    // ===================================================================

    @Test
    fun `flujo initiator completo challenge response OK authenticated`() {
        val session = AuthSession.initiator(alice, aliceId, ed25519, fixedClock(defaultTs), acceptingGuard())

        // Recibir challenge
        session.receiveChallenge(challenge())
        assertEquals(AuthSessionState.CHALLENGE_RECEIVED, session.state)
        assertNotNull(session.nonce)

        // Construir response
        val response = session.buildResponse(relayId)
        assertEquals(aliceId, response.identityId)
        assertEquals(3, response.protocolVersion)

        // Marcar enviado
        session.markResponseSent()
        assertEquals(AuthSessionState.RESPONSE_SENT, session.state)

        // Recibir AUTH_OK
        val serverTranscript = TranscriptBuilder.serverAuthTranscript(sessionId, serverNonce, aliceId)
        val ok = AuthOk(sessionId, serverNonce, relay.publicKey,
            ed25519.sign(relay.privateKey, serverTranscript).bytes)
        session.receiveAuthOk(ok, aliceId)
        assertEquals(AuthSessionState.AUTHENTICATED, session.state)
        assertEquals(sessionId, session.sessionId)
    }

    @Test
    fun `buildResponse produce transcript de 152 bytes con firma valida`() {
        val session = AuthSession.initiator(alice, aliceId, ed25519, fixedClock(defaultTs), acceptingGuard())
        session.receiveChallenge(challenge())
        val response = session.buildResponse(relayId)

        // Verificar que el transcript tiene 152 bytes
        val transcript = TranscriptBuilder.authTranscript(nonce, defaultTs, relayId, aliceId)
        assertEquals(152, transcript.size)

        // Verificar que la firma es correcta
        assertTrue(ed25519.verify(alice.publicKey, transcript, Signature(response.signature)))
    }

    // ===================================================================
    // 3G.3 — Responder flow
    // ===================================================================

    @Test
    fun `flujo responder challenge recibido response authenticated`() {
        val session = AuthSession.responder(relay, relayId, ed25519, fixedClock(defaultTs), acceptingGuard())

        session.receiveChallenge(challenge())
        assertEquals(AuthSessionState.CHALLENGE_RECEIVED, session.state)
    }

    // ===================================================================
    // 3G.4 — Failure / error cases
    // ===================================================================

    @Test
    fun `AUTH_OK invalido por server signature incorrecta devuelve error`() {
        val session = AuthSession.initiator(alice, aliceId, ed25519, fixedClock(defaultTs), acceptingGuard())
        session.receiveChallenge(challenge())
        session.buildResponse(relayId)
        session.markResponseSent()

        // AUTH_OK con firma incorrecta (firmado con clave de Alice, no del relay)
        val serverTranscript = TranscriptBuilder.serverAuthTranscript(sessionId, serverNonce, aliceId)
        val ok = AuthOk(sessionId, serverNonce, relay.publicKey,
            ed25519.sign(alice.privateKey, serverTranscript).bytes) // firma de Alice, no relay

        val result = session.receiveAuthOk(ok, aliceId)
        assertTrue(result.isFailure)
        // Estado NO cambia: validacion previa a transicion
        assertEquals(AuthSessionState.RESPONSE_SENT, session.state)
    }

    @Test
    fun `AUTH_OK con identityId incorrecto devuelve error`() {
        val session = AuthSession.initiator(alice, aliceId, ed25519, fixedClock(defaultTs), acceptingGuard())
        session.receiveChallenge(challenge())
        session.buildResponse(relayId)
        session.markResponseSent()

        val wrongId = "f".repeat(64)
        val serverTranscript = TranscriptBuilder.serverAuthTranscript(sessionId, serverNonce, wrongId)
        val ok = AuthOk(sessionId, serverNonce, relay.publicKey,
            ed25519.sign(relay.privateKey, serverTranscript).bytes)

        val result = session.receiveAuthOk(ok, aliceId)
        assertTrue(result.isFailure)
        // Estado NO cambia: validacion previa a transicion
        assertEquals(AuthSessionState.RESPONSE_SENT, session.state)
    }

    @Test
    fun `timeout desde RESPONSE_SENT lleva a FAILED`() {
        val session = AuthSession.initiator(alice, aliceId, ed25519, fixedClock(defaultTs), acceptingGuard())
        session.receiveChallenge(challenge())
        session.buildResponse(relayId)
        session.markResponseSent()
        session.handleTimeout()
        assertEquals(AuthSessionState.FAILED, session.state)
    }

    @Test
    fun `AUTH_RESPONSE no se puede construir sin challenge recibido`() {
        val session = AuthSession.initiator(alice, aliceId, ed25519, fixedClock(defaultTs), acceptingGuard())
        assertThrows<IllegalStateException> { session.buildResponse(relayId) }
    }

    @Test
    fun `AUTH_RESPONSE no se puede construir despues de enviado`() {
        val session = AuthSession.initiator(alice, aliceId, ed25519, fixedClock(defaultTs), acceptingGuard())
        session.receiveChallenge(challenge())
        session.buildResponse(relayId)
        session.markResponseSent()
        assertThrows<IllegalStateException> { session.buildResponse(relayId) }
    }

    // ===================================================================
    // identityId validation en constructor
    // ===================================================================

    @Test
    fun `constructor rechaza identityId que no deriva de publicKey`() {
        val otherId = identityId(ed25519.generateKeyPair().publicKey)
        assertThrows<IllegalArgumentException> {
            AuthSession.initiator(alice, otherId, ed25519)
        }
    }

    @Test
    fun `constructor rechaza identityId de longitud incorrecta`() {
        assertThrows<IllegalArgumentException> {
            AuthSession.initiator(alice, "a1b2c3", ed25519)
        }
    }

    @Test
    fun `constructor rechaza identityId con caracteres no hex`() {
        assertThrows<IllegalArgumentException> {
            AuthSession.initiator(alice, "z".repeat(64), ed25519)
        }
    }

    // ===================================================================
    // Replay protection (heredado de 3F)
    // ===================================================================

    @Test
    fun `nonce reutilizado entre sesiones distintas es rechazado`() {
        val seenNonces = mutableSetOf<String>()
        val guard = NonceReplayGuard { nonce ->
            val key = nonce.joinToString("")
            if (key in seenNonces) false else { seenNonces.add(key); true }
        }

        // Primera sesion: OK
        val session1 = AuthSession.initiator(alice, aliceId, ed25519, fixedClock(defaultTs), guard)
        session1.receiveChallenge(challenge())
        assertEquals(AuthSessionState.CHALLENGE_RECEIVED, session1.state)
        session1.buildResponse(relayId)
        session1.markResponseSent()

        // Segunda sesion con el MISMO nonce: rechazado (estado sigue en IDLE)
        val session2 = AuthSession.initiator(alice, aliceId, ed25519, fixedClock(defaultTs), guard)
        val result = session2.receiveChallenge(challenge())
        assertTrue(result.isFailure, "nonce reutilizado debe dar isFailure")
        assertEquals(AuthSessionState.IDLE, session2.state, "estado no cambia si validation falla")
    }

    @Test
    fun `nonce distinto en sesiones distintas es aceptado`() {
        val seenNonces = mutableSetOf<String>()
        val guard = NonceReplayGuard { nonce ->
            val key = nonce.joinToString("")
            if (key in seenNonces) false else { seenNonces.add(key); true }
        }

        // Primera sesion con nonce=ByteArray(16){1}
        val session1 = AuthSession.initiator(alice, aliceId, ed25519, fixedClock(defaultTs), guard)
        session1.receiveChallenge(challenge())

        // Segunda sesion con nonce DISTINTO
        val ch2 = AuthChallenge(ByteArray(16) { 3 }, defaultTs, 3, relayId)
        val session2 = AuthSession.initiator(alice, aliceId, ed25519, fixedClock(defaultTs), guard)
        session2.receiveChallenge(ch2)
        assertEquals(AuthSessionState.CHALLENGE_RECEIVED, session2.state)
    }

    // ===================================================================
    // Invariantes arquitectonicas
    // ===================================================================

    @Test
    fun `publicKey usada para firmar, NO identityId`() {
        val session = AuthSession.initiator(alice, aliceId, ed25519, fixedClock(defaultTs), acceptingGuard())
        session.receiveChallenge(challenge())
        val response = session.buildResponse(relayId)

        // Verificar que publicKey tiene 32 bytes
        assertEquals(32, response.publicKey.size)

        // Verificar que identityId NO tiene 32 bytes (tiene 64)
        assertEquals(64, response.identityId.length)

        // La firma verifica con publicKey
        val transcript = TranscriptBuilder.authTranscript(nonce, defaultTs, relayId, aliceId)
        assertTrue(ed25519.verify(response.publicKey, transcript, Signature(response.signature)))

        // La firma NO verifica con identityId como bytes
        assertThrows<IllegalArgumentException> {
            ed25519.verify(response.identityId.encodeToByteArray(), transcript, Signature(response.signature))
        }
    }

    @Test
    fun `identityId derivado de publicKey en session`() {
        val expected = identityId(alice.publicKey)
        assertEquals(expected, aliceId)

        val session = AuthSession.initiator(alice, aliceId, ed25519)
        assertEquals(aliceId, session.localIdentityId)
    }
}