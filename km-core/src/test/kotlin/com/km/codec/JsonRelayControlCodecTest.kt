package com.km.codec

import com.km.model.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.util.UUID

class JsonRelayControlCodecTest {

    private val codec = JsonRelayControlCodec()

    @Test
    fun `stored receipt roundtrip`() {
        val receipt = StoredReceipt(
            messageId = MessageId(UUID.randomUUID()),
            timestamp = 1721827200500L,
            originalMessageId = MessageId(UUID.randomUUID()),
            to = IdentityId("bob-identity"),
            expiresAt = 1722432000000L,
            relayNodeId = IdentityId("relay-node")
        )
        val encoded = codec.encodeStored(receipt).getOrThrow()
        val decoded = codec.decodeStored(encoded).getOrThrow()
        assertEquals(receipt, decoded)
    }

    @Test
    fun `relay expired notice roundtrip`() {
        val notice = RelayExpiredNotice(
            messageId = MessageId(UUID.randomUUID()),
            timestamp = 1722432000000L,
            originalMessageId = MessageId(UUID.randomUUID()),
            to = IdentityId("alice-identity"),
            reason = "TTL_EXPIRED"
        )
        val encoded = codec.encodeRelayExpired(notice).getOrThrow()
        val decoded = codec.decodeRelayExpired(encoded).getOrThrow()
        assertEquals(notice, decoded)
    }

    @Test
    fun `relay error notice roundtrip with optional fields`() {
        val error = RelayErrorNotice(
            messageId = MessageId(UUID.randomUUID()),
            timestamp = 1721827201000L,
            errorCode = RelayErrorCode.STORAGE_FULL,
            errorMessage = "Relay cache at capacity",
            originalMessageId = MessageId(UUID.randomUUID())
        )
        val encoded = codec.encodeError(error).getOrThrow()
        val decoded = codec.decodeError(encoded).getOrThrow()
        assertEquals(error, decoded)
    }

    @Test
    fun `relay error notice roundtrip without optional fields`() {
        val error = RelayErrorNotice(
            messageId = MessageId(UUID.randomUUID()),
            timestamp = 1721827201000L,
            errorCode = RelayErrorCode.TTL_EXPIRED
        )
        val encoded = codec.encodeError(error).getOrThrow()
        val decoded = codec.decodeError(encoded).getOrThrow()
        assertEquals(error, decoded)
    }

    @Test
    fun `decodeStored includes expected type field`() {
        val receipt = StoredReceipt(
            messageId = MessageId(UUID.randomUUID()),
            timestamp = 1721827200500L,
            originalMessageId = MessageId(UUID.randomUUID()),
            to = IdentityId("bob-identity"),
            expiresAt = 1722432000000L,
            relayNodeId = IdentityId("relay-node")
        )
        val text = String(codec.encodeStored(receipt).getOrThrow())
        assertTrue(text.contains("\"type\":\"STORED\""))
    }

    @Test
    fun `decode invalid json fails`() {
        assertTrue(codec.decodeStored("invalid json".toByteArray()).isFailure)
        assertTrue(codec.decodeRelayExpired("invalid json".toByteArray()).isFailure)
        assertTrue(codec.decodeError("invalid json".toByteArray()).isFailure)
    }
}
