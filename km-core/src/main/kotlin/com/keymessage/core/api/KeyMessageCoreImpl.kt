package com.keymessage.core.api

import com.keymessage.core.codec.*
import com.keymessage.core.crypto.*
import com.keymessage.core.model.*
import com.keymessage.core.protocol.*
import com.keymessage.core.storage.*
import java.time.Instant
import java.util.UUID

class KeyMessageCoreImpl(
    private val localIdentity: IdentityId,
    private val localPrivateKey: ByteArray,
    private val transport: Transport,
    private val ed25519: Ed25519 = Ed25519Impl(),
    private val messageStore: MessageStore = InMemoryMessageStore(),
    private val offlineQueue: OfflineQueue = InMemoryOfflineQueue(),
    private val duplicateStore: DuplicateStore = InMemoryDuplicateStore(),
    private val encoder: Encoder = JsonEncoder(),
    private val decoder: Decoder = JsonDecoder(),
    private val validator: Validator = ValidatorImpl()
) : KeyMessageCore {

    private val orderingManager: OrderingManagerImpl = OrderingManagerImpl()
    private val retryManager: RetryManagerImpl = RetryManagerImpl()
    private val ackManager: AckManagerImpl = AckManagerImpl(messageStore, ed25519)
    private lateinit var sender: SenderImpl
    private lateinit var receiver: ReceiverImpl

    init {
        sender = SenderImpl(
            messageStore, offlineQueue, encoder, validator, ed25519,
            transport, ackManager, retryManager, localIdentity, localPrivateKey
        )
        receiver = ReceiverImpl(
            messageStore, duplicateStore, decoder, encoder, validator, ed25519,
            transport, orderingManager, localIdentity, localPrivateKey
        )

        retryManager.onRetry { messageId, attempt ->
            sender.onRetryExpired(messageId, attempt)
        }
        transport.onPeerOnline { peerId ->
            sender.onPeerOnline(peerId)
        }
        transport.setMessageHandler { data ->
            receiver.onMessageBytesReceived(data)
        }
        transport.setAckHandler { data ->
            val ack = decoder.decodeAck(data).getOrNull()
            if (ack != null) ackManager.onAckReceived(ack)
        }

        sender.registerStateHandler { id, state, _ ->
            stateChangeHandlers.forEach { it(id, state) }
        }
    }

    private val stateChangeHandlers = mutableListOf<(MessageId, MessageState) -> Unit>()

    override fun start(): Result<Unit> {
        if (transport.isOnline()) return Result.success(Unit)
        return transport.connect()
    }

    override fun stop() {
        if (transport.connectionState() == ConnectionState.DISCONNECTED) return
        transport.disconnect()
    }

    override fun createMessage(to: IdentityId, payload: ByteArray): Result<Message> = runCatching {
        val messageId = MessageId(UUID.randomUUID())
        Message(
            messageId = messageId,
            from = localIdentity,
            to = to,
            payload = payload,
            timestamp = Instant.now().toEpochMilli(),
            state = MessageState.CREATED
        )
    }

    override fun sendMessage(message: Message): Result<SendResult> = runCatching {
        val result = sender.send(message)
        if (result.isSuccess) {
            SendResult(message.messageId, MessageState.SENT)
        } else {
            SendResult(message.messageId, MessageState.FAILED, FailureCode.INTERNAL_ERROR)
        }
    }

    override fun getMessageState(messageId: MessageId): Result<MessageState> {
        return messageStore.get(messageId).map { it?.state ?: MessageState.FAILED }
    }

    override fun getConversation(peerId: IdentityId): Result<List<Message>> {
        return messageStore.listByConversation(localIdentity, peerId)
    }

    override fun registerAckHandler(handler: (Ack) -> Unit) {
        ackManager.registerAckHandler(handler)
    }

    override fun registerMessageHandler(handler: (Message) -> Unit) {
        receiver.registerMessageHandler(handler)
    }

    override fun registerStateChangeHandler(handler: (MessageId, MessageState) -> Unit) {
        stateChangeHandlers.add(handler)
    }
}
