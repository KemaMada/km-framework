package com.keymessage.core.model

import com.keymessage.core.kmid.KmIds

/**
 * NodeAnnouncement: anuncio firmado de un Node en la red.
 *
 * KM-0005 §9.4 — Verificacion:
 *   1. identityId == SHA-256("KM-ID-IDENTITY" || publicKey)
 *   2. La firma es valida contra publicKey
 *   3. timestamp no esta en el futuro ni excede validez de 300s
 *
 * La firma se verifica CONTRA LOS BYTES DEL WIRE (JSON), no contra una
 * re-serializacion del modelo. Usar [verifyWireBytes] pasando los bytes
 * originales del payload firmable (el JSON sin el campo `signature`).
 */
data class NodeAnnouncement(
    val messageId: MessageId,
    val timestamp: Long,
    val protocolVersion: String,
    val identity: NodeIdentity,
    val endpoints: List<NodeEndpoint>,
    val capabilities: Set<NodeCapability>,
    val limits: RelayLimits? = null,
    val signature: ByteArray = ByteArray(0)
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is NodeAnnouncement) return false
        return messageId == other.messageId &&
            timestamp == other.timestamp &&
            protocolVersion == other.protocolVersion &&
            identity == other.identity &&
            endpoints == other.endpoints &&
            capabilities == other.capabilities &&
            limits == other.limits &&
            signature.contentEquals(other.signature)
    }

    override fun hashCode(): Int {
        var result = messageId.hashCode()
        result = 31 * result + timestamp.hashCode()
        result = 31 * result + protocolVersion.hashCode()
        result = 31 * result + identity.hashCode()
        result = 31 * result + endpoints.hashCode()
        result = 31 * result + capabilities.hashCode()
        result = 31 * result + (limits?.hashCode() ?: 0)
        result = 31 * result + signature.contentHashCode()
        return result
    }

    /**
     * Verifica el anuncio contra los bytes originales del wire.
     *
     * @param wireSignableBytes Bytes JSON del cuerpo firmable (todo excepto `signature`)
     * @param now Timestamp actual para la ventana de validez
     * @param maxAgeMs Ventana de validez del timestamp (default: 300s)
     */
    fun verifyWireBytes(
        wireSignableBytes: ByteArray,
        now: Long = System.currentTimeMillis(),
        maxAgeMs: Long = 300_000L,
    ): Result<Unit> = runCatching {
        val pubKey = identity.publicKey
        val expectedId = KmIds.identityId(pubKey)
        val declaredIdHex = identity.nodeId.value

        // 1. identityId(k) == nodeId
        if (!identityIdHexToBytes(declaredIdHex).contentEquals(expectedId)) {
            throw NodeAnnouncementVerificationException(
                "IDENTITY_ID_MISMATCH",
                "nodeId no coincide con SHA-256(\"KM-ID-IDENTITY\" || publicKey)",
            )
        }

        // 2. Firma contra publicKey
        val ed25519 = com.keymessage.core.crypto.Ed25519Impl()
        if (!ed25519.verify(pubKey, wireSignableBytes, com.keymessage.core.crypto.Signature(signature))) {
            throw NodeAnnouncementVerificationException(
                "INVALID_SIGNATURE",
                "la firma del NodeAnnouncement no verifica contra publicKey",
            )
        }

        // 3. Ventana de tiempo
        val age = now - timestamp
        if (age < 0) {
            throw NodeAnnouncementVerificationException(
                "TIMESTAMP_IN_FUTURE",
                "el timestamp del anuncio esta en el futuro",
            )
        }
        if (age > maxAgeMs) {
            throw NodeAnnouncementVerificationException(
                "TIMESTAMP_TOO_OLD",
                "el timestamp del anuncio excede la ventana de validez ($maxAgeMs ms)",
            )
        }
    }
}

/**
 * Convierte un hex string a ByteArray.
 * Ej: "abcd" → byteArrayOf(0xAB, 0xCD)
 * Lanza si la longitud es impar o contiene caracteres no hex.
 */
internal fun identityIdHexToBytes(hex: String): ByteArray {
    require(hex.length % 2 == 0) { "hex string debe tener longitud par" }
    return ByteArray(hex.length / 2) { i ->
        ((Character.digit(hex[i * 2], 16) shl 4) or Character.digit(hex[i * 2 + 1], 16)).toByte()
    }
}

/** Excepcion especifica para errores de verificacion de NodeAnnouncement. */
class NodeAnnouncementVerificationException(
    val code: String,
    override val message: String,
) : Exception("$code: $message")