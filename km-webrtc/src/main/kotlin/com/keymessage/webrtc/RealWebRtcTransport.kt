package com.keymessage.webrtc

import com.keymessage.core.model.IdentityId
import com.keymessage.core.node.TransportBackend
import com.keymessage.core.node.TransportEventCallbacks
import com.keymessage.core.node.TransportError
import com.keymessage.core.node.TransportResult
import dev.onvoid.webrtc.CreateSessionDescriptionObserver
import dev.onvoid.webrtc.PeerConnectionFactory
import dev.onvoid.webrtc.PeerConnectionObserver
import dev.onvoid.webrtc.RTCAnswerOptions
import dev.onvoid.webrtc.RTCConfiguration
import dev.onvoid.webrtc.RTCDataChannel
import dev.onvoid.webrtc.RTCDataChannelBuffer
import dev.onvoid.webrtc.RTCDataChannelInit
import dev.onvoid.webrtc.RTCDataChannelObserver
import dev.onvoid.webrtc.RTCDataChannelState
import dev.onvoid.webrtc.RTCIceCandidate
import dev.onvoid.webrtc.RTCIceConnectionState
import dev.onvoid.webrtc.RTCIceGatheringState
import dev.onvoid.webrtc.RTCOfferOptions
import dev.onvoid.webrtc.RTCPeerConnection
import dev.onvoid.webrtc.RTCPeerConnectionState
import dev.onvoid.webrtc.RTCSdpType
import dev.onvoid.webrtc.RTCSessionDescription
import dev.onvoid.webrtc.SetSessionDescriptionObserver
import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentHashMap

/**
 * Implementacion real de [TransportBackend] sobre webrtc-java (JNI).
 *
 * ARQUITECTURA (3N):
 * <pre>
 * PeerTransportManager  (km-core: identidad, auth, estado)
 *         │
 *    RealWebRtcTransport  (km-webrtc: adapter)
 *         │
 *    RTCPeerConnection  (webrtc-java / JNI)
 *         │
 *    libwebrtc nativo
 * </pre>
 *
 * REGLA DE SEGURIDAD:
 * Este adapter NO decide quien es el peer. Recibe `(localIdentity, remotePeerId)`
 * ya validados por [com.keymessage.core.node.PeerTransportManager] y solo
 * ejecuta operaciones de transporte. La clave de identidad (identityId)
 * NO participa en la negociacion WebRTC.
 *
 * ASINCRONIA:
 * WebRTC es asincrono. Los metodos de [TransportBackend] son comandos que
 * encolan operaciones; los resultados (SDP local, ICE candidates, estado,
 * datos recibidos) llegan por callbacks y se propagan via [eventCallbacks].
 *
 * MAPEO DE ESTADOS:
 * <pre>
 * RTCPeerConnectionState.CONNECTED  -> callback onConnectionStateChange(connected=true)
 * RTCPeerConnectionState.FAILED     -> callback onConnectionStateChange(connected=false)
 * RTCPeerConnectionState.DISCONNECTED/CLOSED -> connected=false
 * RTCDataChannelState.OPEN          -> onDataReceived habilitado
 * </pre>
 */
class RealWebRtcTransport(
    private val iceServers: List<String> = emptyList(),
) : TransportBackend {

    override var eventCallbacks: TransportEventCallbacks = TransportEventCallbacks()

    private var factory: PeerConnectionFactory? = null

    /**
     * Executor para despachar eventos de WebRTC fuera del hilo nativo.
     *
     * INVARIANTE (evita deadlock de re-entrada):
     * Los callbacks de WebRTC (onIceCandidate, onConnectionChange, onMessage...)
     * llegan en un hilo interno de libwebrtc. Invocar operaciones de WebRTC
     * (addIceCandidate, setRemoteDescription...) desde esos callbacks provoca
     * interbloqueo: el hilo nativo espera su propio mutex mientras ejecuta
     * la operacion reentrante.
     *
     * Por eso los [eventCallbacks] NUNCA se invocan en linea desde el hilo
     * nativo; se despachan en este executor. Downstream (signaling, manager)
     * puede asi re-entrar sin bloquear a libwebrtc.
     */
    private val callbackExecutor = java.util.concurrent.Executors.newSingleThreadExecutor { r ->
        Thread(r, "km-webrtc-callbacks").apply { isDaemon = true }
    }

    /**
     * Recursos de un par (localIdentity, remotePeerId).
     *
     * @param generation epoch de esta conexion. Cada vez que se crea una
     *   PeerConnection para el mismo par se incrementa. Todo callback
     *   captura su generation y se descarta si ya no es la vigente
     *   (ver [isCurrent]). Esto implementa la invariante 3N.2:
     *   "un evento de un binding cerrado o reemplazado no puede mutar
     *   el estado del binding actual".
     */
    private data class PeerResources(
        val connection: RTCPeerConnection,
        val generation: Long,
        var dataChannel: RTCDataChannel? = null,
        var localDescription: RTCSessionDescription? = null,
        /** Tipo de la descripcion remota aplicada (para inferir el tipo local). */
        var remoteDescriptionType: RTCSdpType? = null,
        var iceGatheringState: RTCIceGatheringState = RTCIceGatheringState.NEW,
        var iceConnectionState: RTCIceConnectionState = RTCIceConnectionState.NEW,
    )

    /**
     * Recursos por par. La clave es exactamente la misma que usa
     * PeerTransportManager: (localIdentity, remotePeerId).
     */
    private val peers = ConcurrentHashMap<Pair<IdentityId, IdentityId>, PeerResources>()

    /**
     * Contador monotono de epochs por par. Permite distinguir una conexion
     * de una anterior para la misma identidad logica del peer.
     */
    private val generations =
        ConcurrentHashMap<Pair<IdentityId, IdentityId>, java.util.concurrent.atomic.AtomicLong>()

    /**
     * Devuelve el siguiente epoch para un par. Monotono y sin reutilizar,
     * incluso tras cerrar y recrear la conexion.
     */
    private fun nextGeneration(key: Pair<IdentityId, IdentityId>): Long =
        generations.computeIfAbsent(key) { java.util.concurrent.atomic.AtomicLong(0) }
            .incrementAndGet()

    /**
     * Verifica que un callback sigue siendo relevante.
     *
     * Un callback es stale si su PeerConnection fue cerrada o reemplazada,
     * es decir si su generation ya no coincide con la vigente del par.
     */
    private fun isCurrent(key: Pair<IdentityId, IdentityId>, generation: Long): Boolean {
        val current = peers[key] ?: return false
        return current.generation == generation
    }

    /**
     * Buffer de candidatos ICE generados por el peer local ANTES de que
     * onIceCandidateGenerated este registrado, para no perder eventos.
     * Se drenan en cuanto llega el primer evento.
     */
    private val pendingLocalIce =
        ConcurrentHashMap<Pair<IdentityId, IdentityId>, MutableList<String>>()

    private val lock = Any()

    // ===================================================================
    // Ciclo de vida
    // ===================================================================

    override fun initialize(): TransportResult<Unit> {
        if (factory != null) {
            return TransportResult.Success(Unit)
        }
        return try {
            // Verifica coherencia build/runtime antes de cargar JNI.
            val expected = buildPlatform()
            WebRtcPlatformDetector.requireSupported(expected)
            factory = PeerConnectionFactory()
            TransportResult.Success(Unit)
        } catch (e: WebRtcPlatformException) {
            TransportResult.Failure(TransportError.INVALID_STATE_TRANSITION, e.message ?: "plataforma no soportada")
        } catch (e: Throwable) {
            TransportResult.Failure(
                TransportError.INVALID_STATE_TRANSITION,
                "no se pudo inicializar PeerConnectionFactory: ${e::class.simpleName}: ${e.message}"
            )
        }
    }

    override fun shutdown(): TransportResult<Unit> {
        synchronized(lock) {
            peers.keys.toList().forEach { (lid, pid) -> closeResources(lid, pid) }
            peers.clear()
            pendingLocalIce.clear()
            factory?.dispose()
            factory = null
        }
        return TransportResult.Success(Unit)
    }

    /**
     * Detiene el executor de callbacks. Llamar tras [shutdown] y tras
     * drenar cualquier trabajo pendiente.
     */
    fun disposeCallbacks() {
        callbackExecutor.shutdown()
        try {
            callbackExecutor.awaitTermination(2, java.util.concurrent.TimeUnit.SECONDS)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }

    // ===================================================================
    // Creacion de binding
    // ===================================================================

    override fun onCreateBinding(localIdentity: IdentityId, remotePeerId: IdentityId): TransportResult<Unit> {
        val f = factory ?: return notInitialized()
        val key = localIdentity to remotePeerId
        peers[key]?.let {
            return TransportResult.Failure(
                TransportError.BINDING_ALREADY_EXISTS,
                "ya existe PeerConnection para ($localIdentity, $remotePeerId)"
            )
        }
        return try {
            val config = RTCConfiguration().apply {
                this.iceServers = emptyList() // host-only: sin STUN/TURN en 3N.1
                if (iceServers.isNotEmpty()) {
                    // 3N.3: aqui se configurarian RTCIceServer para STUN/TURN.
                }
            }
            // Epoch递增 ANTES de crear el observer, para que los callbacks
            // capturen la generation vigente desde su primer evento.
            val generation = nextGeneration(key)
            val observer = buildObserver(localIdentity, remotePeerId, generation)
            val pc = f.createPeerConnection(config, observer)
            peers[key] = PeerResources(pc, generation)
            TransportResult.Success(Unit)
        } catch (e: Throwable) {
            TransportResult.Failure(
                TransportError.INVALID_STATE_TRANSITION,
                "createPeerConnection fallo: ${e.message}"
            )
        }
    }

    // ===================================================================
    // SDP
    // ===================================================================

    /**
     * Aplica una descripcion LOCAL (offer o answer) generada por WebRTC.
     *
     * El tipo se infiere del estado de la negociacion:
     * - si ya aplicamos una oferta remota -> lo local es la RESPUESTA
     * - si no                                     -> lo local es la OFERTA
     *
     * Esto es determinista porque WebRTC exige setRemoteDescription(OFFER)
     * antes de createAnswer(). La conmutacion de signaling usa el mismo
     * modelo para ambos roles.
     */
    override fun onLocalSdp(localIdentity: IdentityId, remotePeerId: IdentityId, sdp: String): TransportResult<Unit> {
        val res = peers[localIdentity to remotePeerId] ?: return noBinding(localIdentity, remotePeerId)
        return try {
            val localType = if (res.remoteDescriptionType == RTCSdpType.OFFER) {
                RTCSdpType.ANSWER
            } else {
                RTCSdpType.OFFER
            }
            val desc = RTCSessionDescription(localType, sdp)
            // Idempotencia: si ya fijamos exactamente esta descripcion,
            // no la reaplicamos (evita re-setLocalDescription espurio).
            if (res.localDescription?.sdp == sdp && res.localDescription?.sdpType == localType) {
                return TransportResult.Success(Unit)
            }
            res.connection.setLocalDescription(desc, noopSetObserver())
            res.localDescription = desc
            TransportResult.Success(Unit)
        } catch (e: Throwable) {
            TransportResult.Failure(TransportError.INVALID_SDP, "setLocalDescription fallo: ${e.message}")
        }
    }

    override fun onRemoteSdp(localIdentity: IdentityId, remotePeerId: IdentityId, sdp: String): TransportResult<Unit> {
        val res = peers[localIdentity to remotePeerId] ?: return noBinding(localIdentity, remotePeerId)
        return try {
            // Si ya enviamos nuestra oferta, lo remoto es la RESPUESTA.
            // Si no, lo remoto es una OFERTA (rol answerer).
            val remoteType = if (res.localDescription?.sdpType == RTCSdpType.OFFER) {
                RTCSdpType.ANSWER
            } else {
                RTCSdpType.OFFER
            }
            val desc = RTCSessionDescription(remoteType, sdp)
            res.connection.setRemoteDescription(desc, noopSetObserver())
            res.remoteDescriptionType = remoteType
            TransportResult.Success(Unit)
        } catch (e: Throwable) {
            TransportResult.Failure(TransportError.INVALID_SDP, "setRemoteDescription fallo: ${e.message}")
        }
    }

    override fun onIceCandidate(localIdentity: IdentityId, remotePeerId: IdentityId, candidate: String): TransportResult<Unit> {
        val res = peers[localIdentity to remotePeerId] ?: return noBinding(localIdentity, remotePeerId)
        return try {
            val ice = parseCandidate(candidate)
            res.connection.addIceCandidate(ice)
            TransportResult.Success(Unit)
        } catch (e: Throwable) {
            TransportResult.Failure(
                TransportError.INVALID_ICE_CANDIDATE,
                "addIceCandidate fallo: ${e.message}"
            )
        }
    }

    // ===================================================================
    // DataChannel
    // ===================================================================

    override fun sendData(remotePeerId: IdentityId, data: ByteArray): TransportResult<Unit> {
        // Localiza el recurso cuyo remotePeerId coincide. La clave del manager es
        // (localIdentity, remotePeerId); aqui buscamos por remotePeerId.
        val entry = peers.entries.firstOrNull { it.key.second == remotePeerId }
            ?: return TransportResult.Failure(
                TransportError.BINDING_NOT_FOUND,
                "no hay PeerConnection para remotePeerId $remotePeerId"
            )
        val dc = entry.value.dataChannel
            ?: return TransportResult.Failure(
                TransportError.INVALID_STATE_TRANSITION,
                "no hay DataChannel para remotePeerId $remotePeerId"
            )
        if (dc.state != RTCDataChannelState.OPEN) {
            return TransportResult.Failure(
                TransportError.INVALID_STATE_TRANSITION,
                "DataChannel no OPEN, estado: ${dc.state}"
            )
        }
        return try {
            dc.send(RTCDataChannelBuffer(ByteBuffer.wrap(data), true))
            TransportResult.Success(Unit)
        } catch (e: Throwable) {
            TransportResult.Failure(
                TransportError.INVALID_STATE_TRANSITION,
                "DataChannel.send fallo: ${e.message}"
            )
        }
    }

    override fun onCloseBinding(localIdentity: IdentityId, remotePeerId: IdentityId): TransportResult<Unit> {
        closeResources(localIdentity, remotePeerId)
        return TransportResult.Success(Unit)
    }

    // ===================================================================
    // Operaciones del flujo iniciador/respondedor
    // ===================================================================

    /**
     * Genera una oferta SDP real. La SDP producida debe enviarse al peer
     * por el signaling (relay). El adapter la entrega via
     * [eventCallbacks.onLocalSdpGenerated].
     */
    fun requestOffer(localIdentity: IdentityId, remotePeerId: IdentityId): TransportResult<Unit> {
        val res = peers[localIdentity to remotePeerId] ?: return noBinding(localIdentity, remotePeerId)
        return try {
            res.connection.createOffer(RTCOfferOptions(), object : CreateSessionDescriptionObserver {
                override fun onSuccess(description: RTCSessionDescription) {
                    res.connection.setLocalDescription(description, noopSetObserver())
                    res.localDescription = description
                    dispatch { eventCallbacks.onLocalSdpGenerated?.invoke(localIdentity, remotePeerId, description.sdp) }
                }

                override fun onFailure(error: String) {
                    dispatch { eventCallbacks.onTransportError?.invoke(localIdentity, remotePeerId, "createOffer: $error") }
                }
            })
            TransportResult.Success(Unit)
        } catch (e: Throwable) {
            TransportResult.Failure(TransportError.INVALID_STATE_TRANSITION, "createOffer fallo: ${e.message}")
        }
    }

    /**
     * Como respondedor: aplica la oferta remota y genera la respuesta SDP.
     * La respuesta se entrega via [eventCallbacks.onLocalSdpGenerated].
     */
    fun acceptOfferAndAnswer(localIdentity: IdentityId, remotePeerId: IdentityId, remoteOfferSdp: String): TransportResult<Unit> {
        val res = peers[localIdentity to remotePeerId] ?: return noBinding(localIdentity, remotePeerId)
        return try {
            val offer = RTCSessionDescription(RTCSdpType.OFFER, remoteOfferSdp)
            res.remoteDescriptionType = RTCSdpType.OFFER
            res.connection.setRemoteDescription(offer, object : SetSessionDescriptionObserver {
                override fun onSuccess() {
                    res.connection.createAnswer(RTCAnswerOptions(), object : CreateSessionDescriptionObserver {
                        override fun onSuccess(description: RTCSessionDescription) {
                            res.connection.setLocalDescription(description, noopSetObserver())
                            res.localDescription = description
                            dispatch { eventCallbacks.onLocalSdpGenerated?.invoke(localIdentity, remotePeerId, description.sdp) }
                        }

                        override fun onFailure(error: String) {
                            dispatch { eventCallbacks.onTransportError?.invoke(localIdentity, remotePeerId, "createAnswer: $error") }
                        }
                    })
                }

                override fun onFailure(error: String) {
                    dispatch {
                        eventCallbacks.onTransportError?.invoke(localIdentity, remotePeerId, "setRemoteDescription(offer): $error")
                    }
                }
            })
            TransportResult.Success(Unit)
        } catch (e: Throwable) {
            TransportResult.Failure(TransportError.INVALID_SDP, "acceptOfferAndAnswer fallo: ${e.message}")
        }
    }

    /**
     * Aplica la respuesta SDP remota (flujo iniciador).
     */
    fun applyRemoteAnswer(localIdentity: IdentityId, remotePeerId: IdentityId, remoteAnswerSdp: String): TransportResult<Unit> {
        val res = peers[localIdentity to remotePeerId] ?: return noBinding(localIdentity, remotePeerId)
        return try {
            val answer = RTCSessionDescription(RTCSdpType.ANSWER, remoteAnswerSdp)
            res.connection.setRemoteDescription(answer, noopSetObserver())
            res.remoteDescriptionType = RTCSdpType.ANSWER
            TransportResult.Success(Unit)
        } catch (e: Throwable) {
            TransportResult.Failure(TransportError.INVALID_SDP, "setRemoteDescription(answer) fallo: ${e.message}")
        }
    }

    /**
     * Crea el DataChannel en el lado iniciador (antes del offer).
     */
    fun createDataChannel(localIdentity: IdentityId, remotePeerId: IdentityId, label: String): TransportResult<Unit> {
        val res = peers[localIdentity to remotePeerId] ?: return noBinding(localIdentity, remotePeerId)
        return try {
            val init = RTCDataChannelInit().apply {
                ordered = true
                negotiated = false
            }
            val dc = res.connection.createDataChannel(label, init)
            registerDataChannel(res, localIdentity to remotePeerId, res.generation, dc)
            res.dataChannel = dc
            TransportResult.Success(Unit)
        } catch (e: Throwable) {
            TransportResult.Failure(TransportError.INVALID_STATE_TRANSITION, "createDataChannel fallo: ${e.message}")
        }
    }

    /** Estado actual de la conexion (para diagnostico en tests). */
    fun connectionState(localIdentity: IdentityId, remotePeerId: IdentityId): RTCPeerConnectionState? =
        peers[localIdentity to remotePeerId]?.connection?.connectionState

    /** Estado actual del DataChannel (para diagnostico y espera en tests). */
    fun dataChannelState(localIdentity: IdentityId, remotePeerId: IdentityId): RTCDataChannelState? =
        peers[localIdentity to remotePeerId]?.dataChannel?.state

    /**
     * Espera (bloqueante) a que el DataChannel este OPEN.
     * Util para tests y para callers que ya saben que la conexion avanzo.
     */
    fun awaitDataChannelOpen(localIdentity: IdentityId, remotePeerId: IdentityId, timeoutMs: Long = 10_000L): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (dataChannelState(localIdentity, remotePeerId) == RTCDataChannelState.OPEN) return true
            Thread.sleep(20)
        }
        return false
    }

    /** Estado de recoleccion ICE del par (observabilidad 3N.2). */
    fun iceGatheringState(localIdentity: IdentityId, remotePeerId: IdentityId): RTCIceGatheringState? =
        peers[localIdentity to remotePeerId]?.iceGatheringState

    /** Estado de conectividad ICE del par (observabilidad 3N.2). */
    fun iceConnectionState(localIdentity: IdentityId, remotePeerId: IdentityId): RTCIceConnectionState? =
        peers[localIdentity to remotePeerId]?.iceConnectionState

    /** Epoch vigente del par. Sube con cada nueva PeerConnection. */
    fun generation(localIdentity: IdentityId, remotePeerId: IdentityId): Long? =
        peers[localIdentity to remotePeerId]?.generation

    /** Numero de PeerConnections activas para el par (debe ser 0 o 1). */
    fun activeConnectionCount(localIdentity: IdentityId, remotePeerId: IdentityId): Int =
        if (peers.containsKey(localIdentity to remotePeerId)) 1 else 0

    /**
     * Entrega y limpia los candidatos ICE acumulados del par.
     *
     * WebRTC puede generar candidatos antes de que downstream este listo
     * (p.ej. durante la oferta). Este buffer evita perderlos. Al recrear
     * el binding el buffer se vacia, de modo que candidatos de una conexion
     * cerrada no se mezclan con los de la nueva.
     */
    fun drainPendingIce(localIdentity: IdentityId, remotePeerId: IdentityId): List<String> {
        val key = localIdentity to remotePeerId
        val list = pendingLocalIce.remove(key) ?: return emptyList()
        return synchronized(list) { list.toList() }
    }

    /**
     * Espera a que la recoleccion ICE alcance COMPLETE.
     * Devuelve false si se agota el tiempo.
     */
    fun awaitIceGatheringComplete(
        localIdentity: IdentityId,
        remotePeerId: IdentityId,
        timeoutMs: Long = 10_000L,
    ): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (iceGatheringState(localIdentity, remotePeerId) == RTCIceGatheringState.COMPLETE) return true
            Thread.sleep(20)
        }
        return false
    }

    /**
     * Reinicia ICE: fuerza generacion de credenciales ICE nuevas.
     *
     * No cambia la identidad logica del peer ni el epoch: un ICE restart
     * reusa la misma PeerConnection. El epoch solo cambia al recrear la
     * conexion (ver [onCreateBinding]).
     *
     * Tras un restart debe regenerarse la oferta (credenciales nuevas).
     */
    fun restartIce(localIdentity: IdentityId, remotePeerId: IdentityId): TransportResult<Unit> {
        val res = peers[localIdentity to remotePeerId] ?: return noBinding(localIdentity, remotePeerId)
        return try {
            res.connection.restartIce()
            TransportResult.Success(Unit)
        } catch (e: Throwable) {
            TransportResult.Failure(
                TransportError.INVALID_STATE_TRANSITION,
                "restartIce fallo: ${e.message}"
            )
        }
    }

    // ===================================================================
    // Observers
    // ===================================================================

    /**
     * Construye el observer de una PeerConnection concreta.
     *
     * Todos los callbacks comprueban [isCurrent] con la [generation] que
     * capturan. Un callback de una conexion cerrada o reemplazada se
     * descarta silenciosamente: no muta estado ni notifica downstream.
     */
    private fun buildObserver(
        localIdentity: IdentityId,
        remotePeerId: IdentityId,
        generation: Long,
    ): PeerConnectionObserver {
        val key = localIdentity to remotePeerId
        return object : PeerConnectionObserver {

            override fun onIceCandidate(candidate: RTCIceCandidate) {
                if (!isCurrent(key, generation)) return
                val encoded = encodeCandidate(candidate)
                // Buffer local inmediato: ningun candidato generado se pierde,
                // aunque downstream aun no este listo para consumirlo.
                pendingLocalIce.getOrPut(key) { mutableListOf() }.let { list ->
                    synchronized(list) { list.add(encoded) }
                }
                dispatch {
                    if (!isCurrent(key, generation)) return@dispatch
                    eventCallbacks.onIceCandidateGenerated?.invoke(localIdentity, remotePeerId, encoded)
                }
            }

            override fun onIceGatheringChange(state: RTCIceGatheringState) {
                if (!isCurrent(key, generation)) return
                peers[key]?.iceGatheringState = state
            }

            override fun onIceConnectionChange(state: RTCIceConnectionState) {
                if (!isCurrent(key, generation)) return
                peers[key]?.iceConnectionState = state
            }

            override fun onStandardizedIceConnectionChange(state: RTCIceConnectionState) {
                if (!isCurrent(key, generation)) return
                peers[key]?.iceConnectionState = state
            }

            override fun onConnectionChange(state: RTCPeerConnectionState) {
                if (!isCurrent(key, generation)) return
                val connected = state == RTCPeerConnectionState.CONNECTED
                dispatch {
                    if (!isCurrent(key, generation)) return@dispatch
                    eventCallbacks.onConnectionStateChange?.invoke(localIdentity, remotePeerId, connected)
                }
            }

            override fun onDataChannel(channel: RTCDataChannel) {
                if (!isCurrent(key, generation)) {
                    // Conexion ya reemplazada: no adoptar el canal.
                    runCatching { channel.close() }
                    return
                }
                val res = peers[key] ?: return
                registerDataChannel(res, key, generation, channel)
                res.dataChannel = channel
            }

            override fun onIceCandidateError(event: dev.onvoid.webrtc.RTCPeerConnectionIceErrorEvent) {
                if (!isCurrent(key, generation)) return
                dispatch {
                    if (!isCurrent(key, generation)) return@dispatch
                    eventCallbacks.onTransportError?.invoke(
                        localIdentity, remotePeerId,
                        "ICE candidate error: ${event.errorText} (${event.errorCode})"
                    )
                }
            }
        }
    }

    private fun registerDataChannel(
        res: PeerResources,
        key: Pair<IdentityId, IdentityId>,
        generation: Long,
        dc: RTCDataChannel,
    ) {
        dc.registerObserver(object : RTCDataChannelObserver {
            override fun onBufferedAmountChange(bufferedAmount: Long) {}

            override fun onStateChange() {
                // El manager controla el estado del binding; el estado del
                // DataChannel solo se usa para diagnostico.
            }

            override fun onMessage(buffer: RTCDataChannelBuffer) {
                // Fencing: un mensaje de un canal de una conexion cerrada o
                // reemplazada NO debe entregarse downstream.
                if (!isCurrent(key, generation)) return
                val bytes = ByteArray(buffer.data.remaining())
                buffer.data.get(bytes)
                dispatch {
                    if (!isCurrent(key, generation)) return@dispatch
                    eventCallbacks.onDataReceived?.invoke(key.second, bytes)
                }
            }
        })
    }

    // ===================================================================
    // Helpers
    // ===================================================================

    /**
     * Despacha una operacion de callback fuera del hilo nativo de WebRTC.
     * Ver invariante de [callbackExecutor].
     */
    private fun dispatch(block: () -> Unit) {
        try {
            callbackExecutor.execute(block)
        } catch (_: java.util.concurrent.RejectedExecutionException) {
            // Executor detenido (shutdown). Ignorar silenciosamente.
        }
    }

    private fun closeResources(localIdentity: IdentityId, remotePeerId: IdentityId) {
        val key = localIdentity to remotePeerId
        val res = peers.remove(key) ?: return
        // El epoch NO se reinicia al cerrar: si luego se recrea la conexion,
        // nextGeneration() seguira entregando un valor mayor, de modo que
        // cualquier callback tardio de ESTA conexion destruida se descarta.
        try {
            res.dataChannel?.close()
        } catch (_: Throwable) {}
        try {
            res.connection.close()
        } catch (_: Throwable) {}
        pendingLocalIce.remove(key)
    }

    private fun notInitialized(): TransportResult<Unit> =
        TransportResult.Failure(
            TransportError.INVALID_STATE_TRANSITION,
            "RealWebRtcTransport no inicializado (llamar a initialize())"
        )

    private fun noBinding(localIdentity: IdentityId, remotePeerId: IdentityId): TransportResult<Unit> =
        TransportResult.Failure(
            TransportError.BINDING_NOT_FOUND,
            "no hay PeerConnection para ($localIdentity, $remotePeerId)"
        )

    private fun noopSetObserver(): SetSessionDescriptionObserver =
        object : SetSessionDescriptionObserver {
            override fun onSuccess() {}
            override fun onFailure(error: String) {}
        }

    /**
     * Codifica un candidato ICE a String estable para el signaling.
     * Formato: "sdpMid|sdpMLineIndex|sdp"
     */
    private fun encodeCandidate(c: RTCIceCandidate): String =
        "${c.sdpMid}|${c.sdpMLineIndex}|${c.sdp}"

    /**
     * Decodifica un candidato ICE desde el formato de signaling.
     */
    private fun parseCandidate(encoded: String): RTCIceCandidate {
        val parts = encoded.split("|", limit = 3)
        require(parts.size == 3) { "candidato ICE malformado: $encoded" }
        val mid = parts[0].takeIf { it != "null" && it.isNotEmpty() }
        val index = parts[1].toIntOrNull() ?: 0
        return RTCIceCandidate(mid, index, parts[2])
    }

    private fun buildPlatform(): WebRtcPlatform {
        // El build resuelve el clasificador; en runtime coincidimos con el.
        // Esta indireccion permite que tests pasen plataformas simuladas.
        return platformOverride ?: WebRtcPlatformDetector.detect()
    }

    /**
     * Permite a tests simular una plataforma distinta. No usar en produccion.
     */
    internal var platformOverride: WebRtcPlatform? = null
}