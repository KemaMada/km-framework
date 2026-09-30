package com.keymessage.core.model

enum class MessageState {
    CREATED,
    QUEUED,
    SENDING,
    SENT,
    DELIVERED,
    FAILED,
    EXPIRED
}
