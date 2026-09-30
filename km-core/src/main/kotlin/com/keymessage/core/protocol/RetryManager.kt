package com.keymessage.core.protocol

import com.keymessage.core.model.MessageId

interface RetryManager {
    fun scheduleRetry(messageId: MessageId, attempt: Int): Result<Unit>
    fun cancelRetry(messageId: MessageId): Result<Unit>
    fun maxRetries(): Int
    fun backoffDelay(attempt: Int): Long
}
