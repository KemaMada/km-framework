package com.keymessage.core.protocol

import com.keymessage.core.model.MessageId
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.util.UUID

class RetryManagerTest {
    private val manager = RetryManagerImpl(maxRetryCount = 3, initialDelayMs = 10)

    @Test
    fun `backoff delay increases exponentially`() {
        assertEquals(10, manager.backoffDelay(1))
        assertEquals(20, manager.backoffDelay(2))
        assertEquals(40, manager.backoffDelay(3))
    }

    @Test
    fun `max retries is configurable`() {
        assertEquals(3, manager.maxRetries())
    }

    @Test
    fun `schedule and cancel retry`() {
        val msgId = MessageId(UUID.randomUUID())
        var called = false
        manager.onRetry { _, _ -> called = true }
        manager.scheduleRetry(msgId, 1).getOrThrow()
        manager.cancelRetry(msgId).getOrThrow()
        assertFalse(called)
    }
}
