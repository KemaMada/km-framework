package com.keymessage.core.crypto

import com.keymessage.core.model.IdentityId

interface SignatureVerifier {
    fun verifyMessage(message: SignedMessage): Boolean
    fun verifyAck(ack: SignedAck): Boolean
}

/**
 * Mensaje firmado con su clave pública Ed25519.
 *
 * `identityId` se usa para binding/identificación (KM-ID-0001 §6).
 * `publicKey` es la clave criptográfica real (32 bytes, RFC 8032).
 */
data class SignedMessage(
    val identityId: IdentityId,
    val publicKey: ByteArray,
    val data: ByteArray,
    val signature: Signature
)

/**
 * ACK firmado con su clave pública Ed25519.
 *
 * `identityId` se usa para binding/identificación (KM-ID-0001 §6).
 * `publicKey` es la clave criptográfica real (32 bytes, RFC 8032).
 */
data class SignedAck(
    val identityId: IdentityId,
    val publicKey: ByteArray,
    val data: ByteArray,
    val signature: Signature
)
