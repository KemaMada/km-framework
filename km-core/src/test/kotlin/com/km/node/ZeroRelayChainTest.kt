package com.km.node

import com.km.frame.BinarySecureFrameCodec
import com.km.frame.FrameType
import com.km.frame.RatchetHeader
import com.km.frame.SecureFrame
import com.km.frame.SecureFrameSpec
import com.km.messaging.FrameIdentity
import com.km.model.Ack
import com.km.model.IdentityId
import com.km.model.Message
import com.km.model.MessageId
import com.km.model.RelayLimits
import com.km.model.StoredMessage
import com.km.storage.RelayStore
import com.km.storage.RelayStoreStats
import com.km.transmit.TransmitLedger
import com.km.unit.OutboundDeliveryState
import com.km.unit.OutboundRecord
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * A1.3 — Degradacion a cero reles.
 *
 * Lo que se demuestra:
 *  1. Sin `RelayService`, el plan es `[p2p]` (o `[p2p, tor]` con Tor). El
 *     eslabon de relay NO se inventa.
 *  2. Con `RelayService`, el eslabon de relay SI esta en el plan.
 *  3. Materializar el plan nunca produce una cadena con un relay sin backend.
 *  4. Si P2P falla en una cadena sin relay, la ronda queda `Exhausted`: no hay
 *     un medio fantasma que la salve.
 *  5. Un tier-2 (relay) presente pero sin medio tambien agota la ronda.
 *  6. Tras `Exhausted` los frames SIGUEN durables en el [TransmitLedger]: no
 *     se descarta nada, queda para un reintento posterior.
 */
class ZeroRelayChainTest {

    private val local = IdentityId("a".repeat(64))
    private val remote = IdentityId("b".repeat(64))

    // ===================================================================
    // El plan
    // ===================================================================

    @Test
    @DisplayName("A1.3-01 sin relay el plan es solo p2p")
    fun sinRelayPlanSoloP2p() {
        val plan = TransportPlan.of(relayAvailable = false)
        assertEquals(listOf(TransportTier.P2P), plan.tiers)
        assertEquals("p2p", plan.tierNames)
    }

    @Test
    @DisplayName("A1.3-02 sin relay pero con tor el plan es p2p+tor")
    fun sinRelayConTor() {
        val plan = TransportPlan.of(relayAvailable = false, torAvailable = true)
        assertEquals(listOf(TransportTier.P2P, TransportTier.TOR), plan.tiers)
        assertEquals("p2p,tor", plan.tierNames)
    }

    @Test
    @DisplayName("A1.3-03 con relay el plan conserva el eslabon relay")
    fun conRelayConservaEslabon() {
        assertEquals(listOf(TransportTier.P2P, TransportTier.RELAY), TransportPlan.of(true).tiers)
        assertEquals(
            listOf(TransportTier.P2P, TransportTier.RELAY, TransportTier.TOR),
            TransportPlan.of(true, true).tiers,
        )
    }

    @Test
    @DisplayName("A1.3-04 buildTransportPlan: sin RelayService no hay eslabon relay")
    fun buildPlanSinRelayService() {
        assertEquals(listOf(TransportTier.P2P), NodeRuntimeImpl.buildTransportPlan(relayService = null).tiers)
        assertEquals(
            listOf(TransportTier.P2P, TransportTier.TOR),
            NodeRuntimeImpl.buildTransportPlan(relayService = null, torAvailable = true).tiers,
        )
    }

    @Test
    @DisplayName("A1.3-05 buildTransportPlan: con RelayService el eslabon relay existe")
    fun buildPlanConRelayService() {
        val relay = fakeRelayService()
        assertEquals(
            listOf(TransportTier.P2P, TransportTier.RELAY),
            NodeRuntimeImpl.buildTransportPlan(relayService = relay).tiers,
        )
    }

    @Test
    @DisplayName("A1.3-06 materializeChain no inventa un relay sin backend")
    fun materializeNoInventaRelay() {
        val p2p = ScriptableTransportBackend("p2p")
        val chain = NodeRuntimeImpl.materializeChain(
            TransportPlan.of(relayAvailable = true),
            mapOf(TransportTier.P2P to p2p),
        )
        assertEquals(listOf("p2p"), chain.transports.map { it.name })
    }

    @Test
    @DisplayName("A1.3-07 materializeChain rechaza una cadena sin backends")
    fun materializeRechazaVacio() {
        assertThrows(IllegalArgumentException::class.java) {
            NodeRuntimeImpl.materializeChain(TransportPlan.of(relayAvailable = false), emptyMap())
        }
    }

    // ===================================================================
    // La ronda agota
    // ===================================================================

    @Test
    @DisplayName("A1.3-08 sin relay, si p2p falla la ronda queda Exhausted")
    fun sinRelayP2pFallaAgota() {
        val p2p = ScriptableTransportBackend("p2p")
        val est = ScriptableEstablishment("p2p", p2p).apply {
            awaitReadyOutcome = TransportResult.Failure(TransportError.BACKEND_NOT_READY, "sin canal")
        }
        val chain = NodeRuntimeImpl.materializeChain(
            TransportPlan.of(relayAvailable = false),
            mapOf(TransportTier.P2P to p2p),
        )

        val result = chain.establish(local, remote, factory = { est })

        assertTrue(result is EstablishmentResult.Exhausted, "sin relay no hay a quien recurrir")
        val ex = result as EstablishmentResult.Exhausted
        assertEquals(1, ex.attempts.size)
        assertEquals("p2p", ex.attempts[0].transport)
        assertNull(ex.activeTransport)
    }

    @Test
    @DisplayName("A1.3-09 el tier 2 (relay) sin medio tambien agota la ronda")
    fun tier2RelaySinMedioAgota() {
        val p2p = ScriptableTransportBackend("p2p")
        val relay = ScriptableTransportBackend("relay")
        val estP2p = ScriptableEstablishment("p2p", p2p).apply {
            awaitReadyOutcome = TransportResult.Failure(TransportError.BACKEND_NOT_READY, "p2p sin canal")
        }
        val estRelay = ScriptableEstablishment("relay", relay).apply {
            awaitReadyOutcome = TransportResult.Failure(TransportError.PEER_NOT_ONLINE_AT_RELAY, "relay sin peer")
        }
        val chain = NodeRuntimeImpl.materializeChain(
            TransportPlan.of(relayAvailable = true),
            mapOf(TransportTier.P2P to p2p, TransportTier.RELAY to relay),
        )

        val result = chain.establish(
            local,
            remote,
            factory = ScriptableEstablishment.factory(mapOf("p2p" to estP2p, "relay" to estRelay)),
        )

        assertTrue(result is EstablishmentResult.Exhausted)
        val ex = result as EstablishmentResult.Exhausted
        assertEquals(2, ex.attempts.size)
        assertEquals("relay", ex.attempts[1].transport, "el tier 2 es el relay")
        assertNull(ex.activeTransport, "ningun medio quedo vivo")
    }

    // ===================================================================
    // Nada se descarta
    // ===================================================================

    @Test
    @DisplayName("A1.3-10 tras Exhausted los frames siguen durables en el TransmitLedger")
    fun framesSiguenDurablesTrasAgotar() {
        val frame = buildFrame()
        val messageId = MessageId(UUID.randomUUID())
        val record = OutboundRecord(
            recordVersion = 1u,
            deliveryState = OutboundDeliveryState.PENDIENTE,
            messageId = messageId,
            frameIdentity = FrameIdentity.fromWire(frame),
            createdOrdinal = 7uL,
            ciphertext = frame.copyOfRange(SecureFrameSpec.CIPHERTEXT_OFFSET, frame.size),
        )
        val ledger = TransmitLedger()
        ledger.registrarDurable(record)

        // Ronda cero-rele que FALLA: el unico medio rechaza el envio.
        val p2p = ScriptableTransportBackend("p2p").apply { failWithTransport() }
        val est = ScriptableEstablishment("p2p", p2p).apply {
            awaitReadyOutcome = TransportResult.Failure(TransportError.BACKEND_NOT_READY, "sin canal")
        }
        val chain = NodeRuntimeImpl.materializeChain(
            TransportPlan.of(relayAvailable = false),
            mapOf(TransportTier.P2P to p2p),
        )

        assertTrue(chain.establish(local, remote, factory = { est }) is EstablishmentResult.Exhausted)
        assertTrue(chain.sendData(remote, frame) is TransportResult.Failure)

        // NADA se descarto: el frame sigue durable para un reintento posterior.
        assertTrue(ledger.esDurable(messageId), "el frame no puede desaparecer del libro")
        assertEquals(1, ledger.tamanho())
        assertEquals(OutboundDeliveryState.PENDIENTE, ledger.estadoDe(messageId))
        assertArrayEquals(record.ciphertext, ledger.registros().single().ciphertext)
    }

    // ===================================================================
    // Helpers
    // ===================================================================

    private fun buildFrame(): ByteArray {
        val header = RatchetHeader(
            dhPublicKey = ByteArray(SecureFrameSpec.DH_PUBLIC_KEY_LENGTH) { it.toByte() },
            previousChainLength = 0u,
            messageNumber = 1u,
        )
        val frame = SecureFrame(
            version = SecureFrameSpec.VERSION,
            type = FrameType.MESSAGE,
            ratchetHeader = header,
            ciphertext = ByteArray(SecureFrameSpec.TAG_LENGTH) { 0x11 },
        )
        return BinarySecureFrameCodec.encode(frame)
    }

    /** RelayService minimo: solo se usa su PRESENCIA, no su comportamiento. */
    private fun fakeRelayService(): RelayService = object : RelayService {
        override val relayStore: RelayStore = object : RelayStore {
            override fun store(message: StoredMessage): Result<Unit> = noUsado()
            override fun get(messageId: MessageId): Result<StoredMessage?> = noUsado()
            override fun getForRecipient(recipientId: IdentityId): Result<List<StoredMessage>> = noUsado()
            override fun exists(messageId: MessageId): Result<Boolean> = noUsado()
            override fun delete(messageId: MessageId): Result<Unit> = noUsado()
            override fun listExpired(now: Long): Result<List<StoredMessage>> = noUsado()
            override fun expire(now: Long): Result<Int> = noUsado()
            override fun stats(): Result<RelayStoreStats> = noUsado()
        }

        override val limits: RelayLimits = RelayLimits()

        override fun handleMessage(message: Message, recipientOnline: Boolean): Result<RelayDecision> = noUsado()
        override fun handleAck(ack: Ack): Result<Unit> = noUsado()
        override fun expire(now: Long): Result<List<StoredMessage>> = noUsado()
        override fun registerStoredHandler(handler: (StoredMessage) -> Unit) = Unit
        override fun registerAckForwardedHandler(handler: (Ack) -> Unit) = Unit
    }

    private fun <T> noUsado(): Result<T> = Result.failure(UnsupportedOperationException("no usado"))
}
