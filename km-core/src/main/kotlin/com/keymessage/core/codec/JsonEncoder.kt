package com.keymessage.core.codec

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import com.keymessage.core.model.*

class JsonEncoder(private val mapper: ObjectMapper = ObjectMapper().registerKotlinModule()) : Encoder {

    override fun encodeMessage(message: Message): Result<ByteArray> = runCatching {
        val root = mapper.createObjectNode()
        root.put("messageId", message.messageId.value.toString())
        root.put("from", message.from.value)
        root.put("to", message.to.value)
        root.put("payload", java.util.Base64.getEncoder().encodeToString(message.payload))
        root.put("timestamp", message.timestamp)
        mapper.writeValueAsBytes(root)
    }

    override fun encodeAck(ack: Ack): Result<ByteArray> = runCatching {
        val root = mapper.createObjectNode()
        root.put("from", ack.from.value)
        root.put("to", ack.to.value)
        root.put("originalMessageId", ack.originalMessageId.value.toString())
        root.put("status", ack.status.name)
        root.put("timestamp", ack.timestamp)
        root.put("signature", java.util.Base64.getEncoder().encodeToString(ack.signature))
        mapper.writeValueAsBytes(root)
    }
}

fun ObjectNode.putBytes(field: String, value: ByteArray): ObjectNode {
    put(field, java.util.Base64.getEncoder().encodeToString(value))
    return this
}
