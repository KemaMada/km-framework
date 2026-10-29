package com.km.node

import com.km.model.IdentityId
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.test.assertIs

/**
 * Presupuesto TOTAL del establecimiento (verify #487, CRITICAL 2).
 *
 * ## Que se fija aqui
 *
 * El presupuesto es de la RONDA, no de cada fase. Antes, `negotiate` podia
 * consumir 8 s y `awaitReady` otros 8 s: el fallback tardaba hasta 16 s y
 * `TP-6 (<=8 s)` dejaba de cumplirse. Ahora la cadena toma UN deadline con su
 * reloj y reparte lo que QUEDA entre las fases que esperan.
 *
 * ## Por que un reloj falso
 *
 * El test no duerme: los establecimientos consumen presupuesto avanzando el
 * reloj compartido, que es el mismo que usa la cadena. Si la cadena usara
 * `System.currentTimeMillis`, este test no podria ver el reparto.
 */
class TransportChainDeadlineTest {

    private val local = IdentityId("11".repeat(32))
    private val remote = IdentityId("22".repeat(32))

    private class FakeClock {
        var millis: Long = 0L
        fun now(): Long = millis
        fun advance(delta: Long) { millis += delta }
    }

    /**
     * Establecimiento que HONRA el presupuesto que recibe.
     *
     * Consume como maximo el presupuesto concedido a cada fase: es lo que hace
     * un medio bien portado (`startOffering(budget)`, `awaitDataChannelOpen(budget)`).
     */
    private class BudgetedEstablishment(
        override val transportName: String,
        private val clock: FakeClock,
        private val negotiateCost: Long,
        private val readyCost: Long,
        private val readyOk: Boolean,
    ) : TransportEstablishment {

        var negotiateBudget: Long? = null
        var readyBudget: Long? = null
        var readyCalls: Int = 0
            private set

        override var state: EstablishmentState = EstablishmentState.CREATED
            private set

        override fun initialize(): TransportResult<Unit> = TransportResult.Success(Unit)

        override fun createBinding(localIdentity: IdentityId, remotePeerId: IdentityId): TransportResult<Unit> =
            TransportResult.Success(Unit)

        override fun negotiate(): TransportResult<Unit> = negotiate(Long.MAX_VALUE)

        override fun negotiate(budgetMs: Long): TransportResult<Unit> {
            negotiateBudget = budgetMs
            clock.advance(minOf(negotiateCost, budgetMs))
            state = EstablishmentState.NEGOTIATING
            return TransportResult.Success(Unit)
        }

        override fun awaitReady(timeoutMs: Long): TransportResult<Unit> {
            readyCalls++
            readyBudget = timeoutMs
            clock.advance(minOf(readyCost, timeoutMs))
            return if (readyOk) {
                state = EstablishmentState.READY
                TransportResult.Success(Unit)
            } else {
                state = EstablishmentState.FAILED
                TransportResult.Failure(TransportError.BACKEND_SEND_FAILED, "DataChannel no OPEN")
            }
        }

        override fun abort() { state = EstablishmentState.CLOSED }
    }

    private class Fixture(
        val clock: FakeClock,
        val chain: TransportChain,
        val p2p: BudgetedEstablishment,
        val relay: BudgetedEstablishment,
    )

    private fun fixture(
        negotiateCost: Long,
        readyCost: Long,
    ): Fixture {
        val clock = FakeClock()
        val p2p = BudgetedEstablishment(
            "p2p", clock,
            negotiateCost = negotiateCost,
            readyCost = readyCost,
            readyOk = false,
        )
        val relay = BudgetedEstablishment(
            "relay", clock,
            negotiateCost = 0,
            readyCost = 0,
            readyOk = true,
        )
        val chain = TransportChain(
            listOf(ScriptableTransportBackend("p2p"), ScriptableTransportBackend("relay")),
            clock::now,
        )
        return Fixture(clock, chain, p2p, relay)
    }

    private fun Fixture.establish(timeoutMs: Long = 8_000L): EstablishmentResult =
        chain.establish(local, remote, timeoutMs) { backend ->
            if (backend.name == "p2p") p2p else relay
        }

    @Test
    @DisplayName("DL-01 negotiate consume 5 s y awaitReady recibe los 3 s RESTANTES")
    fun repartePresupuesto() {
        val f = fixture(negotiateCost = 5_000L, readyCost = 8_000L)

        val result = f.establish()

        assertEquals(8_000L, f.p2p.negotiateBudget, "negotiate recibe el presupuesto entero")
        assertEquals(3_000L, f.p2p.readyBudget, "awaitReady recibe lo que QUEDA, no 8 s otra vez")
        assertEquals(0L, f.relay.readyBudget, "el relay no espera dos veces: le queda 0")
        assertIs<EstablishmentResult.Ready>(result)
        assertEquals("relay", (result as EstablishmentResult.Ready).attempt.transport)
        assertEquals(8_000L, f.clock.now(), "la ronda completa no puede durar mas que el presupuesto")
    }

    @Test
    @DisplayName("DL-02 negotiate agota el presupuesto: awaitReady recibe 0 y no bloquea")
    fun negotiateAgotaPresupuesto() {
        val f = fixture(negotiateCost = 8_000L, readyCost = 8_000L)

        val result = f.establish()

        assertEquals(0L, f.p2p.readyBudget, "sin presupuesto no se espera: 0")
        assertEquals(1, f.p2p.readyCalls, "se consulta una vez, sin dormir")
        assertIs<EstablishmentResult.Ready>(result)
        assertEquals(8_000L, f.clock.now())
    }

    @Test
    @DisplayName("DL-03 fallback con presupuesto agotado NO suma otra ronda de 8 s")
    fun fallbackNoEsperaDosVeces() {
        val f = fixture(negotiateCost = 7_999L, readyCost = 8_000L)

        f.establish()

        assertTrue(f.clock.now() <= 8_000L, "el total fue ${f.clock.now()} ms")
        assertEquals(1L, f.p2p.readyBudget, "solo queda 1 ms para el canal")
        assertEquals(0L, f.relay.negotiateBudget, "el relay hereda el deadline, no uno nuevo")
    }

    @Test
    @DisplayName("DL-04 sin TURN no se llega a esta ronda; aqui, con TURN, el relay es el suelo")
    fun elSueloEsElRelay() {
        val f = fixture(negotiateCost = 1_000L, readyCost = 1_000L)

        val result = f.establish()

        val ready = assertIs<EstablishmentResult.Ready>(result)
        assertEquals("relay", ready.attempt.transport)
        assertFalse(f.chain.isActive(Medium.P2P.linkName), "P2P no tiene autoridad tras fallar")
    }
}
