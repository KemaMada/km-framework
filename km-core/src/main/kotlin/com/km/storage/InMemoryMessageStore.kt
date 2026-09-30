package com.km.storage

import com.km.model.*

class InMemoryMessageStore : MessageStore {
    private val messages = mutableMapOf<MessageId, Message>()

    override fun insert(message: Message): Result<Unit> = runCatching {
        messages[message.messageId] = message
    }

    override fun updateState(messageId: MessageId, state: MessageState, failureCode: FailureCode?): Result<Unit> = runCatching {
        val current = messages[messageId]
            ?: throw NoSuchElementException("Message $messageId not found")
        messages[messageId] = current.copy(state = state, failureCode = failureCode)
    }

    override fun get(messageId: MessageId): Result<Message?> = runCatching {
        messages[messageId]
    }

    override fun listByConversation(localId: IdentityId, peerId: IdentityId): Result<List<Message>> = runCatching {
        messages.values.filter { msg ->
            (msg.from == localId && msg.to == peerId) ||
                    (msg.from == peerId && msg.to == localId)
        }.sortedBy { it.timestamp }
    }

    override fun delete(messageId: MessageId): Result<Unit> = runCatching {
        messages.remove(messageId)
    }
}
