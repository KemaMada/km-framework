package com.km.codec

import com.km.model.Ack
import com.km.model.Message

interface Validator {
    fun validateMessage(message: Message): Result<Unit>
    fun validateAck(ack: Ack): Result<Unit>
}
