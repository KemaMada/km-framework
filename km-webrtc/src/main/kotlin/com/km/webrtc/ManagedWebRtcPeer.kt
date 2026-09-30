package com.km.webrtc

import com.km.model.IdentityId
import com.km.node.PeerContext
import com.km.node.PeerTransportBinding
import com.km.node.PeerTransportManager
import com.km.node.TransportError
import com.km.node.TransportEventCallbacks
import com.km.node.TransportResult
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Conductor de una sesion WebRTC gobernada por [PeerTransportManager].
 *
 * ARQUITECTURA (3N.4):
 * <pre>
 *   NodeIdentity / AuthSession (km-core)
 *            |
 *        PeerContext ──► remotePeerId (autoritativo)
 *            |
 *   PeerTransportManager (km-core)  ◄── autoridad de estado y seguridad
 *            |  valida PeerContext AUTHENTICATED antes de cualquier transporte
 *     TransportBackend
 *            |
 *   ManagedWebRtcPeer (km-webrtc)  ◄── este conductor
 *            |
 *   RealWebRtcTransport
 *            |
 *   PeerConnection / DataChannel
 * </pre>
 *
 * SEPARACION DE RESPONSABILIDADES:
 * - [PeerTransportManager] decide SI una operacion es permitida y cual es el
 *   estado. Este conductor NUNCA escribe estado directamente: PIDE la
 *   transicion al manager, que la valida.
 * - El `remotePeerId` se toma EXCLUSIVAMENTE de [PeerContext.remotePeerId], nunca de un
 *   parametro del llamador. El transporte no puede desviarse del peer que
 *   km-core autentico.
 * - Este conductor vive en km-webrtc porque traduce la asincronia de WebRTC
 *   (callbacks) al modelo sincrono del manager. No introduce logica de
 *   identidad ni de seguridad: eso es de km-core.
 *
 * NOTA SOBRE ROLES SDP:
 * La maquina de estados de [PeerTransportManager] (3K) fue disenada desde la
 * perspectiva del offerer. WebRTC real tiene dos roles. Para no alterar
 * km-core, el conductor mapea ambos a las transiciones existentes usando su
 * semantica real ("descripcion local enviada"):
 * <pre>
 *   offerer : createBinding -> sendOffer(offer) -> receiveAnswer(answer) -> CONNECTED
 *   answerer: createBinding -> sendOffer(answer) -> (ICE)               -> CONNECTED
 * </pre>
 * En ambos casos el manager valida la transicion y registra el SDP real.
 */
class ManagedWebRtcPeer(
    val localIdentity: IdentityId,
    val peerContext: PeerContext,
    val manager: PeerTransportManager,
    val transport: RealWebRtcTransport,
) {
    /** remotePeerId autoritativo, tomado del PeerContext (nunca del llamador). */
    val remotePeerId: IdentityId get() = peerContext.remotePeerId

    /** Datos recibidos del DataChannel, entregados a la aplicacion. */
    val receivedData = ConcurrentLinkedQueue<ByteArray>()

    /** Canal de senalizacion (SDP/ICE) hacia el par remoto. */
    var signaling: PeerSignaling? = null

    /** Callback de aplicacion al recibir datos. */
    @Volatile var onApplicationData: ((ByteArray) -> Unit)? = null

    /**
     * Bytes CRUDOS recibidos del DataChannel, antes de cualquier descifrado.
     *
     * Es el punto de enganche para una capa de mensaje segura: aqui llegan
     * los bytes opacos del wire, que la sesion de mensajeria descifra.
     *
     * Mantiene a km-webrtc ignorante de la criptografia: el conductor solo
     * entrega bytes, y quien sabe de ratchets y SecureFrame es km-core.
     */
    @Volatile var onTransportData: ((ByteArray) -> Unit)? = null

    private val localSdpLatch = CountDownLatch(1)
    @Volatile private var pendingLocalSdp: String? = null

    private val connectedLatch = CountDownLatch(1)
    @Volatile private var connectedObserved = false

    /**
     * Abre el bindingERNANDO al manager. Solo procede si el PeerContext esta
     * AUTHENTICATED: si no, el manager rechaza y NO se crea PeerConnection.
     */
    fun open(): TransportResult<PeerTransportBinding> {
        val result = manager.createBinding(localIdentity, peerContext)
        if (result.isFailure) return result
        attach()
        return result
    }

    /**
     * Conecta los eventos del transporte sin crear el binding.
     *
     * En una integracion real el binding lo crea `PeerTransportManager`
     * (que es la autoridad), no el conductor. Este metodo cubre ese caso:
     * el manager ya abrio el binding y aqui solo se cablean los callbacks
     * que traducen eventos de WebRTC a acciones sobre el manager.
     */
    fun attach() {
        wireTransportEvents()
    }

    /**
     * Traduce los eventos del backend WebRTC a acciones sobre el manager.
     */
    private fun wireTransportEvents() {
        transport.eventCallbacks = TransportEventCallbacks(
            onIceCandidateGenerated = { _, _, candidate ->
                signaling?.sendIce(remotePeerId, candidate)
            },
            onDataReceived = { fromPeerId, data ->
                // Solo datos del peer autenticado. Un canal de otro par
                // llegaria con otro remotePeerId y se descarta aqui.
                if (fromPeerId == remotePeerId) {
                    receivedData.add(data)
                    // Bytes crudos primero: la capa de mensaje segura los
                    // descifra. onApplicationData queda para diagnostico.
                    onTransportData?.invoke(data)
                    onApplicationData?.invoke(data)
                }
            },
            onConnectionStateChange = { _, _, isConnected ->
                if (isConnected) {
                    // El manager es la autoridad: le PIDIAMOS la transicion.
                    val r = manager.markConnected(localIdentity, peerContext)
                    if (r.isSuccess) {
                        connectedObserved = true
                        connectedLatch.countDown()
                    }
                }
            },
            onLocalSdpGenerated = { _, _, sdp ->
                pendingLocalSdp = sdp
                localSdpLatch.countDown()
                signaling?.sendSdp(remotePeerId, sdp)
            },
            onTransportError = { _, _, reason ->
                manager.failBinding(localIdentity, remotePeerId, reason)
            }
        )
    }

    // ------------------------------------------------------------------
    // Rol OFFERER
    // ------------------------------------------------------------------

    /**
     * Crea el DataChannel, pide la oferta real y la registra.
     *
     * Nota de division de responsabilidades: `createDataChannel` y
     * `requestOffer` NO tienen equivalente en el API del manager (son
     * operaciones puras de transporte). El manager aporta la validacion
     * de estado y el registro de la SDP real.
     */
    fun startOffering(timeoutMs: Long = 15_000L): TransportResult<PeerTransportBinding> {
        transport.createDataChannel(localIdentity, remotePeerId, DATA_CHANNEL_LABEL).let { r ->
            if (r is TransportResult.Failure) return r
        }
        transport.requestOffer(localIdentity, remotePeerId).let { r ->
            if (r is TransportResult.Failure) return r
        }
        val offer = awaitLocalSdp(timeoutMs) ?: return TransportResult.Failure(
            TransportError.INVALID_SDP, "timeout esperando oferta SDP"
        )
        // El manager valida NEW -> OFFER_SENT, registra la SDP real
        // y la aplica al backend (setLocalDescription).
        return manager.sendOffer(localIdentity, peerContext, offer, remotePeerId)
    }

    /**
     * Registra la respuesta remota (rol offerer).
     *
     * El manager aplica la SDP al backend via `onRemoteSdp`; el conductor
     * NO la aplica por su cuenta para evitar duplicar la operacion.
     */
    fun applyAnswer(remoteAnswer: String): TransportResult<PeerTransportBinding> =
        manager.receiveAnswer(localIdentity, remotePeerId, remoteAnswer)

    // ------------------------------------------------------------------
    // Rol ANSWERER
    // ------------------------------------------------------------------

    /**
     * Acepta la oferta remota, genera la respuesta real y la registra.
     *
     * Aplicar una oferta remota (setRemoteDescription + createAnswer) no
     * existe en el API del manager, que fue disenado desde el offerer.
     * El conductor la ejecuta como operacion de transporte; la validacion
     * de estado y el registro siguen siendo del manager.
     */
    fun acceptOffer(remoteOffer: String, timeoutMs: Long = 15_000L): TransportResult<PeerTransportBinding> {
        transport.acceptOfferAndAnswer(localIdentity, remotePeerId, remoteOffer).let { r ->
            if (r is TransportResult.Failure) return r
        }
        val answer = awaitLocalSdp(timeoutMs) ?: return TransportResult.Failure(
            TransportError.INVALID_SDP, "timeout esperando respuesta SDP"
        )
        // "descripcion local enviada" (la respuesta). El manager valida.
        return manager.sendOffer(localIdentity, peerContext, answer, remotePeerId)
    }

    // ------------------------------------------------------------------
    // ICE / datos / cierre
    // ------------------------------------------------------------------

    /** Registra un candidato ICE remoto (el manager valida el estado). */
    fun deliverIce(candidate: String): TransportResult<PeerTransportBinding> =
        manager.addIceCandidate(localIdentity, remotePeerId, candidate)

    /** Envia datos a traves del manager (que exige CONNECTED). */
    fun send(data: ByteArray): TransportResult<Unit> =
        manager.sendData(localIdentity, peerContext, data)

    /** Espera a que el manager marque el binding como CONNECTED. */
    fun awaitConnected(timeoutMs: Long = 15_000L): Boolean =
        connectedLatch.await(timeoutMs, TimeUnit.MILLISECONDS) && connectedObserved

    /** Cierra el binding via el manager. */
    fun close(): TransportResult<Unit> = manager.closeBinding(localIdentity, remotePeerId)

    /** Estado del binding segun el manager (la autoridad). */
    fun bindingState() = manager.getBinding(localIdentity, remotePeerId)?.state

    private fun awaitLocalSdp(timeoutMs: Long): String? {
        if (!localSdpLatch.await(timeoutMs, TimeUnit.MILLISECONDS)) return null
        return pendingLocalSdp
    }

    companion object {
        const val DATA_CHANNEL_LABEL = "km"
    }
}

/**
 * Canal de senalizacion que transporta SDP e ICE entre pares.
 *
 * En 3N.4 se implementa en memoria para aislar la integracion
 * manager<->WebRTC. En 3N.5 se implementara sobre el RelayServer.
 *
 * REGLA: el canal transporta SDP/ICE; nunca decide identidad.
 */
interface PeerSignaling {
    fun sendSdp(toPeer: IdentityId, sdp: String)
    fun sendIce(toPeer: IdentityId, candidate: String)
}

/**
 * Enlace de senalizacion en memoria entre dos [ManagedWebRtcPeer].
 *
 * Replica lo que hara el relay: entrega SDP/ICE al par destino correcto,
 * sin introducir una via que evite las invariantes del manager.
 */
class DirectSignaling : PeerSignaling {
    /** Entregas recibidas del par remoto. */
    val sdpsReceived = ConcurrentLinkedQueue<String>()
    val iceReceived = ConcurrentLinkedQueue<String>()

    /** Acciones a ejecutar cuando llega un SDP/ICE del remoto. */
    @Volatile var onRemoteSdp: ((String) -> Unit)? = null
    @Volatile var onRemoteIce: ((String) -> Unit)? = null

    /** remotePeerId remoto esperado: evita entregar SDP al par equivocado. */
    @Volatile var expectedRemotePeer: IdentityId? = null

    override fun sendSdp(toPeer: IdentityId, sdp: String) {
        if (expectedRemotePeer != null && toPeer != expectedRemotePeer) return
        sdpsReceived.add(sdp)
        onRemoteSdp?.invoke(sdp)
    }

    override fun sendIce(toPeer: IdentityId, candidate: String) {
        if (expectedRemotePeer != null && toPeer != expectedRemotePeer) return
        iceReceived.add(candidate)
        onRemoteIce?.invoke(candidate)
    }
}
