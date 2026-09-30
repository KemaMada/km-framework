package com.keymessage.core.model

data class RelayExpiredNotice(
    val messageId: MessageId,
    val timestamp: Long,
    val originalMessageId: MessageId,
    val to: IdentityId,
    val reason: String
)
