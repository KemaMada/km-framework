package com.km.node

import com.km.model.IdentityId
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * A4.1 — Equivalencia con el flag `km.chain.probe` APAGADO.
 *
 * Con el flag apagado el supervisor es un decorador transparente: la secuencia
 * de intentos, el medio con autoridad y el resultado de `sendData` son
 * IDENTICOS a usar [TransportChain] directamente, y los disparadores de A4.2
 * son NO-OP. Es la prueba de que "todo esta detras del flag".
 */
class ChainSupervisorFlagOffTest {

    private val local = IdentityId("a".repeat(64))
    private val remote = IdentityId("b".repeat(64))

    private fun fabricaPara(p2p: ScriptableTransportBackend): (TransportBackend) -> TransportEstablishment =
        { backend ->
            ScriptableEstablishment(backend.name, backend).also {
                if (backend.name == "P2P") {
                    it.awaitReadyOutcome = TransportResult.Failure(TransportError.BINDING_CLOSED, "P2P cayo")
                }
            }
        }

    @Test
    @DisplayName("A4.1-50 con el flag apagado establish/sendData son identicos a la cadena desnuda")
    fun decoradorTransparente() {
        val p2pDirecto = ScriptableTransportBackend("P2P")
        val relayDirecto = ScriptableTransportBackend("Relay")
        val directo = TransportChain(listOf(p2pDirecto, relayDirecto))

        val p2pSup = ScriptableTransportBackend("P2P")
        val relaySup = ScriptableTransportBackend("Relay")
        val sup = ChainSupervisor(
            chain = TransportChain(listOf(p2pSup, relaySup)),
            tiers = listOf(TransportTier.P2P, TransportTier.RELAY),
            enabled = false,
            torEnabled = false,
            now = { 0L },
            jitter = { 0.5 },
        )

        val rDirecto = directo.establish(local, remote, factory = fabricaPara(p2pDirecto))
        val rSup = sup.establish(local, remote, factory = fabricaPara(p2pSup))

        assertEquals(rDirecto::class, rSup::class, "mismo tipo de resultado")
        assertEquals(
            (rDirecto as EstablishmentResult.Ready).activeTransport,
            (rSup as EstablishmentResult.Ready).activeTransport,
            "mismo medio ganador",
        )
        assertEquals(directo.activeTransport, sup.chain.activeTransport)
        assertEquals("Relay", directo.activeTransport, "el escenario ejercita el fallback P2P -> relay")

        val bytes = ByteArray(16) { it.toByte() }
        // P2P esta caido tambien en el envio: el primer eslabon disponible es el relay.
        p2pDirecto.failWithTransport(TransportError.BINDING_CLOSED)
        p2pSup.failWithTransport(TransportError.BINDING_CLOSED)
        assertEquals(
            directo.sendData(remote, bytes)::class,
            sup.sendData(remote, bytes)::class,
            "mismo tipo de resultado de envio",
        )
        assertTrue(relayDirecto.recibidos().contentEquals(relaySup.recibidos()))
    }

    @Test
    @DisplayName("A4.1-51 con el flag apagado el estado no se toca y los triggers son NO-OP")
    fun flagApagadoSinEfectos() {
        val p2p = ScriptableTransportBackend("P2P")
        val relay = ScriptableTransportBackend("Relay")
        val sup = ChainSupervisor(
            chain = TransportChain(listOf(p2p, relay)),
            tiers = listOf(TransportTier.P2P, TransportTier.RELAY),
            enabled = false,
            torEnabled = false,
        )
        sup.establish(local, remote) { ScriptableEstablishment(it.name, it) }

        assertEquals(ChainState.DOWN, sup.state, "el estado del supervisor no participa con el flag apagado")
        assertEquals(0, sup.retryCount)
        assertFalse(sup.onNetworkChange())
        assertFalse(sup.onConnectionStateChange(connected = false))
        assertFalse(sup.onProbe(999_999L))
        assertEquals(0, sup.retryCount)
        assertTrue(sup.triggersRaised().isEmpty())
    }

    @Test
    @DisplayName("A4.1-52 el flag OFF sigue delegando TODO al backend de la cadena")
    fun delegacionCompleta() {
        val p2p = ScriptableTransportBackend("P2P")
        val chain = TransportChain(listOf(p2p))
        val sup = ChainSupervisor(chain, listOf(TransportTier.P2P), enabled = false, torEnabled = false)

        assertTrue(sup.initialize() is TransportResult.Success)
        assertTrue(p2p.initialized, "initialize llega al backend")
        sup.shutdown()
        assertTrue(p2p.shutdownCalled, "shutdown llega al backend")
        assertSame(chain.eventCallbacks, sup.eventCallbacks)
    }
}

/** Acceso al ultimo payload verbatim, para comparar dos relays. */
private fun ScriptableTransportBackend.recibidos(): ByteArray = sentPayloads.last().copyOf()
