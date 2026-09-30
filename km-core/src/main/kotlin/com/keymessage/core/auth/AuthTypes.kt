package com.keymessage.core.auth

/**
 * Tipos wire de la autenticacion KM-0002, seccion 10.
 *
 * Los campos binarios (nonce, publicKey, signature) se transportan en
 * Base64URL y en memoria como ByteArray crudo (sin re-codificar para firmar).
 */
data class AuthChallenge(
    val nonce: ByteArray,           // 16 bytes
    val timestampMillis: Long,      // uint64 epoch ms
    val version: Int,               // version soportada por el Responder
    val responderIdentityId: String, // 64 hex ascii
)

data class AuthResponse(
    val identityId: String,         // 64 hex ascii
    val publicKey: ByteArray,       // 32 bytes Ed25519
    val signature: ByteArray,       // 64 bytes
    val protocolVersion: Int,
)

data class AuthOk(
    val sessionId: String,          // 40 hex ascii
    val serverNonce: ByteArray,     // 16 bytes
    val responderPublicKey: ByteArray, // 32 bytes Ed25519 del Responder
    val signature: ByteArray,       // 64 bytes
)