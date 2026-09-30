package com.keymessage.core.storage

import com.keymessage.core.model.IdentityId
import com.keymessage.core.model.MessageId

class InMemoryDuplicateStore : DuplicateStore {
    private val seen = mutableSetOf<Pair<MessageId, IdentityId>>()

    override fun isDuplicate(messageId: MessageId, from: IdentityId): Boolean {
        return (messageId to from) in seen
    }

    override fun mark(messageId: MessageId, from: IdentityId) {
        seen.add(messageId to from)
    }

    override fun cleanup(beforeTimestamp: Long): Result<Int> = runCatching {
        seen.size.also { seen.clear() }
    }
}
