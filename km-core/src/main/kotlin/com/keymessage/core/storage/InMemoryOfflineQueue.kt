package com.keymessage.core.storage

import com.keymessage.core.model.*

class InMemoryOfflineQueue : OfflineQueue {
    private val queue = mutableListOf<Message>()

    override fun enqueue(message: Message): Result<Unit> = runCatching {
        queue.add(message.copy(state = MessageState.QUEUED))
    }

    override fun dequeue(peerId: IdentityId): Result<Message?> = runCatching {
        val idx = queue.indexOfFirst { it.to == peerId }
        if (idx == -1) null else queue.removeAt(idx)
    }

    override fun peekAll(): Result<List<Message>> = runCatching {
        queue.toList()
    }

    override fun remove(messageId: MessageId): Result<Unit> = runCatching {
        queue.removeAll { it.messageId == messageId }
    }

    override fun expire(beforeTimestamp: Long): Result<Int> = runCatching {
        val expired = queue.filter { it.timestamp < beforeTimestamp }
        queue.removeAll(expired.toSet())
        expired.size
    }
}
