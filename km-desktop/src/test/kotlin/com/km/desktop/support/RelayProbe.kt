package com.km.desktop.support

import com.km.model.IdentityId
import com.km.node.FakeTransportBackend
import com.km.node.TransportBackend
import com.km.node.TransportEventCallbacks
import com.km.node.TransportResult

/**
 * Backend que CUENTA los bytes que salen por el camino cifrado, sobre el
 * [FakeTransportBackend] de km-core.
 *
 * ## POR QUE UN DIBUJO Y NO EL DE km-CORE
 *
 * `FakeTransportBackend.sendData` es una no-operacion documentada: sirve para
 * que el manager pueda probarse sin red, y por eso no lleva ninguna cuenta.
 * Aqui lo que hay que afirmar es "CERO bytes salieron", y eso necesita un doble
 * que pueda DEMOSTRAR que no recibio ninguno. Es una propiedad del doble, no del
 * framework.
 *
 * ## POR QUE DELEGA Y NO REIMPLEMENTA
 *
 * Todo lo que no es `sendData` se delega al `FakeTransportBackend` de km-core
 * (`initialize`, `onCreateBinding`, `onLocalSdp`, ...). Reimplementarlos
 * permitiria que el doble se desviara del contrato en un metodo que aqui no se
 * mide, y un doble que miente en lo que nadie mira no es un doble: es una
 * segunda implementacion del framework.
 *
 * [eventCallbacks] NO se delega de forma ciega: se guarda en la arnes porque es
 * el cable por el que un byte remoto entra, y `deliver` lo usa para empujar un
 * `SecureFrame` como si hubiera llegado del rele.
 */
class RelayProbe(
    private val delegate: TransportBackend = FakeTransportBackend(),
) : TransportBackend {

    /** Bytes que el manager ha entregado a `sendData`, en orden. */
    val enviados: MutableList<Pair<IdentityId, ByteArray>> = mutableListOf()

    /** Bytes de entrada empujados por [deliver], en orden. */
    val recibidos: MutableList<Pair<IdentityId, ByteArray>> = mutableListOf()

    /** Cuantos `sendData` han ocurrido. Es un dato de traza, NO una asercion. */
    var llamadas: Int = 0
        private set

    override var eventCallbacks: TransportEventCallbacks = TransportEventCallbacks()
        get() = field
        set(value) {
            field = value
            delegate.eventCallbacks = value
        }

    override fun initialize(): TransportResult<Unit> = delegate.initialize()

    override fun onCreateBinding(localIdentity: IdentityId, remotePeerId: IdentityId): TransportResult<Unit> =
        delegate.onCreateBinding(localIdentity, remotePeerId)

    override fun onLocalSdp(localIdentity: IdentityId, remotePeerId: IdentityId, sdp: String): TransportResult<Unit> =
        delegate.onLocalSdp(localIdentity, remotePeerId, sdp)

    override fun onRemoteSdp(localIdentity: IdentityId, remotePeerId: IdentityId, sdp: String): TransportResult<Unit> =
        delegate.onRemoteSdp(localIdentity, remotePeerId, sdp)

    override fun onIceCandidate(localIdentity: IdentityId, remotePeerId: IdentityId, candidate: String): TransportResult<Unit> =
        delegate.onIceCandidate(localIdentity, remotePeerId, candidate)

    /**
     * Lo UNICO que este doble mide.
     *
     * Cuenta BYTES, no llamadas: un `sendData` de cero bytes y otro de doscientos
     * son el mismo evento para un contador de llamadas, y el primero es
     * precisamente el que haria pasar un "no salio nada" que si salio.
     */
    override fun sendData(remotePeerId: IdentityId, data: ByteArray): TransportResult<Unit> {
        llamadas++
        enviados += remotePeerId to data.copyOf()
        return delegate.sendData(remotePeerId, data)
    }

    override fun onCloseBinding(localIdentity: IdentityId, remotePeerId: IdentityId): TransportResult<Unit> =
        delegate.onCloseBinding(localIdentity, remotePeerId)

    override fun shutdown(): TransportResult<Unit> = delegate.shutdown()

    /** Bytes totales que salieron por el camino cifrado. */
    val bytesSalientes: Int get() = enviados.sumOf { it.second.size }

    /**
     * Empuja un frame remoto por el MISMO cable que usaria el rele.
     *
     * Se invoca el `onDataReceived` que el manager encadeno en su `init`, de modo
     * que lo que se entrega es exactamente lo que el manager entrego en su dia a
     * las sesiones: no hay un atajo que esquive el cable y falsee el reparto.
     */
    fun deliver(remotePeerId: IdentityId, wire: ByteArray) {
        recibidos += remotePeerId to wire.copyOf()
        val slot = eventCallbacks.onDataReceived
            ?: throw AssertionError("el backend no tiene cable de recepcion: el manager no se inicializo sobre esta arnes")
        slot(remotePeerId, wire.copyOf())
    }

    /** Empuja un frame LEGADO por el cable `onLegacyInboundFrame`. */
    fun deliverLegacy(frame: com.km.node.LegacyInboundFrame, peerId: IdentityId) {
        val slot = eventCallbacks.onLegacyInboundFrame
            ?: throw AssertionError("el camino legado no esta dado de alta: attachLegacy() no se llamo o ya se solto")
        slot(frame, peerId)
    }
}

/** El `PeerTransportManager` de una [RelayProbe], ya enlazado a ella. */
fun relayProbeManager(): Pair<com.km.node.PeerTransportManager, RelayProbe> {
    val probe = RelayProbe()
    return com.km.node.PeerTransportManager(probe, now = { 0L }) to probe
}