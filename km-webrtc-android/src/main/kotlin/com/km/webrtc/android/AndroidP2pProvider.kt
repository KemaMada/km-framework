package com.km.webrtc.android

import com.km.model.IdentityId
import com.km.node.EstablishmentState
import com.km.node.P2pProvider
import com.km.node.P2pRole
import com.km.node.P2pSignalKind
import com.km.node.PeerSignalPort
import com.km.node.PeerTransportManager
import com.km.node.TransportBackend
import com.km.node.TransportError
import com.km.node.TransportEstablishment
import com.km.node.TransportResult
import org.webrtc.SessionDescription
import android.util.Log

private const val TAG: String = "KeyMessage"

/**
 * El medio P2P de Android detras del puerto `P2pProvider`.
 *
 * La politica y la orquestacion son las mismas que en escritorio: este modulo
 * solo aporta el backend nativo. Si no hay `PeerContext` autenticado para el
 * par, [link] devuelve un establecimiento que falla como TRANSPORT y la cadena
 * cae al relay en vez de detenerse.
 */
class AndroidP2pProvider(
    private val manager: PeerTransportManager,
    private val localIdentity: IdentityId,
    private val backend: AndroidWebRtcBackend,
    /**
     * Resuelve el contexto AUTENTICADO del par.
     *
     * Se inyecta y no se lee de [manager]: el core crea los bindings en SU
     * manager (el del bundle), no en el manager P2P. Consultar el equivocado
     * devolvia "sin PeerContext" y todo intento moria como `p2p_failed` sin
     * llegar a SDP. Es el mismo cableado que el escritorio.
     */
    private val peerContextFor: (IdentityId) -> com.km.node.PeerContext?,
) : P2pProvider {

    init {
        require(manager.backend() === backend) {
            "el manager debe operar sobre el mismo AndroidWebRtcBackend que este provider"
        }
    }

    override fun backend(): TransportBackend = backend

    override fun link(
        remotePeerId: IdentityId,
        role: P2pRole,
        signals: PeerSignalPort,
    ): TransportEstablishment {
        val context = peerContextFor(remotePeerId)
            ?: return NotReadyEstablishment(
                "sin PeerContext autenticado para ${remotePeerId.value.take(8)}…",
            ).also {
                Log.w(TAG, "[CONFIG] p2p sin PeerContext peer=${remotePeerId.value.take(8)}…")
            }
        // El contexto se exige por autoridad, no por sus campos: el manager ya
        // lo valido al crear el binding.
        require(context.remotePeerId == remotePeerId)
        Log.i(TAG, "[CONFIG] p2p link peer=${remotePeerId.value.take(8)}… role=$role")
        return AndroidP2pEstablishment(backend, localIdentity, remotePeerId, role, signals)
    }
}

/**
 * Establecimiento P2P de Android: ICE + DTLS + DataChannel.
 *
 * Escucha el puerto de senalizacion porque es lo unico que convierte un
 * `Success` intermedio en `READY`; el presupuesto total lo reparte la cadena
 * entre `negotiate` y `awaitReady`.
 */
internal class AndroidP2pEstablishment(
    private val backend: AndroidWebRtcBackend,
    private val localIdentity: IdentityId,
    private val remotePeerId: IdentityId,
    private val role: P2pRole,
    private val signals: PeerSignalPort,
) : TransportEstablishment {

    override val transportName: String = "p2p"

    override var state: EstablishmentState = EstablishmentState.CREATED
        private set

    @Volatile
    private var signalSubscription: (() -> Unit)? = null

    init {
        signalSubscription = signals.onSignal { from, kind, sdp, candidate ->
            if (from == remotePeerId) onRemoteSignal(kind, sdp, candidate)
        }
        // El ICE saliente necesita el puerto de ESTE establecimiento. Se ata al
        // crear el objeto para no perder candidatos tempranos.
        backend.bindIceSink(remotePeerId) { encoded ->
            signals.sendSignal(remotePeerId, P2pSignalKind.ICE_CANDIDATE, candidate = encoded)
        }
    }

    private fun onRemoteSignal(kind: String, sdp: String?, candidate: String?) {
        when (kind) {
            P2pSignalKind.SDP_OFFER -> if (role == P2pRole.ANSWERER && sdp != null) {
                // La respuesta se genera fuera del hilo de senalizacion: crear
                // SDP puede tardar y bloquearía la lectura del relay.
                Thread({
                    val remoteSet = backend.setRemoteDescription(
                        remotePeerId,
                        SessionDescription.Type.OFFER,
                        sdp,
                    )
                    if (remoteSet is TransportResult.Failure) {
                        state = EstablishmentState.FAILED
                        Log.w(TAG, "[CONFIG] p2p answer: setRemote(offer) fallo: ${remoteSet.message}")
                        return@Thread
                    }
                    when (val answer = backend.createAnswer(remotePeerId)) {
                        is TransportResult.Success ->
                            signals.sendSignal(remotePeerId, P2pSignalKind.SDP_ANSWER, sdp = answer.value)
                        is TransportResult.Failure -> {
                            state = EstablishmentState.FAILED
                            Log.w(TAG, "[CONFIG] p2p answer: createAnswer fallo: ${answer.message}")
                        }
                    }
                }, "km-p2p-answer").start()
            }

            P2pSignalKind.SDP_ANSWER -> if (role == P2pRole.OFFERER && sdp != null) {
                if (backend.setRemoteDescription(remotePeerId, SessionDescription.Type.ANSWER, sdp)
                    is TransportResult.Failure
                ) {
                    state = EstablishmentState.FAILED
                    Log.w(TAG, "[CONFIG] p2p offer: setRemote(answer) fallo")
                } else {
                    Log.i(TAG, "[CONFIG] p2p offer: answer aplicada")
                }
            }

            P2pSignalKind.ICE_CANDIDATE -> if (candidate != null) {
                val r = backend.addIce(remotePeerId, candidate)
                if (r is TransportResult.Failure) {
                    Log.w(TAG, "[CONFIG] p2p ice rechazado: ${r.message}")
                }
            }
        }
    }

    override fun initialize(): TransportResult<Unit> {
        val r = backend.initialize()
        state = if (r.isSuccess) EstablishmentState.CREATED else EstablishmentState.FAILED
        return r
    }

    override fun createBinding(localIdentity: IdentityId, remotePeerId: IdentityId): TransportResult<Unit> {
        val r = backend.onCreateBinding(localIdentity, remotePeerId)
        state = if (r.isSuccess) EstablishmentState.CREATED else EstablishmentState.FAILED
        return r
    }

    override fun negotiate(): TransportResult<Unit> = negotiate(NEGOTIATE_TIMEOUT_MS)

    override fun negotiate(budgetMs: Long): TransportResult<Unit> {
        if (role != P2pRole.OFFERER) {
            state = EstablishmentState.NEGOTIATING
            return TransportResult.Success(Unit)
        }
        if (budgetMs <= 0L) {
            state = EstablishmentState.FAILED
            return TransportResult.Failure(TransportError.BACKEND_SEND_FAILED, "presupuesto agotado antes del SDP")
        }
        val channel = backend.ensureDataChannel(remotePeerId)
        if (channel is TransportResult.Failure) {
            state = EstablishmentState.FAILED
            return channel
        }
        return when (val offer = backend.createOffer(remotePeerId)) {
            is TransportResult.Success -> {
                val sent = signals.sendSignal(remotePeerId, P2pSignalKind.SDP_OFFER, sdp = offer.value)
                Log.i(TAG, "[CONFIG] p2p offer emitida peer=${remotePeerId.value.take(8)}… enviada=$sent")
                state = EstablishmentState.NEGOTIATING
                TransportResult.Success(Unit)
            }

            is TransportResult.Failure -> {
                state = EstablishmentState.FAILED
                Log.w(TAG, "[CONFIG] p2p createOffer fallo: ${offer.message}")
                offer
            }
        }
    }

    override fun awaitReady(timeoutMs: Long): TransportResult<Unit> {
        val abierto = backend.awaitOpen(remotePeerId, timeoutMs)
        return if (abierto) {
            state = EstablishmentState.READY
            Log.i(TAG, "[CONFIG] p2p READY peer=${remotePeerId.value.take(8)}…")
            TransportResult.Success(Unit)
        } else {
            state = EstablishmentState.FAILED
            Log.w(TAG, "[CONFIG] p2p timeout peer=${remotePeerId.value.take(8)}… (${timeoutMs}ms)")
            TransportResult.Failure(
                TransportError.BACKEND_SEND_FAILED,
                "DataChannel no OPEN tras ${timeoutMs}ms",
            )
        }
    }

    override fun abort() {
        runCatching { signalSubscription?.invoke() }
        signalSubscription = null
        backend.unbindIceSink(remotePeerId)
        runCatching { backend.onCloseBinding(localIdentity, remotePeerId) }
        state = EstablishmentState.CLOSED
    }

    private companion object {
        const val NEGOTIATE_TIMEOUT_MS: Long = 8_000L
    }
}

/**
 * Fallo de autoridad: es TRANSPORT, para que la cadena caiga al relay. Un
 * error STATE detendria la cadena y dejaria al cliente sin medio.
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
