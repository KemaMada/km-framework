package com.km.auth

import com.km.crypto.Ed25519
import com.km.crypto.Signature
import com.km.identity.KmIds

/**
 * Modos de fallo de la autenticacion (KM-0002 seccion 13, ampliado para la
 * verificacion por capas de 3F). Cada fixture negativo tiene UNA causa
 * normativa primaria y el verificador devuelve la primera capa que falla.
 */
enum class AuthError {
    INVALID_IDENTITY,
    INVALID_DEVICE,
    INVALID_SIGNATURE,
    INVALID_SERVER_SIGNATURE,
    INVALID_TIMESTAMP,
    REPLAY_DETECTED,
    UNKNOWN_PROTOCOL,
    INVALID_AUTH,
}

/** Indica si el nonce es FRESCO (true) o repetido (false), y lo recuerda. */
fun interface NonceReplayGuard {
    fun checkAndRemember(nonce: ByteArray): Boolean
}

/** Fuente de tiempo inyectable para que AuthVerifier no posea estado alguno. */
fun interface Clock {
    fun nowMillis(): Long
}

/**
 * Verificador de la autenticacion KM-0002 (3F.3).
 *
 * ARCHITECTURE: AuthVerifier NO es una base de datos de sesiones. La
 * verificacion es una funcion casi pura de los parametros; el unico destino
 * de estado es [NonceReplayGuard], inyectado, y queda preparado para que el
 * futuro AuthSession lo alimente.
 *
 * ORDEN DE CAPAS (determinista para los vectores):
 *   V1 identity      -> INVALID_IDENTITY
 *   V2 device        -> INVALID_DEVICE
 *   V3 signature     -> INVALID_SIGNATURE
 *   V4 server sig    -> INVALID_SERVER_SIGNATURE
 *   V5 timestamp     -> INVALID_TIMESTAMP
 *   V6 replay        -> REPLAY_DETECTED
 *
 * INVARIANTE HEREDADA DE 3E: `publicKey` es el unico valor permitido como
 * clave de verificacion Ed25519. `identityId` y `deviceId` son identificadores
 * y JAMAS se interpretan como claves publicas.
 */
class AuthVerifier(
    private val ed25519: Ed25519,
    private val clock: Clock,
    private val replayGuard: NonceReplayGuard,
    private val timestampWindowMillis: Long = DEFAULT_TIMESTAMP_WINDOW_MS,
) {

    /** Verifica la fase challenge-response del Initiator. null == OK. */
    fun verifyChallengeResponse(challenge: AuthChallenge, response: AuthResponse): AuthError? {
        // V1 -- identity: el identityId declarado debe derivar de publicKey.
        if (!isValidHex64(response.identityId)) return AuthError.INVALID_IDENTITY
        val expectedId = Hex.lower(KmIds.identityId(response.publicKey))
        if (!response.identityId.equals(expectedId, ignoreCase = true)) {
            return AuthError.INVALID_IDENTITY
        }

        // V2 -- device: no participa del challenge-response base de 3F
        // (KM-0002 seccion 9.4). Se reserva la capa para el roster/deviceId.

        // V3 -- firma sobre el Authentication Transcript (152 bytes).
        val transcript = TranscriptBuilder.authTranscript(
            nonce = challenge.nonce,
            timestampMillis = challenge.timestampMillis,
            responderIdentityId = challenge.responderIdentityId,
            identityId = response.identityId,
        )
        if (!ed25519.verify(response.publicKey, transcript, Signature(response.signature))) {
            return AuthError.INVALID_SIGNATURE
        }

        // V4 -- firma del servidor: se verifica en verifyAuthOk, despues del AUTH_OK.

        // V5 -- timestamp dentro de la ventana de tolerancia.
        if (kotlin.math.abs(clock.nowMillis() - challenge.timestampMillis) > timestampWindowMillis) {
            return AuthError.INVALID_TIMESTAMP
        }

        // V6 -- replay del nonce.
        if (!replayGuard.checkAndRemember(challenge.nonce)) {
            return AuthError.REPLAY_DETECTED
        }
        return null
    }

    /**
     * Verifica el AUTH_OK del Responder (firma sobre el Server Authentication
     * Transcript, 120 bytes) vinculando la Session a la identidad del Initiator.
     */
    fun verifyAuthOk(authOk: AuthOk, initiatorIdentityId: String): AuthError? {
        if (!isValidHex64(initiatorIdentityId)) return AuthError.INVALID_IDENTITY
        if (!isValidHex40(authOk.sessionId)) return AuthError.INVALID_SERVER_SIGNATURE
        if (authOk.serverNonce.size != SERVER_NONCE_BYTES) return AuthError.INVALID_SERVER_SIGNATURE

        val transcript = TranscriptBuilder.serverAuthTranscript(
            sessionId = authOk.sessionId,
            serverNonce = authOk.serverNonce,
            initiatorIdentityId = initiatorIdentityId,
        )
        if (!ed25519.verify(authOk.responderPublicKey, transcript, Signature(authOk.signature))) {
            return AuthError.INVALID_SERVER_SIGNATURE
        }
        return null
    }

    private fun isValidHex64(value: String): Boolean =
        value.length == 64 && value.all { it in TranscriptBuilderHex }

    private fun isValidHex40(value: String): Boolean =
        value.length == 40 && value.all { it in TranscriptBuilderHex }

    private object Hex {
        fun lower(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it) }
    }

    companion object {
        const val DEFAULT_TIMESTAMP_WINDOW_MS: Long = 300_000L
        const val SERVER_NONCE_BYTES: Int = 16
        private val TranscriptBuilderHex = "0123456789abcdef".toSet()
    }
}