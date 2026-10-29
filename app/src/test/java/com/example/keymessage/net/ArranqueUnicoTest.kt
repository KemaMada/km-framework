package com.example.keymessage.net

import java.util.concurrent.CountDownLatch
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * CRITICAL #1 — El candado del arranque no deja abrir DOS conexiones a la vez.
 *
 * ## QUE MIDE ESTE TEST
 *
 * La carrera real: `KeyMessageService.onCreate` y `MainActivity.onCreate`
 * llamaban a `arrancar()` los dos, en hilos distintos, con el guard
 * `if (stack != null) return true` NO atomico. Aqui se reproduce con N hilos
 * que llegan a la barrera a la vez y se afirma que el cuerpo de conexion corre
 * **una sola vez** y que todos reciben el mismo resultado.
 *
 * ## CONTROL NEGATIVO
 *
 * `un candado ROTO deja pasar N intentos`: el mismo arnes sobre un guard no
 * atomico (el `if` + `return` que habia antes) ejecuta el cuerpo N veces. Sin
 * esa mitad, "corre una vez" no probaria nada — podria ser que el test no
 * consiguiera concurrencia.
 */
class ArranqueUnicoTest {

    @Test
    fun `N hilos que arrancan a la vez producen UN solo intento`() {
        val arranque = ArranqueUnico()
        val hilos = 8
        val barrera = CyclicBarrier(hilos)
        val intentos = AtomicInteger(0)
        val listos = CountDownLatch(hilos)
        val pool = Executors.newFixedThreadPool(hilos)

        try {
            // Cuerpo de conexion LENTO a proposito: si el candado no coalesce, la
            // ventana de carrera es ancha y el contador sube solo.
            val resultados = (0 until hilos).map {
                pool.submit<Boolean> {
                    barrera.await()
                    val r = arranque.unaVez {
                        intentos.incrementAndGet()
                        Thread.sleep(50)
                        true
                    }
                    listos.countDown()
                    r
                }
            }

            assertTrue(listos.await(10, TimeUnit.SECONDS), "todos los hilos terminaron")
            assertEquals(1, intentos.get(), "8 llamantes concurrentes abren UNA conexion, no 8")
            assertTrue(resultados.all { it.get() }, "y todos reciben el mismo exito")
        } finally {
            pool.shutdownNow()
        }
    }

    @Test
    fun `control un guard NO atomico deja pasar los ocho intentos`() {
        val hilos = 8
        val barrera = CyclicBarrier(hilos)
        val intentos = AtomicInteger(0)
        val listos = CountDownLatch(hilos)
        val pool = Executors.newFixedThreadPool(hilos)
        // El guard que habia antes: un `if` seguido de trabajo. Dos hilos leen el
        // estado a la vez, los dos pasan. Es el CRITICAL #1 en miniatura.
        var stackNoAtomico: Int? = null

        try {
            (0 until hilos).map {
                pool.submit {
                    barrera.await()
                    if (stackNoAtomico == null) {
                        Thread.sleep(20)
                        intentos.incrementAndGet()
                        stackNoAtomico = 1
                    }
                    listos.countDown()
                }
            }

            assertTrue(listos.await(10, TimeUnit.SECONDS), "todos los hilos terminaron")
            assertTrue(
                intentos.get() > 1,
                "el guard no atomico SI deja pasar varios intentos (${intentos.get()}): " +
                    "sin esto, el test de arriba no probaria que el candado hace algo",
            )
        } finally {
            pool.shutdownNow()
        }
    }

    @Test
    fun `un fallo NO cierra el candado y el siguiente intento vuelve a probar`() {
        val arranque = ArranqueUnico()
        val intentos = AtomicInteger(0)

        val primero = arranque.unaVez {
            intentos.incrementAndGet()
            false
        }
        val segundo = arranque.unaVez {
            intentos.incrementAndGet()
            true
        }

        assertFalse(primero, "el primer intento fallo")
        assertTrue(segundo, "y el segundo pudo reintentar: un candado que se cierra con un fallo deja la app sin reconectar nunca")
        assertEquals(2, intentos.get(), "el cuerpo corrio las dos veces, no una cacheada")
    }

    @Test
    fun `un exito SI cierra el candado y no se vuelve a intentar`() {
        val arranque = ArranqueUnico()
        val intentos = AtomicInteger(0)

        repeat(3) {
            arranque.unaVez {
                intentos.incrementAndGet()
                true
            }
        }

        assertEquals(1, intentos.get(), "tras conectar, tres llamadas mas no abren tres motores")
        assertTrue(arranque.yaConectado(), "y el candado lo dice")
    }

    @Test
    fun `reiniciar reabre el candado para que parar permita reconectar`() {
        val arranque = ArranqueUnico()
        val intentos = AtomicInteger(0)

        arranque.unaVez { intentos.incrementAndGet(); true }
        arranque.reiniciar()
        assertFalse(arranque.yaConectado(), "tras reiniciar el candado esta abierto")
        arranque.unaVez { intentos.incrementAndGet(); true }

        assertEquals(2, intentos.get(), "y una conexion nueva vuelve a intentarse")
    }
}
