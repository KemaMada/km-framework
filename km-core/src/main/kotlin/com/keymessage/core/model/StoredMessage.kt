package com.keymessage.core.model

data class StoredMessage(
    val messageId: MessageId,
    val senderId: IdentityId,
    val recipientId: IdentityId,
    val wireMessage: ByteArray,
    val storedAt: Long,
    val expiresAt: Long
) {
    fun isExpired(now: Long): Boolean = expiresAt <= now

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is StoredMessage) return false
        return messageId == other.messageId &&
            senderId == other.senderId &&
            recipientId == other.recipientId &&
            wireMessage.contentEquals(other.wireMessage) &&
            storedAt == other.storedAt &&
            expiresAt == other.expiresAt
    }

    override fun hashCode(): Int {
        var result = messageId.hashCode()
        result = 31 * result + senderId.hashCode()
        result = 31 * result + recipientId.hashCode()
        result = 31 * result + wireMessage.contentHashCode()
        result = 31 * result + storedAt.hashCode()
        result = 31 * result + expiresAt.hashCode()
        return result
    }
}