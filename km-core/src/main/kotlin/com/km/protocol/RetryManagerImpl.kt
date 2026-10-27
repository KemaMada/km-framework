package com.km.protocol

import com.km.model.MessageId
import java.util.concurrent.ConcurrentHashMap

/**
 * Backoff del reintento: 5 s, x2, 5 intentos. NO cambia por este cambio.
 *
 * ## LO QUE SI CAMBIA
 *
 * 1. El temporizador es INYECTABLE ([RetryScheduler], D12): produccion usa
 *    [DaemonRetryScheduler] (mismo hilo daemon de siempre) y el test de orden
 *    usa uno manual, para recorrer los 155 s de techo sin dormir.
 * 2. `scheduleRetry` CANCELA el futuro anterior del mismo mensaje antes de
 *    programar el nuevo. Antes lo sobrescribia en el mapa sin cancelarlo, de
 *    modo que un doble `scheduleRetry` dejaba DOS tareas vivas y el mensaje se
 *    reintentaba dos veces por vencimiento. Con la puerta de cabecera eso ya no
 *    podia adelantar a nadie, pero seguia siendo trafico duplicado.
 *
 * El resto —cuenta de intentos, backoff, maximo— es el de antes: la ventana
 * residual de `network-change-renegotiation` se calculo con estos numeros.
 */
class RetryManagerImpl(
    private val maxRetryCount: Int = 5,
    private val initialDelayMs: Long = 5000,
    private val backoffFactor: Double = 2.0,
    private val scheduler: RetryScheduler = DaemonRetryScheduler(),
) : RetryManager {

    private val retryCounts = ConcurrentHashMap<MessageId, Int>()
    private val handles = ConcurrentHashMap<MessageId, RetryHandle>()
    private val retryHandlers = mutableListOf<(MessageId, Int) -> Unit>()

    override fun scheduleRetry(messageId: MessageId, attempt: Int): Result<Unit> = runCatching {
        if (attempt > maxRetryCount) {
            cancelRetry(messageId)
            return@runCatching
        }
        // Una sola tarea viva por mensaje: la anterior se cancela ANTES de
        // programar, no se deja huerfana en el mapa.
        cancelRetry(messageId)
        val delay = backoffDelay(attempt)
        val handle = scheduler.schedule(delay) {
            retryHandlers.forEach { it(messageId, attempt) }
        }
        handles[messageId] = handle
        retryCounts[messageId] = attempt
    }

    override fun cancelRetry(messageId: MessageId): Result<Unit> = runCatching {
        handles.remove(messageId)?.cancel()
        retryCounts.remove(messageId)
    }

    override fun maxRetries(): Int = maxRetryCount

    override fun backoffDelay(attempt: Int): Long {
        return (initialDelayMs * Math.pow(backoffFactor, (attempt - 1).toDouble())).toLong()
    }

    fun onRetry(handler: (MessageId, Int) -> Unit) {
        retryHandlers.add(handler)
    }

    /** Intentos programados ahora mismo. Observabilidad y tests. */
    internal fun scheduledCount(): Int = handles.size

    fun shutdown() {
        scheduler.shutdown()
    }
}
