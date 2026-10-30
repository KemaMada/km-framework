package com.km.webrtc.android

import android.content.Context
import android.util.Log
import com.km.config.IceConfig
import com.km.model.IdentityId
import com.km.node.P2pProvider
import com.km.node.P2pRole
import com.km.node.P2pSignalKind
import com.km.node.PeerSignalPort
import com.km.node.PeerTransportManager
import com.km.node.TransportBackend
import com.km.node.TransportError
import com.km.node.TransportEstablishment
import com.km.node.TransportEventCallbacks
import com.km.node.TransportResult
import org.webrtc.DataChannel
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.MediaStream
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.RtpReceiver
import org.webrtc.RtpTransceiver
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * El medio P2P de Android: WebRTC (org.webrtc) detras del mismo puerto que el
 * escritorio.
 *
 * ## La flecha de dependencia
 *
 * `km-core` declara `TransportBackend`/`P2pProvider`; este modulo los
 * implementa con el SDK Android. `km-core` y `km-transport` no conocen
 * `org.webrtc`, y la app solo inyecta el provider.
 *
 * ## Una fabrica por proceso
 *
 * Igual que en escritorio, hay UN `PeerConnectionFactory`: abrir una por par
 * multiplicaria los hilos nativos. El mapa [peers] multiplexa por identidad
 * remota.
 *
 * ## Solo DataChannel
 *
 * No se crean modulos de audio ni EGL: este backend transporta bytes opacos,
 * asi que la app no necesita permisos de camara ni microfono.
 */
class AndroidWebRtcBackend(
    private val context: Context,
    private val ice: IceConfig,
) : TransportBackend {

    override var eventCallbacks: TransportEventCallbacks = TransportEventCallbacks()

    private val lock = Any()
    private var factory: PeerConnectionFactory? = null
    private val peers = ConcurrentHashMap<String, Peer>()
    private val iceSinks = ConcurrentHashMap<String, (String) -> Unit>()

    override fun initialize(): TransportResult<Unit> = synchronized(lock) {
        if (factory != null) return TransportResult.Success(Unit)
        return try {
            PeerConnectionFactory.initialize(
                PeerConnectionFactory.InitializationOptions
                    .builder(context.applicationContext)
                    .createInitializationOptions(),
            )
            factory = PeerConnectionFactory.builder().createPeerConnectionFactory()
            TransportResult.Success(Unit)
        } catch (e: Throwable) {
            Log.e(TAG, "[CONFIG] p2p init fallo: ${e::class.java.simpleName}: ${e.message}", e)
            TransportResult.Failure(
                TransportError.BACKEND_NOT_READY,
                "no se pudo crear PeerConnectionFactory: ${e.message}",
            )
        }
    }

    override fun onCreateBinding(localIdentity: IdentityId, remotePeerId: IdentityId): TransportResult<Unit> {
        val f = factory ?: return notReady()
        val key = key(remotePeerId)
        if (peers.containsKey(key)) return TransportResult.Success(Unit)
        val pc = try {
            f.createPeerConnection(rtcConfiguration(), observer(localIdentity, remotePeerId))
        } catch (e: Throwable) {
            null
        } ?: return TransportResult.Failure(
            TransportError.BACKEND_NOT_READY,
            "createPeerConnection devolvio null",
        )
        peers[key] = Peer(localIdentity, remotePeerId, pc)
        return TransportResult.Success(Unit)
    }

    /**
     * Estos dos metodos existen porque el contrato los exige, pero el
     * establecimiento P2P conduce SDP directamente con [createOffer] y
     * [setRemoteDescription]: el manager no intermedia en este medio.
     */
    override fun onLocalSdp(localIdentity: IdentityId, remotePeerId: IdentityId, sdp: String): TransportResult<Unit> =
        TransportResult.Success(Unit)

    override fun onRemoteSdp(localIdentity: IdentityId, remotePeerId: IdentityId, sdp: String): TransportResult<Unit> =
        TransportResult.Success(Unit)

    override fun onIceCandidate(
        localIdentity: IdentityId,
        remotePeerId: IdentityId,
        candidate: String,
    ): TransportResult<Unit> = addIce(remotePeerId, candidate)

    override fun sendData(remotePeerId: IdentityId, data: ByteArray): TransportResult<Unit> {
        val peer = peers[key(remotePeerId)]
            ?: return TransportResult.Failure(TransportError.BINDING_NOT_FOUND, "sin binding P2P")
        val dc = peer.dataChannel
            ?: return TransportResult.Failure(TransportError.BINDING_CLOSED, "sin DataChannel")
        if (dc.state() != DataChannel.State.OPEN) {
            return TransportResult.Failure(TransportError.BINDING_CLOSED, "DataChannel ${dc.state()}")
        }
        val ok = dc.send(DataChannel.Buffer(ByteBuffer.wrap(data), true))
        return if (ok) {
            TransportResult.Success(Unit)
        } else {
            TransportResult.Failure(TransportError.BACKEND_SEND_FAILED, "DataChannel rechazo el envio")
        }
    }

    override fun onCloseBinding(localIdentity: IdentityId, remotePeerId: IdentityId): TransportResult<Unit> {
        val peer = peers.remove(key(remotePeerId)) ?: return TransportResult.Success(Unit)
        iceSinks.remove(key(remotePeerId))
        runCatching { peer.dataChannel?.unregisterObserver() }
        runCatching { peer.dataChannel?.close() }
        runCatching { peer.pc.close() }
        runCatching { peer.pc.dispose() }
        return TransportResult.Success(Unit)
    }

    override fun shutdown(): TransportResult<Unit> = synchronized(lock) {
        peers.keys.toList().forEach { remote ->
            val peer = peers.remove(remote) ?: return@forEach
            runCatching { peer.dataChannel?.close() }
            runCatching { peer.pc.close() }
            runCatching { peer.pc.dispose() }
        }
        iceSinks.clear()
        runCatching { factory?.dispose() }
        factory = null
        TransportResult.Success(Unit)
    }

    // ------------------------------------------------------------------
    // API que usa el establecimiento (no forma parte del contrato del core)
    // ------------------------------------------------------------------

    /** Crea el DataChannel si aun no existe. Solo lo llama el offerer. */
    internal fun ensureDataChannel(remotePeerId: IdentityId): TransportResult<Unit> {
        val peer = peers[key(remotePeerId)]
            ?: return TransportResult.Failure(TransportError.BINDING_NOT_FOUND, "sin binding P2P")
        if (peer.dataChannel != null) return TransportResult.Success(Unit)
        val init = DataChannel.Init().apply { ordered = true }
        val dc = peer.pc.createDataChannel(DATA_CHANNEL_LABEL, init)
            ?: return TransportResult.Failure(TransportError.BACKEND_NOT_READY, "createDataChannel null")
        registerDataChannel(peer, dc)
        return TransportResult.Success(Unit)
    }

    /** Genera la oferta local y la deja como descripcion local. Devuelve el SDP. */
    internal fun createOffer(remotePeerId: IdentityId): TransportResult<String> =
        createDescription(remotePeerId, offer = true)

    /** Genera la respuesta local y la deja como descripcion local. Devuelve el SDP. */
    internal fun createAnswer(remotePeerId: IdentityId): TransportResult<String> =
        createDescription(remotePeerId, offer = false)

    internal fun setRemoteDescription(
        remotePeerId: IdentityId,
        type: SessionDescription.Type,
        sdp: String,
    ): TransportResult<Unit> {
        val peer = peers[key(remotePeerId)]
            ?: return TransportResult.Failure(TransportError.BINDING_NOT_FOUND, "sin binding P2P")
        val latch = CountDownLatch(1)
        val error = AtomicReference<String?>(null)
        peer.pc.setRemoteDescription(latchObserver(latch, error), SessionDescription(type, sdp))
        if (!latch.await(SDP_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
            return TransportResult.Failure(TransportError.INVALID_SDP, "timeout en setRemoteDescription")
        }
        error.get()?.let {
            return TransportResult.Failure(TransportError.INVALID_SDP, it)
        }
        peer.remoteDescriptionSet = true
        // Candidatos que llegaron antes que el SDP remoto.
        val pendientes = synchronized(peer.pending) {
            peer.pending.toList().also { peer.pending.clear() }
        }
        pendientes.forEach { runCatching { peer.pc.addIceCandidate(it) } }
        return TransportResult.Success(Unit)
    }

    internal fun addIce(remotePeerId: IdentityId, encoded: String): TransportResult<Unit> {
        val peer = peers[key(remotePeerId)]
            ?: return TransportResult.Failure(TransportError.BINDING_NOT_FOUND, "sin binding P2P")
        val candidate = try {
            parseCandidate(encoded)
        } catch (e: IllegalArgumentException) {
            return TransportResult.Failure(TransportError.INVALID_ICE_CANDIDATE, e.message ?: "candidato malformado")
        }
        if (!peer.remoteDescriptionSet) {
            synchronized(peer.pending) { peer.pending += candidate }
            return TransportResult.Success(Unit)
        }
        return if (runCatching { peer.pc.addIceCandidate(candidate) }.isSuccess) {
            TransportResult.Success(Unit)
        } else {
            TransportResult.Failure(TransportError.INVALID_ICE_CANDIDATE, "addIceCandidate fallo")
        }
    }

    /** Espera a que el DataChannel este OPEN. Sondeo corto: sin busy-wait largo. */
    internal fun awaitOpen(remotePeerId: IdentityId, timeoutMs: Long): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val dc = peers[key(remotePeerId)]?.dataChannel
            if (dc != null && dc.state() == DataChannel.State.OPEN) return true
            Thread.sleep(POLL_MS)
        }
        val state = peers[key(remotePeerId)]?.dataChannel?.state()
        Log.w(TAG, "[CONFIG] p2p sin DataChannel OPEN tras ${timeoutMs}ms (estado=$state)")
        return state == DataChannel.State.OPEN
    }

    /** El establecimiento registra aqui su puerto de senalizacion para el ICE saliente. */
    internal fun bindIceSink(remotePeerId: IdentityId, sink: (String) -> Unit) {
        iceSinks[key(remotePeerId)] = sink
    }

    internal fun unbindIceSink(remotePeerId: IdentityId) {
        iceSinks.remove(key(remotePeerId))
    }

    // ------------------------------------------------------------------
    // Interno
    // ------------------------------------------------------------------

    private fun createDescription(remotePeerId: IdentityId, offer: Boolean): TransportResult<String> {
        val peer = peers[key(remotePeerId)]
            ?: return TransportResult.Failure(TransportError.BINDING_NOT_FOUND, "sin binding P2P")
        val created = CountDownLatch(1)
        val applied = CountDownLatch(1)
        val sdpRef = AtomicReference<String?>(null)
        val error = AtomicReference<String?>(null)

        val afterSet = object : SdpObserver {
            override fun onSetSuccess() {
                applied.countDown()
            }

            override fun onSetFailure(reason: String?) {
                error.set(reason)
                applied.countDown()
            }

            override fun onCreateSuccess(desc: SessionDescription?) = Unit
            override fun onCreateFailure(reason: String?) = Unit
        }

        val creator = object : SdpObserver {
            override fun onCreateSuccess(desc: SessionDescription) {
                sdpRef.set(desc.description)
                peer.pc.setLocalDescription(afterSet, desc)
                created.countDown()
            }

            override fun onCreateFailure(reason: String?) {
                error.set(reason)
                created.countDown()
                applied.countDown()
            }

            override fun onSetSuccess() = Unit
            override fun onSetFailure(reason: String?) = Unit
        }

        if (offer) {
            peer.pc.createOffer(creator, MediaConstraints())
        } else {
            peer.pc.createAnswer(creator, MediaConstraints())
        }
        if (!created.await(SDP_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
            return TransportResult.Failure(TransportError.INVALID_SDP, "timeout creando SDP local")
        }
        if (!applied.await(SDP_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
            return TransportResult.Failure(TransportError.INVALID_SDP, "timeout aplicando SDP local")
        }
        error.get()?.let { return TransportResult.Failure(TransportError.INVALID_SDP, it) }
        val sdp = sdpRef.get()
            ?: return TransportResult.Failure(TransportError.INVALID_SDP, "SDP local vacio")
        Log.i(TAG, "[CONFIG] p2p SDP local listo (${if (offer) "offer" else "answer"}, ${sdp.length} B)")
        return TransportResult.Success(sdp)
    }

    private fun latchObserver(latch: CountDownLatch, error: AtomicReference<String?>): SdpObserver =
        object : SdpObserver {
            override fun onSetSuccess() = latch.countDown()
            override fun onSetFailure(reason: String?) {
                error.set(reason)
                latch.countDown()
            }

            override fun onCreateSuccess(desc: SessionDescription?) = Unit
            override fun onCreateFailure(reason: String?) = Unit
        }

    private fun registerDataChannel(peer: Peer, dc: DataChannel) {
        peer.dataChannel = dc
        dc.registerObserver(object : DataChannel.Observer {
            override fun onBufferedAmountChange(previousAmount: Long) = Unit

            override fun onStateChange() {
                val open = dc.state() == DataChannel.State.OPEN
                eventCallbacks.onConnectionStateChange?.invoke(peer.local, peer.remote, open)
            }

            override fun onMessage(buffer: DataChannel.Buffer) {
                val bytes = ByteArray(buffer.data.remaining())
                buffer.data.get(bytes)
                eventCallbacks.onDataReceived?.invoke(peer.remote, bytes)
            }
        })
    }

    private fun observer(local: IdentityId, remote: IdentityId): PeerConnection.Observer =
        object : PeerConnection.Observer {
            override fun onSignalingChange(state: PeerConnection.SignalingState?) = Unit
            override fun onIceConnectionChange(state: PeerConnection.IceConnectionState?) = Unit
            override fun onIceConnectionReceivingChange(receiving: Boolean) = Unit
            override fun onIceGatheringChange(state: PeerConnection.IceGatheringState?) = Unit
            override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>?) = Unit
            override fun onAddStream(stream: MediaStream?) = Unit
            override fun onRemoveStream(stream: MediaStream?) = Unit
            override fun onRenegotiationNeeded() = Unit
            override fun onAddTrack(receiver: RtpReceiver?, streams: Array<out MediaStream>?) = Unit
            override fun onTrack(transceiver: RtpTransceiver?) = Unit

            override fun onDataChannel(channel: DataChannel?) {
                val peer = peers[key(remote)] ?: return
                if (channel != null) registerDataChannel(peer, channel)
            }

            override fun onIceCandidate(candidate: IceCandidate?) {
                if (candidate == null) return
                val encoded = encodeCandidate(candidate)
                iceSinks[key(remote)]?.invoke(encoded)
                eventCallbacks.onIceCandidateGenerated?.invoke(local, remote, encoded)
            }
        }

    private fun rtcConfiguration(): PeerConnection.RTCConfiguration {
        val servers = ice.urls.map { url ->
            if (url.startsWith("turn:", ignoreCase = true) || url.startsWith("turns:", ignoreCase = true)) {
                PeerConnection.IceServer.builder(url)
                    .setUsername(ice.turnUsername.orEmpty())
                    .setPassword(ice.turnCredential.orEmpty())
                    .createIceServer()
            } else {
                PeerConnection.IceServer.builder(url).createIceServer()
            }
        }
        return PeerConnection.RTCConfiguration(servers).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
        }
    }

    private fun notReady(): TransportResult<Unit> =
        TransportResult.Failure(TransportError.BACKEND_NOT_READY, "backend P2P no inicializado")

    private fun key(remote: IdentityId): String = remote.value

    private class Peer(
        val local: IdentityId,
        val remote: IdentityId,
        val pc: PeerConnection,
    ) {
        @Volatile
        var dataChannel: DataChannel? = null

        @Volatile
        var remoteDescriptionSet: Boolean = false

        val pending: MutableList<IceCandidate> = mutableListOf()
    }

    private companion object {
        const val TAG: String = "KeyMessage"
        const val DATA_CHANNEL_LABEL: String = "km"
        const val SDP_TIMEOUT_MS: Long = 8_000L
        const val POLL_MS: Long = 25L

        fun encodeCandidate(c: IceCandidate): String = "${c.sdpMid}|${c.sdpMLineIndex}|${c.sdp}"

        fun parseCandidate(encoded: String): IceCandidate {
            val parts = encoded.split("|", limit = 3)
            require(parts.size == 3) { "candidato ICE malformado" }
            val mid = parts[0].takeIf { it != "null" && it.isNotEmpty() }
            val index = parts[1].toIntOrNull() ?: 0
            return IceCandidate(mid, index, parts[2])
        }
    }
}
