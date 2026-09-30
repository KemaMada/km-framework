package com.keymessage.core.codec

import com.keymessage.core.model.Ack
import com.keymessage.core.model.Message

interface Encoder {
    fun encodeMessage(message: Message): Result<ByteArray>
    fun encodeAck(ack: Ack): Result<ByteArray>
}
