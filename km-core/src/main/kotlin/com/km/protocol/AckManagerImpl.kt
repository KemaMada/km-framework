package com.km.protocol

import com.km.crypto.Ed25519
import com.km.model.*
import com.km.storage.MessageStore
import java.util.concurrent.ConcurrentHashMap

class AckManagerImpl(
    private val messageStore: MessageStore,
    private val ed25519: Ed25519,
    /**
     * Clave de FIRMA Ed25519 del emisor del `ACK`, por identidad.
     *
     * Es OBLIGATORIA para hacer nada: sin ella, [onAckReceived] devuelve
     * `success` sin verificar y sin procesar, y el emisor se queda esperando un
     * `ACK` que ya llego. Ese era el defecto D3: no una comprobacion que
     * sobraba, sino la unica forma de distinguir un `ACK` autentico de uno que
     * cualquiera puede fabricar con un `originalMessageId` ajeno.
     *
     * El valor por defecto es `{ null }` para no romper a quien construye el
     * manager sin claves, pero ese manager NO autentica nada. Quien tenga el
     * par de claves del peer debe pasarlo, y `KeyMessageCoreImpl` lo hace con
     * el resolver que le inyecta su consumidor.
     */
    private val senderPublicKeyResolver: (IdentityId) -> ByteArray? = { null },
) : AckManager {

    private val pendingAcks = ConcurrentHashMap<MessageId, MutableList<Ack>>()
    private val ackHandlers = mutableListOf<(Ack) -> Unit>()

    override fun onAckReceived(ack: Ack): Result<Unit> {
        val messageResult = messageStore.get(ack.originalMessageId)
        val message = messageResult.getOrNull() ?: return Result.success(Unit)

        if (ack.from != message.to) {
            return Result.success(Unit)
        }

        val ackData = AckTranscript.build(ack)
        val pubKey = senderPublicKeyResolver(ack.from) ?: return Result.success(Unit)
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
}
