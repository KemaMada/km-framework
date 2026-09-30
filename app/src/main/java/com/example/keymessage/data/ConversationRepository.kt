package com.example.keymessage.data

import com.example.keymessage.storage.room.dao.MessageDao
import com.example.keymessage.storage.room.entity.MessageEntity
import com.keymessage.core.api.KeyMessageCore
import com.keymessage.core.model.IdentityId
import com.keymessage.core.model.Message
import com.keymessage.core.model.MessageState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class ConversationRepository(
    private val core: KeyMessageCore,
    private val messageDao: MessageDao,
    private val localIdentity: IdentityId
) {
    fun observeConversation(peerId: IdentityId): Flow<List<Message>> {
        return messageDao.observeConversation(localIdentity.value, peerId.value)
            .map { entities -> entities.map { it.toMessage() } }
    }

    suspend fun sendMessage(to: IdentityId, payload: ByteArray): Result<Unit> {
        val message = core.createMessage(to, payload).getOrElse { return Result.failure(it) }
        val result = core.sendMessage(message)
        return if (result.isSuccess) Result.success(Unit)
        else Result.failure(Exception("Send failed: ${result.getOrNull()}"))
    }
}
