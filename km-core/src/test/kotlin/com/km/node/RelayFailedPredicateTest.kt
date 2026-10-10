package com.km.node

import com.km.node.ChainFailover.TierOutcome
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * A4.1 — Predicado de fallo de tier (diseno §1) y sus numeros.
 *
 * "El tier fallo si CUALQUIERA": fallo con `permitsFallback`, sin `READY`
 * dentro de `iceTimeoutMs`, se supero el `retryBudget` de escrituras, o hubo un
 * heartbeat miss. Y una regla de precedencia: un fallo SIN fallback no es
 * "fallo con failover", es `BLOCKED`.
 */
class RelayFailedPredicateTest {

    private fun outcome(
        ready: Boolean = true,
        result: TransportResult<Unit>? = null,
        elapsedMs: Long = 0L,
        writeAttempts: Int = 0,
        lastHeartbeatMs: Long? = null,
        nowMs: Long = 0L,
    ) = TierOutcome(ready, result, elapsedMs, writeAttempts, lastHeartbeatMs, nowMs)

    // --- Los numeros exactos del diseno ---

    @Test
    @DisplayName("A4.1-20 los numeros del diseno son los del codigo")
    fun numerosExactos() {
        assertEquals(10_000L, ChainFailover.ESTABLISH_TIMEOUT_MS)
        assertEquals(8_000L, ChainFailover.ICE_TIMEOUT_MS)
        assertEquals(15_000L, ChainFailover.HEARTBEAT_INTERVAL_MS)
        assertEquals(5_000L, ChainFailover.HEARTBEAT_TIMEOUT_MS)
        assertEquals(3, ChainFailover.RETRY_BUDGET)
        assertEquals(60_000L, ChainFailover.P2P_PROBE_INTERVAL_MS)
        assertEquals(0.20, ChainFailover.JITTER_FRACTION)
        assertEquals(listOf(1_000L, 2_000L, 4_000L, 8_000L, 16_000L, 30_000L), ChainFailover.BACKOFF_MS)
    }

    // --- Rama 1: fallo de medio que autoriza fallback ---

    @Test
    @DisplayName("A4.1-21 un fallo TRANSPORT autoriza el failover")
    fun fallbackPermitido() {
        val o = outcome(ready = false, result = TransportResult.Failure(TransportError.BINDING_CLOSED, "cayo"))
        assertTrue(ChainFailover.tierFailed(o))
        assertFalse(ChainFailover.blocked(o))
    }

    // --- Rama 2: sin READY dentro de iceTimeoutMs ---

    @Test
    @DisplayName("A4.1-22 sin READY en iceTimeoutMs (8000) el tier falla")
    fun iceTimeout() {
        assertFalse(ChainFailover.tierFailed(outcome(ready = false, elapsedMs = 7_999L)))
        assertTrue(ChainFailover.tierFailed(outcome(ready = false, elapsedMs = 8_000L)))
        // Si YA esta READY, el tiempo transcurrido no lo tumba.
        assertFalse(ChainFailover.tierFailed(outcome(ready = true, elapsedMs = 999_999L)))
    }

    // --- Rama 3: presupuesto de escritura ---

    @Test
    @DisplayName("A4.1-23 superar retryBudget=3 escrituras fallidas falla el tier")
    fun retryBudget() {
        assertFalse(ChainFailover.tierFailed(outcome(writeAttempts = 3)))
        assertTrue(ChainFailover.tierFailed(outcome(writeAttempts = 4)))
    }

    // --- Rama 4: heartbeat miss ---

    @Test
    @DisplayName("A4.1-24 un silencio mayor que heartbeatTimeoutMs (5000) falla el tier")
    fun heartbeatMiss() {
        assertFalse(ChainFailover.heartbeatMissed(lastHeartbeatMs = 0L, nowMs = 5_000L))
        assertTrue(ChainFailover.heartbeatMissed(lastHeartbeatMs = 0L, nowMs = 5_001L))
        assertFalse(
            ChainFailover.tierFailed(outcome(ready = true, lastHeartbeatMs = 0L, nowMs = 5_000L)),
            "justo en el limite todavia no es miss",
        )
        assertTrue(
            ChainFailover.tierFailed(outcome(ready = true, lastHeartbeatMs = 0L, nowMs = 5_001L)),
            "un enlace que estaba READY y deja de responder es un fallo",
        )
    }

    // --- Precedencia: BLOCKED manda ---

    @Test
    @DisplayName("A4.1-25 un fallo sin fallback es BLOCKED aunque se cumplan las otras ramas")
    fun bloqueoMandaSobreTodo() {
        val bloqueado = outcome(
            ready = false,
            result = TransportResult.Failure(TransportError.SESSION_MISMATCH, "estado invalido"),
            elapsedMs = 999_999L,
            writeAttempts = 99,
            lastHeartbeatMs = 0L,
            nowMs = 999_999L,
        )
        assertTrue(ChainFailover.blocked(bloqueado))
        assertFalse(
            ChainFailover.tierFailed(bloqueado),
            "SECURITY/PROTOCOL/STATE no se convierte en fallback ni por tiempo ni por escrituras",
        )
    }

    @Test
    @DisplayName("A4.1-26 un enlace sano no falla")
    fun enlaceSano() {
        assertFalse(
            ChainFailover.tierFailed(
                outcome(ready = true, result = TransportResult.Success(Unit), lastHeartbeatMs = 0L, nowMs = 1_000L),
            ),
        )
    }

    // --- El presupuesto del supervisor ---

    @Test
    @DisplayName("A4.1-27 recordWriteResult supera el presupuesto en la cuarta escritura")
    fun presupuestoDelSupervisor() {
        val sup = ChainSupervisor(
            chain = TransportChain(listOf(ScriptableTransportBackend("P2P"))),
            tiers = listOf(TransportTier.P2P),
            enabled = true,
            torEnabled = false,
        )
        val fallo = TransportResult.Failure(TransportError.BACKEND_SEND_FAILED, "write")
        assertFalse(sup.recordWriteResult(fallo))
        assertFalse(sup.recordWriteResult(fallo))
        assertFalse(sup.recordWriteResult(fallo))
        assertTrue(sup.recordWriteResult(fallo), "la cuarta escritura fallida supera el presupuesto")
        assertEquals(4, sup.writeFailures)
        // Un exito reinicia el presupuesto.
        assertFalse(sup.recordWriteResult(TransportResult.Success(Unit)))
        assertEquals(0, sup.writeFailures)
    }
}
