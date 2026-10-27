package com.km.protocol

import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/**
 * Un temporizador cancelable: lo que [RetryScheduler.schedule] devuelve.
 *
 * `fun interface` para que un scheduler de prueba pueda devolver una lambda, y
 * para que cancelar sea UNA operacion y no un protocolo de dos llamadas.
 */
fun interface RetryHandle {
    fun cancel()
}

/**
 * D12 — LA COSTURA DE TIEMPO DEL REINTENTO.
 *
 * Existe por una razon concreta: el test de regresion del orden (sonda G) tiene
 * que recorrer el backoff REAL —5 s, x2, hasta 5 intentos— sin dormir 155
 * segundos. Un test que duerme es un test que se desactiva en la primera CI
 * lenta, y entonces la garantia de orden deja de estar pinzada.
 *
 * Con esta costura, produccion sigue usando [DaemonRetryScheduler] (el mismo
 * `Executors.newSingleThreadScheduledExecutor` de siempre, mismo hilo daemon) y
 * el test inyecta un scheduler que avanza el reloj a mano. El `SenderImpl` no
 * sabe cual de los dos tiene: solo pide "ejecuta esto dentro de N ms".
 *
 * NO cambia ni el backoff ni el numero de intentos: eso sigue en
 * `RetryManagerImpl` (5 s, x2, 5 intentos, techo 155 s).
 */
interface RetryScheduler {

    /**
     * Ejecuta [task] dentro de [delayMs].
     *
     * @return handle para cancelarla. Cancelar una tarea ya ejecutada no es un
     *   error: es una operacion sin efecto, como cancelar un `ScheduledFuture`
     *   terminado.
     */
    fun schedule(delayMs: Long, task: () -> Unit): RetryHandle

    /** Libera recursos. El scheduler por defecto lo usa; el de test no hace nada. */
    fun shutdown() {}
}

/**
 * El scheduler de PRODUCCION: un unico hilo daemon.
 *
 * Un solo hilo es parte del contrato heredado (el orden dentro de un camino se
 * apoyaba en el), y sigue siendolo: las tareas vencen en orden de vencimiento y
 * se ejecutan de una en una. Ahora, ademas, la puerta de cabecera de
 * `PerPeerSendQueue` garantiza el orden aunque hubiera varios hilos; el hilo
 * unico es la red de seguridad, no la garantia.
 */
class DaemonRetryScheduler(
    private val executor: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "km-retry-scheduler").also { it.isDaemon = true }
    },
) : RetryScheduler {

    override fun schedule(delayMs: Long, task: () -> Unit): RetryHandle {
        val future = executor.schedule(task, delayMs, TimeUnit.MILLISECONDS)
        return RetryHandle { future.cancel(false) }
    }

    override fun shutdown() {
        executor.shutdown()
    }
}
