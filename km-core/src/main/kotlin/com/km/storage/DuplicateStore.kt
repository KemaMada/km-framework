package com.km.storage

import com.km.model.IdentityId
import com.km.model.MessageId

interface DuplicateStore {
    fun isDuplicate(messageId: MessageId, from: IdentityId): Boolean
    fun mark(messageId: MessageId, from: IdentityId)
    fun cleanup(beforeTimestamp: Long): Result<Int>
}
