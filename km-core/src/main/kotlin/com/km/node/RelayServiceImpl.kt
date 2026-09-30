package com.km.node

import com.km.codec.Encoder
import com.km.codec.JsonEncoder
import com.km.model.Ack
import com.km.model.AckStatus
import com.km.model.FailureCode
import com.km.model.Message
import com.km.model.RelayLimits
import com.km.model.StoredMessage
import com.km.storage.RelayStore

class RelayServiceImpl(
    override val relayStore: RelayStore,
    override val limits: RelayLimits = RelayLimits(),
    private val encoder: Encoder = JsonEncoder(),
    private val now: () -> Long = System::currentTimeMillis
) : RelayService {

    private val storedHandlers = mutableListOf<(StoredMessage) -> Unit>()
    private val ackForwardedHandlers = mutableListOf<(Ack) -> Unit>()

    override fun handleMessage(message: Message, recipientOnline: Boolean): Result<RelayDecision> = runCatching {
        when {
            message.payload.size.toLong() > limits.maxMessageSize ->
                RelayDecision.Rejected(FailureCode.MESSAGE_TOO_LARGE)

            message.from == message.to ->
                RelayDecision.Rejected(FailureCode.INVALID_MESSAGE)

            relayStore.exists(message.messageId).getOrThrow() ->
                RelayDecision.Duplicate

            recipientOnline ->
                RelayDecision.Forwarded(message)

            else -> {
                val stored = storeForDelivery(message)
                RelayDecision.Stored(stored)
            }
        }
    }

    override fun handleAck(ack: Ack): Result<Unit> = runCatching {
        if (ack.status == AckStatus.DELIVERED) {
            relayStore.delete(ack.originalMessageId)
        }
        ackForwardedHandlers.forEach { it(ack) }
    }

    override fun expire(now: Long): Result<List<StoredMessage>> = runCatching {
        val expired = relayStore.listExpired(now).getOrThrow()
        expired.forEach { relayStore.delete(it.messageId) }
        expired
    }

    override fun registerStoredHandler(handler: (StoredMessage) -> Unit) {
        storedHandlers.add(handler)
    }

    override fun registerAckForwardedHandler(handler: (Ack) -> Unit) {
        ackForwardedHandlers.add(handler)
    }

    private fun storeForDelivery(message: Message): StoredMessage {
        val timestamp = now()
        val wire = encoder.encodeMessage(message).getOrThrow()
        val stored = StoredMessage(
            messageId = message.messageId,
            senderId = message.from,
            recipientId = message.to,
            wireMessage = wire,
            storedAt = timestamp,
            expiresAt = timestamp + limits.maxMessageAgeMs
        )
        relayStore.store(stored).getOrThrow()
        storedHandlers.forEach { it(stored) }
        return stored
    }
}