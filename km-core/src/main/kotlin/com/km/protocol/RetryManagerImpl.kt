package com.km.protocol

import com.km.model.MessageId
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

class RetryManagerImpl(
    private val maxRetryCount: Int = 5,
    private val initialDelayMs: Long = 5000,
    private val backoffFactor: Double = 2.0
) : RetryManager {

    private val retryCounts = ConcurrentHashMap<MessageId, Int>()
    private val scheduledTasks = ConcurrentHashMap<MessageId, ScheduledFuture<*>>()
    private val scheduler = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "km-retry-scheduler").also { it.isDaemon = true }
    }
    private val retryHandlers = mutableListOf<(MessageId, Int) -> Unit>()

    override fun scheduleRetry(messageId: MessageId, attempt: Int): Result<Unit> = runCatching {
        if (attempt > maxRetryCount) {
            cancelRetry(messageId)
            return@runCatching
        }
        val delay = backoffDelay(attempt)
        val future = scheduler.schedule({
            retryHandlers.forEach { it(messageId, attempt) }
        }, delay, TimeUnit.MILLISECONDS)
        scheduledTasks[messageId] = future
        retryCounts[messageId] = attempt
    }

    override fun cancelRetry(messageId: MessageId): Result<Unit> = runCatching {
        scheduledTasks.remove(messageId)?.cancel(false)
        retryCounts.remove(messageId)
    }

    override fun maxRetries(): Int = maxRetryCount

    override fun backoffDelay(attempt: Int): Long {
        return (initialDelayMs * Math.pow(backoffFactor, (attempt - 1).toDouble())).toLong()
    }

    fun onRetry(handler: (MessageId, Int) -> Unit) {
        retryHandlers.add(handler)
    }

    fun shutdown() {
        scheduler.shutdown()
    }
}
