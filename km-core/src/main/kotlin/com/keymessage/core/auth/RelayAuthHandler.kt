package com.keymessage.core.auth

import com.keymessage.core.crypto.Ed25519
import com.keymessage.core.crypto.KeyPair
import com.keymessage.core.crypto.Signature

/**
 * Manejo de autenticacion KM-0002 para el protocolo Relay (KM-0003).
 *
 * 3F.8 — Integracion KM-0002 ↔ KM-0003.
 *
 * Es una envoltura ESTATALTA que consume los tipos de KM-0002 sin duplicar
 * ni reinterpretar su criptografia. Cada llamada recibe todos los parametros
 * necesarios explicitamente; no hay estado interno de sesion.
 *
 * ARCHITECTURE:
 *   RelayClient                       AuthVerifier
 *       |                                  |
 *       ├── recibe AUTH_CHALLENGE          |
 *       │                                  |
 *       ▼                                  ▼
 *   RelayAuthHandler.buildResponse()  TranscriptBuilder + Ed25519.sign
 *       │
 *       ├── envia AUTH_RESPONSE
 *       │
 *       ├── recibe AUTH_OK
 *       │
 *       ▼
 *   RelayAuthHandler.verifyAuthOk()   AuthVerifier.verifyAuthOk()
 *
 * INVARIANTE: identityId se deriva de publicKey en buildResponse().
 * RelayClient nunca pasa identityId sin verificar que publicKey lo produce.
 */
class RelayAuthHandler(private val ed25519: Ed25519) {

    /**
     * Construye un AUTH_RESPONSE para el challenge recibido.
     *
     * @param challenge challenge emitido por el relay.
     * @param identityId identityId del cliente (DEBE derivar de keyPair.publicKey).
     * @param keyPair par de claves Ed25519 del cliente.
     * @return AuthResponse listo para serializar y enviar.
     * @throws IllegalArgumentException si identityId no deriva de keyPair.publicKey.
     */
    fun buildResponse(
        challenge: AuthChallenge,
        identityId: String,
        keyPair: KeyPair,
    ): AuthResponse {
        val expectedId = KmIdsIdentity.derive(keyPair.publicKey)
        require(identityId.equals(expectedId, ignoreCase = true)) {
            "identityId no deriva de publicKey: esperado $expectedId, recibido $identityId"
        }

        val transcript = TranscriptBuilder.authTranscript(
            nonce = challenge.nonce,
            timestampMillis = challenge.timestampMillis,
            responderIdentityId = challenge.responderIdentityId,
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

    /**
     * Verifica el AUTH_OK del relay (V4).
     *
     * @param authOk mensaje AUTH_OK recibido.
     * @param initiatorIdentityId identityId que se envio en el AUTH_RESPONSE.
     * @return null si OK, AuthError si falla.
     */
    fun verifyAuthOk(authOk: AuthOk, initiatorIdentityId: String): AuthError? {
        return AuthVerifier(
            ed25519 = ed25519,
            clock = Clock { System.currentTimeMillis() },
            replayGuard = NonceReplayGuard { true },
        ).verifyAuthOk(authOk, initiatorIdentityId)
    }
}

/**
 * Derivacion de identityId, separada para evitar dependencia circular.
 */
internal object KmIdsIdentity {
    fun derive(publicKey: ByteArray): String {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        digest.update("KM-ID-IDENTITY".encodeToByteArray())
        digest.update(publicKey)
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}