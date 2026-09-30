package com.keymessage.core.model

data class Ack(
    val from: IdentityId,
    val to: IdentityId,
    val originalMessageId: MessageId,
    val status: AckStatus,
    val timestamp: Long,
    val signature: ByteArray
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is Ack) return false
        return from == other.from &&
                to == other.to &&
                originalMessageId == other.originalMessageId &&
                status == other.status &&
                timestamp == other.timestamp &&
                signature.contentEquals(other.signature)
    }

    override fun hashCode(): Int {
        var result = from.hashCode()
        result = 31 * result + to.hashCode()
        result = 31 * result + originalMessageId.hashCode()
        result = 31 * result + status.hashCode()
        result = 31 * result + timestamp.hashCode()
        result = 31 * result + signature.contentHashCode()
        return result
    }
}
