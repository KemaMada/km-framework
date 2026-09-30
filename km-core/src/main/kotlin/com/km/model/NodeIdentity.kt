package com.km.model

/**
 * NodeIdentity: identidad de un Node en la red KeyMessage.
 *
 * Invariante KM-ID-0001 §6 + KM-0005 §9.4:
 *     nodeId == identityId == SHA-256("KM-ID-IDENTITY" || publicKey)
 *
 * Construir via [fromKeyPair] garantiza el invariante. Usar el constructor
 * directamente solo para migracion/deserializacion, verificando despues.
 */
data class NodeIdentity(
    val nodeId: IdentityId,
    val publicKey: ByteArray,
    val nodeName: String? = null
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is NodeIdentity) return false
        return nodeId == other.nodeId && publicKey.contentEquals(other.publicKey) && nodeName == other.nodeName
    }

    override fun hashCode(): Int {
        var result = nodeId.hashCode()
        result = 31 * result + publicKey.contentHashCode()
        result = 31 * result + (nodeName?.hashCode() ?: 0)
        return result
    }

    companion object {
        /**
         * Construye un NodeIdentity desde una clave publica Ed25519.
         *
         * Deriva `nodeId` segun KM-ID-0001 §6:
         *     nodeId == identityId == SHA-256("KM-ID-IDENTITY" || publicKey)
         *
         * @param publicKey Clave publica Ed25519 (32 bytes)
         * @param nodeName Nombre legible opcional
         * @param identityIdProvider Dependency inversion para KmIds.identityId()
         *   (default: usingKmIds). Inyectable para testing.
         */
        fun fromKeyPair(
            publicKey: ByteArray,
            nodeName: String? = null,
            identityIdProvider: (ByteArray) -> ByteArray = ::usingKmIds,
        ): NodeIdentity {
            require(publicKey.size == 32) { "publicKey debe ser 32 bytes (Ed25519)" }
            val identityIdBytes = identityIdProvider(publicKey)
            return NodeIdentity(
                nodeId = IdentityId(identityIdBytes.toHexString()),
                publicKey = publicKey,
                nodeName = nodeName,
            )
        }
    }
}

/**
 * Convierte un ByteArray a su representacion hexadecimal en minusculas.
 * Ej: byteArrayOf(0xAB, 0xCD) → "abcd"
 */
internal fun ByteArray.toHexString(): String {
    val sb = StringBuilder(size * 2)
    for (x in this) sb.append("%02x".format(x))
    return sb.toString()
}

/** Provider por defecto: deriva identityId via KmIds. */
private fun usingKmIds(publicKey: ByteArray): ByteArray =
    com.km.identity.KmIds.identityId(publicKey)