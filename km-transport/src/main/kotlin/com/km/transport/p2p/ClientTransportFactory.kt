package com.km.transport.p2p

import com.km.config.IceConfig
import com.km.model.IdentityId
import com.km.node.ChainedLinks
import com.km.node.ChainedTransport
import com.km.node.Medium
import com.km.node.P2pProvider
import com.km.node.P2pReason
import com.km.node.P2pRole
import com.km.node.PeerTransportManager
import com.km.node.TransportBackend
import com.km.node.TransportChain
import com.km.protocol.Transport
import com.km.transport.JvmRelayTransport
import com.km.transport.RelayClientStack
import java.util.concurrent.ConcurrentHashMap

/**
 * Las tres piezas del medio P2P que la composicion necesita.
 *
 * Se agrupan en un tipo —y no en tres parametros sueltos— porque la coherencia
 * entre ellas es un invariante: el manager P2P tiene que gobernar EXACTAMENTE el
 * backend que entrega el provider. Si se pudieran pasar por separado, un
 * manager sobre otro backend compilaria y el DataChannel no llegaria a ninguna
 * sesion.
 *
 * El escritorio construye esto SOLO cuando el modo no es `off` y hay ICE: en
 * `off` la fabrica se llama con `p2p = null` y no se toca `km-webrtc`.
 */
class P2pComponents private constructor(
    val provider: P2pProvider,
    val manager: PeerTransportManager,
) {
    /** El backend del medio, que es el que entra en la cadena. */
    val backend: TransportBackend get() = provider.backend()

    init {
        require(manager.backend() === provider.backend()) {
            "el manager P2P debe gobernar el backend del provider: los bytes entraarian por otro manager"
        }
    }

    companion object {
        fun of(provider: P2pProvider, manager: PeerTransportManager): P2pComponents =
            P2pComponents(provider, manager)
    }
}

/**
 * La pieza UNICA que el cliente inyecta en el nucleo.
 *
 * `transport` es lo que ve `KeyMessageCoreImpl`; `manager` es lo que ve la capa
 * de sesiones y el manager que conduce los bindings. En modo `off` las dos son
 * las de siempre (motor + manager sobre el motor) y no hay cadena.
 */
class ClientTransportBundle internal constructor(
    /** El `Transport` de once metodos: relay puro o chained. */
    val transport: Transport,
    /** El manager que deben usar las sesiones (relay puro o enrutado). */
    val manager: PeerTransportManager,
    /** Motor + manager + drivers, ya compuestos. */
    val stack: RelayClientStack,
    /** Configuracion ICE efectiva. Redactada en `toString`. */
    val ice: IceConfig,
    /** Si se construyo el tier P2P. */
    val p2pEnabled: Boolean,
    private val route: PeerRouteBackend?,
    private val coordinator: P2pLinkCoordinator?,
    private val p2pManager: PeerTransportManager?,
    private val chains: Map<IdentityId, TransportChain>,
    private val mounts: (IdentityId) -> TransportChain?,
    private val initialReason: String?,
) {
    /**
     * Observador del medio por par.
     *
     * Se asigna DESPUES de componer porque el bus de eventos del cliente nace
     * mas tarde. Un solo observador: el cliente tiene una pantalla.
     */
    @Volatile
    var onMediumChanged: ((IdentityId, Medium, String) -> Unit)? = null

    /** Medio activo del par. Sin enlace P2P, el relay es el medio de datos. */
    fun mediumFor(remotePeerId: IdentityId): Medium =
        if (chains[remotePeerId]?.activeTransport == Medium.P2P.linkName) Medium.P2P else Medium.RELAY

    /**
     * Motivo estable del ultimo intento, o el motivo inicial del modo.
     *
     * Inicial: `p2p_off` con el tier apagado, `p2p_no_ice` en `auto` sin ICE,
     * `p2p_ready` cuando hay configuracion y aun no se intento nada.
     */
    fun reasonFor(remotePeerId: IdentityId): String? =
        coordinator?.reason(remotePeerId) ?: initialReason

    /** Motivo del modo, independiente de que haya pares. */
    val modeReason: String? get() = initialReason

    /** Cuantas cadenas por par se han montado. Solo lectura, para diagnostico. */
    fun mountedChains(): Int = chains.size

    /**
     * Cadena del par, montandola si aun no existe.
     *
     * Es `internal` porque es una costura de COMPOSICION: el cliente no debe
     * montar cadenas a mano, pero los tests tienen que poder comprobar el orden
     * `[P2P, relay]` sin depender de un socket que declare presencia.
     */
    internal fun chainOf(remotePeerId: IdentityId): TransportChain? = chains[remotePeerId]

    /** Monta (o devuelve) la cadena del par. Solo para tests de composicion. */
    internal fun mountChainFor(remotePeerId: IdentityId): TransportChain? = mounts(remotePeerId)

    internal fun reportMedium(remotePeerId: IdentityId, medium: Medium, reason: String) {
        onMediumChanged?.invoke(remotePeerId, medium, reason)
    }

    /**
     * Suelta el medio P2P y sus bindings.
     *
     * No cierra el motor del relay: de eso se encarga [RelayClientStack.close],
     * que el cliente ya llama. Aqui solo se libera lo que esta composicion
     * anadio, para que `off` y `on` no dejen recursos distintos.
     */
    fun close() {
        runCatching { coordinator?.closeAll() }
        chains.values.forEach { runCatching { it.releaseSelected() } }
        runCatching { p2pManager?.clear() }
    }
}

/**
 * Compone `[P2P, relay]` por par y devuelve UNA pieza para el nucleo.
 *
 * ## Que decide esta clase y que NO
 *
 * Decide la TOPOLOGIA: quien es el suelo, que manager gobierna que backend y
 * donde se monta cada cadena. NO decide el modo (`off`/`auto`/`on`): eso es del
 * cliente, que llama con `p2p = null` o con [P2pComponents]. Tampoco conoce
 * WebRTC: el provider llega inyectado por el puerto de `km-core`.
 *
 * ## Por que una cadena POR PAR
 *
 * `TransportChain.selected` es un unico slot. Una cadena global haria que el
 * establecimiento del segundo par robara la autoridad del primero. Los
 * backends ya son multi-par, asi que lo unico por par es la cadena.
 *
 * ## `off` no construye nada
 *
 * Con `p2p = null` se devuelve exactamente el camino actual: manager sobre el
 * motor, `transport = relay`, sin `ChainedTransport` y sin coordinador. Es el
 * invariante de byte-identidad del baseline.
 */
class ClientTransportFactory(
    private val relay: JvmRelayTransport,
    private val identity: IdentityId,
    /** `null` = tier P2P apagado para esta ejecucion. */
    private val p2p: P2pComponents?,
    private val ice: IceConfig = IceConfig.hostOnly(),
    /** Motivo inicial cuando no hay P2P o no hay ICE; `null` si aun no se intento. */
    private val initialReason: String? = if (p2p == null) P2pReason.OFF else null,
    private val managerFactory: (TransportBackend) -> PeerTransportManager = { PeerTransportManager(it) },
    private val now: () -> Long = System::currentTimeMillis,
) {

    fun compose(): ClientTransportBundle {
        if (p2p == null) {
            val manager = managerFactory(relay)
            val stack = RelayClientStack.compose(relay, manager)
            return ClientTransportBundle(
                transport = relay,
                manager = manager,
                stack = stack,
                ice = ice,
                p2pEnabled = false,
                route = null,
                coordinator = null,
                p2pManager = null,
                chains = emptyMap(),
                mounts = { null },
                initialReason = initialReason,
            )
        }

        val route = PeerRouteBackend(relay)
        val manager = managerFactory(route)
        val stack = RelayClientStack.composeRouted(relay, manager, route)

        // El manager P2P entrega sus bytes al manager del nucleo por el MISMO
        // contrato. Sin esto, el DataChannel seria un medio que escribe pero
        // cuyo inbound no llega a ninguna sesion.
        p2p.manager.onData { from, data -> route.deliverInbound(from, data) }

        val signalPort = RelaySignalPort(relay)
        val chains = ConcurrentHashMap<IdentityId, TransportChain>()
        val chainFor: (IdentityId) -> TransportChain? = { peer ->
            chains.computeIfAbsent(peer) { TransportChain(listOf(p2p.backend, relay), now) }
        }
        route.chainFor = chainFor

        val coordinator = P2pLinkCoordinator(
            localIdentity = identity,
            chainFor = chainFor,
            establishmentFor = { peer, role, backend ->
                if (backend === p2p.backend) {
                    p2p.provider.link(peer, role, signalPort)
                } else {
                    RelayTransportEstablishment(backend) { remote -> relay.isPeerPresent(remote) }
                }
            },
            now = now,
        )

        lateinit var bundle: ClientTransportBundle
        val links = object : ChainedLinks {
            override fun onPeerOnline(remotePeerId: IdentityId) {
                report(remotePeerId, coordinator.prepare(remotePeerId))
            }

            override fun onPeerOffline(remotePeerId: IdentityId) {
                coordinator.close(remotePeerId)
                bundle.reportMedium(remotePeerId, Medium.RELAY, P2pReason.CLOSED)
            }

            override fun closeAll() {
                coordinator.closeAll()
                chains.values.forEach { runCatching { it.releaseSelected() } }
            }

            private fun report(remotePeerId: IdentityId, outcome: LinkOutcome) {
                val medium = if (outcome is LinkOutcome.Ready) Medium.P2P else Medium.RELAY
                bundle.reportMedium(remotePeerId, medium, outcome.reason)
            }
        }

        val chained = ChainedTransport(
            relay = relay,
            chainFor = chainFor,
            links = links,
        )
        bundle = ClientTransportBundle(
            transport = chained,
            manager = manager,
            stack = stack,
            ice = ice,
            p2pEnabled = true,
            route = route,
            coordinator = coordinator,
            p2pManager = p2p.manager,
            chains = chains,
            mounts = chainFor,
            initialReason = initialReason,
        )
        return bundle
    }

    companion object {
        /** Roles de prueba: un provider que no llega a construirse. */
        internal fun roleFor(local: IdentityId, remote: IdentityId): P2pRole =
            P2pRole.forPair(local, remote)
    }
}
