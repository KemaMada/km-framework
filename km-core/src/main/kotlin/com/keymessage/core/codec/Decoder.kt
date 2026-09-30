package com.keymessage.core.codec

import com.keymessage.core.model.Ack
import com.keymessage.core.model.Message

interface Decoder {
    fun decodeMessage(data: ByteArray): Result<Message>
    fun decodeAck(data: ByteArray): Result<Ack>
}
