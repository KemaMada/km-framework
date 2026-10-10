package com.km.node

import com.km.model.IdentityId
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * A4.1 — Maquina de estados del [ChainSupervisor] (R5, R12).
 *
 * Demuestra, con un backend de transporte programable:
 *  1. P2P listo -> `P2P_ACTIVE`; la secuencia exacta de estados.
 *  2. P2P falla con fallback -> `DEGRADED` -> relay -> `RELAY_ACTIVE`.
 *  3. P2P falla SIN fallback -> `BLOCKED`, y el relay NI SE TOCA.
 *  4. Sin mas tiers -> `EPHEMERAL_QUEUE`.
 *  5. Un medio que "dice que si" sin estar `READY` NO activa nada.
 *  6. La tabla de transiciones es la del diseño.
 *  7. `TOR_*` es inalcanzable con `km.tor.mode` apagado.
 */
class ChainSupervisorStateTest {

    private val local = IdentityId("a".repeat(64))
    private val remote = IdentityId("b".repeat(64))

    /** Supervisor sobre `[p2p, relay]` con reloj y jitter fijos. */
    private fun supervisor(
        p2p: ScriptableTransportBackend,
        relay: ScriptableTransportBackend,
        enabled: Boolean = true,
    ) = ChainSupervisor(
        chain = TransportChain(listOf(p2p, relay)),
        tiers = listOf(TransportTier.P2P, TransportTier.RELAY),
        enabled = enabled,
        torEnabled = false,
        now = { 0L },
        jitter = { 0.5 },
    )

    @Test
    @DisplayName("A4.1-01 P2P listo lleva a P2P_ACTIVE con la secuencia del diseño")
    fun p2pListoActivaP2p() {
        val p2p = ScriptableTransportBackend("P2P")
        val relay = ScriptableTransportBackend("Relay")
        val sup = supervisor(p2p, relay)

        val result = sup.establish(local, remote) { ScriptableEstablishment(it.name, it) }

        assertTrue(result is EstablishmentResult.Ready, "P2P tiene que quedar listo: $result")
        assertEquals("P2P", result.activeTransport)
        assertEquals(ChainState.P2P_ACTIVE, sup.state)
        assertEquals("P2P", sup.chain.activeTransport)
        assertEquals(
            listOf(ChainState.DOWN, ChainState.P2P_NEGOTIATING, ChainState.P2P_ACTIVE),
            sup.history(),
            "la secuencia observable es la del diseño",
        )
    }

    @Test
    @DisplayName("A4.1-02 P2P falla y relay absorbe: P2P_NEGOTIATING -> DEGRADED -> RELAY_NEGOTIATING -> RELAY_ACTIVE")
    fun p2pFallaRelayAbsorbe() {
        val p2p = ScriptableTransportBackend("P2P")
        val relay = ScriptableTransportBackend("Relay")
        val sup = supervisor(p2p, relay)

        val result = sup.establish(local, remote) { backend ->
            ScriptableEstablishment(backend.name, backend).also {
                if (backend.name == "P2P") {
                    it.awaitReadyOutcome = TransportResult.Failure(TransportError.BINDING_CLOSED, "el canal cayo")
                }
            }
        }

        assertTrue(result is EstablishmentResult.Ready, "el relay tiene que absorber: $result")
        assertEquals("Relay", result.activeTransport)
        assertEquals(ChainState.RELAY_ACTIVE, sup.state)
        assertEquals("Relay", sup.chain.activeTransport)
        assertFalse(sup.chain.isActive("P2P"), "P2P no puede conservar la autoridad")
        assertEquals(
            listOf(
                ChainState.DOWN,
                ChainState.P2P_NEGOTIATING,
                ChainState.DEGRADED,
                ChainState.RELAY_NEGOTIATING,
                ChainState.RELAY_ACTIVE,
            ),
            sup.history(),
            "el orden es serial: no hay estados de relay antes de degradar P2P",
        )
    }

    @Test
    @DisplayName("A4.1-03 fallo sin fallback -> BLOCKED y el relay ni se toca")
    fun falloSinFallbackBloquea() {
        val p2p = ScriptableTransportBackend("P2P")
        val relay = ScriptableTransportBackend("Relay")
        val sup = supervisor(p2p, relay)

        val factoryCalls = mutableListOf<String>()
        val result = sup.establish(local, remote) { backend ->
            factoryCalls.add(backend.name)
            ScriptableEstablishment(backend.name, backend).also {
                // SECURITY: no autoriza fallback. Cambiar de medio solo lo esconderia.
                it.awaitReadyOutcome = TransportResult.Failure(TransportError.PEER_ID_MISMATCH, "suplantacion")
            }
        }

        assertTrue(result is EstablishmentResult.Stopped, "un fallo de seguridad DETIENE: $result")
        assertEquals(ChainState.BLOCKED, sup.state)
        assertEquals(listOf("P2P"), factoryCalls, "el relay no se intenta jamas tras un fallo sin fallback")
        assertEquals(
            listOf(ChainState.DOWN, ChainState.P2P_NEGOTIATING, ChainState.BLOCKED),
            sup.history(),
        )
    }

    @Test
    @DisplayName("A4.1-04 sin mas tiers tras el fallo -> EPHEMERAL_QUEUE")
    fun sinMasTiersColaEfimera() {
        val p2p = ScriptableTransportBackend("P2P")
        val sup = ChainSupervisor(
            chain = TransportChain(listOf(p2p)),
            tiers = listOf(TransportTier.P2P),
            enabled = true,
            torEnabled = false,
            now = { 0L },
            jitter = { 0.5 },
        )

        val result = sup.establish(local, remote) { backend ->
            ScriptableEstablishment(backend.name, backend).also {
                it.awaitReadyOutcome = TransportResult.Failure(TransportError.BACKEND_NOT_READY, "sin canal")
            }
        }

        assertTrue(result is EstablishmentResult.Exhausted, "sin mas medios la ronda agota: $result")
        assertEquals(ChainState.EPHEMERAL_QUEUE, sup.state)
        assertNull(sup.chain.activeTransport, "ningun medio con autoridad")
        assertEquals(
            listOf(ChainState.DOWN, ChainState.P2P_NEGOTIATING, ChainState.EPHEMERAL_QUEUE),
            sup.history(),
        )
    }

    @Test
    @DisplayName("A4.1-05 un medio que dice que si sin estar READY no activa nada")
    fun noActivacionOptimista() {
        val p2p = ScriptableTransportBackend("P2P")
        val relay = ScriptableTransportBackend("Relay")
        val sup = supervisor(p2p, relay)

        val result = sup.establish(local, remote) { backend ->
            ScriptableEstablishment(backend.name, backend).also {
                // Success en awaitReady SIN pasar a READY: el backend miente.
                it.claimsSuccessWithoutReady = true
            }
        }

        assertTrue(result is EstablishmentResult.Exhausted, "un canal que no abre no es Ready: $result")
        assertFalse(
            sup.history().contains(ChainState.P2P_ACTIVE),
            "READY jamas se declara sin confirmacion del transporte: ${sup.history()}",
        )
        assertFalse(sup.history().contains(ChainState.RELAY_ACTIVE))
        assertNull(sup.chain.activeTransport)
    }

    @Test
    @DisplayName("A4.1-06 la tabla de transiciones es la del diseño")
    fun tablaDelDiseno() {
        assertTrue(ChainSupervisor.canTransition(ChainState.DOWN, ChainState.P2P_NEGOTIATING))
        assertTrue(ChainSupervisor.canTransition(ChainState.P2P_NEGOTIATING, ChainState.P2P_ACTIVE))
        assertTrue(ChainSupervisor.canTransition(ChainState.P2P_NEGOTIATING, ChainState.DEGRADED))
        assertTrue(ChainSupervisor.canTransition(ChainState.P2P_ACTIVE, ChainState.DEGRADED))
        assertTrue(ChainSupervisor.canTransition(ChainState.DEGRADED, ChainState.RELAY_NEGOTIATING))
        assertTrue(ChainSupervisor.canTransition(ChainState.RELAY_NEGOTIATING, ChainState.RELAY_ACTIVE))
        assertTrue(ChainSupervisor.canTransition(ChainState.P2P_NEGOTIATING, ChainState.BLOCKED))
        assertTrue(ChainSupervisor.canTransition(ChainState.P2P_NEGOTIATING, ChainState.EPHEMERAL_QUEUE))
        assertTrue(ChainSupervisor.canTransition(ChainState.BLOCKED, ChainState.DOWN))
        // Saltos que el diseño NO permite:
        assertFalse(ChainSupervisor.canTransition(ChainState.DOWN, ChainState.P2P_ACTIVE))
        assertFalse(ChainSupervisor.canTransition(ChainState.P2P_NEGOTIATING, ChainState.RELAY_ACTIVE))
        assertFalse(ChainSupervisor.canTransition(ChainState.BLOCKED, ChainState.RELAY_NEGOTIATING))
    }

    @Test
    @DisplayName("A4.1-07 TOR_* es inalcanzable con km.tor.mode apagado")
    fun torInalcanzableConFlagApagado() {
        // Un tier TOR con el flag apagado es una configuracion que el diseño prohibe.
        assertThrows(IllegalArgumentException::class.java) {
            ChainSupervisor(
                chain = TransportChain(listOf(ScriptableTransportBackend("P2P"), ScriptableTransportBackend("Tor"))),
                tiers = listOf(TransportTier.P2P, TransportTier.TOR),
                enabled = true,
                torEnabled = false,
            )
        }
        // Y en una corrida normal no aparece ningun estado Tor.
        val p2p = ScriptableTransportBackend("P2P")
        val sup = supervisor(p2p, ScriptableTransportBackend("Relay"))
        sup.establish(local, remote) { ScriptableEstablishment(it.name, it) }
        assertTrue(sup.history().none { it.isTor }, "sin flag Tor no puede haber estado Tor")
        assertTrue(ChainState.TOR_ACTIVE.isTor)
    }

    @Test
    @DisplayName("A4.1-08 toda transicion observada pertenece a la tabla")
    fun transicionesObservadasLegales() {
        val p2p = ScriptableTransportBackend("P2P")
        val relay = ScriptableTransportBackend("Relay")
        val sup = supervisor(p2p, relay)

        val observed = mutableListOf<Pair<ChainState, ChainState>>()
        var previous = sup.state
        sup.onStateChange { next ->
            observed.add(previous to next)
            previous = next
        }

        sup.establish(local, remote) { backend ->
            ScriptableEstablishment(backend.name, backend).also {
                if (backend.name == "P2P") {
                    it.awaitReadyOutcome = TransportResult.Failure(TransportError.BINDING_CLOSED, "cayo")
                }
            }
        }

        observed.forEach { (from, to) ->
            assertTrue(
                ChainSupervisor.canTransition(from, to),
                "transicion fuera de la tabla: $from -> $to",
            )
        }
        assertTrue(observed.isNotEmpty())
    }
}
