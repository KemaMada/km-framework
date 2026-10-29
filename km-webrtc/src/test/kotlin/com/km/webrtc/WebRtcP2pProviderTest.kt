package com.km.webrtc

import com.km.crypto.Ed25519Impl
import com.km.model.IdentityId
import com.km.node.TransportError
import com.km.node.TransportErrorCategory
import com.km.node.TransportResult
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertIs

/**
 * S6 — `WebRtcP2pProvider` detras del puerto y `attach()` idempotente.
 *
 * Sin handshake WebRTC completo: lo que se prueba es la COMPOSICION (que
 * backend, que rol, que error cuando no hay contexto) y que reenganchar dos
 * veces no duplica la entrega de bytes. El traversal real tiene sus propios
 * tests (`RealTransportChainTest`, `IceLifecycleTest`).
 */
class WebRtcP2pProviderTest {

    private val ed25519 = Ed25519Impl()
    private val alice = AuthenticatedNode(ed25519, "alice")
    private val bob = AuthenticatedNode(ed25519, "bob")

    @AfterEach
    fun tearDown() {
        listOf(alice, bob).forEach {
            runCatching { it.transport.shutdown() }
            runCatching { it.transport.disposeCallbacks() }
        }
    }

    private fun providerA(contexts: Map<IdentityId, com.km.node.PeerContext> = emptyMap()) =
        WebRtcP2pProvider(
            manager = alice.manager,
            localIdentity = alice.identityId,
            transport = alice.transport,
            peerContextFor = { contexts[it] },
        )

    // ------------------------------------------------------------------
    // Composicion
    // ------------------------------------------------------------------

    @Test
    @DisplayName("6.4-08 el backend del provider es EXACTAMENTE el transporte real")
    fun backendEsElTransporte() {
        assertSame(alice.transport, providerA().backend())
    }

    @Test
    @DisplayName("6.4-09 el manager tiene que gobernar el mismo transporte")
    fun managerDelMismoTransporte() {
        assertThrows(IllegalArgumentException::class.java) {
            WebRtcP2pProvider(
                manager = alice.manager,
                localIdentity = alice.identityId,
                transport = bob.transport,
                peerContextFor = { null },
            )
        }
    }

    @Test
    @DisplayName("6.4-10 un par con contexto produce un establecimiento 'p2p'")
    fun linkConContexto() {
        val ctx = alice.authenticatedContextTowards(bob)
        val signals = FakeSignals()
        val est = providerA(mapOf(bob.identityId to ctx)).link(bob.identityId, com.km.node.P2pRole.OFFERER, signals)

        assertEquals("p2p", est.transportName)
        assertTrue(est.initialize().isSuccess)
    }

    @Test
    @DisplayName("6.4-11 sin contexto autenticado, 'no listo' es TRANSPORT y no STATE")
    fun sinContextoEsTransport() {
        val est = providerA().link(bob.identityId, com.km.node.P2pRole.ANSWERER, FakeSignals())

        val failure = assertIs<TransportResult.Failure>(est.initialize())

        assertEquals(TransportError.BACKEND_NOT_READY, failure.error)
        assertEquals(TransportErrorCategory.TRANSPORT, failure.error.category)
        assertTrue(failure.error.permitsFallback, "sin contexto el relay sigue siendo el suelo")
    }

    // ------------------------------------------------------------------
    // attach idempotente
    // ------------------------------------------------------------------

    @Test
    @DisplayName("6.4-12 attach() dos veces NO duplica la entrega de datos")
    fun attachIdempotente() {
        val ctx = alice.authenticatedContextTowards(bob)
        val peer = ManagedWebRtcPeer(alice.identityId, ctx, alice.manager, alice.transport)
        val entregas = AtomicInteger(0)
        val crudos = AtomicInteger(0)
        peer.onApplicationData = { entregas.incrementAndGet() }
        peer.onTransportData = { crudos.incrementAndGet() }

        peer.attach()
        peer.attach()

        assertTrue(peer.isAttached)
        alice.transport.eventCallbacks.onDataReceived?.invoke(bob.identityId, byteArrayOf(1, 2, 3))

        assertEquals(1, entregas.get(), "un byte inbound se entrega UNA vez aunque attach se llame dos")
        assertEquals(1, crudos.get())
        assertEquals(1, peer.receivedData.size)
    }

    @Test
    @DisplayName("6.4-13 createBinding reusa un binding ya abierto y NO devuelve BINDING_ALREADY_EXISTS")
    fun bindingReusado() {
        val ctx = alice.authenticatedContextTowards(bob)
        // El coordinador crea el binding primero.
        assertTrue(alice.manager.createBinding(alice.identityId, ctx).isSuccess)

        val peer = ManagedWebRtcPeer(alice.identityId, ctx, alice.manager, alice.transport)
        val est = WebRtcEstablishment(peer, WebRtcEstablishment.Role.OFFERER)

        val r = est.createBinding(alice.identityId, bob.identityId)

        assertTrue(r.isSuccess, "D9: reusar no es un fallo de estado: $r")
        assertTrue(peer.isAttached, "reusar el binding vuelve a cablear los eventos")
    }

    // ------------------------------------------------------------------
    // Senalizacion por puerto: rol -> kind
    // ------------------------------------------------------------------

    @Test
    @DisplayName("6.4-14 el offerer emite sdp_offer por el puerto (no sdp_answer)")
    fun kindPorRol() {
        val ctx = alice.authenticatedContextTowards(bob)
        val signals = FakeSignals()
        val est = providerA(mapOf(bob.identityId to ctx))
            .link(bob.identityId, com.km.node.P2pRole.OFFERER, signals)
        try {
            assertTrue(est.initialize().isSuccess)
            assertTrue(est.createBinding(alice.identityId, bob.identityId).isSuccess)
            assertTrue(est.negotiate().isSuccess)

            // `startOffering` espera la SDP local y el `sigaling.sendSdp` corre
            // DESPUES de liberar el latch: la emision es asincrona respecto de
            // `negotiate`. Se espera el hecho, no se asume el orden.
            val deadline = System.currentTimeMillis() + 2_000
            var emitido = signals.sent.firstOrNull { it.first == com.km.node.P2pSignalKind.SDP_OFFER }
            while (emitido == null && System.currentTimeMillis() < deadline) {
                Thread.sleep(10)
                emitido = signals.sent.firstOrNull { it.first == com.km.node.P2pSignalKind.SDP_OFFER }
            }
            assertTrue(emitido != null, "el offerer debe emitir sdp_offer; emitio: ${signals.sent}")
            assertFalse(emitido!!.second.isNullOrBlank(), "el SDP de la oferta no puede ir vacio")
        } finally {
            est.abort()
        }
    }

    @Test
    @DisplayName("6.4-15 abort() da de baja la suscripcion de SIGNAL: no quedan handlers vivos")
    fun abortBajaSuscripcion() {
        val ctx = alice.authenticatedContextTowards(bob)
        val signals = FakeSignals()
        val est = providerA(mapOf(bob.identityId to ctx))
            .link(bob.identityId, com.km.node.P2pRole.ANSWERER, signals)

        assertEquals(1, signals.handlerCount(), "el establecimiento se suscribe al nacer")
        est.abort()
        assertEquals(0, signals.handlerCount(), "abort tiene que dar de baja: si no, cada reintento acumula")

        est.abort()
        assertEquals(0, signals.handlerCount(), "abort es idempotente")
    }

    /** Puerto de senalizacion en memoria, con registro de lo emitido. */
    private class FakeSignals : com.km.node.PeerSignalPort {
        val sent = CopyOnWriteArrayList<Triple<String, String?, String?>>()
        private val handlers = CopyOnWriteArrayList<(IdentityId, String, String?, String?) -> Unit>()

        override fun sendSignal(to: IdentityId, kind: String, sdp: String?, candidate: String?): Boolean {
            sent.add(Triple(kind, sdp, candidate))
            return true
        }

        override fun onSignal(
            handler: (from: IdentityId, kind: String, sdp: String?, candidate: String?) -> Unit,
        ): () -> Unit {
            handlers.add(handler)
            return { handlers.remove(handler) }
        }

        /** Consumidores vivos: la baja de [com.km.node.PeerSignalPort] tiene que verse. */
        fun handlerCount(): Int = handlers.size

        fun emit(from: IdentityId, kind: String, sdp: String? = null, candidate: String? = null) {
            handlers.forEach { it(from, kind, sdp, candidate) }
        }
    }
}
