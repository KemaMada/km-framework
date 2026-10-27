package com.km.protocol

/**
 * D12 — scheduler de PRUEBA: el tiempo se avanza a mano, nadie duerme.
 *
 * `DaemonRetryScheduler` es el de produccion; este existe para que el test de
 * orden recorra el backoff REAL (5 s, x2, 5 intentos, techo 155 s) sin esperar
 * 155 segundos de reloj. Un test que duerme es un test que alguien desactiva en
 * la primera CI lenta, y entonces la garantia de orden deja de estar pinzada.
 *
 * ## CONTRATO (el mismo que el real, y con dos detalles que importan)
 *
 * - Las tareas vencen en orden de `(vencimiento, orden de programacion)`. Dos
 *   tareas al mismo instante se ejecutan en el orden en que se pidieron: el
 *   scheduler real de un solo hilo hace lo mismo.
 * - Una tarea CANCELADA no se ejecuta nunca, y una tarea que se programa
 *   DENTRO de otra se mide desde el instante que se esta ejecutando, no desde
 *   el final del avance.
 * - `advanceBy` procesa tambien lo que vence exactamente en el instante
 *   limite: `advanceBy(155_000)` incluye el vencimiento de los 155 s.
 */
class ManualRetryScheduler : RetryScheduler {

    private class Task(
        val at: Long,
        val seq: Long,
        val run: () -> Unit,
    ) {
        var cancelled: Boolean = false
    }

    private var now: Long = 0L
    private var nextSeq: Long = 0L
    private val tasks = mutableListOf<Task>()

    override fun schedule(delayMs: Long, task: () -> Unit): RetryHandle {
        val scheduled = Task(at = now + delayMs, seq = nextSeq++, run = task)
        tasks.add(scheduled)
        return RetryHandle { scheduled.cancelled = true }
    }

    /**
     * Avanza el reloj [ms] y ejecuta lo que vence en el camino.
     */
    fun advanceBy(ms: Long) {
        require(ms >= 0) { "el tiempo no va hacia atras" }
        val target = now + ms
        while (true) {
            val next = tasks
                .filter { !it.cancelled && it.at <= target }
                .minWithOrNull(compareBy({ it.at }, { it.seq }))
                ?: break
            tasks.remove(next)
            now = next.at
            next.run()
        }
        now = target
    }

    /** Instante actual del reloj simulado. */
    val currentTimeMs: Long get() = now

    /** Tareas vivas. Observabilidad para el test. */
    val pendingCount: Int get() = tasks.count { !it.cancelled }
}
