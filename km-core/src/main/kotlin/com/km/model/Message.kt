package com.km.model

data class Message(
    val messageId: MessageId,
    val from: IdentityId,
    val to: IdentityId,
    val payload: ByteArray,
    val timestamp: Long,
    val state: MessageState = MessageState.CREATED,
    val failureCode: FailureCode? = null
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is Message) return false
        return messageId == other.messageId &&
                from == other.from &&
                to == other.to &&
                payload.contentEquals(other.payload) &&
                timestamp == other.timestamp &&
                state == other.state &&
                failureCode == other.failureCode
    }

    override fun hashCode(): Int {
        var result = messageId.hashCode()
        result = 31 * result + from.hashCode()
        result = 31 * result + to.hashCode()
        result = 31 * result + payload.contentHashCode()
        result = 31 * result + timestamp.hashCode()
        result = 31 * result + state.hashCode()
        result = 31 * result + (failureCode?.hashCode() ?: 0)
        return result
    }
}
