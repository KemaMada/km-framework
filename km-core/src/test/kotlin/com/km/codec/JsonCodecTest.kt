package com.km.codec

import com.km.model.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.util.UUID

class JsonCodecTest {
    private val encoder = JsonEncoder()
    private val decoder = JsonDecoder()

    @Test
    fun `encode and decode message roundtrip`() {
        val original = Message(
            messageId = MessageId(UUID.randomUUID()),
            from = IdentityId("alice"),
            to = IdentityId("bob"),
            payload = "Hello, Bob!".toByteArray(),
            timestamp = 1000L
        )
        val encoded = encoder.encodeMessage(original).getOrThrow()
        val decoded = decoder.decodeMessage(encoded).getOrThrow()
        assertEquals(original.messageId, decoded.messageId)
        assertEquals(original.from, decoded.from)
        assertEquals(original.to, decoded.to)
        assertArrayEquals(original.payload, decoded.payload)
        assertEquals(original.timestamp, decoded.timestamp)
    }

    @Test
    fun `encode and decode ack roundtrip`() {
        val original = Ack(
            from = IdentityId("bob"),
            to = IdentityId("alice"),
            originalMessageId = MessageId(UUID.randomUUID()),
            status = AckStatus.DELIVERED,
            timestamp = 2000L,
            signature = ByteArray(64) { it.toByte() }
        )
        val encoded = encoder.encodeAck(original).getOrThrow()
        val decoded = decoder.decodeAck(encoded).getOrThrow()
        assertEquals(original.from, decoded.from)
        assertEquals(original.to, decoded.to)
        assertEquals(original.originalMessageId, decoded.originalMessageId)
        assertEquals(original.status, decoded.status)
        assertEquals(original.timestamp, decoded.timestamp)
        assertArrayEquals(original.signature, decoded.signature)
    }

    @Test
    fun `decode invalid json fails`() {
        val result = decoder.decodeMessage("invalid json".toByteArray())
        assertTrue(result.isFailure)
    }
}
