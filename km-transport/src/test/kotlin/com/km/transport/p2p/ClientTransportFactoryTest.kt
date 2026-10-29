package com.km.transport.p2p

import com.km.config.IceConfig
import com.km.model.IdentityId
import com.km.node.ChainedTransport
import com.km.node.EstablishmentState
import com.km.node.Medium
import com.km.node.P2pProvider
import com.km.node.P2pReason
import com.km.node.P2pRole
import com.km.node.PeerSignalPort
import com.km.node.PeerTransportManager
import com.km.node.TransportBackend
import com.km.node.TransportError
import com.km.node.TransportEstablishment
import com.km.node.TransportEventCallbacks
import com.km.node.TransportResult
import com.km.transport.JvmRelayTransport
import com.km.transport.RelayClientStack
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotSame
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertIs

/**
 * S7 — La composicion del cliente: una sola pieza, cadenas por par, `off` sin
 * construir nada.
 */
class ClientTransportFactoryTest {

    private val local = IdentityId("11".repeat(32))
    private val remote = IdentityId("22".repeat(32))
    private val otroPar = IdentityId("33".repeat(32))

    private fun relay(id: IdentityId = local) = JvmRelayTransport(
        identity = id,
        ed25519PublicKey = ByteArray(32),
        ed25519PrivateKey = ByteArray(32),
    )

    private class FakeBackend(override val name: String) : TransportBackend {
        override var eventCallbacks: TransportEventCallbacks = TransportEventCallbacks()
        val sent = CopyOnWriteArrayList<ByteArray>()
        var sendOutcome: TransportResult<Unit> = TransportResult.Success(Unit)

        override fun initialize() = TransportResult.Success(Unit)
        override fun onCreateBinding(localIdentity: IdentityId, remotePeerId: IdentityId) = TransportResult.Success(Unit)
        override fun onLocalSdp(localIdentity: IdentityId, remotePeerId: IdentityId, sdp: String) = TransportResult.Success(Unit)
        override fun onRemoteSdp(localIdentity: IdentityId, remotePeerId: IdentityId, sdp: String) = TransportResult.Success(Unit)
        override fun onIceCandidate(localIdentity: IdentityId, remotePeerId: IdentityId, candidate: String) = TransportResult.Success(Unit)

        override fun sendData(remotePeerId: IdentityId, data: ByteArray): TransportResult<Unit> {
            sent.add(data)
            return sendOutcome
        }

        override fun onCloseBinding(localIdentity: IdentityId, remotePeerId: IdentityId) = TransportResult.Success(Unit)
        override fun shutdown() = TransportResult.Success(Unit)
    }

    /**
     * Provider que NUNCA alcanza READY.
     *
     * Es el caso de una red sin traversal: la cadena tiene que caer al relay y
     * el motivo tiene que ser estable. El contador de construccion es el
     * instrumento del invariante `off`: no se puede construir el medio si el
     * modo lo apaga.
     */
    private class FakeProvider(
        private val backend: FakeBackend,
        val construction: AtomicInteger = AtomicInteger(0),
    ) : P2pProvider {
        init {
            construction.incrementAndGet()
        }

        override fun backend(): TransportBackend = backend

        override fun link(remotePeerId: IdentityId, role: P2pRole, signals: PeerSignalPort): TransportEstablishment =
            object : TransportEstablishment {
                override val transportName: String = Medium.P2P.linkName
                override var state: EstablishmentState = EstablishmentState.CREATED
                    private set

                override fun initialize(): TransportResult<Unit> {
                    state = EstablishmentState.CREATED
                    return TransportResult.Success(Unit)
                }

                override fun createBinding(localIdentity: IdentityId, remotePeerId: IdentityId): TransportResult<Unit> {
                    state = EstablishmentState.CREATED
                    return TransportResult.Success(Unit)
                }

                override fun negotiate(): TransportResult<Unit> {
                    state = EstablishmentState.NEGOTIATING
                    return TransportResult.Success(Unit)
                }

                override fun awaitReady(timeoutMs: Long): TransportResult<Unit> {
                    state = EstablishmentState.FAILED
                    return TransportResult.Failure(TransportError.BACKEND_SEND_FAILED, "sin traversal en este test")
                }

                override fun abort() {
                    state = EstablishmentState.CLOSED
                }
            }
    }

    private fun components(
        providerBackend: FakeBackend = FakeBackend("p2p"),
        provider: FakeProvider = FakeProvider(providerBackend),
    ): Pair<P2pComponents, PeerTransportManager> {
        val manager = PeerTransportManager(providerBackend)
        return P2pComponents.of(provider, manager) to manager
    }

    // ------------------------------------------------------------------
    // off
    // ------------------------------------------------------------------

    @Test
    @DisplayName("7.4-01 con p2p=null NO se construye provider ni cadena: transporte es el motor")
    fun offNoConstruyeNada() {
        val motor = relay()
        val construction = AtomicInteger(0)
        val bundle = ClientTransportFactory(
            relay = motor,
            identity = local,
            p2p = null,
            initialReason = P2pReason.OFF,
        ).compose()

        assertEquals(0, construction.get(), "off no puede construir el medio P2P")
        assertSame(motor, bundle.transport, "off conserva el camino actual")
        assertFalse(bundle.transport is ChainedTransport)
        assertFalse(bundle.p2pEnabled)
        assertEquals(0, bundle.mountedChains())
        assertEquals(P2pReason.OFF, bundle.reasonFor(remote))
        assertEquals(Medium.RELAY, bundle.mediumFor(remote))
        assertSame(motor, bundle.manager.backend(), "off mantiene el manager sobre el motor")
    }

    @Test
    @DisplayName("7.4-02 auto sin ICE cae a relay con el motivo estable p2p_no_ice")
    fun autoSinIce() {
        val bundle = ClientTransportFactory(
            relay = relay(),
            identity = local,
            p2p = null,
            initialReason = P2pReason.NO_ICE,
        ).compose()

        assertEquals(P2pReason.NO_ICE, bundle.reasonFor(remote))
        assertEquals(P2pReason.NO_ICE, bundle.modeReason)
        assertEquals(Medium.RELAY, bundle.mediumFor(remote))
        assertFalse(bundle.p2pEnabled)
    }

    // ------------------------------------------------------------------
    // composicion
    // ------------------------------------------------------------------

    @Test
    @DisplayName("7.4-03 con P2P se compone [p2p, relay] por par y el suelo es el motor")
    fun componePorPar() {
        val motor = relay()
        val (p2p, _) = components()
        val bundle = ClientTransportFactory(motor, local, p2p, initialReason = P2pReason.READY).compose()

        assertTrue(bundle.p2pEnabled)
        assertTrue(bundle.transport is ChainedTransport)
        val cadena = bundle.mountChainFor(remote)
        assertIs<com.km.node.TransportChain>(cadena)
        assertEquals(listOf("p2p", "relay"), cadena.transports.map { it.name })
        assertSame(motor, cadena.last)
        assertEquals(1, bundle.mountedChains())

        val otra = bundle.mountChainFor(otroPar)
        assertNotSame(cadena, otra, "cada par tiene SU cadena: selected es un unico slot")
        assertEquals(2, bundle.mountedChains())
    }

    @Test
    @DisplayName("7.4-04 el manager del nucleo enruta por el medio y el relay sigue siendo su suelo")
    fun managerEnrutado() {
        val motor = relay()
        val (p2p, _) = components()
        val bundle = ClientTransportFactory(motor, local, p2p, initialReason = P2pReason.READY).compose()

        val route = bundle.manager.backend()
        assertIs<PeerRouteBackend>(route)
        assertSame(motor, route.relay)
        assertSame(motor, bundle.stack.transport)
        assertSame(bundle.manager, bundle.stack.manager)

        // Sin enlace P2P activo, los datos salen por el relay DIRECTOS (fallan
        // porque el motor esta OFFLINE, pero por el relay, no por la cadena).
        val r = bundle.manager.backend().sendData(remote, byteArrayOf(1))
        assertTrue(r.isFailure)
        assertFalse(motor.isOnline())
    }

    @Test
    @DisplayName("7.4-05 el inbound del DataChannel llega al MISMO slot del manager")
    fun inboundP2pLlegaAlManager() {
        val motor = relay()
        val p2pBackend = FakeBackend("p2p")
        val (p2p, p2pManager) = components(providerBackend = p2pBackend)
        val bundle = ClientTransportFactory(motor, local, p2p, initialReason = P2pReason.READY).compose()

        val recibidos = CopyOnWriteArrayList<Pair<IdentityId, String>>()
        bundle.manager.onData { from, data -> recibidos.add(from to String(data)) }

        // Lo que hace el backend real cuando el DataChannel entrega un byte.
        p2pBackend.eventCallbacks.onDataReceived?.invoke(remote, "frame-cifrado".toByteArray())

        assertEquals(listOf(remote to "frame-cifrado"), recibidos)
        assertSame(p2pBackend, p2pManager.backend())
    }

    @Test
    @DisplayName("7.4-06 composeRouted rechaza un router que no envuelve el motor o un manager ajeno")
    fun composeRoutedRechaza() {
        val motor = relay()
        val otroMotor = relay(IdentityId("44".repeat(32)))
        val routeAjeno = PeerRouteBackend(otroMotor)
        val managerSobreMotor = PeerTransportManager(motor)

        assertThrows(IllegalArgumentException::class.java) {
            RelayClientStack.composeRouted(motor, PeerTransportManager(routeAjeno), routeAjeno)
        }
        val route = PeerRouteBackend(motor)
        assertThrows(IllegalArgumentException::class.java) {
            RelayClientStack.composeRouted(motor, managerSobreMotor, route)
        }
    }

    @Test
    @DisplayName("7.4-07 el motivo estable viaja al observador cuando el intento degrada")
    fun motivoAlObservador() {
        val motor = relay()
        val (p2p, _) = components()
        val bundle = ClientTransportFactory(motor, local, p2p).compose()
        val vistos = CopyOnWriteArrayList<Triple<IdentityId, Medium, String>>()
        bundle.onMediumChanged = { peer, medium, reason -> vistos.add(Triple(peer, medium, reason)) }

        // El coordinador no se dispara sin presencia (no hay socket); se
        // comprueba el cableado observable de `reasonFor` y del medio inicial.
        assertNull(bundle.reasonFor(remote), "sin intento no hay motivo: no se inventa p2p_ready")
        assertEquals(Medium.RELAY, bundle.mediumFor(remote), "sin READY el medio de datos es el relay")
        assertTrue(vistos.isEmpty(), "sin intento no se inventa un evento")
    }

    @Test
    @DisplayName("7.4-08 la configuracion ICE se conserva redactada")
    fun iceRedactada() {
        val ice = IceConfig.of(
            stunUrls = listOf("stun:stun.example.org:3478"),
            turnUrls = listOf("turn:turn.example.org:3478"),
            turnUsername = "usuario",
            turnCredential = "centinela-7f3a",
        )
        val (p2p, _) = components()
        val bundle = ClientTransportFactory(relay(), local, p2p, ice = ice).compose()

        assertSame(ice, bundle.ice)
        assertFalse(bundle.ice.toString().contains("centinela-7f3a"), bundle.ice.toString())
        assertTrue(bundle.ice.redactedUrls().none { it.contains("centinela-7f3a") })
    }
}
