package com.km.transport.p2p

import com.km.model.IdentityId
import com.km.node.EstablishmentState
import com.km.node.P2pReason
import com.km.node.TransportBackend
import com.km.node.TransportChain
import com.km.node.TransportError
import com.km.node.TransportEstablishment
import com.km.node.TransportEventCallbacks
import com.km.node.TransportResult
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.test.assertIs

/**
 * S5 — Politica del coordinador: presupuesto, backoff, single-flight, rol y
 * criterios de no-fallback.
 *
 * Reloj y establecimientos INYECTADOS: ningun test duerme 8 s ni 60 s. Lo que
 * se fija es la DECISION, no el paso del tiempo real.
 */
class P2pLinkCoordinatorTest {

    private val local = IdentityId("11".repeat(32))
    private val remote = IdentityId("22".repeat(32))

    private class Clock {
        val millis = AtomicLong(0)
        fun now(): Long = millis.get()
        fun advance(delta: Long) { millis.addAndGet(delta) }
    }

    private class RecordingBackend(override val name: String) : TransportBackend {
        override var eventCallbacks: TransportEventCallbacks = TransportEventCallbacks()
        val ops = java.util.concurrent.CopyOnWriteArrayList<String>()
        val sendCount = AtomicInteger(0)

        override fun initialize(): TransportResult<Unit> {
            ops.add("initialize")
            return TransportResult.Success(Unit)
        }

        override fun onCreateBinding(localIdentity: IdentityId, remotePeerId: IdentityId): TransportResult<Unit> {
            ops.add("onCreateBinding")
            return TransportResult.Success(Unit)
        }

        override fun onLocalSdp(localIdentity: IdentityId, remotePeerId: IdentityId, sdp: String) =
            TransportResult.Success(Unit)

        override fun onRemoteSdp(localIdentity: IdentityId, remotePeerId: IdentityId, sdp: String) =
            TransportResult.Success(Unit)

        override fun onIceCandidate(localIdentity: IdentityId, remotePeerId: IdentityId, candidate: String) =
            TransportResult.Success(Unit)

        override fun sendData(remotePeerId: IdentityId, data: ByteArray): TransportResult<Unit> {
            sendCount.incrementAndGet()
            return TransportResult.Success(Unit)
        }

        override fun onCloseBinding(localIdentity: IdentityId, remotePeerId: IdentityId): TransportResult<Unit> {
            ops.add("onCloseBinding")
            return TransportResult.Success(Unit)
        }

        override fun shutdown(): TransportResult<Unit> = TransportResult.Success(Unit)
    }

    /** Establecimiento P2P programable que puede consumir presupuesto. */
    private class FakeP2pEstablishment(
        private val clock: Clock,
        var createOutcome: TransportResult<Unit> = TransportResult.Success(Unit),
        var readyOutcome: TransportResult<Unit> = TransportResult.Success(Unit),
        var readyDelayMs: Long = 0,
        var gate: CountDownLatch? = null,
        var entered: CountDownLatch? = null,
    ) : TransportEstablishment {
        override val transportName: String = "p2p"
        override var state: EstablishmentState = EstablishmentState.CREATED
            private set

        override fun initialize(): TransportResult<Unit> = TransportResult.Success(Unit)

        override fun createBinding(localIdentity: IdentityId, remotePeerId: IdentityId): TransportResult<Unit> {
            state = if (createOutcome.isSuccess) EstablishmentState.CREATED else EstablishmentState.FAILED
            return createOutcome
        }

        override fun negotiate(): TransportResult<Unit> {
            state = EstablishmentState.NEGOTIATING
            return TransportResult.Success(Unit)
        }

        override fun awaitReady(timeoutMs: Long): TransportResult<Unit> {
            entered?.countDown()
            gate?.await(5, TimeUnit.SECONDS)
            clock.advance(readyDelayMs)
            state = if (readyOutcome.isSuccess) EstablishmentState.READY else EstablishmentState.FAILED
            return readyOutcome
        }

        override fun abort() {
            state = EstablishmentState.CLOSED
        }
    }

    private class Fixture(
        val p2p: RecordingBackend,
        val relay: RecordingBackend,
        val chain: TransportChain,
        val coordinator: P2pLinkCoordinator,
        val establishments: MutableList<FakeP2pEstablishment>,
    )

    private fun fixture(
        p2pReady: TransportResult<Unit> = TransportResult.Success(Unit),
        p2pCreate: TransportResult<Unit> = TransportResult.Success(Unit),
        delayMs: Long = 0,
        clock: Clock = Clock(),
        gate: CountDownLatch? = null,
        entered: CountDownLatch? = null,
    ): Fixture {
        val p2p = RecordingBackend("p2p")
        val relay = RecordingBackend("relay")
        val chain = TransportChain(listOf(p2p, relay))
        val establishments = mutableListOf<FakeP2pEstablishment>()
        val coordinator = P2pLinkCoordinator(
            localIdentity = local,
            chainFor = { if (it == remote) chain else null },
            establishmentFor = { _, _, backend ->
                if (backend === p2p) {
                    FakeP2pEstablishment(
                        clock = clock,
                        createOutcome = p2pCreate,
                        readyOutcome = p2pReady,
                        readyDelayMs = delayMs,
                        gate = gate,
                        entered = entered,
                    ).also { establishments.add(it) }
                } else {
                    RelayTransportEstablishment(backend, present = { true })
                }
            },
            now = clock::now,
        )
        return Fixture(p2p, relay, chain, coordinator, establishments)
    }

    // ------------------------------------------------------------------
    // Exito
    // ------------------------------------------------------------------

    @Test
    @DisplayName("5.2-01 P2P que abre: READY, motivo p2p_ready y el medio es P2P")
    fun exito() {
        val f = fixture()
        val outcome = f.coordinator.prepare(remote)

        val ready = assertIs<LinkOutcome.Ready>(outcome)
        assertEquals("p2p", ready.transport)
        assertEquals(P2pReason.READY, ready.reason)
        assertEquals("p2p", f.chain.activeTransport)
        assertEquals(1, f.coordinator.attempts(remote))
        assertEquals(0, f.relay.sendCount.get(), "el coordinador no envia bytes")
    }

    // ------------------------------------------------------------------
    // Timeout -> relay
    // ------------------------------------------------------------------

    @Test
    @DisplayName("5.2-02 P2P que agota el presupuesto: relay y motivo p2p_timeout")
    fun timeoutCaeARelay() {
        val clock = Clock()
        val f = fixture(
            p2pReady = TransportResult.Failure(TransportError.BACKEND_SEND_FAILED, "DataChannel no OPEN"),
            delayMs = P2pLinkCoordinator.DEFAULT_TIMEOUT_MS,
            clock = clock,
        )

        val outcome = f.coordinator.prepare(remote)

        val degraded = assertIs<LinkOutcome.Degraded>(outcome)
        assertEquals(P2pReason.TIMEOUT, degraded.reason)
        assertFalse(degraded.stopped)
        assertEquals("relay", f.chain.activeTransport, "el suelo es el relay")
        assertEquals(8_000L, clock.now())
        assertEquals(0, f.relay.sendCount.get())
    }

    @Test
    @DisplayName("5.2-03 un fallo de medio sin agotar presupuesto es p2p_failed, no timeout")
    fun falloDeMedioNoEsTimeout() {
        val f = fixture(
            p2pReady = TransportResult.Failure(TransportError.BACKEND_ERROR, "boom"),
            delayMs = 1_000,
        )
        val degraded = assertIs<LinkOutcome.Degraded>(f.coordinator.prepare(remote))
        assertEquals(P2pReason.FAILED, degraded.reason)
        assertEquals("relay", f.chain.activeTransport)
    }

    // ------------------------------------------------------------------
    // No-fallback
    // ------------------------------------------------------------------

    @Test
    @DisplayName("5.2-04 SECURITY detiene la cadena: no toca el relay y lo declara")
    fun seguridadDetiene() {
        val f = fixture(
            p2pCreate = TransportResult.Failure(
                TransportError.PEER_CONTEXT_NOT_AUTHENTICATED,
                "el par no esta autenticado",
            ),
        )

        val outcome = f.coordinator.prepare(remote)

        val degraded = assertIs<LinkOutcome.Degraded>(outcome)
        assertTrue(degraded.stopped, "SECURITY no autoriza fallback")
        assertEquals(P2pReason.FAILED, degraded.reason)
        assertEquals(0, f.relay.ops.size, "los eslabones siguientes ni se intentan: ${f.relay.ops}")
        assertNull(f.chain.activeTransport)
    }

    @Test
    @DisplayName("5.2-05 PROTOCOL y STATE tampoco cambian de medio")
    fun protocoloYEstadoDetienen() {
        for (error in listOf(TransportError.INVALID_SDP, TransportError.INVALID_STATE_TRANSITION)) {
            val f = fixture(p2pCreate = TransportResult.Failure(error, "fallo programado"))
            val degraded = assertIs<LinkOutcome.Degraded>(f.coordinator.prepare(remote))
            assertTrue(degraded.stopped, "$error no autoriza fallback")
            assertEquals(0, f.relay.ops.size, "$error no puede probar el relay")
        }
    }

    // ------------------------------------------------------------------
    // Single-flight
    // ------------------------------------------------------------------

    @Test
    @DisplayName("5.2-06 dos prepare concurrentes producen UN intento y un p2p_in_flight")
    fun singleFlight() {
        val entered = CountDownLatch(1)
        val gate = CountDownLatch(1)
        val f = fixture(gate = gate, entered = entered)

        val primero = java.util.concurrent.atomic.AtomicReference<LinkOutcome>()
        val hilo = Thread { primero.set(f.coordinator.prepare(remote)) }.apply { start() }
        assertTrue(entered.await(5, TimeUnit.SECONDS), "el primer intento no llego a awaitReady")

        val segundo = f.coordinator.prepare(remote)
        assertEquals(P2pReason.IN_FLIGHT, assertIs<LinkOutcome.Skipped>(segundo).reason)
        assertTrue(f.coordinator.isInFlight(remote))

        gate.countDown()
        hilo.join(5_000)

        assertIs<LinkOutcome.Ready>(primero.get())
        assertEquals(1, f.coordinator.attempts(remote), "solo un intento real")
        assertEquals(1, f.establishments.size)
        assertFalse(f.coordinator.isInFlight(remote))
    }

    // ------------------------------------------------------------------
    // Backoff
    // ------------------------------------------------------------------

    @Test
    @DisplayName("5.2-07 el reintento no ocurre antes de 60 s")
    fun backoff() {
        val clock = Clock()
        // El primer intento falla; el segundo (tras el backoff) tendria exito.
        val f = fixture(
            p2pReady = TransportResult.Failure(TransportError.BACKEND_ERROR, "boom"),
            clock = clock,
        )
        assertIs<LinkOutcome.Degraded>(f.coordinator.prepare(remote))
        assertEquals(1, f.coordinator.attempts(remote))

        clock.advance(P2pLinkCoordinator.DEFAULT_BACKOFF_MS - 1)
        assertEquals(
            P2pReason.BACKOFF,
            assertIs<LinkOutcome.Skipped>(f.coordinator.prepare(remote)).reason,
        )
        assertEquals(1, f.coordinator.attempts(remote), "59999 ms no basta para reintentar")

        clock.advance(1)
        f.coordinator.prepare(remote)
        assertEquals(2, f.coordinator.attempts(remote), "a los 60 s SI se reintenta")
    }

    @Test
    @DisplayName("5.2-08 close() limpia el backoff y marca p2p_closed")
    fun closeLimpiaBackoff() {
        val clock = Clock()
        val f = fixture(p2pReady = TransportResult.Failure(TransportError.BACKEND_ERROR, "boom"), clock = clock)
        f.coordinator.prepare(remote)

        f.coordinator.close(remote)

        assertEquals(P2pReason.CLOSED, f.coordinator.reason(remote))
        assertNull(f.chain.activeTransport)
        // Sin backoff pendiente, el par que vuelve se intenta de nuevo YA.
        clock.advance(1)
        f.coordinator.prepare(remote)
        assertEquals(2, f.coordinator.attempts(remote))
    }

    @Test
    @DisplayName("5.2-09 closeAll suelta todos los medios intentados")
    fun closeAll() {
        val f = fixture()
        f.coordinator.prepare(remote)
        assertEquals("p2p", f.chain.activeTransport)

        f.coordinator.closeAll()

        assertNull(f.chain.activeTransport)
    }

    // ------------------------------------------------------------------
    // Rol
    // ------------------------------------------------------------------

    @Test
    @DisplayName("5.2-10 el rol lo decide el orden lexicografico, no el tiempo")
    fun rolLexicografico() {
        assertEquals(
            com.km.node.P2pRole.OFFERER,
            com.km.node.P2pRole.forPair(IdentityId("aa".repeat(32)), IdentityId("bb".repeat(32))),
        )
        assertEquals(
            com.km.node.P2pRole.ANSWERER,
            com.km.node.P2pRole.forPair(IdentityId("bb".repeat(32)), IdentityId("aa".repeat(32))),
        )
        // Simetria: cada extremo obtiene un rol DISTINTO para el mismo par.
        val uno = IdentityId("0f".repeat(32))
        val otro = IdentityId("10".repeat(32))
        assertEquals(
            setOf(com.km.node.P2pRole.OFFERER, com.km.node.P2pRole.ANSWERER),
            setOf(
                com.km.node.P2pRole.forPair(uno, otro),
                com.km.node.P2pRole.forPair(otro, uno),
            ),
        )
    }

    @Test
    @DisplayName("5.2-11 sin cadena el coordinador no intenta y lo dice")
    fun sinCadena() {
        val coordinator = P2pLinkCoordinator(
            localIdentity = local,
            chainFor = { null },
            establishmentFor = { _, _, _ -> error("no debio construir establecimiento") },
        )
        val outcome = coordinator.prepare(remote)
        assertEquals(P2pReason.NO_CHAIN, assertIs<LinkOutcome.Skipped>(outcome).reason)
    }
}
