package com.keymessage.core.codec

import com.keymessage.core.model.Ack
import com.keymessage.core.model.Message

interface Validator {
    fun validateMessage(message: Message): Result<Unit>
    fun validateAck(ack: Ack): Result<Unit>
}
