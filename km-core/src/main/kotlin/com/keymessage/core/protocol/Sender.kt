package com.keymessage.core.protocol

import com.keymessage.core.model.*
import com.keymessage.core.crypto.Signature

interface Sender {
    fun send(message: Message): Result<Unit>
    fun retry(messageId: MessageId): Result<Unit>
    fun cancel(messageId: MessageId): Result<Unit>
}

data class SendResult(
    val messageId: MessageId,
    val state: MessageState,
    val failureCode: FailureCode? = null
)
