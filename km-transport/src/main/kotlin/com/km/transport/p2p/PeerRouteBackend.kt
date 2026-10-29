package com.km.transport.p2p

import com.km.model.IdentityId
import com.km.node.Medium
import com.km.node.TransportBackend
import com.km.node.TransportChain
import com.km.node.TransportEventCallbacks
import com.km.node.TransportResult
import java.util.concurrent.atomic.AtomicReference

/**
 * El backend que ve el [com.km.node.PeerTransportManager] cuando hay tier P2P.
 *
 * ## Que resuelve
 *
 * `PeerTransportManager` exige UN backend en su constructor, y las sesiones
 * cifradas envian por `manager.sendData`. Si el manager se construyera sobre el
 * motor del relay —como en el modo `off`— ningun byte de sesion pasaria nunca
 * por el DataChannel: el tier P2P existiria y no transportaria nada.
 *
 * Este backend enruta **solo los datos** por la cadena del par:
 *
 * ```
 * manager.sendData(peer, bytes)
 *        |
 *        v
 *   PeerRouteBackend ---> TransportChain(peer) = [P2P, relay]
 *        |
 *        +-- si no hay enlace P2P activo ---> relay.sendData (directo, sin cola)
 * ```
 *
 * El resto de operaciones de [TransportBackend] se delegan al relay. En
 * particular, las de senalizacion (`onLocalSdp`, `onRemoteSdp`,
 * `onIceCandidate`) van SIEMPRE al motor del relay: el binding LOGICO del
 * manager es el del relay, y quien negocia WebRTC es el establecimiento del
 * coordinador con su propio manager, no este.
 *
 * ## Inbound sin doble entrega
 *
 * El manager cablea [eventCallbacks] aqui (una sola vez, en su `init`). Los
 * bytes del relay llegan al motor y se reenvian al slot; los del DataChannel
 * llegan por el manager P2P y se inyectan con [deliverInbound]. El resultado es
 * que el manager —y por tanto `SecureInbound`— ve las dos procedencias por el
 * MISMO contrato `(remotePeerId, data)`, sin abrir un segundo camino para la
 * sesion cifrada.
 *
 * ## Lo que NO hace
 *
 * No conoce SDP, no negocia, no cifra y no decide quien es el par. Solo elige
 * el medio de DATOS con la misma regla que
 * [com.km.node.ChainedTransport.send]: si la cadena del par tiene el eslabon
 * P2P con autoridad, se usa la cadena; si no, el relay directo.
 */
class PeerRouteBackend(
    /** El motor del relay, que es el suelo y el dueno del socket. */
    val relay: TransportBackend,
) : TransportBackend {

    override val name: String = "route[relay+p2p]"

    /**
     * Cadena del par, o `null` si aun no se monto.
     *
     * Lo fija la composicion ([ClientTransportFactory]). Es `var` porque la
     * cadena se construye por par y de forma perezosa; leerlo antes de que la
     * fabrica lo cablee devuelve `null`, que significa "sin P2P" y cae al relay.
     */
    @Volatile
    var chainFor: (IdentityId) -> TransportChain? = { null }

    private val callbacks = AtomicReference(TransportEventCallbacks())

    /**
     * El slot que el manager usa para recibir eventos.
     *
     * Es una asignacion pura sobre un `AtomicReference`: NO toca el motor del
     * relay. Los eventos del relay se empalman una vez en `init` y leen este
     * slot en el momento del evento, de modo que un cambio de callbacks no
     * pierde el cableado del medio.
     */
    override var eventCallbacks: TransportEventCallbacks
        get() = callbacks.get()
        set(value) {
            callbacks.set(value)
        }

    init {
        // Componer y NO sustituir: `eventCallbacks` es un `var` con semantica de
        // asignacion, y el motor del relay puede tener ya un compositor
        // (legado, verbose). Se encadena el previo y despues se entrega al slot.
        val previo = relay.eventCallbacks
        relay.eventCallbacks = previo.copy(
            onDataReceived = { remotePeerId, data ->
                previo.onDataReceived?.invoke(remotePeerId, data)
                deliverInbound(remotePeerId, data)
            },
        )
    }

    /**
     * Entrega al manager un byte que llego por el DataChannel.
     *
     * Lo llama el cableado del manager P2P. Un byte sin callback registrado se
     * descarta: inventar un consumidor seria peor que no tener ninguno.
     */
    fun deliverInbound(remotePeerId: IdentityId, data: ByteArray) {
        callbacks.get().onDataReceived?.invoke(remotePeerId, data)
    }

    /**
     * Envia datos por el medio ACTIVO del par.
     *
     * Si la cadena no tiene P2P con autoridad —no se ha establecido, o el medio
     * elegido es el relay— se usa el relay DIRECTO: encolar romperia el orden y
     * arriesgaria un duplicado cuando la sesion reintente.
     */
    override fun sendData(remotePeerId: IdentityId, data: ByteArray): TransportResult<Unit> {
        val chain = chainFor(remotePeerId)
        if (chain != null && chain.activeTransport == Medium.P2P.linkName) {
            return chain.sendData(remotePeerId, data)
        }
        return relay.sendData(remotePeerId, data)
    }

    // ------------------------------------------------------------------
    // Establecimiento y ciclo de vida: SIEMPRE relay
    // ------------------------------------------------------------------

    override fun initialize(): TransportResult<Unit> = relay.initialize()

    override fun onCreateBinding(localIdentity: IdentityId, remotePeerId: IdentityId): TransportResult<Unit> =
        relay.onCreateBinding(localIdentity, remotePeerId)

    override fun onLocalSdp(localIdentity: IdentityId, remotePeerId: IdentityId, sdp: String): TransportResult<Unit> =
        relay.onLocalSdp(localIdentity, remotePeerId, sdp)

    override fun onRemoteSdp(localIdentity: IdentityId, remotePeerId: IdentityId, sdp: String): TransportResult<Unit> =
        relay.onRemoteSdp(localIdentity, remotePeerId, sdp)

    override fun onIceCandidate(localIdentity: IdentityId, remotePeerId: IdentityId, candidate: String): TransportResult<Unit> =
        relay.onIceCandidate(localIdentity, remotePeerId, candidate)

    override fun onCloseBinding(localIdentity: IdentityId, remotePeerId: IdentityId): TransportResult<Unit> =
        relay.onCloseBinding(localIdentity, remotePeerId)

    /** Cierra el motor del relay. El medio P2P lo cierra la composicion. */
    override fun shutdown(): TransportResult<Unit> = relay.shutdown()
}
