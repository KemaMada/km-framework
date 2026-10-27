package com.km.desktop.support

import com.km.protocol.RetryHandle
import com.km.protocol.RetryScheduler

/**
 * Reloj MANUAL del reintento, LOCAL a `km-desktop`.
 *
 * ## POR QUE NO SE REUTILIZA EL DE `km-core`
 *
 * Porque el suyo vive en `km-core/src/test`, y las clases de test de un modulo
 * no son dependencia de test de otro (no se publican). Una prueba de escritorio
 * que importara `com.km.protocol.ManualRetryScheduler` compilaria solo dentro de
 * km-core. Esta es la misma costura, con el mismo contrato, en el lado que la
 * necesita.
 *
 * ## CONTRATO (identico al de produccion, sin dormir)
 *
 * - Las tareas vencen por `(instante, orden de programacion)`; dos al mismo
 *   instante corren en el orden en que se pidieron, como el hilo unico real.
 * - Una tarea cancelada no corre nunca.
 * - Una tarea programada DENTRO de otra se mide desde el instante que se esta
 *   ejecutando, no desde el final del avance.
 * - El limite es INCLUSIVO: `advanceBy(155_000)` ejecuta lo que vence en
 *   155_000, que es justo el techo de la cabeza.
 *
 * NO cambia backoff ni intentos: eso sigue en `RetryManagerImpl` de km-core
 * (5 s, x2, 5 intentos, techo 155 s).
 */
class DesktopManualRetryScheduler : RetryScheduler {

    private class Task(val at: Long, val seq: Long, val run: () -> Unit) {
        var cancelled: Boolean = false
    }

    private val lock = Any()
    private var now: Long = 0L
    private var nextSeq: Long = 0L
    private val tasks = mutableListOf<Task>()

    override fun schedule(delayMs: Long, task: () -> Unit): RetryHandle = synchronized(lock) {
        val programada = Task(at = now + delayMs, seq = nextSeq++, run = task)
        tasks.add(programada)
        RetryHandle { synchronized(lock) { programada.cancelled = true } }
    }

    /** Avanza el reloj [ms] y ejecuta, en orden, lo que vence por el camino. */
    fun advanceBy(ms: Long) {
        require(ms >= 0) { "el tiempo no va hacia atras" }
        while (true) {
            val siguiente = synchronized(lock) {
                val target = now + ms
                val next = tasks
                    .filter { !it.cancelled && it.at <= target }
                    .minWithOrNull(compareBy({ it.at }, { it.seq }))
                if (next != null) {
                    tasks.remove(next)
                    now = next.at
                } else {
                    // Sin tareas en la ventana: el reloj si avanza; la siguiente
                    // llamada vuelve a medir desde el nuevo instante.
                    now = target
                }
                next
            } ?: return
            siguiente.run()
        }
    }

    /** Tareas vivas, para observabilidad del test. */
    fun pendingCount(): Int = synchronized(lock) { tasks.count { !it.cancelled } }

    override fun shutdown() = Unit
}
