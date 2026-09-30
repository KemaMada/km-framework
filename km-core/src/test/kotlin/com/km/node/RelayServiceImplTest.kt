package com.km.node

import com.km.model.*
import com.km.storage.InMemoryRelayStore
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.util.UUID

class RelayServiceImplTest {

    private val alice = IdentityId("alice-identity")
    private val bob = IdentityId("bob-identity")

    private fun message(
        from: IdentityId = alice,
        to: IdentityId = bob,
        payload: ByteArray = "hello".toByteArray()
    ) = Message(
        messageId = MessageId(UUID.randomUUID()),
        from = from,
        to = to,
        payload = payload,
        timestamp = 1000L
    )

    private fun ack(originalMessageId: MessageId, status: AckStatus = AckStatus.DELIVERED) = Ack(
        from = bob,
        to = alice,
        originalMessageId = originalMessageId,
        status = status,
        timestamp = 2000L,
        signature = ByteArray(64)
    )

    private fun service(
        store: InMemoryRelayStore = InMemoryRelayStore(now = { 0L }),
        limits: RelayLimits = RelayLimits(),
        now: () -> Long = { 0L }
    ) = RelayServiceImpl(relayStore = store, limits = limits, now = now)

    @Test
    fun `returns Forwarded when recipient online without storing`() {
        val store = InMemoryRelayStore(now = { 0L })
        val service = service(store, now = { 0L })
        val msg = message()
        val decision = service.handleMessage(msg, recipientOnline = true).getOrThrow()
        assertEquals(RelayDecision.Forwarded(msg), decision)
        assertEquals(0, store.stats().getOrThrow().storedCount)
    }

    @Test
    fun `stores when recipient offline and returns receipt`() {
        val store = InMemoryRelayStore(now = { 0L })
        val service = RelayServiceImpl(relayStore = store, now = { 0L })
        val msg = message()
        val decision = service.handleMessage(msg, recipientOnline = false).getOrThrow()
        assertTrue(decision is RelayDecision.Stored)
        val stored = (decision as RelayDecision.Stored).stored
        assertEquals(msg.messageId, stored.messageId)
        assertEquals(msg.from, stored.senderId)
        assertEquals(msg.to, stored.recipientId)
        assertEquals(0L, stored.storedAt)
        assertEquals(0L + RelayLimits().maxMessageAgeMs, stored.expiresAt)
        assertTrue(store.exists(msg.messageId).getOrThrow())
    }

    @Test
    fun `rejects message over max size`() {
        val service = service(limits = RelayLimits(maxMessageSize = 10))
        val decision = service.handleMessage(message(payload = ByteArray(11)), recipientOnline = false).getOrThrow()
        assertEquals(RelayDecision.Rejected(FailureCode.MESSAGE_TOO_LARGE), decision)
    }

    @Test
    fun `rejects self addressed message`() {
        val service = service()
        val decision = service.handleMessage(message(from = bob, to = bob), recipientOnline = false).getOrThrow()
        assertEquals(RelayDecision.Rejected(FailureCode.INVALID_MESSAGE), decision)
    }

    @Test
    fun `returns Duplicate for already stored message`() {
        val service = service()
        val msg = message()
        service.handleMessage(msg, recipientOnline = false).getOrThrow()
        val decision = service.handleMessage(msg, recipientOnline = false).getOrThrow()
        assertEquals(RelayDecision.Duplicate, decision)
        assertEquals(1, service.relayStore.stats().getOrThrow().storedCount)
    }

    @Test
    fun `handleAck deletes stored copy only for DELIVERED and forwards ack`() {
        val store = InMemoryRelayStore(now = { 0L })
        val service = service(store, now = { 0L })
        var forwarded: Ack? = null
        service.registerAckForwardedHandler { forwarded = it }
        val msg = message()
        service.handleMessage(msg, recipientOnline = false).getOrThrow()
        assertTrue(store.exists(msg.messageId).getOrThrow())

        val a = ack(msg.messageId, AckStatus.DELIVERED)
        service.handleAck(a).getOrThrow()
        assertFalse(store.exists(msg.messageId).getOrThrow())
        assertEquals(a, forwarded)
    }

    @Test
    fun `handleAck with FAILED status keeps stored copy`() {
        val store = InMemoryRelayStore(now = { 0L })
        val service = service(store, now = { 0L })
        val msg = message()
        service.handleMessage(msg, recipientOnline = false).getOrThrow()

        service.handleAck(ack(msg.messageId, AckStatus.FAILED)).getOrThrow()
        assertTrue(store.exists(msg.messageId).getOrThrow())
    }

    @Test
    fun `expire returns removed messages`() {
        var clock = 1000L
        val store = InMemoryRelayStore(now = { clock })
        val service = RelayServiceImpl(
            relayStore = store,
            limits = RelayLimits(maxMessageAgeMs = 1000L),
            now = { clock }
        )
        val expired = message()
        service.handleMessage(expired, recipientOnline = false).getOrThrow()

        clock = 2000L
        val alive = message(payload = "alive".toByteArray())
        service.handleMessage(alive, recipientOnline = false).getOrThrow()

        val removed = service.expire(2500L).getOrThrow()
        assertEquals(listOf(expired.messageId), removed.map { it.messageId })
        assertFalse(store.exists(expired.messageId).getOrThrow())
        assertTrue(store.exists(alive.messageId).getOrThrow())
    }

    @Test
    fun `stored handler is invoked on store`() {
        val service = service()
        var storedMsg: StoredMessage? = null
        service.registerStoredHandler { storedMsg = it }
        val msg = message()
        service.handleMessage(msg, recipientOnline = false).getOrThrow()
        assertEquals(msg.messageId, storedMsg?.messageId)
    }
}
