package com.km.node

import com.km.auth.AuthChallenge
import com.km.auth.AuthOk
import com.km.auth.AuthSession
import com.km.auth.Clock
import com.km.auth.TranscriptBuilder
import com.km.crypto.Ed25519
import com.km.crypto.Ed25519Impl
import com.km.crypto.KeyPair
import com.km.model.IdentityId
import com.km.model.MessageId
import com.km.model.NodeAnnouncement
import com.km.model.NodeAnnouncementJsonCodec
import com.km.model.NodeIdentity
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.security.MessageDigest
import java.util.UUID

/**
 * A4.2 — Disparadores de re-intento de tier-1 (R5/S7).
 *
 * Los tres del diseño: cambio de red, caida ICE despues de `READY`, y sonda
 * cada 60000 ms. Cada uno levanta `RetryP2P` y crea un establecimiento FRESCO.
 * Y el flag apagado los convierte en NO-OP.
 */
class Tier1RetryTriggerTest {

    private val ed25519: Ed25519 = Ed25519Impl()
    private val clock: Clock = Clock { 1_000L }

    // Identidades reales (AuthSession rechaza un id que no derive de la clave).
    private val aliceKP: KeyPair = ed25519.generateKeyPair()
    private val bobKP: KeyPair = ed25519.generateKeyPair()
    private val aliceId = idDe(aliceKP)
    private val bobId = idDe(bobKP)

    private fun idDe(kp: KeyPair): IdentityId = IdentityId(
        MessageDigest.getInstance("SHA-256").let { d ->
            d.update("KM-ID-IDENTITY".toByteArray())
            d.update(kp.publicKey)
            d.digest().joinToString("") { "%02x".format(it) }
        },
    )

    private fun contexto(): PeerContext {
        val session = AuthSession.initiator(aliceKP, aliceId.value, ed25519, clock = clock)
        session.receiveChallenge(AuthChallenge(ByteArray(16) { 5 }, 1_000L, 3, bobId.value)).getOrThrow()
        session.markResponseSent()
        val sid = "b".repeat(40)
        val nonce = ByteArray(16) { 6 }
        val transcript = TranscriptBuilder.serverAuthTranscript(sid, nonce, aliceId.value)
        val ok = AuthOk(sid, nonce, aliceKP.publicKey, ed25519.sign(aliceKP.privateKey, transcript).bytes)
        session.receiveAuthOk(ok, aliceId.value).getOrThrow()
        val partial = NodeAnnouncement(
            messageId = MessageId(UUID.randomUUID()),
            timestamp = 0L,
            protocolVersion = "1.0",
            identity = NodeIdentity(bobId, bobKP.publicKey),
            endpoints = emptyList(),
            capabilities = emptySet(),
        )
        val ann = partial.copy(
            signature = ed25519.sign(bobKP.privateKey, NodeAnnouncementJsonCodec.signableJson(partial)).bytes,
        )
        return PeerContext.authenticated(bobId, bobKP.publicKey, ann, session)
    }

    private fun supervisor(
        p2p: ScriptableTransportBackend,
        relay: ScriptableTransportBackend,
        now: () -> Long,
        enabled: Boolean = true,
    ) = ChainSupervisor(
        chain = TransportChain(listOf(p2p, relay)),
        tiers = listOf(TransportTier.P2P, TransportTier.RELAY),
        enabled = enabled,
        torEnabled = false,
        now = now,
        jitter = { 0.5 },
    )

    @Test
    @DisplayName("A4.2-01 un cambio de red levanta RetryP2P y crea un establecimiento FRESCO")
    fun cambioDeRedReintenta() {
        var clock = 0L
        val p2p = ScriptableTransportBackend("P2P")
        val relay = ScriptableTransportBackend("Relay")
        val sup = supervisor(p2p, relay, { clock })
        val creados = mutableListOf<ScriptableEstablishment>()
        val factory: (TransportBackend) -> TransportEstablishment = { b ->
            ScriptableEstablishment(b.name, b).also { creados.add(it) }
        }

        sup.establish(aliceId, bobId, factory = factory)
        assertEquals(ChainState.P2P_ACTIVE, sup.state)
        val primerEstablecimiento = creados.single()

        assertTrue(sup.onNetworkChange(), "el cambio de red tiene que disparar")
        assertEquals(1, sup.retryCount)
        assertEquals(2, creados.size, "el re-intento crea un establecimiento NUEVO, no reutiliza el viejo")
        assertNotSame(primerEstablecimiento, creados[1], "y es una instancia fresca")
        assertEquals(ChainState.P2P_ACTIVE, sup.state)
        assertEquals(listOf(ChainTrigger.NETWORK_CHANGE), sup.triggersRaised())
    }

    @Test
    @DisplayName("A4.2-02 una caida ICE despues de READY levanta RetryP2P")
    fun caidaIceReintenta() {
        var clock = 0L
        val p2p = ScriptableTransportBackend("P2P")
        val relay = ScriptableTransportBackend("Relay")
        val sup = supervisor(p2p, relay, { clock })
        sup.establish(aliceId, bobId) { ScriptableEstablishment(it.name, it) }
        assertEquals(ChainState.P2P_ACTIVE, sup.state)

        assertTrue(sup.onConnectionStateChange(connected = false), "ICE DROPPED/FAILED sobre P2P_ACTIVE dispara")
        assertEquals(1, sup.retryCount)
        assertEquals(listOf(ChainTrigger.ICE_FAILED), sup.triggersRaised())
    }

    @Test
    @DisplayName("A4.2-03 la sonda dispara a los 60000 ms y no antes")
    fun sondaPeriodica() {
        var clock = 0L
        val p2p = ScriptableTransportBackend("P2P")
        val relay = ScriptableTransportBackend("Relay")
        val sup = supervisor(p2p, relay, { clock })

        // P2P cae en el establecimiento -> queda en RELAY_ACTIVE (tier-2).
        sup.establish(aliceId, bobId) { b ->
            ScriptableEstablishment(b.name, b).also {
                if (b.name == "P2P") {
                    it.awaitReadyOutcome = TransportResult.Failure(TransportError.BINDING_CLOSED, "sin canal")
                }
            }
        }
        assertEquals(ChainState.RELAY_ACTIVE, sup.state)

        clock = 59_999L
        assertFalse(sup.onProbe(clock), "todavia no toca sonear")
        assertEquals(0, sup.retryCount)

        clock = 60_000L
        assertTrue(sup.onProbe(clock), "a los 60000 ms toca re-intentar tier-1")
        assertEquals(1, sup.retryCount)
        assertEquals(listOf(ChainTrigger.P2P_PROBE), sup.triggersRaised())
    }

    @Test
    @DisplayName("A4.2-04 la re-seleccion solo cambia el puntero del medio, no reconstruye nada")
    fun reSeleccionSoloPuntero() {
        var clock = 0L
        val p2p = ScriptableTransportBackend("P2P")
        val relay = ScriptableTransportBackend("Relay")
        val sup = supervisor(p2p, relay, { clock })

        val factory: (TransportBackend) -> TransportEstablishment = { b ->
            ScriptableEstablishment(b.name, b).also {
                if (b.name == "P2P") {
                    it.awaitReadyOutcome = TransportResult.Failure(TransportError.BINDING_CLOSED, "cayo")
                }
            }
        }
        sup.establish(aliceId, bobId, factory = factory)
        assertEquals("Relay", sup.chain.activeTransport)

        // Un re-intento vuelve a empezar por tier-1; la autoridad se mueve con el resultado.
        sup.onNetworkChange()
        assertEquals("Relay", sup.chain.activeTransport, "el relay sigue siendo el medio con autoridad")

        // Los MISMOS bytes salen tras el cambio de medio: el supervisor no transforma bytes.
        val bytes = ByteArray(32) { it.toByte() }
        p2p.failWithTransport(TransportError.BINDING_CLOSED)
        assertTrue(sup.sendData(bobId, bytes) is TransportResult.Success)
        assertTrue(relay.sentPayloads.any { it.contentEquals(bytes) }, "el relay recibe los bytes VERBATIM")
    }

    @Test
    @DisplayName("A4.2-05 el manager avisa al supervisor de la caida del enlace")
    fun managerAvisaAlSupervisor() {
        var clock = 0L
        val p2p = ScriptableTransportBackend("P2P")
        val relay = ScriptableTransportBackend("Relay")
        val sup = supervisor(p2p, relay, { clock })
        sup.establish(aliceId, bobId) { ScriptableEstablishment(it.name, it) }

        val manager = PeerTransportManager(transportBackend = sup, now = { 0L })
        sup.attachTo(manager)
        val ctx = contexto()
        manager.createBinding(aliceId, ctx).getOrThrow()
        manager.sendOffer(aliceId, ctx, "offer", bobId).getOrThrow()
        manager.receiveAnswer(aliceId, bobId, "answer").getOrThrow()
        manager.markConnected(aliceId, ctx).getOrThrow()

        val antes = sup.retryCount
        manager.failBinding(aliceId, bobId, "ICE failed").getOrThrow()

        assertEquals(antes + 1, sup.retryCount, "la caida del binding tiene que re-intentar tier-1")
        assertTrue(sup.triggersRaised().contains(ChainTrigger.ICE_FAILED))
    }

    @Test
    @DisplayName("A4.2-06 con km.chain.probe apagado los disparadores son NO-OP")
    fun flagApagadoNoDispara() {
        var clock = 0L
        val p2p = ScriptableTransportBackend("P2P")
        val relay = ScriptableTransportBackend("Relay")
        val sup = supervisor(p2p, relay, { clock }, enabled = false)
        val creados = mutableListOf<ScriptableEstablishment>()
        sup.establish(aliceId, bobId) { b -> ScriptableEstablishment(b.name, b).also { creados.add(it) } }

        assertFalse(sup.onNetworkChange())
        assertFalse(sup.onConnectionStateChange(connected = false))
        assertFalse(sup.onProbe(clock))
        assertEquals(0, sup.retryCount)
        assertEquals(1, creados.size, "no se creo ningun establecimiento extra")
        assertEquals(ChainState.DOWN, sup.state, "con el flag apagado el estado no se toca")
    }
}
