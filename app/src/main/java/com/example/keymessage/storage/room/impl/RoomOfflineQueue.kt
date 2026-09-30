package com.example.keymessage.storage.room.impl

import com.example.keymessage.storage.room.dao.OfflineQueueDao
import com.example.keymessage.storage.room.entity.OfflineMessageEntity
import com.keymessage.core.model.IdentityId
import com.keymessage.core.model.Message
import com.keymessage.core.model.MessageId
import com.keymessage.core.storage.OfflineQueue
import kotlinx.coroutines.runBlocking

class RoomOfflineQueue(
    private val offlineQueueDao: OfflineQueueDao
) : OfflineQueue {

    override fun enqueue(message: Message): Result<Unit> = runBlocking {
        runCatching {
            offlineQueueDao.enqueue(OfflineMessageEntity.fromMessage(message))
        }
    }

    override fun dequeue(peerId: IdentityId): Result<Message?> = runBlocking {
        runCatching {
            offlineQueueDao.dequeue(peerId.value)?.toMessage()
        }
    }

    override fun peekAll(): Result<List<Message>> = runBlocking {
        runCatching {
            offlineQueueDao.peekAll().map { it.toMessage() }
        }
    }

    override fun remove(messageId: MessageId): Result<Unit> = runBlocking {
        runCatching {
            offlineQueueDao.remove(messageId.value.toString())
        }
    }

    override fun expire(beforeTimestamp: Long): Result<Int> = runBlocking {
        runCatching {
            offlineQueueDao.expire(beforeTimestamp)
        }
    }
}
