package com.example.keymessage.model

enum class MessageStatus {
    SENDING, SENT, DELIVERED, READ, FAILED
}

data class ChatMessage(
    val id: String = java.util.UUID.randomUUID().toString(),
    val senderId: String,
    val receiverId: String,
    val text: String,
    val timestamp: Long = System.currentTimeMillis(),
    val isMine: Boolean = false,
    val status: MessageStatus = if (isMine) MessageStatus.SENDING else MessageStatus.SENT
)
