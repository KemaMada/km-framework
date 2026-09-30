package com.km.storage

import com.km.model.IdentityId
import com.km.model.MessageId
import com.km.model.StoredMessage

class InMemoryRelayStore(
    private val maxStoredMessages: Int = Int.MAX_VALUE,
    private val maxStorageBytes: Long = Long.MAX_VALUE,
    private val now: () -> Long = System::currentTimeMillis
) : RelayStore {

    private val messages = mutableMapOf<MessageId, StoredMessage>()
    private var expiredCount: Long = 0

    override fun store(message: StoredMessage): Result<Unit> = runCatching {
        require(!message.isExpired(now())) { "StoredMessage already expired at ${message.expiresAt}" }
        if (messages.size >= maxStoredMessages) {
            throw IllegalStateException("Relay cache at capacity: $maxStoredMessages messages")
        }
        if (!messages.containsKey(message.messageId) && wouldExceedStorage(message)) {
            throw IllegalStateException("Relay cache at capacity: $maxStorageBytes bytes")
        }
        messages[message.messageId] = message
    }

    override fun get(messageId: MessageId): Result<StoredMessage?> = runCatching {
        messages[messageId]
    }

    override fun getForRecipient(recipientId: IdentityId): Result<List<StoredMessage>> = runCatching {
        messages.values
            .filter { it.recipientId == recipientId }
            .sortedBy { it.storedAt }
    }

    override fun exists(messageId: MessageId): Result<Boolean> = runCatching {
        messages.containsKey(messageId)
    }

    override fun delete(messageId: MessageId): Result<Unit> = runCatching {
        messages.remove(messageId)
    }

    override fun listExpired(now: Long): Result<List<StoredMessage>> = runCatching {
        messages.values.filter { it.isExpired(now) }.sortedBy { it.storedAt }
    }

    override fun expire(now: Long): Result<Int> = runCatching {
        val toRemove = messages.values.filter { it.isExpired(now) }.map { it.messageId }
        toRemove.forEach { messages.remove(it) }
        expiredCount += toRemove.size
        toRemove.size
    }

    override fun stats(): Result<RelayStoreStats> = runCatching {
        RelayStoreStats(
            storedCount = messages.size,
            totalBytes = messages.values.sumOf { it.wireMessage.size.toLong() },
            expiredCount = expiredCount
        )
    }

    private fun wouldExceedStorage(candidate: StoredMessage): Boolean {
        val currentBytes = messages.values.sumOf { it.wireMessage.size.toLong() }
        return currentBytes + candidate.wireMessage.size > maxStorageBytes
    }
}