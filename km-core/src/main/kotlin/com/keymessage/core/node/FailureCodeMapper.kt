package com.keymessage.core.node

import com.keymessage.core.model.FailureCode
import com.keymessage.core.model.RelayErrorCode

internal fun FailureCode.toRelayError(): RelayErrorCode = when (this) {
    FailureCode.MESSAGE_TOO_LARGE -> RelayErrorCode.MESSAGE_TOO_LARGE
    FailureCode.PEER_NOT_FOUND -> RelayErrorCode.PEER_NOT_FOUND
    FailureCode.INVALID_MESSAGE -> RelayErrorCode.RELAY_NOT_AUTHORIZED
    FailureCode.AUTH_FAILED -> RelayErrorCode.RELAY_AUTH_FAILED
    FailureCode.RATE_LIMITED -> RelayErrorCode.RATE_LIMITED
    FailureCode.STORAGE_FULL -> RelayErrorCode.STORAGE_FULL
    else -> RelayErrorCode.RELAY_UNREACHABLE
}
