package com.km.storage

import com.km.model.IdentityId
import com.km.model.MessageId
import com.km.model.StoredMessage

data class RelayStoreStats(
    val storedCount: Int,
    val totalBytes: Long,
    val expiredCount: Long = 0
)

interface RelayStore {
    fun store(message: StoredMessage): Result<Unit>
    fun get(messageId: MessageId): Result<StoredMessage?>
    fun getForRecipient(recipientId: IdentityId): Result<List<StoredMessage>>
    fun exists(messageId: MessageId): Result<Boolean>
    fun delete(messageId: MessageId): Result<Unit>
    fun listExpired(now: Long): Result<List<StoredMessage>>
    fun expire(now: Long): Result<Int>
    fun stats(): Result<RelayStoreStats>
}