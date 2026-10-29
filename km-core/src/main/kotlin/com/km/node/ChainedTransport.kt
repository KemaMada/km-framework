package com.km.node

import com.km.model.IdentityId
import com.km.protocol.ConnectionState
import com.km.protocol.Transport

/**
 * Los 11 metodos de [Transport], con UNA sola pieza para el cliente.
 *
 * ## Reparto
 *
 * ```
 *                          ChainedTransport
 *                      +----------+----------+
 *   control, ACK,      |                     |      datos de un par
 *   handlers, estado,  v                     v
 *   presencia       relay (Transport)     TransportChain(par) -> [P2P, relay]
 * ```
 *
 * - **Datos** ([send]): por la cadena DEL PAR. Si el medio P2P esta `READY`,
 *   la cadena es la que decide y la que aplica `permitsFallback`. Si no hay
 *   P2P activo, sale por el relay DIRECTO, sin encolar ni esperar: el relay ya
 *   esta autenticado y es el suelo.
 * - **ACK** ([sendAck]): SIEMPRE por el relay. Un `Ack` no lleva destinatario,
 *   asi que no hay par al que rutearlo por P2P; y un solo socket autenticado
 *   por identidad es la unica forma de que el relay siga viendo presencia.
 * - **Handlers, estado y presencia**: del relay. El byte inbound de P2P llega
 *   por `PeerTransportManager.onData`, no por [setMessageHandler]; asi no hay
 *   doble entrega.
 *
 * ## Lo que este adaptador NO hace
 *
 * No negocia, no cifra y no conoce SDP: solo enruta entre dos contratos que ya
 * existian. Por eso vive en `km-core` (donde viven ambos) y se prueba con
 * fakes, sin red ni WebRTC.
 *
 * ## Invariante de una sola entrega
 *
 * Cuando el medio activo es P2P, [send] llama a la cadena y NUNCA ademas al
 * relay. Cuando el medio activo no es P2P, llama al relay y NUNCA ademas a la
 * cadena. La cadena ya contiene el relay como ultimo eslabon, de modo que
 * invocarla y luego llamar al relay seria un segundo camino para los mismos
 * bytes.
 */
class ChainedTransport(
    /** El motor del relay: socket, AUTH, control y presencia. */
    private val relay: Transport,
    /**
     * Cadena del par, o `null` si aun no se monto.
     *
     * Es una funcion y no un mapa mutable expuesto porque el dueno de las
     * cadenas es el coordinador; el adaptador solo pregunta.
     */
    private val chainFor: (IdentityId) -> TransportChain? = { null },
    /** Ganchos de ciclo de vida del enlace, tipicamente el coordinador. */
    private val links: ChainedLinks = ChainedLinks.NONE,
    /** Nombre del eslabon P2P dentro de la cadena. */
    private val p2pLinkName: String = Medium.P2P.linkName,
) : Transport {

    init {
        // La presencia se cablea UNA vez, en la composicion, no cuando el
        // consumidor registra su handler: si dependiera del consumidor, un
        // cliente que solo envia nunca prepararia el enlace P2P.
        relay.onPeerOnline { links.onPeerOnline(it) }
        relay.onPeerOffline { links.onPeerOffline(it) }
    }

    // ------------------------------------------------------------------
    // Sesion y control: SIEMPRE relay
    // ------------------------------------------------------------------

    override fun connect(): Result<Unit> = relay.connect()

    /**
     * Suelta la sesion del relay DESPUES de cerrar los enlaces por par.
     *
     * El orden importa: cerrar el relay primero dejaria a los enlaces P2P
     * vivos sin su suelo de fallback, y un datagrama tardio encontraria un
     * coordinador que ya no puede reaccionar.
     */
    override fun disconnect() {
        runCatching { links.closeAll() }
        relay.disconnect()
    }

    override fun sendAck(ackBytes: ByteArray): Result<Unit> = relay.sendAck(ackBytes)

    override fun setMessageHandler(handler: (ByteArray) -> Unit) = relay.setMessageHandler(handler)

    override fun setAckHandler(handler: (ByteArray) -> Unit) = relay.setAckHandler(handler)

    override fun isOnline(): Boolean = relay.isOnline()

    override fun connectionState(): ConnectionState = relay.connectionState()

    override fun onStateChange(handler: (ConnectionState) -> Unit) = relay.onStateChange(handler)

    override fun onPeerOnline(handler: (IdentityId) -> Unit) = relay.onPeerOnline(handler)

    override fun onPeerOffline(handler: (IdentityId) -> Unit) = relay.onPeerOffline(handler)

    // ------------------------------------------------------------------
    // Datos: cadena del par o relay DIRECTO
    // ------------------------------------------------------------------

    /**
     * Envia un `Message` de aplicacion por el medio ya establecido.
     *
     * Mientras el par negocia, o si no hay enlace P2P, sale por relay sin
     * encolar: encolar romperia el orden y arriesgaria un duplicado cuando la
     * sesion reintente por su cuenta.
     */
    override fun send(data: ByteArray, to: IdentityId): Result<Unit> {
        val chain = chainFor(to)
        if (chain != null && chain.activeTransport == p2pLinkName) {
            // La cadena contiene [P2P, relay]: si P2P falla con categoria
            // TRANSPORT, es ELLA quien cae al relay. Llamar tambien a
            // relay.send aqui seria el segundo camino que este adaptador
            // existe para impedir.
            return when (val r = chain.sendData(to, data)) {
                is TransportResult.Success -> Result.success(Unit)
                is TransportResult.Failure -> Result.failure(TransportSendException(r.error, r.message))
            }
        }
        return relay.send(data, to)
    }

    // ------------------------------------------------------------------
    // Observabilidad del medio
    // ------------------------------------------------------------------

    /** Medio activo del par, o `null` si aun no hay establecimiento. */
    fun activeMedium(remotePeerId: IdentityId): Medium? =
        when (chainFor(remotePeerId)?.activeTransport) {
            null -> null
            p2pLinkName -> Medium.P2P
            else -> Medium.RELAY
        }
}

/**
 * Ganchos de ciclo de vida del enlace por par.
 *
 * Existe como interfaz propia —y no como dos lambdas sueltas— para que el
 * coordinador pueda pasarse como una sola dependencia y para que el test
 * pueda contar llamadas sin construir un coordinador entero.
 */
interface ChainedLinks {

    /** El relay declaro al par presente: preparar el enlace P2P. */
    fun onPeerOnline(remotePeerId: IdentityId)

    /** El par desaparecio: cerrar el enlace y no reintentar. */
    fun onPeerOffline(remotePeerId: IdentityId)

    /** Cierre local: soltar TODOS los enlaces. */
    fun closeAll()

    companion object {
        /** Sin ganchos: el adaptador se comporta como relay-only. */
        val NONE: ChainedLinks = object : ChainedLinks {
            override fun onPeerOnline(remotePeerId: IdentityId) = Unit
            override fun onPeerOffline(remotePeerId: IdentityId) = Unit
            override fun closeAll() = Unit
        }
    }
}

/**
 * Fallo de [ChainedTransport.send] con la categoria del medio intacta.
 *
 * `Transport.send` devuelve `Result`, que no tiene categoria; envolverla aqui
 * permite al llamante distinguir un fallo de seguridad de uno de transporte
 * sin perder el codigo original.
 */
class TransportSendException(
    val error: TransportError,
    message: String,
) : IllegalStateException("${error.name}: $message")
