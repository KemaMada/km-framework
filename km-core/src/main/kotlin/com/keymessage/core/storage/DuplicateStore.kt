package com.keymessage.core.storage

import com.keymessage.core.model.IdentityId
import com.keymessage.core.model.MessageId

interface DuplicateStore {
    fun isDuplicate(messageId: MessageId, from: IdentityId): Boolean
    fun mark(messageId: MessageId, from: IdentityId)
    fun cleanup(beforeTimestamp: Long): Result<Int>
}
