package com.km.node

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * A4.1 — Backoff exponencial con jitter +/-20 % (diseno §1).
 *
 * Base: 1s, 2s, 4s, 8s, 16s, 30s y tope en 30s. La muestra de jitter `s` en
 * `[0, 1)` se mapea a un factor `1 + 0.20*(2s-1)` en `[0.8, 1.2]`.
 */
class BackoffTest {

    @Test
    @DisplayName("A4.1-30 con jitter neutro el backoff es exactamente 1,2,4,8,16,30,30")
    fun secuenciaBase() {
        val vistos = (0..7).map { ChainFailover.backoffMs(it, sample = 0.5) }
        assertEquals(listOf(1_000L, 2_000L, 4_000L, 8_000L, 16_000L, 30_000L, 30_000L, 30_000L), vistos)
    }

    @Test
    @DisplayName("A4.1-31 el jitter se mantiene dentro de +/-20 % en cada intento")
    fun jitterAcotado() {
        for (attempt in 0..6) {
            val base = ChainFailover.BACKOFF_MS[minOf(attempt, ChainFailover.BACKOFF_MS.lastIndex)]
            val min = (base * 0.8).toLong()
            val max = (base * 1.2).toLong()
            for (paso in 0..20) {
                val s = paso / 20.0
                val v = ChainFailover.backoffMs(attempt, s)
                assertTrue(v in min..max, "intento $attempt, s=$s -> $v fuera de [$min, $max]")
            }
        }
    }

    @Test
    @DisplayName("A4.1-32 los extremos del jitter son 0.8x y 1.2x")
    fun extremos() {
        assertEquals(800L, ChainFailover.backoffMs(0, 0.0))
        assertEquals(1_200L, ChainFailover.backoffMs(0, 1.0))
        assertEquals(24_000L, ChainFailover.backoffMs(5, 0.0))
        assertEquals(36_000L, ChainFailover.backoffMs(5, 1.0))
    }

    @Test
    @DisplayName("A4.1-33 la muestra de jitter se satura a [0,1] y no desborda el +/-20 %")
    fun jitterSaturado() {
        assertEquals(800L, ChainFailover.backoffMs(0, -5.0))
        assertEquals(1_200L, ChainFailover.backoffMs(0, 5.0))
    }

    @Test
    @DisplayName("A4.1-34 un intento negativo es un error de programacion")
    fun intentoNegativo() {
        assertThrows(IllegalArgumentException::class.java) { ChainFailover.backoffMs(-1, 0.5) }
    }

    @Test
    @DisplayName("A4.1-35 el supervisor aplica el backoff con su fuente de jitter inyectada")
    fun backoffDelSupervisor() {
        val sup = ChainSupervisor(
            chain = TransportChain(listOf(ScriptableTransportBackend("P2P"))),
            tiers = listOf(TransportTier.P2P),
            enabled = true,
            torEnabled = false,
            jitter = { 0.5 },
        )
        assertEquals(1_000L, sup.backoffFor(0))
        assertEquals(30_000L, sup.backoffFor(99))
    }
}
