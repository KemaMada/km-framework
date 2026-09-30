package com.km.auth

/**
 * Estados de una sesion de autenticacion KM-0002 (3G).
 *
 * INVARIANTES:
 *   1. Un AUTH_RESPONSE solamente es valido dentro del challenge que lo origino.
 *   2. identityId siempre se deriva de publicKey.
 *   3. El transcript se genera exclusivamente mediante TranscriptBuilder.
 *   4. AuthSession no implementa criptografia propia: delega en AuthVerifier.
 *   5. Despues de AUTHENTICATED, un segundo AUTH_RESPONSE no reinicia la sesion.
 *   6. Un nonce usado no puede reutilizarse para completar otra autenticacion.
 *   7. Timeout/error es una transicion explicita, no una excepcion.
 */
enum class AuthSessionState {
    /** Sin autenticacion en curso. */
    IDLE,
    /** AUTH_CHALLENGE recibido y pendiente de respuesta. */
    CHALLENGE_RECEIVED,
    /** AUTH_RESPONSE enviado y pendiente de AUTH_OK/AUTH_FAIL. */
    RESPONSE_SENT,
    /** Autenticacion completada con exito. */
    AUTHENTICATED,
    /** Autenticacion fallida (AUTH_FAIL, timeout, error). */
    FAILED,
}

/**
 * Transiciones legales de AuthSessionState.
 */
object AuthSessionTransitions {
    private val legal: Map<AuthSessionState, Set<AuthSessionState>> = mapOf(
        AuthSessionState.IDLE to setOf(
            AuthSessionState.CHALLENGE_RECEIVED,   // recibe AUTH_CHALLENGE
            AuthSessionState.FAILED,                // error inmediato
        ),
        AuthSessionState.CHALLENGE_RECEIVED to setOf(
            AuthSessionState.RESPONSE_SENT,         // envia AUTH_RESPONSE
            AuthSessionState.FAILED,                // fallo al validar challenge
        ),
        AuthSessionState.RESPONSE_SENT to setOf(
            AuthSessionState.AUTHENTICATED,         // recibe AUTH_OK valido
            AuthSessionState.FAILED,                // recibe AUTH_FAIL, timeout
        ),
        AuthSessionState.AUTHENTICATED to setOf(
            AuthSessionState.FAILED,                // solo puede fallar por error grave
        ),
        AuthSessionState.FAILED to setOf(
            AuthSessionState.IDLE,                  // reinicio explicito
        ),
    )

    fun isLegal(from: AuthSessionState, to: AuthSessionState): Boolean =
        legal[from]?.contains(to) == true

    fun requireLegal(from: AuthSessionState, to: AuthSessionState) {
        require(isLegal(from, to)) {
            "transicion ilegal: $from -> $to"
        }
    }
}