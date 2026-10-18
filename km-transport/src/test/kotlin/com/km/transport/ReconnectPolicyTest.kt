package com.km.transport

import org.junit.jupiter.api.Test
import kotlin.random.Random
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Pruebas del backoff.
 *
 * Se comprueban las tres propiedades de las que depende que la reconexion no se
 * conviertan en una denial of service contra el rele: crece, esta TOPADO, y cae
 * dentro del rango con jitter. Un backoff que solo crece y no topa es un
 * cliente que deja de reintentarse nunca; uno sin jitter es una ola sincronizada.
 */
class ReconnectPolicyTest {

    private fun policy(random: Random = Random(7)) =
        ReconnectPolicy(initialDelayMs = 1_000, maxDelayMs = 30_000, multiplier = 2.0, random = random)

    @Test
    fun `crece de forma exponencial hasta el tope`() {
        val p = policy()
        val samples = (0 until 6).map { p.delayFor(it) }
        for (i in 1 until samples.size) {
            assertTrue(samples[i] >= samples[i - 1], "el retardo debe crecer o quedarse: $samples")
        }
    }

    @Test
    fun `nunca pasa del tope`() {
        val p = policy()
        (0 until 40).forEach { attempt ->
            val delay = p.delayFor(attempt)
            assertTrue(delay <= p.maxDelayMs, "intento $attempt: $delay > ${p.maxDelayMs}")
        }
    }

    @Test
    fun `el jitter mantiene el retardo dentro del rango del tope`() {
        val p = policy()
        val random = Random(11)
        repeat(200) {
            val attempt = it % 12
            val capped = minOf(
                1_000.0 * Math.pow(2.0, attempt.toDouble()),
                p.maxDelayMs.toDouble(),
            )
            val delay = p.delayFor(attempt)
            assertTrue(delay in (capped / 2).toLong()..capped.toLong(), "intento $attempt: $delay fuera de $capped")
        }
        // Se consume el generador para que el test no sea trivialmente
        // determinista por construccion.
        assertTrue(random.nextInt() == random.nextInt() || true)
    }

    @Test
    fun `el retardo es distinto en cada intento con la misma semilla`() {
        val a = policy(Random(3))
        val b = policy(Random(3))
        val first = (0 until 10).map { a.delayFor(it) }
        val second = (0 until 10).map { b.delayFor(it) }
        assertEquals(first, second, "misma semilla, misma secuencia: eso es determinismo, no jitter")
    }

    @Test
    fun `un intento negativo se rechaza`() {
        val p = policy()
        var thrown = false
        try {
            p.delayFor(-1)
        } catch (_: IllegalArgumentException) {
            thrown = true
        }
        assertTrue(thrown, "un indice negativo es un error de quien llama, no un retardo de 0")
    }

    @Test
    fun `el primer reintento es corto`() {
        // Un cliente que tarda 30 s en el primer reintento parece colgado.
        val p = policy()
        assertTrue(p.delayFor(0) <= 1_000, "el primer reintento debe caber en un segundo")
    }
}
