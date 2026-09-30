package com.keymessage.core.model

enum class FailureCode {
    MESSAGE_TOO_LARGE,
    PEER_NOT_FOUND,
    INVALID_MESSAGE,
    AUTH_FAILED,
    RATE_LIMITED,
    STORAGE_FULL,
    INTERNAL_ERROR,
    TIMEOUT,
    UNKNOWN
}
