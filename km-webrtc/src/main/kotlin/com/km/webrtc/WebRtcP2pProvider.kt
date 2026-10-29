package com.km.webrtc

import com.km.model.IdentityId
import com.km.node.EstablishmentState
import com.km.node.P2pProvider
import com.km.node.P2pRole
import com.km.node.P2pSignalKind
import com.km.node.PeerContext
import com.km.node.PeerSignalPort
import com.km.node.PeerTransportManager
import com.km.node.TransportBackend
import com.km.node.TransportError
import com.km.node.TransportEstablishment
import com.km.node.TransportResult
import dev.onvoid.webrtc.RTCDataChannelState

/**
 * El medio P2P de escritorio: WebRTC detras del puerto `P2pProvider`.
 *
 * ## La flecha de dependencia
 *
 * `km-transport` compone y conduce; NO conoce WebRTC. Este modulo implementa el
 * puerto que `km-core` declara, y el escritorio inyecta esta clase. Por eso el
 * coordinador no arrastra JNI y se prueba con fakes.
 *
 * ## Una fabrica, un PeerConnectionFactory
 *
 * [transport] es UNO solo para todos los pares: ya multiplexa por
 * `(localIdentity, remotePeerId)`. Crear un `RealWebRtcTransport` por vecino
 * abriria una factoria nativa por par.
 *
 * ## Sin contexto autenticado no hay enlace
 *
 * [link] NO inventa autoridad. Si no hay `PeerContext` para el par, devuelve un
 * establecimiento que falla con `BACKEND_NOT_READY` (TRANSPORT): la cadena cae
 * al relay en vez de detenerse con un fallo de estado.
 */
class WebRtcP2pProvider(
    private val manager: PeerTransportManager,
    private val localIdentity: IdentityId,
    private val transport: RealWebRtcTransport,
    /** Contexto ya autenticado del par, o `null` si no lo hay. */
    private val peerContextFor: (IdentityId) -> PeerContext?,
) : P2pProvider {

    init {
        // El manager tiene que gobernar ESTE transporte. Si gobernara otro, los
        // callbacks de recepcion irian al manager equivocado y los bytes del
        // DataChannel no llegarian a ninguna sesion: el enlace pareceria vivo.
        require(manager.backend() === transport) {
            "el manager debe operar sobre el mismo RealWebRtcTransport que este provider"
        }
    }

    override fun backend(): TransportBackend = transport

    override fun link(
        remotePeerId: IdentityId,
        role: P2pRole,
        signals: PeerSignalPort,
    ): TransportEstablishment {
        val context = peerContextFor(remotePeerId)
            ?: return NotReadyEstablishment(
                "sin PeerContext autenticado para ${remotePeerId.value.take(8)}…",
            )
        val peer = ManagedWebRtcPeer(
            localIdentity = localIdentity,
            peerContext = context,
            manager = manager,
            transport = transport,
        )
        // El rol decide el `kind` del SDP SALIENTE: el offerer solo emite
        // ofertas y el answerer solo respuestas. libwebrtc usa el mismo callback
        // para las dos, y sin esta distincion el par recibiria una "respuesta"
        // que es una oferta.
        peer.signaling = SignalPortSignaling(remotePeerId, role, signals)
        return WebRtcP2pEstablishment(peer, role, signals)
    }
}

/**
 * Adaptador [PeerSignaling] -> [PeerSignalPort].
 *
 * Traduce SDP e ICE al puerto; no decide nada mas. El `kind` del SDP es fijo
 * por rol (ver [WebRtcP2pProvider.link]).
 */
private class SignalPortSignaling(
    private val remotePeerId: IdentityId,
    private val role: P2pRole,
    private val signals: PeerSignalPort,
) : PeerSignaling {

    override fun sendSdp(toPeer: IdentityId, sdp: String) {
        if (toPeer != remotePeerId) return
        val kind = when (role) {
            P2pRole.OFFERER -> P2pSignalKind.SDP_OFFER
            P2pRole.ANSWERER -> P2pSignalKind.SDP_ANSWER
        }
        signals.sendSignal(toPeer, kind, sdp = sdp)
    }

    override fun sendIce(toPeer: IdentityId, candidate: String) {
        if (toPeer != remotePeerId) return
        signals.sendSignal(toPeer, P2pSignalKind.ICE_CANDIDATE, candidate = candidate)
    }
}

/**
 * Establecimiento P2P real: ICE + DTLS + DataChannel sobre un
 * [ManagedWebRtcPeer].
 *
 * ## Lo que NO es
 *
 * No es [WebRtcEstablishment]: aquel asume que el SDP viaja por un
 * `PeerSignaling` ya cableado y no escucha respuestas entrantes. Aqui el
 * establecimiento SI escucha el puerto —oferta, respuesta y candidatos— porque
 * es lo unico que puede convertir un `Success` intermedio en `READY`.
 *
 * ## Presupuesto
 *
 * [negotiateTimeoutMs] es el techo PROPIO de la SDP local (8 s). El techo
 * efectivo es el que recibe de la cadena, que reparte un UNICO deadline de
 * ronda entre `negotiate` y `awaitReady`: el presupuesto total del intento P2P
 * no puede superarse por sumar dos esperas.
 */
class WebRtcP2pEstablishment internal constructor(
    private val peer: ManagedWebRtcPeer,
    private val role: P2pRole,
    private val signals: PeerSignalPort,
    private val negotiateTimeoutMs: Long = NEGOTIATE_TIMEOUT_MS,
) : TransportEstablishment {

    override val transportName: String = "p2p"

    override var state: EstablishmentState = EstablishmentState.CREATED
        private set

    /** Baja del puerto de senalizacion; `null` hasta que `init` la registra. */
    @Volatile
    private var signalSubscription: (() -> Unit)? = null

    init {
        // Suscripcion mientras viva el establecimiento. El filtro por `from`
        // es obligatorio: el puerto es global al relay y el mismo handler
        // recibe senales de TODOS los pares.
        //
        // La suscripcion se DA DE BAJA en [abort]: sin eso, cada reintento de
        // 60 s dejaria un handler vivo y los establecimientos viejos seguirian
        // procesando senales de un par ya cerrado.
        signalSubscription = signals.onSignal { from, kind, sdp, candidate ->
            if (from == peer.remotePeerId) onRemoteSignal(kind, sdp, candidate)
        }
    }

    private fun onRemoteSignal(kind: String, sdp: String?, candidate: String?) {
        when (kind) {
            P2pSignalKind.SDP_OFFER -> if (role == P2pRole.ANSWERER && sdp != null) {
                peer.acceptOffer(sdp, negotiateTimeoutMs)
            }
            P2pSignalKind.SDP_ANSWER -> if (role == P2pRole.OFFERER && sdp != null) {
                peer.applyAnswer(sdp)
            }
            P2pSignalKind.ICE_CANDIDATE -> if (candidate != null) {
                peer.deliverIce(candidate)
            }
        }
    }

    override fun initialize(): TransportResult<Unit> {
        val r = peer.transport.initialize()
        state = if (r.isSuccess) EstablishmentState.CREATED else EstablishmentState.FAILED
        return r
    }

    override fun createBinding(localIdentity: IdentityId, remotePeerId: IdentityId): TransportResult<Unit> {
        // `peer.open()` crea el binding en el manager (la autoridad) y cablea
        // los eventos. Si ya existe uno activo, se reutiliza: D9.
        val existing = peer.manager.getBinding(localIdentity, remotePeerId)
        val r = if (existing != null && existing.isActive) {
            peer.attach()
            TransportResult.Success(Unit)
        } else {
            when (val opened = peer.open()) {
                is TransportResult.Success -> TransportResult.Success(Unit)
                is TransportResult.Failure -> opened
            }
        }
        state = if (r.isSuccess) EstablishmentState.CREATED else EstablishmentState.FAILED
        return r
    }

    override fun negotiate(): TransportResult<Unit> = negotiate(negotiateTimeoutMs)

    /**
     * Negociacion acotada por el presupuesto que QUEDA en la ronda.
     *
     * El offerer espera su SDP local como maximo `min(budgetMs, 8 s)`. Antes,
     * `startOffering` usaba siempre 8 s y despues `awaitReady` recibia otros
     * 8 s: el fallback podia tardar 16 s. Ahora el que manda es el reloj de la
     * cadena; si no queda presupuesto se falla de inmediato en vez de esperar.
     */
    override fun negotiate(budgetMs: Long): TransportResult<Unit> {
        val budget = minOf(budgetMs, negotiateTimeoutMs).coerceAtLeast(0L)
        val r = when (role) {
            // El offerer genera oferta y la emite por el puerto; sigue sin ser
            // READY hasta que el DataChannel abra.
            P2pRole.OFFERER -> if (budget <= 0L) {
                TransportResult.Failure(
                    TransportError.BACKEND_SEND_FAILED,
                    "presupuesto agotado antes de negociar el SDP",
                )
            } else {
                when (val offering = peer.startOffering(budget)) {
                    is TransportResult.Success -> TransportResult.Success(Unit)
                    is TransportResult.Failure -> offering
                }
            }
            // El answerer espera el SDP remoto como EVENTO: no hay paso que
            // completar aqui.
            P2pRole.ANSWERER -> TransportResult.Success(Unit)
        }
        if (r is TransportResult.Failure) {
            state = EstablishmentState.FAILED
            return r
        }
        state = EstablishmentState.NEGOTIATING
        return TransportResult.Success(Unit)
    }

    override fun awaitReady(timeoutMs: Long): TransportResult<Unit> {
        val local = peer.localIdentity
        val remote = peer.remotePeerId
        val abierto = peer.transport.awaitDataChannelOpen(local, remote, timeoutMs) &&
            peer.transport.dataChannelState(local, remote) == RTCDataChannelState.OPEN
        if (abierto) {
            state = EstablishmentState.READY
            return TransportResult.Success(Unit)
        }
        state = EstablishmentState.FAILED
        return TransportResult.Failure(
            TransportError.BACKEND_SEND_FAILED,
            "DataChannel no OPEN tras ${timeoutMs}ms",
        )
    }

    override fun abort() {
        // Camino de error: ni la baja del puerto ni `close` pueden propagar.
        signalSubscription?.let { baja -> runCatching { baja() } }
        signalSubscription = null
        runCatching { peer.close() }
        runCatching { peer.transport.onCloseBinding(peer.localIdentity, peer.remotePeerId) }
        state = EstablishmentState.CLOSED
    }

    companion object {
        /** Presupuesto de la SDP local: el mismo que el coordinador (8 s). */
        const val NEGOTIATE_TIMEOUT_MS: Long = 8_000L
    }
}

/**
 * Establecimiento que falla al primer paso por falta de contexto.
 *
 * `BACKEND_NOT_READY` es TRANSPORT: el coordinador cae al relay. Un
 * `INVALID_STATE_TRANSITION` (STATE) habria detenido la cadena y dejado al
 * cliente sin medio.
 */
private class NotReadyEstablishment(private val why: String) : TransportEstablishment {

    override val transportName: String = "p2p"

    override var state: EstablishmentState = EstablishmentState.CREATED
        private set

    override fun initialize(): TransportResult<Unit> {
        state = EstablishmentState.FAILED
        return TransportResult.Failure(TransportError.BACKEND_NOT_READY, why)
    }

    override fun createBinding(localIdentity: IdentityId, remotePeerId: IdentityId): TransportResult<Unit> =
        TransportResult.Failure(TransportError.BACKEND_NOT_READY, why)

    override fun negotiate(): TransportResult<Unit> =
        TransportResult.Failure(TransportError.BACKEND_NOT_READY, why)

    override fun awaitReady(timeoutMs: Long): TransportResult<Unit> =
        TransportResult.Failure(TransportError.BACKEND_NOT_READY, why)

    override fun abort() {
        state = EstablishmentState.CLOSED
    }
}
