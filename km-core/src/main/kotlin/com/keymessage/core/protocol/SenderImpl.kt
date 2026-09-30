package com.keymessage.core.protocol

import com.keymessage.core.codec.Encoder
import com.keymessage.core.codec.Validator
import com.keymessage.core.crypto.Ed25519
import com.keymessage.core.model.*
import com.keymessage.core.storage.MessageStore
import com.keymessage.core.storage.OfflineQueue

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
        messageStore.updateState(message.messageId, MessageState.SENDING, null).getOrThrow()
        stateHandlers.forEach { it(message.messageId, MessageState.SENDING, null) }

        val encoded = encoder.encodeMessage(message).getOrThrow()

        if (!transport.isOnline()) {
            return queueOffline(message)
        }

        val sendResult = transport.send(encoded, message.to)
        return if (sendResult.isSuccess) {
            messageStore.updateState(message.messageId, MessageState.SENT, null).getOrThrow()
            stateHandlers.forEach { it(message.messageId, MessageState.SENT, null) }
            ackManager.expectAck(message.messageId)
            retryManager.scheduleRetry(message.messageId, 1)
            Result.success(Unit)
        } else {
            queueOffline(message)
        }
    }

    override fun retry(messageId: MessageId): Result<Unit> {
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
