package com.km.protocol

import com.km.model.*
import com.km.crypto.Signature

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
