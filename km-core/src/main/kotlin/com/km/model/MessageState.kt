package com.km.model

enum class MessageState {
    CREATED,
    QUEUED,
    SENDING,
    SENT,
    DELIVERED,
    FAILED,
    EXPIRED
}
