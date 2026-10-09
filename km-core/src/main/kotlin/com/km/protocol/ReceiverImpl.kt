package com.km.protocol

import com.km.codec.Decoder
import com.km.codec.Encoder
import com.km.codec.Validator
import com.km.crypto.Ed25519
import com.km.model.*
import com.km.storage.DuplicateStore
import com.km.storage.MessageStore
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
    private val localPrivateKey: ByteArray,
    /**
     * Emision del `ACK` ya firmado, inyectada por el nucleo.
     *
     * F2/D2: el `ACK` tiene que viajar por un frame `ACK`, no por un
     * `MESSAGE`. Si el receptor lo emitiera con `transport.send`, el transporte
     * lo envolveria en un envelope `MESSAGE` con un `messageId` NUEVO, el
     * receptor contrario lo trataria como un mensaje entrante mas y le
     * contestaria con otro `ACK`: el ciclo de amplificacion. Delegar la
     * emision es lo que hace que ese camino no pueda volver a abrirse.
     */
    private val ackEmitter: (Ack) -> Result<Unit> = { defaultAckEmitter(transport, encoder, ed25519, localIdentity, localPrivateKey, it) }
) : Receiver {

    private val messageHandlers = mutableListOf<(Message) -> Unit>()

    override fun onMessageReceived(message: Message): Result<Unit> {
        validator.validateMessage(message).getOrThrow()

        if (duplicateStore.isDuplicate(message.messageId, message.from)) {
            ackEmitter(buildAck(message.messageId, message.from, AckStatus.DELIVERED))
            return Result.success(Unit)
        }

        val stored = message.copy(state = MessageState.DELIVERED)
        messageStore.insert(stored).getOrThrow()
        duplicateStore.mark(message.messageId, message.from)

        ackEmitter(buildAck(message.messageId, message.from, AckStatus.DELIVERED))

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

    private fun buildAck(originalMessageId: MessageId, toPeer: IdentityId, status: AckStatus): Ack {
        val timestamp = Instant.now().toEpochMilli()
        val ackData = AckTranscript.build(localIdentity, toPeer, originalMessageId, status, timestamp)
        val signature = ed25519.sign(localPrivateKey, ackData)
        return Ack(
            from = localIdentity,
            to = toPeer,
            // S2.1: el `ACK` lleva el identificador del mensaje que CONFIRMA, no
            // uno nuevo. Es la deduplicacion del lado del emisor.
            originalMessageId = originalMessageId,
            status = status,
            timestamp = timestamp,
            signature = signature.bytes
        )
    }
}

/**
 * Emision por defecto, para quien construye un [ReceiverImpl] sin nucleo.
 *
 * Firma, codifica y llama a `Transport.sendAck`, que es el canal `ACK`.
 */
internal fun defaultAckEmitter(
    transport: Transport,
    encoder: Encoder,
    ed25519: Ed25519,
    localIdentity: IdentityId,
    localPrivateKey: ByteArray,
    ack: Ack,
): Result<Unit> {
    val encoded = encoder.encodeAck(ack).getOrElse { return Result.failure(it) }
    return transport.sendAck(encoded)
}
