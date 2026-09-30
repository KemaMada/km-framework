package com.example.keymessage.storage.room.impl

import com.example.keymessage.storage.room.dao.DuplicateDao
import com.example.keymessage.storage.room.entity.DuplicateEntity
import com.keymessage.core.model.IdentityId
import com.keymessage.core.model.MessageId
import com.keymessage.core.storage.DuplicateStore
import kotlinx.coroutines.runBlocking

class RoomDuplicateStore(
    private val duplicateDao: DuplicateDao
) : DuplicateStore {

    override fun isDuplicate(messageId: MessageId, from: IdentityId): Boolean = runBlocking {
        runCatching {
            duplicateDao.count(messageId.value.toString(), from.value) > 0
        }.getOrDefault(false)
    }

    override fun mark(messageId: MessageId, from: IdentityId) {
        runBlocking {
            runCatching {
                duplicateDao.insert(
                    DuplicateEntity(
                        messageId = messageId.value.toString(),
                        from = from.value,
                        seenAt = System.currentTimeMillis()
                    )
                )
            }
        }
    }

    override fun cleanup(beforeTimestamp: Long): Result<Int> = runBlocking {
        runCatching {
            duplicateDao.cleanup(beforeTimestamp)
        }
    }
}
