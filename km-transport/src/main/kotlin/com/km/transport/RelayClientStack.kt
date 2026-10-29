package com.km.transport

import com.km.model.IdentityId
import com.km.node.ChainedLinks
import com.km.node.ChainedTransport
import com.km.node.PeerContext
import com.km.node.PeerTransportManager
import com.km.node.TransportBackend
import com.km.node.TransportChain

/**
 * Motor + manager + drivers + compositor, en una sola instancia.
 *
 * ## QUE RESUELVE
 *
 * Que un cliente de rele tenga las CUATRO piezas y no tenga que recordar como se
 * enchufan entre si. En particular, dos cosas que son EMBARRADAS de hacer bien
 * a mano:
 *
 *  1. **El backend del manager es el MISMO motor.** No una segunda fachada, no
 *     un wrapper. Si se le pasara otro objeto, los bytes y la presencia
 *     viajarian por sitios distintos y la divergencia seria silenciosa: la
 *     conexion pareceria viva y el envio no llegaria, o al reves.
 *     [RelayClientStackTest] lo ata con `backend === transport`.
 *
 *  2. **El compositor del camino legado.** Sin el, cablear las dos caras deja el
 *     camino en claro sin nada, y eso se parece muchisimo a "funciona".
 *
 * ## QUE NO RESUELVE, Y SE DICE
 *
 * Que identidad se autentica en el rele. El motor habla con el rele como
 * [identity], y `SecureMessagingSession` filtra por la RAIZ del contacto. Si el
 * rele autentica el DISPOSITIVO, `from` y `remotePeerId` no coinciden y todo
 * frame se descarta como `unknownPeerFrames`. Esa es una decision de p3; aqui se
 * compone y se deja dicha.
 */
class RelayClientStack private constructor(
    /** El motor. Es a la vez `RelayTransport` y [TransportBackend]. */
    val transport: JvmRelayTransport,
    /** El manager, con el cable de recepcion de B2 ya instalado. */
    val manager: PeerTransportManager,
    private val conductorFactory: (PeerContext) -> RelayPeerChannelDriver,
) {
    /**
     * El backend del manager.
     *
     * `=== transport` por construccion: [compose] RECHAZA un manager que no
     * tenga este motor por backend, en vez de componer una pila con dos motores
     * que nadie va a notar que son dos.
     */
    val backend: TransportBackend get() = transport

    /** Identidad con la que este cliente habla con el rele. */
    val identity: IdentityId get() = transport.relayIdentityId()

    private val drivers = java.util.concurrent.ConcurrentHashMap<IdentityId, RelayPeerChannelDriver>()

    private var legacy: LegacyInboundConductor? = null

    /** El driver de un par, creandolo si es la primera vez. */
    fun driverFor(peerContext: PeerContext): RelayPeerChannelDriver =
        drivers.computeIfAbsent(peerContext.remotePeerId) { conductorFactory(peerContext) }

    /** El driver de un par, si ya existe. */
    fun driverOf(remotePeerId: IdentityId): RelayPeerChannelDriver? = drivers[remotePeerId]

    /**
     * Da de alta el camino legado sobre el cable de [backend].
     *
     * Devuelve `false` si ya habia uno. Dos compositores sobre el mismo slot
     * harian que cada frame se entregase DOS veces por el camino en claro, y
     * `KeyMessageCoreImpl` no deduplica `Message`: se verian dos mensajes
     * iguales. Por eso la segunda alta se RECHAZA y no se ignora en silencio.
     */
    fun attachLegacy(): Boolean {
        if (legacy != null) return false
        val conductor = LegacyInboundConductor.forEngine(transport)
        legacy = conductor
        conductor.attach()
        return true
    }

    /** Da de baja el camino legado y RESTAURA lo que hubiera antes. */
    fun detachLegacy(): Boolean = legacy?.detach() ?: false

    /** Si el camino legado esta dado de alta. */
    fun isLegacyAttached(): Boolean = legacy?.isAttached() ?: false

    /** Cierra el motor. El manager no tiene estado propio que cerrar. */
    fun close() {
        detachLegacy()
        transport.close()
    }

    // ------------------------------------------------------------------
    // Senalizacion: el intercambio de contactos
    // ------------------------------------------------------------------

    /**
     * Envia el portador del contacto propio a [to] por `SIGNAL/contact_exchange`.
     *
     * ## POR QUE VIVE EN LA PILA Y NO EN EL MOTOR SOLO
     *
     * Porque el motor habla con el rele, y la pila es la que sabe que este
     * cliente es "el que tiene contactos". Delegar aqui evita que el consumidor
     * tenga que compositionar dos objetos y que las dos caras se puedan
     * desalinear: el bundle que sale y el que entra usan el MISMO `kind` y el
     * MISMO `RelayClientStack`.
     */
    fun sendContactBundle(to: IdentityId, bundle: ByteArray): Result<Unit> =
        transport.sendSignal(to, RelayDataCodec.KIND_CONTACT_EXCHANGE, bundle)

    /**
     * Se suscribe al portador de contacto que llega de [from].
     *
     * MULTI-suscriptor, como `onPeerOnline`: uno mas no puede matar al otro.
     */
    fun onContactBundle(handler: (from: IdentityId, bundle: ByteArray) -> Unit) {
        transport.onSignal { decoded ->
            if (decoded !is DecodedSignal.Known) return@onSignal
            if (decoded.kind != RelayDataCodec.KIND_CONTACT_EXCHANGE) return@onSignal
            val bytes = decoded.contactBundle ?: return@onSignal
            handler(decoded.from, bytes)
        }
    }

    companion object {
        /**
         * Compone una pila cuyo `Transport` es [ChainedTransport].
         *
         * ## Invariante que se exige aqui
         *
         * El ULTIMO eslabon de la cadena tiene que ser ESTE motor. El relay es
         * el suelo: si la cadena terminara en otro backend, un fallo de todos
         * los medios no encontraria el socket autenticado y la sesion se
         * quedaria sin medio sin que nada lo dijera. Se comprueba por IDENTIDAD
         * (`===`), no por nombre.
         *
         * ## Una cadena POR PAR
         *
         * [chainFor] es el accessor que usa el adaptador. Una cadena compartida
         * por varios pares haria que el establecimiento del segundo robara la
         * autoridad del primero ([TransportChain.selected] es un unico slot).
         * Aqui se acepta una [chain] de referencia para el caso de un solo par y
         * se permite sustituir el accessor; el llamante que compone varios pares
         * DEBE pasarlo.
         */
        fun composeChained(
            transport: JvmRelayTransport,
            manager: PeerTransportManager,
            chain: TransportChain,
            chainFor: (IdentityId) -> TransportChain? = { chain },
            links: ChainedLinks = ChainedLinks.NONE,
        ): ChainedRelayStack {
            require(chain.last === transport) {
                "el ultimo eslabon de la cadena debe ser el motor del relay; era ${chain.last.name}"
            }
            val relay = compose(transport, manager)
            val chained = ChainedTransport(
                relay = transport,
                chainFor = chainFor,
                links = links,
            )
            return ChainedRelayStack(relay = relay, chain = chain, transport = chained)
        }

        /**
         * Compone la pila cuando el manager ENRUTA datos por una cadena.
         *
         * ## Por que existe esta entrada y no se relaja [compose]
         *
         * En el tier P2P el manager del nucleo tiene que enviar los bytes de
         * sesion por el medio activo del par. Para eso su backend es un
         * `PeerRouteBackend`, no el motor del relay. Relajar [compose] haria
         * que el modo `off` pudiera componerse con un backend cualquiera, y esa
         * comprobacion es justo la que impide dos motores en la misma pila.
         *
         * Aqui el invariante se enuncia sobre el ROUTER: su suelo tiene que ser
         * ESTE motor y el manager tiene que gobernar el router. Las dos cosas
         * por identidad (`===`). Los drivers siguen hablando con el motor, que
         * es el unico dueno del socket.
         *
         * @throws IllegalArgumentException si el router no envuelve este motor
         *   o si el manager no gobierna el router.
         */
        fun composeRouted(
            transport: JvmRelayTransport,
            manager: PeerTransportManager,
            route: com.km.transport.p2p.PeerRouteBackend,
        ): RelayClientStack {
            require(route.relay === transport) {
                "el router debe envolver ESTE motor del relay; envolvia ${route.relay.name}"
            }
            require(manager.backend() === route) {
                "el manager debe gobernar el router; governaba ${manager.backend().name}"
            }
            return RelayClientStack(
                transport = transport,
                manager = manager,
                conductorFactory = { ctx ->
                    RelayPeerChannelDriver(
                        manager = manager,
                        engine = transport,
                        localIdentity = transport.relayIdentityId(),
                    ).forPeer(ctx)
                },
            )
        }

        /**
         * Compone la pila sobre un motor YA CONSTRUIDO.
         *
         * @throws IllegalArgumentException si [manager] no tiene este motor por
         *   backend. Es una excepcion y no un `TransportResult.Failure` porque
         *   es un fallo de ENSAMBLADO, no de transporte: no ocurre en produccion
         *   si la pila se compone por aqui, y si ocurre significa que alguien
         *   construyo las piezas a mano y las conecto mal. Dejarlo pasar seria
         *   exactamente el fallo silencioso que esta clase existe para evitar.
         */
        fun compose(
            transport: JvmRelayTransport,
            manager: PeerTransportManager,
        ): RelayClientStack {
            require(manager.backend() === transport) {
                "el manager debe usar ESTE motor como backend; el cable de recepcion esta en el otro"
            }
            return RelayClientStack(
                transport = transport,
                manager = manager,
                conductorFactory = { ctx ->
                    RelayPeerChannelDriver(
                        manager = manager,
                        engine = transport,
                        localIdentity = transport.relayIdentityId(),
                    ).forPeer(ctx)
                },
            )
        }
    }
}
/**
 * Las tres piezas de una pila encadenada.
 *
 * Es un tipo y no un `Triple` para que el orden de los componentes no se pueda
 * confundir en el sitio de uso: `relay` es la pila de control, `chain` la
 * cadena de un par y `transport` la unica instancia que el cliente inyecta.
 */
class ChainedRelayStack(
    /** Pila de relay: motor, manager y drivers. */
    val relay: RelayClientStack,
    /** Cadena configurada para el par de referencia. */
    val chain: TransportChain,
    /** `Transport` de 11 metodos que ve el cliente. */
    val transport: ChainedTransport,
)
