package com.keymessage.core.protocol

import com.keymessage.core.codec.Decoder
import com.keymessage.core.codec.Encoder
import com.keymessage.core.codec.Validator
import com.keymessage.core.crypto.Ed25519
import com.keymessage.core.model.*
import com.keymessage.core.storage.DuplicateStore
import com.keymessage.core.storage.MessageStore
import java.time.Instant

class ReceiverImpl(
    private val messageStore: MessageStore,
    private val duplicateStore: DuplicateStore,
    private val decoder: Decoder,
    private val encoder: Encoder,
    private val validator: Validator,
    private val ed25519: Ed25519,
    private val transport: Transport,
    private val orderingManager: OrderingManager,
    private val localIdentity: IdentityId,
    private val localPrivateKey: ByteArray
) : Receiver {

    private val messageHandlers = mutableListOf<(Message) -> Unit>()

    override fun onMessageReceived(message: Message): Result<Unit> {
        validator.validateMessage(message).getOrThrow()

        if (duplicateStore.isDuplicate(message.messageId, message.from)) {
            sendAck(message.messageId, message.from, AckStatus.DELIVERED)
            return Result.success(Unit)
        }

        val stored = message.copy(state = MessageState.DELIVERED)
        messageStore.insert(stored).getOrThrow()
        duplicateStore.mark(message.messageId, message.from)

        sendAck(message.messageId, message.from, AckStatus.DELIVERED)

        orderingManager.onMessageDelivered(stored)
        messageHandlers.forEach { it(stored) }

        return Result.success(Unit)
    }

    fun onMessageBytesReceived(data: ByteArray) {
        val message = decoder.decodeMessage(data).getOrNull() ?: return
        onMessageReceived(message)
    }

    fun registerMessageHandler(handler: (Message) -> Unit) {
        messageHandlers.add(handler)
    }

    private fun sendAck(originalMessageId: MessageId, toPeer: IdentityId, status: AckStatus) {
        val timestamp = Instant.now().toEpochMilli()
        val ackData = buildAckData(localIdentity, toPeer, originalMessageId, status, timestamp)
        val signature = ed25519.sign(localPrivateKey, ackData)
        val ack = Ack(
            from = localIdentity,
            to = toPeer,
            originalMessageId = originalMessageId,
            status = status,
            timestamp = timestamp,
            signature = signature.bytes
        )
        val encoded = encoder.encodeAck(ack).getOrNull() ?: return
        transport.send(encoded, toPeer)
    }

    private fun buildAckData(from: IdentityId, to: IdentityId, originalMessageId: MessageId, status: AckStatus, timestamp: Long): ByteArray {
        return (from.value + to.value + originalMessageId.value.toString() + status.name + timestamp.toString()).toByteArray()
    }
}
