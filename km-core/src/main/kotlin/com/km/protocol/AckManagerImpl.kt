package com.km.protocol

import com.km.crypto.Ed25519
import com.km.model.*
import com.km.storage.MessageStore
import java.util.concurrent.ConcurrentHashMap

class AckManagerImpl(
    private val messageStore: MessageStore,
    private val ed25519: Ed25519,
    private val senderPublicKey: (IdentityId) -> ByteArray? = { null },
) : AckManager {

    private val pendingAcks = ConcurrentHashMap<MessageId, MutableList<Ack>>()
    private val ackHandlers = mutableListOf<(Ack) -> Unit>()

    override fun onAckReceived(ack: Ack): Result<Unit> {
        val messageResult = messageStore.get(ack.originalMessageId)
        val message = messageResult.getOrNull() ?: return Result.success(Unit)

        if (ack.from != message.to) {
            return Result.success(Unit)
        }

        val ackData = buildAckData(ack)
        val pubKey = senderPublicKey(ack.from) ?: return Result.success(Unit)
        val isValid = ed25519.verify(pubKey, ackData, com.km.crypto.Signature(ack.signature))
        if (!isValid) {
            return Result.success(Unit)
        }

        val currentState = messageStore.get(ack.originalMessageId).getOrNull()?.state
        if (currentState == MessageState.DELIVERED) {
            return Result.success(Unit)
        }

        val newState = when (ack.status) {
            AckStatus.DELIVERED -> MessageState.DELIVERED
            AckStatus.FAILED -> MessageState.FAILED
        }
        messageStore.updateState(ack.originalMessageId, newState, null)

        pendingAcks[ack.originalMessageId]?.let { list ->
            synchronized(list) {
                list.add(ack)
                (list as Object).notifyAll()
            }
        }

        ackHandlers.forEach { it(ack) }
        return Result.success(Unit)
    }

    override fun waitForAck(messageId: MessageId): Result<Ack> = runCatching {
        val list = pendingAcks.getOrPut(messageId) { mutableListOf() }
        synchronized(list) {
            if (list.isNotEmpty()) return@runCatching list.first()
            (list as Object).wait(5000)
            list.firstOrNull() ?: throw java.util.concurrent.TimeoutException("ACK timeout for $messageId")
        }
    }

    fun registerAckHandler(handler: (Ack) -> Unit) {
        ackHandlers.add(handler)
    }

    fun expectAck(messageId: MessageId) {
        pendingAcks[messageId] = mutableListOf()
    }

    private fun buildAckData(ack: Ack): ByteArray {
        return (ack.from.value + ack.to.value + ack.originalMessageId.value.toString() + ack.status.name + ack.timestamp.toString()).toByteArray()
    }
}
