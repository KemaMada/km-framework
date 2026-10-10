package com.km.node

import com.km.auth.AuthChallenge
import com.km.auth.AuthOk
import com.km.auth.AuthSession
import com.km.auth.Clock
import com.km.auth.TranscriptBuilder
import com.km.crypto.Ed25519
import com.km.crypto.Ed25519Impl
import com.km.crypto.KeyPair
import com.km.messaging.FrameIdentity
import com.km.messaging.GuionDeMigracion
import com.km.messaging.RelayDeTransporte
import com.km.model.IdentityId
import com.km.model.MessageId
import com.km.model.NodeAnnouncement
import com.km.model.NodeAnnouncementJsonCodec
import com.km.model.NodeIdentity
import com.km.transmit.TransmitLedger
import com.km.unit.OutboundDeliveryState
import com.km.unit.OutboundRecord
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList

/**
 * A4.2 — ENFORCEMENT en runtime: cableado de produccion de eventos y latidos.
 *
 * El slice A4.1/A4.2 dejo los predicados y la maquina de estados como POLITICA.
 * Aqui se prueba lo que faltaba: que las fuentes REALES esten conectadas y que
 * el timeout de latido se aplique durante la ronda.
 *
 * Lo que NO satisface esta suite: llamar a los metodos del supervisor a mano.
 * Al menos una prueba (A4.2-10) recorre el camino de produccion completo:
 * `PeerTransportManager.markConnected/failBinding/closeBinding` -> runtime ->
 * supervisor. Es la unica forma de demostrar que la suscripcion existe.
 */
class ChainSupervisorEnforcementTest {

    private val ed25519: Ed25519 = Ed25519Impl()
    private val authClock: Clock = Clock { 1_000L }

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

    /** PeerContext autenticado real contra [bobId] (el manager lo exige). */
    private fun contexto(): PeerContext {
        val session = AuthSession.initiator(aliceKP, aliceId.value, ed25519, clock = authClock)
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

    /**
     * Arnés de cadena `[P2P, Relay]` con reloj y jitter fijos. `p2pMuerto`
     * controla si el PROXIMO establecimiento de P2P falla: es como se simula
     * una caida ICE despues de READY.
     */
    private class Arnes(enabled: Boolean = true) {
        var clock: Long = 0L
        val p2p = ScriptableTransportBackend("P2P")
        val relay = RelayDeTransporte("Relay")
        @Volatile var p2pMuerto: Boolean = false
        val creados = CopyOnWriteArrayList<ScriptableEstablishment>()

        val sup = ChainSupervisor(
            chain = TransportChain(listOf(p2p, relay)),
            tiers = listOf(TransportTier.P2P, TransportTier.RELAY),
            enabled = enabled,
            torEnabled = false,
            now = { this.clock },
            jitter = { 0.5 },
        )

        val factory: (TransportBackend) -> TransportEstablishment = { b ->
            ScriptableEstablishment(b.name, b).also {
                creados.add(it)
                if (b.name == "P2P" && p2pMuerto) {
                    it.awaitReadyOutcome = TransportResult.Failure(TransportError.BINDING_CLOSED, "P2P cayo")
                }
            }
        }
    }

    /** Fuente de cambio de red controlable, como la implementaria un host. */
    private class FakeNetwork : NetworkChangeSource {
        private val handlers = CopyOnWriteArrayList<() -> Unit>()
        override fun onNetworkChange(handler: () -> Unit): ChainSubscription {
            handlers.add(handler)
            return ChainSubscription { handlers.remove(handler) }
        }
        fun fire() { handlers.toList().forEach { it() } }
        val subscribers: Int get() = handlers.size
    }

    /** Deja un binding del manager en CONNECTED por el camino real. */
    private fun conectar(manager: PeerTransportManager, ctx: PeerContext) {
        manager.createBinding(aliceId, ctx).getOrThrow()
        manager.sendOffer(aliceId, ctx, "offer", bobId).getOrThrow()
        manager.receiveAnswer(aliceId, bobId, "answer").getOrThrow()
        manager.markConnected(aliceId, ctx).getOrThrow()
    }

    // ===================================================================
    // A4.2-10..11 — Ruta de suscripcion de produccion (no unitaria)
    // ===================================================================

    @Test
    @DisplayName("A4.2-10 failBinding real migra el supervisor por la suscripcion de produccion")
    fun failBindingRealMigra() {
        val a = Arnes()
        a.sup.establish(aliceId, bobId, factory = a.factory)
        assertEquals(ChainState.P2P_ACTIVE, a.sup.state)

        val manager = PeerTransportManager(transportBackend = a.sup, now = { a.clock })
        val runtime = ChainSupervisorRuntime(a.sup, manager, enabled = true)
        runtime.attach()
        assertTrue(runtime.attached, "el runtime debe suscribirse al manager")

        conectar(manager, contexto())
        assertEquals(ChainState.P2P_ACTIVE, a.sup.state)

        val antes = a.creados.size
        a.p2pMuerto = true
        manager.failBinding(aliceId, bobId, "ICE failed").getOrThrow()

        // El evento REAL del manager produjo la transicion Y el fallback.
        assertEquals(ChainState.RELAY_ACTIVE, a.sup.state)
        assertEquals("Relay", a.sup.chain.activeTransport)
        assertTrue(a.sup.triggersRaised().contains(ChainTrigger.ICE_FAILED))
        assertTrue(a.creados.size > antes, "el failover crea un establecimiento FRESCO")
    }

    @Test
    @DisplayName("A4.2-11 closeBinding real tambien dispara la caida via la suscripcion")
    fun closeBindingRealDispara() {
        val a = Arnes()
        a.sup.establish(aliceId, bobId, factory = a.factory)
        val manager = PeerTransportManager(transportBackend = a.sup, now = { a.clock })
        val runtime = ChainSupervisorRuntime(a.sup, manager, enabled = true)
        runtime.attach()
        conectar(manager, contexto())

        a.p2pMuerto = true
        manager.closeBinding(aliceId, bobId).getOrThrow()

        assertEquals(ChainState.RELAY_ACTIVE, a.sup.state)
        assertTrue(a.sup.triggersRaised().contains(ChainTrigger.ICE_FAILED))
    }

    // ===================================================================
    // A4.2-12 — Latido expirado DURANTE la ronda
    // ===================================================================

    @Test
    @DisplayName("A4.2-12 un latido expirado produce la transicion Y el fallback")
    fun latidoExpiradoProduceFallback() {
        val a = Arnes()
        a.sup.establish(aliceId, bobId, factory = a.factory)
        assertEquals(ChainState.P2P_ACTIVE, a.sup.state)

        // Justo en el limite: 5000 ms de silencio todavia NO es un miss.
        a.clock = ChainFailover.HEARTBEAT_TIMEOUT_MS
        assertFalse(a.sup.checkHeartbeat(a.clock), "5000 ms justos aun no expiran")
        assertEquals(ChainState.P2P_ACTIVE, a.sup.state)

        // Un latido real extiende la ventana: la referencia se mueve.
        assertTrue(a.sup.onHeartbeat(a.clock))
        assertEquals(a.clock, a.sup.lastHeartbeatMs)
        assertEquals(ChainFailover.HEARTBEAT_TIMEOUT_MS, a.clock)

        // A los 5001 ms DEL LATIDO, el enlace se da por perdido.
        a.p2pMuerto = true
        a.clock = 2 * ChainFailover.HEARTBEAT_TIMEOUT_MS
        assertFalse(a.sup.checkHeartbeat(a.clock), "5000 ms tras el latido aun no expira")
        val antes = a.creados.size
        a.clock += 1
        assertTrue(a.sup.checkHeartbeat(a.clock), "5001 ms tras el latido SI expira")

        assertEquals(ChainState.RELAY_ACTIVE, a.sup.state, "el latido expirado migro la cadena")
        assertEquals(1, a.sup.retryCount)
        assertTrue(a.sup.triggersRaised().contains(ChainTrigger.ICE_FAILED))
        assertTrue(a.creados.size > antes, "el fallback se intento de verdad")
    }

    // ===================================================================
    // A4.2-13 — Duplicados y concurrencia
    // ===================================================================

    @Test
    @DisplayName("A4.2-13 eventos duplicados no causan migraciones duplicadas")
    fun duplicadosNoMigranDosVeces() {
        val a = Arnes()
        a.sup.establish(aliceId, bobId, factory = a.factory)
        a.sup.onConnectionStateChange(connected = true) // confirmacion del transporte

        a.p2pMuerto = true
        assertTrue(a.sup.onConnectionStateChange(connected = false))
        assertFalse(a.sup.onConnectionStateChange(connected = false), "el duplicado es NO-OP")
        assertFalse(a.sup.onConnectionStateChange(connected = false), "y el tercero tambien")

        assertEquals(1, a.sup.retryCount, "una sola caida = una sola migracion")
        assertEquals(1, a.sup.history().count { it == ChainState.DEGRADED }, "sin estados intercalados")
    }

    @Test
    @DisplayName("A4.2-13b eventos concurrentes no intercalan la maquina de estados")
    fun eventosConcurrentesNoIntercalan() {
        val a = Arnes()
        a.sup.establish(aliceId, bobId, factory = a.factory)
        a.sup.onConnectionStateChange(connected = true)
        a.p2pMuerto = true

        val hilos = (1..8).map { Thread { a.sup.onConnectionStateChange(connected = false) } }
        hilos.forEach { it.start() }
        hilos.forEach { it.join() }

        assertEquals(1, a.sup.retryCount, "8 caidas concurrentes = 1 migracion")
        assertEquals(1, a.sup.history().count { it == ChainState.DEGRADED })
        // Dos P2P_NEGOTIATING: la ronda inicial y la disparada. Nada intercalado.
        assertEquals(2, a.sup.history().count { it == ChainState.P2P_NEGOTIATING })
    }

    // ===================================================================
    // A4.2-14 — permitsFallback == false jamas activa el relay
    // ===================================================================

    @Test
    @DisplayName("A4.2-14 un fallo sin fallback nunca activa el relay")
    fun sinFallbackNuncaActivaRelay() {
        val a = Arnes()
        val relayAttempts = CopyOnWriteArrayList<String>()
        val factory: (TransportBackend) -> TransportEstablishment = { b ->
            if (b.name == "Relay") relayAttempts.add(b.name)
            ScriptableEstablishment(b.name, b).also {
                if (b.name == "P2P") {
                    // SECURITY: no autoriza fallback.
                    it.awaitReadyOutcome = TransportResult.Failure(TransportError.PEER_ID_MISMATCH, "suplantacion")
                }
            }
        }
        val result = a.sup.establish(aliceId, bobId, factory = factory)

        assertTrue(result is EstablishmentResult.Stopped)
        assertEquals(ChainState.BLOCKED, a.sup.state)
        assertTrue(relayAttempts.isEmpty(), "un fallo SECURITY no toca el relay")
        assertNull(a.sup.chain.activeTransport)

        // Ni una caida ni un latido expirado lo desbloquean hacia el relay.
        assertFalse(a.sup.onConnectionStateChange(connected = false))
        assertFalse(a.sup.checkHeartbeat(999_999L))
        assertTrue(relayAttempts.isEmpty())
        assertEquals(ChainState.BLOCKED, a.sup.state)
    }

    // ===================================================================
    // A4.2-15 — El cambio de transporte conserva el registro durable
    // ===================================================================

    @Test
    @DisplayName("A4.2-15 el cambio de transporte conserva el OutboundRecord y el ciphertext byte a byte")
    fun cambioDeTransporteConservaRegistro() {
        val guion = GuionDeMigracion("ENF")
        val frame = guion.alice.encrypt("cruza la migracion sin re-cifrar".toByteArray())
        val ciphertext = frame.copyOf()
        val nTrasCifrar = guion.aliceSession.currentSendMessageNumber()
        val fotoTrasCifrar = guion.aliceSession.snapshot()

        val messageId = MessageId(UUID.randomUUID())
        val record = OutboundRecord(
            recordVersion = 1u,
            deliveryState = OutboundDeliveryState.PENDIENTE,
            messageId = messageId,
            frameIdentity = FrameIdentity.fromWire(frame),
            createdOrdinal = 1uL,
            ciphertext = frame,
        )
        val ledger = TransmitLedger()
        ledger.registrarDurable(record)

        val a = Arnes()
        a.sup.establish(aliceId, bobId, factory = a.factory)
        assertTrue(a.sup.sendData(bobId, record.ciphertext) is TransportResult.Success)
        assertArrayEquals(ciphertext, a.p2p.sentPayloads.single())

        // Migracion por la RUTA REAL: manager -> runtime -> supervisor.
        val manager = PeerTransportManager(transportBackend = a.sup, now = { a.clock })
        val runtime = ChainSupervisorRuntime(a.sup, manager, enabled = true)
        runtime.attach()
        conectar(manager, contexto())

        a.p2p.failWithTransport(TransportError.BINDING_CLOSED)
        a.p2pMuerto = true
        manager.failBinding(aliceId, bobId, "ICE failed").getOrThrow()
        assertEquals(ChainState.RELAY_ACTIVE, a.sup.state)

        // Reenvio del MISMO registro: sale por relay, byte a byte.
        assertTrue(a.sup.sendData(bobId, record.ciphertext) is TransportResult.Success)
        assertArrayEquals(ciphertext, a.relay.entregar())

        // Invariantes del registro y de la sesion.
        assertArrayEquals(ciphertext, record.ciphertext, "el registro no fue re-escrito")
        assertTrue(ledger.esDurable(messageId), "el fallo de transporte no borra el durable")
        assertArrayEquals(ciphertext, ledger.registros().single().ciphertext)
        assertEquals(nTrasCifrar, guion.aliceSession.currentSendMessageNumber(), "N no se movio")
        assertArrayEquals(fotoTrasCifrar.rootKey, guion.aliceSession.snapshot().rootKey, "SessionKey intacta")
    }

    // ===================================================================
    // A4.2-16 — Unsubscribe deja el manager intacto
    // ===================================================================

    @Test
    @DisplayName("A4.2-16 detach retira el observador y el manager deja de disparar")
    fun detachRetiraObservador() {
        val a = Arnes()
        a.sup.establish(aliceId, bobId, factory = a.factory)
        val manager = PeerTransportManager(transportBackend = a.sup, now = { a.clock })
        val runtime = ChainSupervisorRuntime(a.sup, manager, enabled = true)
        runtime.attach()
        assertTrue(runtime.attached)
        runtime.detach()
        assertFalse(runtime.attached)

        conectar(manager, contexto())
        a.p2pMuerto = true
        val retryAntes = a.sup.retryCount
        manager.failBinding(aliceId, bobId, "ICE failed").getOrThrow()

        assertEquals(retryAntes, a.sup.retryCount, "sin suscriptor no hay migracion")
        assertFalse(a.sup.triggersRaised().contains(ChainTrigger.ICE_FAILED))
    }

    // ===================================================================
    // A4.2-17 — Fuentes externas: red y sonda de 60 s
    // ===================================================================

    @Test
    @DisplayName("A4.2-17 un cambio de red externo y la sonda de 60 s entran por el runtime")
    fun redExternaYSondaSeCablean() {
        val a = Arnes()
        // Ronda inicial por relay (P2P cae) para habilitar la sonda.
        a.p2pMuerto = true
        a.sup.establish(aliceId, bobId, factory = a.factory)
        assertEquals(ChainState.RELAY_ACTIVE, a.sup.state)

        val red = FakeNetwork()
        val manager = PeerTransportManager(transportBackend = a.sup, now = { a.clock })
        val runtime = ChainSupervisorRuntime(a.sup, manager, network = red, enabled = true)
        runtime.attach()
        assertEquals(1, red.subscribers, "el runtime suscribe la fuente de red")

        // Cambio de red REAL: re-intenta tier-1 y P2P vuelve.
        a.p2pMuerto = false
        red.fire()
        assertEquals(ChainState.P2P_ACTIVE, a.sup.state)
        assertTrue(a.sup.triggersRaised().contains(ChainTrigger.NETWORK_CHANGE))

        // Vuelve a relay y, a los 60 s, la sonda del tick del host re-intenta P2P.
        a.sup.onConnectionStateChange(connected = true)
        a.p2pMuerto = true
        a.sup.onConnectionStateChange(connected = false)
        assertEquals(ChainState.RELAY_ACTIVE, a.sup.state)

        a.clock += ChainFailover.P2P_PROBE_INTERVAL_MS
        assertTrue(runtime.tick(a.clock), "la sonda de 60 s se aplica desde el tick del host")
        assertEquals(1, a.sup.triggersRaised().count { it == ChainTrigger.P2P_PROBE })

        runtime.detach()
        assertEquals(0, red.subscribers, "detach desuscribe la fuente de red")
    }

    // ===================================================================
    // A4.2-18 — Flag OFF: el cableado no toca nada
    // ===================================================================

    @Test
    @DisplayName("A4.2-18 con km.chain.probe OFF el runtime no suscribe ni dispara")
    fun flagApagadoNoCablea() {
        val a = Arnes(enabled = false)
        a.sup.establish(aliceId, bobId, factory = a.factory)
        assertEquals(ChainState.DOWN, a.sup.state, "con el flag OFF es decorador transparente")

        val manager = PeerTransportManager(transportBackend = a.sup, now = { a.clock })
        val runtime = ChainSupervisorRuntime(a.sup, manager, enabled = false)
        runtime.attach()
        assertFalse(runtime.attached, "con el flag OFF no se suscribe al manager")

        conectar(manager, contexto())
        a.p2pMuerto = true
        manager.failBinding(aliceId, bobId, "ICE failed").getOrThrow()

        assertEquals(0, a.sup.retryCount)
        assertEquals(ChainState.DOWN, a.sup.state)
        assertFalse(runtime.tick(999_999L))
        assertFalse(runtime.heartbeat())
    }
}
