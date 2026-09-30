package com.keymessage.core.codec

import com.keymessage.core.model.*

class ValidatorImpl(private val maxMessageSize: Int = 64 * 1024) : Validator {

    override fun validateMessage(message: Message): Result<Unit> {
        if (message.payload.isEmpty()) {
            return Result.failure(IllegalArgumentException("payload cannot be empty"))
        }
        if (message.payload.size > maxMessageSize) {
            return Result.failure(IllegalArgumentException("payload exceeds $maxMessageSize bytes"))
        }
        return Result.success(Unit)
    }

    override fun validateAck(ack: Ack): Result<Unit> {
        if (ack.signature.size != 64) {
            return Result.failure(IllegalArgumentException("signature must be 64 bytes"))
        }
        return Result.success(Unit)
    }
}
