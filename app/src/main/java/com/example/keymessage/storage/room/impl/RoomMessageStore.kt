package com.example.keymessage.storage.room.impl

import com.example.keymessage.storage.room.dao.MessageDao
import com.example.keymessage.storage.room.entity.MessageEntity
import com.keymessage.core.model.*
import com.keymessage.core.storage.MessageStore
import kotlinx.coroutines.runBlocking

class RoomMessageStore(
    private val messageDao: MessageDao
) : MessageStore {

    override fun insert(message: Message): Result<Unit> = runBlocking {
        runCatching {
            messageDao.insert(MessageEntity.fromMessage(message))
        }
    }

    override fun updateState(messageId: MessageId, state: MessageState, failureCode: FailureCode?): Result<Unit> = runBlocking {
        runCatching {
            messageDao.updateState(messageId.value.toString(), state.name, failureCode?.name)
        }
    }

    override fun get(messageId: MessageId): Result<Message?> = runBlocking {
        runCatching {
            messageDao.get(messageId.value.toString())?.toMessage()
        }
    }

    override fun listByConversation(localId: IdentityId, peerId: IdentityId): Result<List<Message>> = runBlocking {
        runCatching {
            messageDao.listConversation(localId.value, peerId.value).map { it.toMessage() }
        }
    }

    override fun delete(messageId: MessageId): Result<Unit> = runBlocking {
        runCatching {
            messageDao.delete(messageId.value.toString())
        }
    }
}
