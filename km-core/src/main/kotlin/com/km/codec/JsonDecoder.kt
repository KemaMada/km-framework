package com.km.codec

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import com.km.model.*
import java.util.*

class JsonDecoder(private val mapper: ObjectMapper = ObjectMapper().registerKotlinModule()) : Decoder {

    override fun decodeMessage(data: ByteArray): Result<Message> = runCatching {
        val root = mapper.readTree(data)
        Message(
            messageId = MessageId(UUID.fromString(root.get("messageId").asText())),
            from = IdentityId(root.get("from").asText()),
            to = IdentityId(root.get("to").asText()),
            payload = Base64.getDecoder().decode(root.get("payload").asText()),
            timestamp = root.get("timestamp").asLong()
        )
    }

    override fun decodeAck(data: ByteArray): Result<Ack> = runCatching {
        val root = mapper.readTree(data)
        Ack(
            from = IdentityId(root.get("from").asText()),
            to = IdentityId(root.get("to").asText()),
            originalMessageId = MessageId(UUID.fromString(root.get("originalMessageId").asText())),
            status = AckStatus.valueOf(root.get("status").asText()),
            timestamp = root.get("timestamp").asLong(),
            signature = Base64.getDecoder().decode(root.get("signature").asText())
        )
    }
}
