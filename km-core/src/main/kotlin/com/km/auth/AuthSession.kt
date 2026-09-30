package com.km.auth

import com.km.crypto.Ed25519
import com.km.crypto.KeyPair
import com.km.crypto.Signature
import com.km.identity.KmIds

/**
 * Maquina de estados de autenticacion KM-0002 (3G).
 *
 * ARCHITECTURE:
 *   AuthSession es una maquina de estados que SÍ posee estado interno.
 *   A diferencia de AuthVerifier (puro, sin estado), AuthSession conserva
 *   el contexto de una ejecucion concreta: nonce, challenge timestamp,
 *   identidades, sessionId, etc.
 *
 *   AuthSession NO implementa criptografia propia. Delega en:
 *     - TranscriptBuilder (construccion de transcripts)
 *     - AuthVerifier (verificacion V1-V6)
 *     - Ed25519 (firma)
 *
 * INVARIANTES DE 3F heredadas:
 *   - identityId siempre se deriva de publicKey.
 *   - publicKey es el unico valor permitido como clave Ed25519.
 *   - TranscriptBuilder es la unica fuente de verdad para los transcripts.
 *
 * @param role INITIATOR o RESPONDER.
 * @param localKeyPair par de claves Ed25519 del actor local.
 * @param localIdentityId identityId del actor local (DEBE derivar de localKeyPair.publicKey).
 * @param ed25519 implementacion Ed25519 para firmar/verificar.
 * @param clock fuente de tiempo inyectable.
 * @param replayGuard guardia de nonces inyectable.
 */
class AuthSession(
    val role: Role,
    val localKeyPair: KeyPair,
    val localIdentityId: String,
    private val ed25519: Ed25519,
    private val clock: Clock = Clock { System.currentTimeMillis() },
    private val replayGuard: NonceReplayGuard = NonceReplayGuard { true },
) {
    enum class Role { INITIATOR, RESPONDER }

    @Volatile
    private var _state: AuthSessionState = AuthSessionState.IDLE
    val state: AuthSessionState get() = _state

    // Estado interno de la sesion
    private var _nonce: ByteArray? = null
    private var _challengeTimestamp: Long = 0L
    private var _responderIdentityId: String? = null
    private var _peerPublicKey: ByteArray? = null
    private var _sessionId: String? = null
    private var _serverNonce: ByteArray? = null

    val nonce: ByteArray? get() = _nonce
    val sessionId: String? get() = _sessionId

    init {
        require(localIdentityId.length == 64) {
            "localIdentityId debe tener 64 caracteres hex, tiene ${localIdentityId.length}"
        }
        require(localIdentityId.all { it in HEX_CHARS }) {
            "localIdentityId debe ser hex minusculas"
        }
        val expected = identityIdFromKey(localKeyPair.publicKey)
        require(localIdentityId == expected) {
            "localIdentityId no deriva de localKeyPair.publicKey: esperado $expected, recibido $localIdentityId"
        }
    }

    // ===================================================================
    // Transiciones
    // ===================================================================

    /**
     * Recibe un AUTH_CHALLENGE del peer.
     * Transicion: IDLE -> CHALLENGE_RECEIVED, o FAILED si el challenge es invalido.
     *
     * NOTA: valida timestamp (V5) y replay (V6) ANTES de cambiar de estado,
     *       garantizando que un challenge invalido no muta el estado.
     */
    fun receiveChallenge(challenge: AuthChallenge): Result<Unit> = runCatching {
        // Validaciones ANTES de cambiar estado
        AuthSessionTransitions.requireLegal(_state, AuthSessionState.CHALLENGE_RECEIVED)

        val tsDiff = kotlin.math.abs(clock.nowMillis() - challenge.timestampMillis)
        require(tsDiff <= AuthVerifier.DEFAULT_TIMESTAMP_WINDOW_MS) {
            "timestamp fuera de ventana: diff=$tsDiff ms"
        }

        require(replayGuard.checkAndRemember(challenge.nonce)) {
            "nonce reutilizado detectado (replay)"
        }

        // Validaciones OK — cambiar estado y almacenar contexto
        _state = AuthSessionState.CHALLENGE_RECEIVED
        _nonce = challenge.nonce
        _challengeTimestamp = challenge.timestampMillis
        _responderIdentityId = challenge.responderIdentityId
    }

    /**
     * Construye el AUTH_RESPONSE para el challenge recibido.
     * NO cambia de estado — el envio es responsabilidad del llamante.
     */
    fun buildResponse(peerIdentityId: String): AuthResponse {
        check(state == AuthSessionState.CHALLENGE_RECEIVED) {
            "buildResponse solo se puede llamar en estado CHALLENGE_RECEIVED (actual: $state)"
        }
        val nonce = _nonce ?: error("nonce no disponible")
        val responderId = _responderIdentityId ?: error("responderIdentityId no disponible")

        val transcript = TranscriptBuilder.authTranscript(
            nonce = nonce,
            timestampMillis = _challengeTimestamp,
            responderIdentityId = responderId,
            identityId = localIdentityId,
        )
        val sig = ed25519.sign(localKeyPair.privateKey, transcript)

        return AuthResponse(
            identityId = localIdentityId,
            publicKey = localKeyPair.publicKey,
            signature = sig.bytes,
            protocolVersion = 3,
        )
    }

    /**
     * Marca que el AUTH_RESPONSE ha sido enviado.
     * Transicion: CHALLENGE_RECEIVED -> RESPONSE_SENT.
     */
    fun markResponseSent() {
        transition(AuthSessionState.RESPONSE_SENT)
    }

    /**
     * Recibe y verifica un AUTH_OK del peer (válido solo en RESPONSE_SENT).
     * Transicion: RESPONSE_SENT -> AUTHENTICATED, o FAILED si falla.
     *
     * NOTA: verifica el AUTH_OK ANTES de cambiar a AUTHENTICATED,
     *       garantizando que un OK invalido no muta el estado.
     */
    fun receiveAuthOk(authOk: AuthOk, expectedPeerIdentityId: String): Result<Unit> = runCatching {
        AuthSessionTransitions.requireLegal(_state, AuthSessionState.AUTHENTICATED)

        val verifier = AuthVerifier(
            ed25519 = ed25519,
            clock = clock,
            replayGuard = NonceReplayGuard { true },
        )
        val error = verifier.verifyAuthOk(authOk, expectedPeerIdentityId)
        if (error != null) {
            throw AuthSessionException("AUTH_OK invalido: $error", error)
        }

        _state = AuthSessionState.AUTHENTICATED
        _sessionId = authOk.sessionId
        _serverNonce = authOk.serverNonce
        _peerPublicKey = authOk.responderPublicKey
    }

    /**
     * Recibe un AUTH_FAIL del peer.
     * Transicion: cualquier estado (excepto IDLE) -> FAILED.
     */
    fun receiveAuthFail(errorCode: String? = null, errorMessage: String? = null) {
        transition(AuthSessionState.FAILED)
    }

    /**
     * Maneja un timeout de autenticacion.
     * Transicion: CHALLENGE_RECEIVED o RESPONSE_SENT -> FAILED.
     */
    fun handleTimeout() {
        transition(AuthSessionState.FAILED)
    }

    /**
     * Reinicia la sesion a IDLE (desde FAILED).
     * Transicion: FAILED -> IDLE.
     */
    fun reset() {
        transition(AuthSessionState.IDLE)
        _nonce = null
        _challengeTimestamp = 0L
        _responderIdentityId = null
        _peerPublicKey = null
        _sessionId = null
        _serverNonce = null
    }

    // ===================================================================
    // Internal
    // ===================================================================

    private fun transition(target: AuthSessionState) {
        if (_state == target) return // no-op
        AuthSessionTransitions.requireLegal(_state, target)
        _state = target
    }

    companion object {
        private val HEX_CHARS = "0123456789abcdef".toSet()

        /** Construye un AuthSession para el rol INITIATOR. */
        fun initiator(
            keyPair: KeyPair,
            identityId: String,
            ed25519: Ed25519,
            clock: Clock = Clock { System.currentTimeMillis() },
            replayGuard: NonceReplayGuard = NonceReplayGuard { true },
        ): AuthSession = AuthSession(
            role = Role.INITIATOR,
            localKeyPair = keyPair,
            localIdentityId = identityId,
            ed25519 = ed25519,
            clock = clock,
            replayGuard = replayGuard,
        )

        /** Construye un AuthSession para el rol RESPONDER. */
        fun responder(
            keyPair: KeyPair,
            identityId: String,
            ed25519: Ed25519,
            clock: Clock = Clock { System.currentTimeMillis() },
            replayGuard: NonceReplayGuard = NonceReplayGuard { true },
        ): AuthSession = AuthSession(
            role = Role.RESPONDER,
            localKeyPair = keyPair,
            localIdentityId = identityId,
            ed25519 = ed25519,
            clock = clock,
            replayGuard = replayGuard,
        )

        private fun identityIdFromKey(publicKey: ByteArray): String {
            val digest = java.security.MessageDigest.getInstance("SHA-256")
            digest.update("KM-ID-IDENTITY".encodeToByteArray())
            digest.update(publicKey)
            return digest.digest().joinToString("") { "%02x".format(it) }
        }
    }
}

class AuthSessionException(
    message: String,
    val error: AuthError? = null,
) : Exception(message)