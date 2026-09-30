package com.km.codec

import com.km.model.Ack
import com.km.model.Message

interface Decoder {
    fun decodeMessage(data: ByteArray): Result<Message>
    fun decodeAck(data: ByteArray): Result<Ack>
}
