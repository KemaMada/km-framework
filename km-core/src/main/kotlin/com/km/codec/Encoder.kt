package com.km.codec

import com.km.model.Ack
import com.km.model.Message

interface Encoder {
    fun encodeMessage(message: Message): Result<ByteArray>
    fun encodeAck(ack: Ack): Result<ByteArray>
}
