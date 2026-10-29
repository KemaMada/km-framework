package com.example.keymessage.storage.room.impl

import com.example.keymessage.storage.room.dao.OfflineQueueDao
import com.example.keymessage.storage.room.entity.OfflineMessageEntity
import com.km.model.IdentityId
import com.km.model.Message
import com.km.model.MessageId
import com.km.storage.OfflineQueue
import kotlinx.coroutines.runBlocking

/**
 * 5.1 — `com.km.storage.OfflineQueue` sobre Room.
 *
 * La cola guarda mensajes en CLARO de un par concreto: son mensajes que la app
 * ya ha decidido enviar y que aun no han salido. Por eso no lleva nada de
 * criptografia —solo el `messageId` que permitira deduplicar— y por eso la
 * politica de expiracion la fija el llamante con [expire], no esta clase.
 */
class RoomOfflineQueue(
    private val offlineQueueDao: OfflineQueueDao,
) : OfflineQueue {

    override fun enqueue(message: Message): Result<Unit> = runBlocking {
        runCatching { offlineQueueDao.enqueue(OfflineMessageEntity.fromMessage(message)) }
    }

    override fun dequeue(peerId: IdentityId): Result<Message?> = runBlocking {
        runCatching { offlineQueueDao.dequeue(peerId.value)?.toMessage() }
    }

    override fun peekAll(): Result<List<Message>> = runBlocking {
        runCatching { offlineQueueDao.peekAll().map { it.toMessage() } }
    }

    override fun remove(messageId: MessageId): Result<Unit> = runBlocking {
        runCatching { offlineQueueDao.remove(messageId.value.toString()) }
    }

    override fun expire(beforeTimestamp: Long): Result<Int> = runBlocking {
        runCatching { offlineQueueDao.expire(beforeTimestamp) }
    }
}