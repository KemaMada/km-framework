package com.keymessage.core.storage

import com.keymessage.core.model.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.util.UUID

class InMemoryStorageTest {
    private val store = InMemoryMessageStore()
    private val queue = InMemoryOfflineQueue()
    private val duplicateStore = InMemoryDuplicateStore()
    private val alice = IdentityId("alice")
    private val bob = IdentityId("bob")

    @Test
    fun `insert and retrieve message`() {
        val msg = message(alice, bob)
        store.insert(msg).getOrThrow()
        val retrieved = store.get(msg.messageId).getOrThrow()
        assertNotNull(retrieved)
        assertEquals(msg.messageId, retrieved!!.messageId)
    }

    @Test
    fun `update message state`() {
        val msg = message(alice, bob)
        store.insert(msg).getOrThrow()
        store.updateState(msg.messageId, MessageState.SENT, null).getOrThrow()
        val updated = store.get(msg.messageId).getOrThrow()
        assertEquals(MessageState.SENT, updated!!.state)
    }

    @Test
    fun `list conversation messages`() {
        store.insert(message(alice, bob, "msg1", 1))
        store.insert(message(bob, alice, "msg2", 2))
        store.insert(message(alice, bob, "msg3", 3))
        val msgs = store.listByConversation(alice, bob).getOrThrow()
        assertEquals(3, msgs.size)
    }

    @Test
    fun `offline queue enqueue and dequeue`() {
        val msg = message(alice, bob)
        queue.enqueue(msg).getOrThrow()
        val peeked = queue.peekAll().getOrThrow()
        assertEquals(1, peeked.size)
        val dequeued = queue.dequeue(bob).getOrThrow()
        assertNotNull(dequeued)
        assertEquals(msg.messageId, dequeued!!.messageId)
        assertNull(queue.dequeue(bob).getOrThrow())
    }

    @Test
    fun `duplicate detection`() {
        val msgId = MessageId(UUID.randomUUID())
        assertFalse(duplicateStore.isDuplicate(msgId, alice))
        duplicateStore.mark(msgId, alice)
        assertTrue(duplicateStore.isDuplicate(msgId, alice))
        assertFalse(duplicateStore.isDuplicate(msgId, bob))
    }

    private fun message(from: IdentityId, to: IdentityId, payload: String = "hello", ts: Long = System.currentTimeMillis()): Message {
        return Message(
            messageId = MessageId(UUID.randomUUID()),
            from = from,
            to = to,
            payload = payload.toByteArray(),
            timestamp = ts
        )
    }
}
