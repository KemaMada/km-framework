package com.km.webrtc

import com.km.model.IdentityId
import com.km.node.EstablishmentState
import com.km.node.TransportError
import com.km.node.TransportEstablishment
import com.km.node.TransportResult
import dev.onvoid.webrtc.RTCDataChannelState

/**
 * Establecimiento REAL por WebRTC (3Q.3).
 *
 * ## La division de autoridades
 *
 * `RealWebRtcTransport` INFORMA de eventos del medio: el estado del
 * DataChannel, el de la conexion, si el ICE completo. No decide nada. Este
 * establecimiento es quien PREGUNTA y quien CONCLUYE que hay `READY`.
 *
 * ```
 * RealWebRtcTransport   ->  "el DataChannel esta OPEN"   (una consulta)
 * WebRtcEstablishment   ->  "eso significa READY"        (una conclusion)
 * ```
 *
 * Si el backend declarara `READY` por su cuenta tendriamos dos autoridades
 * incompatibles: exactamente el fallo que 3Q.4 previno al congelar
 * `awaitReady` como unico punto terminal.
 *
 * ## Por que `negotiate()` NO es `READY`
 *
 * Con WebRTC esto no es teorico. `startOffering()` completa la generacion de
 * SDP y devuelve `Success`; el ICE puede haberconnectado; y aun asi el
 * DataChannel puede no abrir nunca, si la DTLS no termina o el remoto
 * desaparece. Por eso `negotiate()` deja el establecimiento en
 * [EstablishmentState.NEGOTIATING] y solo [awaitReady] puede moverlo.
 */
class WebRtcEstablishment(
    private val peer: ManagedWebRtcPeer,
    private val role: Role,
    private val negotiateTimeoutMs: Long = 15_000L,
) : TransportEstablishment {

    enum class Role {
        /** Genera la oferta e inicia la negociacion. */
        OFFERER,

        /** Espera la oferta y responde. */
        ANSWERER,
    }

    override val transportName: String get() = "p2p"

    override var state: EstablishmentState = EstablishmentState.CREATED
        private set

    private var negoStartedAt = 0L

    override fun initialize(): TransportResult<Unit> {
        val r = peer.transport.initialize()
        if (r.isFailure) {
            state = EstablishmentState.FAILED
            return r
        }
        state = EstablishmentState.CREATED
        return TransportResult.Success(Unit)
    }

    /**
     * Abre el binding ante el manager.
     *
     * El DataChannel se crea en [negotiate] (`startOffering` /
     * `acceptOffer` lo hacen), porque para WebRTC el canal es parte de la
     * negociacion y no del simple hecho de existir un binding. Y que el canal
     * este CREADO no significa que este ABIERTO: es la razon de ser de
     * [awaitReady].
     */
    override fun createBinding(
        localIdentity: IdentityId,
        remotePeerId: IdentityId,
    ): TransportResult<Unit> {
        val r = peer.open().let {
            when (it) {
                is TransportResult.Success -> TransportResult.Success(Unit)
                is TransportResult.Failure -> it
            }
        }
        if (r is TransportResult.Failure) {
            state = EstablishmentState.FAILED
            return r
        }
        state = EstablishmentState.CREATED
        return TransportResult.Success(Unit)
    }

    override fun negotiate(): TransportResult<Unit> {
        negoStartedAt = System.currentTimeMillis()
        val r = when (role) {
            // `startOffering` devuelve el binding actualizado; aqui solo
            // importa si la operacion fue correcta.
            Role.OFFERER -> peer.startOffering(negotiateTimeoutMs).let {
                when (it) {
                    is TransportResult.Success -> TransportResult.Success(Unit)
                    is TransportResult.Failure -> it
                }
            }
            // El ANSWERER no negotiate aqui: reacciona al offer remoto. La
            // senal es un EVENTO, no un paso que este metodo pueda completar.
            Role.ANSWERER -> TransportResult.Success(Unit)
        }
        if (r is TransportResult.Failure) {
            state = EstablishmentState.FAILED
            return r
        }
        state = EstablishmentState.NEGOTIATING
        return TransportResult.Success(Unit)
    }

    /**
     * Condicion terminal: el DataChannel esta `OPEN`.
     *
     * Es una CONSULTA al medio, no una declaracion del medio. Si el timeout
     * expira, el resultado es del MEDIO y por tanto autoriza fallback: un
     * P2P que no abre canal no es un problema de seguridad ni de protocolo.
     */
    override fun awaitReady(timeoutMs: Long): TransportResult<Unit> {
        val local = peer.localIdentity
        val remote = peer.remotePeerId
        val abierto = peer.transport.awaitDataChannelOpen(local, remote, timeoutMs)
        if (abierto && peer.transport.dataChannelState(local, remote) == RTCDataChannelState.OPEN) {
            state = EstablishmentState.READY
            return TransportResult.Success(Unit)
        }
        state = EstablishmentState.FAILED
        val observado = peer.transport.dataChannelState(local, remote)
        return TransportResult.Failure(
            TransportError.BACKEND_SEND_FAILED,
            "DataChannel no OPEN tras ${timeoutMs}ms (estado real: $observado)",
        )
    }

    override fun abort() {
        // `close()` no debe propagar: abort vive en el camino de error.
        runCatching { peer.close() }
        runCatching { peer.transport.onCloseBinding(peer.localIdentity, peer.remotePeerId) }
        state = EstablishmentState.CLOSED
    }
}
