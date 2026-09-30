package com.keymessage.core.storage

import com.keymessage.core.model.IdentityId
import com.keymessage.core.model.Message
import com.keymessage.core.model.MessageId

interface OfflineQueue {
    fun enqueue(message: Message): Result<Unit>
    fun dequeue(peerId: IdentityId): Result<Message?>
    fun peekAll(): Result<List<Message>>
    fun remove(messageId: MessageId): Result<Unit>
    fun expire(beforeTimestamp: Long): Result<Int>
}
