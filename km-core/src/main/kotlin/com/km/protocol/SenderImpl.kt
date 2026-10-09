package com.km.protocol

import com.km.codec.Encoder
import com.km.codec.Validator
import com.km.crypto.Ed25519
import com.km.model.*
import com.km.storage.MessageStore
import com.km.storage.OfflineQueue

class SenderImpl(
    private val messageStore: MessageStore,
    private val offlineQueue: OfflineQueue,
    private val encoder: Encoder,
    private val validator: Validator,
    private val ed25519: Ed25519,
    private val transport: Transport,
    private val ackManager: AckManagerImpl,
    private val retryManager: RetryManagerImpl,
    private val localIdentity: IdentityId,
    private val localPrivateKey: ByteArray
) : Sender {

    private val stateHandlers = mutableListOf<(MessageId, MessageState, FailureCode?) -> Unit>()

    override fun send(message: Message): Result<Unit> {
        validator.validateMessage(message).getOrThrow()
        // F1: el registro lo escribio `createMessage`, y aqui SOLO se lee.
        //
        // Insertar aqui seria un segundo dono del mismo mensaje: el reintento y
        // `onPeerOnline` pasan por este mismo metodo, asi que un `insert`
        // recrearia el registro en cada intento y el `messageId` -- que es la
        // clave contra la que `AckManager` empareja -- dejaria de ser estable.
        // Ademas, un envio de un mensaje que nadie persistio no es un envio
        // recuperable: si la conexion se cae a mitad, no hay de donde reintentar.
        val stored = messageStore.get(message.messageId).getOrNull()
            ?: return Result.failure(
                NoSuchElementException("Message ${message.messageId} not found")
            )
        messageStore.updateState(stored.messageId, MessageState.SENDING, null).getOrThrow()
        stateHandlers.forEach { it(stored.messageId, MessageState.SENDING, null) }

        val encoded = encoder.encodeMessage(stored).getOrThrow()

        if (!transport.isOnline()) {
            return queueOffline(stored)
        }

        val sendResult = transport.send(encoded, stored.to)
        return if (sendResult.isSuccess) {
            messageStore.updateState(stored.messageId, MessageState.SENT, null).getOrThrow()
            stateHandlers.forEach { it(stored.messageId, MessageState.SENT, null) }
            ackManager.expectAck(stored.messageId)
            retryManager.scheduleRetry(stored.messageId, 1)
            Result.success(Unit)
        } else {
            queueOffline(stored)
        }
    }

    override fun retry(messageId: MessageId): Result<Unit> {
        // Idem `send`: el reintento LEE el registro. `RetryManagerImpl` solo
        // programa el temporizador; no tiene `MessageStore` y no escribe nada.
        val message = messageStore.get(messageId).getOrNull()
            ?: return Result.failure(NoSuchElementException("Message $messageId not found"))

        if (message.state != MessageState.SENT && message.state != MessageState.QUEUED) {
            return Result.success(Unit)
        }

        val encoded = encoder.encodeMessage(message).getOrThrow()

        return if (transport.isOnline()) {
            val sendResult = transport.send(encoded, message.to)
            if (sendResult.isSuccess) {
                messageStore.updateState(message.messageId, MessageState.SENT, null)
                stateHandlers.forEach { it(message.messageId, MessageState.SENT, null) }
                Result.success(Unit)
            } else {
                Result.failure(Exception("Send failed"))
            }
        } else {
            Result.failure(Exception("Offline"))
        }
    }

    override fun cancel(messageId: MessageId): Result<Unit> {
        retryManager.cancelRetry(messageId)
        messageStore.updateState(messageId, MessageState.FAILED, FailureCode.INTERNAL_ERROR)
        stateHandlers.forEach { it(messageId, MessageState.FAILED, FailureCode.INTERNAL_ERROR) }
        return Result.success(Unit)
    }

    fun onRetryExpired(messageId: MessageId, attempt: Int) {
        val nextAttempt = attempt + 1
        if (nextAttempt > retryManager.maxRetries()) {
            messageStore.updateState(messageId, MessageState.EXPIRED, FailureCode.TIMEOUT)
            stateHandlers.forEach { it(messageId, MessageState.EXPIRED, FailureCode.TIMEOUT) }
            return
        }
        val result = retry(messageId)
        if (result.isSuccess) {
            retryManager.scheduleRetry(messageId, nextAttempt)
        } else {
            retryManager.scheduleRetry(messageId, nextAttempt)
        }
    }

    fun onPeerOnline(peerId: IdentityId) {
        // El vaciado de la cola vuelve a pasar por `send`, que ya lee el
        // registro. Por eso un mensaje que se encolo sin estar persistido
        // NO se envia: `send` lo rechaza y el mensaje se queda en la cola en vez
        // de perder el `messageId` que le permitiria reintentarse.
        val queued = offlineQueue.peekAll().getOrNull().orEmpty()
        queued.filter { it.to == peerId }.forEach { message ->
            val sendResult = send(message)
            if (sendResult.isSuccess) {
                offlineQueue.remove(message.messageId)
            }
        }
    }

    fun registerStateHandler(handler: (MessageId, MessageState, FailureCode?) -> Unit) {
        stateHandlers.add(handler)
    }

    private fun queueOffline(message: Message): Result<Unit> {
        offlineQueue.enqueue(message)
        messageStore.updateState(message.messageId, MessageState.QUEUED, null)
        stateHandlers.forEach { it(message.messageId, MessageState.QUEUED, null) }
        return Result.success(Unit)
    }
}
