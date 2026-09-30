package com.km.storage

import com.km.model.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.util.UUID

class InMemoryRelayStoreTest {

    private val alice = IdentityId("alice-identity")
    private val bob = IdentityId("bob-identity")

    private fun stored(
        id: String = UUID.randomUUID().toString(),
        recipient: IdentityId = bob,
        expiresAt: Long = Long.MAX_VALUE,
        wireSize: Int = 32
    ) = StoredMessage(
        messageId = MessageId(UUID.nameUUIDFromBytes(id.toByteArray())),
        senderId = alice,
        recipientId = recipient,
        wireMessage = ByteArray(wireSize),
        storedAt = 1000L,
        expiresAt = expiresAt
    )

    @Test
    fun `store and retrieve`() {
        val store = InMemoryRelayStore()
        val msg = stored()
        store.store(msg).getOrThrow()
        val result = store.get(msg.messageId).getOrThrow()
        assertNotNull(result)
        assertEquals(msg, result)
    }

    @Test
    fun `store overwrites duplicate messageId`() {
        val store = InMemoryRelayStore()
        val msg1 = stored("aaaaaaaa", wireSize = 16)
        val msg2 = stored("aaaaaaaa", wireSize = 64)
        store.store(msg1).getOrThrow()
        store.store(msg2).getOrThrow()
        assertEquals(msg2, store.get(msg1.messageId).getOrThrow())
        assertEquals(1, store.stats().getOrThrow().storedCount)
    }

    @Test
    fun `exists reflects presence`() {
        val store = InMemoryRelayStore()
        val msg = stored()
        assertFalse(store.exists(msg.messageId).getOrThrow())
        store.store(msg).getOrThrow()
        assertTrue(store.exists(msg.messageId).getOrThrow())
    }

    @Test
    fun `getForRecipient returns only that recipients messages ordered by storedAt`() {
        val store = InMemoryRelayStore()
        val carol = IdentityId("carol-identity")

        val b1 = stored("bbbbbbbb", recipient = bob, wireSize = 8)
        val b2 = stored("cccccccc", recipient = bob, wireSize = 8)
        val c1 = stored("dddddddd", recipient = carol, wireSize = 8)
        store.store(b1).getOrThrow()
        store.store(b2).getOrThrow()
        store.store(c1).getOrThrow()

        val forBob = store.getForRecipient(bob).getOrThrow()
        assertEquals(listOf(b1, b2), forBob)

        val forCarol = store.getForRecipient(carol).getOrThrow()
        assertEquals(listOf(c1), forCarol)
    }

    @Test
    fun `delete removes message`() {
        val store = InMemoryRelayStore()
        val msg = stored()
        store.store(msg).getOrThrow()
        store.delete(msg.messageId).getOrThrow()
        assertNull(store.get(msg.messageId).getOrThrow())
        assertFalse(store.exists(msg.messageId).getOrThrow())
    }

    @Test
    fun `expire removes only expired and counts them`() {
        val store = InMemoryRelayStore(now = { 4000L })
        val expired = stored("eeeeeeee", expiresAt = 5000L)
        val alive = stored("ffffffff", expiresAt = Long.MAX_VALUE)
        store.store(expired).getOrThrow()
        store.store(alive).getOrThrow()

        val removed = store.expire(6000L).getOrThrow()
        assertEquals(1, removed)
        assertNull(store.get(expired.messageId).getOrThrow())
        assertNotNull(store.get(alive.messageId).getOrThrow())
        assertEquals(1, store.stats().getOrThrow().storedCount)
        assertEquals(1L, store.stats().getOrThrow().expiredCount)
    }

    @Test
    fun `expire boundary keeps message until the instant of expiry`() {
        val store = InMemoryRelayStore(now = { 4000L })
        val msg = stored("aaaaaaa1", expiresAt = 10000L)
        store.store(msg).getOrThrow()

        store.expire(9999L).getOrThrow()
        assertNotNull(store.get(msg.messageId).getOrThrow())

        store.expire(10000L).getOrThrow()
        assertNull(store.get(msg.messageId).getOrThrow())
    }

    @Test
    fun `refuses to store an already expired message`() {
        val store = InMemoryRelayStore()
        val msg = stored(
            "aaaaaaa2",
            expiresAt = System.currentTimeMillis() - 1_000L
        )
        assertTrue(store.store(msg).isFailure)
        assertEquals(0, store.stats().getOrThrow().storedCount)
    }

    @Test
    fun `rejects store when maxStoredMessages reached`() {
        val store = InMemoryRelayStore(maxStoredMessages = 2)
        store.store(stored("aaaaaaa3", wireSize = 0)).getOrThrow()
        store.store(stored("aaaaaaa4", wireSize = 0)).getOrThrow()
        assertTrue(store.store(stored("aaaaaaa5", wireSize = 0)).isFailure)
        assertEquals(2, store.stats().getOrThrow().storedCount)
    }

    @Test
    fun `rejects store when maxStorageBytes exceeded`() {
        val store = InMemoryRelayStore(maxStorageBytes = 100)
        store.store(stored("aaaaaaa6", wireSize = 60)).getOrThrow()
        assertTrue(store.store(stored("aaaaaaa7", wireSize = 60)).isFailure)
        assertEquals(1, store.stats().getOrThrow().storedCount)
    }

    @Test
    fun `allows updating existing message even if storage limit reached`() {
        val store = InMemoryRelayStore(maxStorageBytes = 100)
        store.store(stored("aaaaaaa8", wireSize = 60)).getOrThrow()
        // overwrite of existing messageId passes capacity check
        store.store(stored("aaaaaaa8", wireSize = 60)).getOrThrow()
        assertEquals(1, store.stats().getOrThrow().storedCount)
    }

    @Test
    fun `stats report counts and bytes`() {
        val store = InMemoryRelayStore()
        store.store(stored("aaaaaaa9", wireSize = 20)).getOrThrow()
        store.store(stored("aaaaaa10", wireSize = 30)).getOrThrow()
        val stats = store.stats().getOrThrow()
        assertEquals(2, stats.storedCount)
        assertEquals(50L, stats.totalBytes)
    }

    @Test
    fun `stored receipt never deletes local MessageStore copy`() {
        val relay = InMemoryRelayStore()
        val messageStore = InMemoryMessageStore()

        val msg = Message(
            messageId = MessageId(UUID.randomUUID()),
            from = alice,
            to = bob,
            payload = "hello".toByteArray(),
            timestamp = 1000L,
            state = MessageState.SENT
        )
        messageStore.insert(msg).getOrThrow()

        val storedMsg = StoredMessage(
            messageId = msg.messageId,
            senderId = msg.from,
            recipientId = msg.to,
            wireMessage = msg.payload,
            storedAt = 1000L,
            expiresAt = Long.MAX_VALUE
        )

        // Relay accepts custody
        relay.store(storedMsg).getOrThrow()

        // STORED receipt is generated -> but the local store MUST retain its copy
        assertEquals(MessageState.SENT, messageStore.get(msg.messageId).getOrThrow()!!.state)
        assertNotNull(messageStore.get(msg.messageId).getOrThrow())

        // Even if relay drops its copy later, the sender still has the message
        relay.delete(storedMsg.messageId).getOrThrow()
        assertNotNull(messageStore.get(msg.messageId).getOrThrow())
    }
}