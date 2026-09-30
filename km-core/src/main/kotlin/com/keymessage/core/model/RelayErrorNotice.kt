package com.keymessage.core.model

enum class RelayErrorCode {
    RELAY_UNREACHABLE,
    RELAY_AUTH_FAILED,
    STORAGE_FULL,
    TTL_EXPIRED,
    MESSAGE_TOO_LARGE,
    RELAY_NOT_AUTHORIZED,
    PEER_NOT_FOUND,
    RELAY_HANDOFF_FAILED,
    RATE_LIMITED
}

data class RelayErrorNotice(
    val messageId: MessageId,
    val timestamp: Long,
    val errorCode: RelayErrorCode,
    val errorMessage: String? = null,
    val originalMessageId: MessageId? = null
)
