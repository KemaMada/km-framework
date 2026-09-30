package com.km.storage

import com.km.model.IdentityId
import com.km.model.Message
import com.km.model.MessageId

interface OfflineQueue {
    fun enqueue(message: Message): Result<Unit>
    fun dequeue(peerId: IdentityId): Result<Message?>
    fun peekAll(): Result<List<Message>>
    fun remove(messageId: MessageId): Result<Unit>
    fun expire(beforeTimestamp: Long): Result<Int>
}
