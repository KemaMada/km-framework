package com.km.model

data class StoredReceipt(
    val messageId: MessageId,
    val timestamp: Long,
    val originalMessageId: MessageId,
    val to: IdentityId,
    val expiresAt: Long,
    val relayNodeId: IdentityId
)
