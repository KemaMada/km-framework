package com.km.transport

import com.km.model.IdentityId
import com.km.node.PeerContext
import com.km.node.PeerTransportManager
import com.km.node.TransportBackend

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