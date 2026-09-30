package com.km.codec

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import com.km.model.*
import java.util.UUID

class JsonRelayControlCodec(
    private val mapper: ObjectMapper = ObjectMapper().registerKotlinModule()
) : RelayControlCodec {

    override fun encodeStored(receipt: StoredReceipt): Result<ByteArray> = runCatching {
        val root = mapper.createObjectNode()
        root.put("type", "STORED")
        root.put("messageId", receipt.messageId.value.toString())
        root.put("timestamp", receipt.timestamp)
        root.put("originalMessageId", receipt.originalMessageId.value.toString())
        root.put("to", receipt.to.value)
        root.put("expiresAt", receipt.expiresAt)
        root.put("relayNodeId", receipt.relayNodeId.value)
        mapper.writeValueAsBytes(root)
    }

    override fun decodeStored(data: ByteArray): Result<StoredReceipt> = runCatching {
        val root = mapper.readTree(data)
        StoredReceipt(
            messageId = MessageId(UUID.fromString(root.get("messageId").asText())),
            timestamp = root.get("timestamp").asLong(),
            originalMessageId = MessageId(UUID.fromString(root.get("originalMessageId").asText())),
            to = IdentityId(root.get("to").asText()),
            expiresAt = root.get("expiresAt").asLong(),
            relayNodeId = IdentityId(root.get("relayNodeId").asText())
        )
    }

    override fun encodeRelayExpired(notice: RelayExpiredNotice): Result<ByteArray> = runCatching {
        val root = mapper.createObjectNode()
        root.put("type", "RELAY_EXPIRED")
        root.put("messageId", notice.messageId.value.toString())
        root.put("timestamp", notice.timestamp)
        root.put("originalMessageId", notice.originalMessageId.value.toString())
        root.put("to", notice.to.value)
        root.put("reason", notice.reason)
        mapper.writeValueAsBytes(root)
    }

    override fun decodeRelayExpired(data: ByteArray): Result<RelayExpiredNotice> = runCatching {
        val root = mapper.readTree(data)
        RelayExpiredNotice(
            messageId = MessageId(UUID.fromString(root.get("messageId").asText())),
            timestamp = root.get("timestamp").asLong(),
            originalMessageId = MessageId(UUID.fromString(root.get("originalMessageId").asText())),
            to = IdentityId(root.get("to").asText()),
            reason = root.get("reason").asText()
        )
    }

    override fun encodeError(error: RelayErrorNotice): Result<ByteArray> = runCatching {
        val root = mapper.createObjectNode()
        root.put("type", "RELAY_ERROR")
        root.put("messageId", error.messageId.value.toString())
        root.put("timestamp", error.timestamp)
        root.put("errorCode", error.errorCode.name)
        error.errorMessage?.let { root.put("errorMessage", it) }
        error.originalMessageId?.let { root.put("originalMessageId", it.value.toString()) }
        mapper.writeValueAsBytes(root)
    }

    override fun decodeError(data: ByteArray): Result<RelayErrorNotice> = runCatching {
        val root = mapper.readTree(data)
        RelayErrorNotice(
            messageId = MessageId(UUID.fromString(root.get("messageId").asText())),
            timestamp = root.get("timestamp").asLong(),
            errorCode = RelayErrorCode.valueOf(root.get("errorCode").asText()),
            errorMessage = root.get("errorMessage")?.asText(),
            originalMessageId = root.get("originalMessageId")?.let { MessageId(UUID.fromString(it.asText())) }
        )
    }
}
