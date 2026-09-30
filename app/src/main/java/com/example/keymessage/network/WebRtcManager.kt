package com.example.keymessage.network

import android.content.Context
import android.os.Handler
import android.os.HandlerThread
import android.util.Base64
import com.example.keymessage.util.LogBuffer
import org.webrtc.*
import java.nio.ByteBuffer
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

class PendingAck(val msgId: String, val timestamp: Long)

class PeerSession {
    val sessionId: String = UUID.randomUUID().toString()
    val txSeq = AtomicLong(0)
    val rxSeq = AtomicLong(0)
    @Volatile var lastPongTime: Long = 0
    @Volatile var rtt: Long = -1
    @Volatile var protocolVersion: Int = 1
    val features = mutableSetOf<String>()
    val pendingAcks = ConcurrentHashMap<Long, PendingAck>()
}

class WebRtcManager(
    private val context: Context
) {
    private val thread = HandlerThread("webrtc").also { it.start() }
    private val handler = Handler(thread.looper)
    private var factory: PeerConnectionFactory? = null
    private val peers = mutableMapOf<String, PeerConnection>()
    private val dataChannels = mutableMapOf<String, DataChannel>()
    private val sessions = ConcurrentHashMap<String, PeerSession>()
    @Volatile private var destroyed = false

    var onMessage: ((peerId: String, data: ByteArray) -> Unit)? = null
    var onConnected: ((peerId: String) -> Unit)? = null
    var onDisconnected: ((peerId: String) -> Unit)? = null
    var onSdpReady: ((peerId: String, sdp: String, type: String) -> Unit)? = null
    var onIceCandidate: ((peerId: String, sdp: String, sdpMid: String, sdpMLineIndex: Int) -> Unit)? = null
    var onAcknowledged: ((peerId: String, msgId: String) -> Unit)? = null
    var onSessionReady: ((peerId: String) -> Unit)? = null

    fun init() {
        handler.post {
            try {
                PeerConnectionFactory.InitializationOptions.builder(context)
                    .createInitializationOptions()
                    .let { PeerConnectionFactory.initialize(it) }
                factory = PeerConnectionFactory.builder().createPeerConnectionFactory()
                LogBuffer.log("WebRTC", "Factory ready")
            } catch (e: Exception) {
                LogBuffer.log("WebRTC", "Init error: ${e.message}")
                android.util.Log.e("RTC", "Init error", e)
            }
        }
    }

    fun createPeerConnection(peerId: String) {
        if (destroyed) return
        handler.post { getOrCreatePeerConnection(peerId) }
    }

    private fun getOrCreatePeerConnection(peerId: String): PeerConnection? {
        val existingPc = peers[peerId]
        if (existingPc != null) {
            val state = existingPc.iceConnectionState()
            if (state != PeerConnection.IceConnectionState.DISCONNECTED &&
                state != PeerConnection.IceConnectionState.FAILED &&
                state != PeerConnection.IceConnectionState.CLOSED) {
                LogBuffer.log("RTC-03", "PC $peerId en $state, manteniendo")
                return existingPc
            }
            LogBuffer.log("RTC-03", "PC $peerId $state, re-creando")
            dataChannels.remove(peerId)?.close()
            peers.remove(peerId)?.close()
        }
        val f = factory ?: run {
            LogBuffer.log("RTC-03", "Factory null, no se puede crear PC para $peerId")
            return null
        }
        try {
            LogBuffer.log("RTC-03", "Creando PeerConnection para $peerId")
            val turnUser = generateTurnUser()
            val iceServers = listOf(
                PeerConnection.IceServer.builder("stun:13.140.155.230:3478").createIceServer(),
                PeerConnection.IceServer.builder("turn:13.140.155.230:3478")
                    .setUsername(turnUser)
                    .setPassword(generateTurnPass(turnUser))
                    .createIceServer()
            )
            val config = PeerConnection.RTCConfiguration(iceServers).apply {
                sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
                iceTransportsType = PeerConnection.IceTransportsType.ALL
                enableImplicitRollback = true
                continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
            }
            val pc = f.createPeerConnection(config, peerObserver(peerId))
            if (pc != null) {
                peers[peerId] = pc
                LogBuffer.log("RTC-04", "PeerConnection creada para $peerId")
            } else {
                LogBuffer.log("RTC-04", "createPeerConnection devolvió null para $peerId")
            }
            return pc
        } catch (e: Exception) {
            LogBuffer.log("RTC-04", "Error PC $peerId: ${e.message}")
            android.util.Log.e("RTC", "createPeerConnection error $peerId", e)
            return null
        }
    }

    fun createOffer(peerId: String) {
        if (destroyed) return
        handler.post {
            val existingDc = dataChannels[peerId]
            if (existingDc != null && existingDc.state() == DataChannel.State.OPEN) {
                LogBuffer.log("RTC-07", "DC OPEN para $peerId, saltando Offer")
                return@post
            }
            dataChannels.remove(peerId)?.close()
            val pc = getOrCreatePeerConnection(peerId) ?: run {
                LogBuffer.log("RTC-07", "No se pudo crear PC para Offer $peerId")
                return@post
            }
            try {
                LogBuffer.log("RTC-05", "Creando DataChannel para $peerId")
                val channel = pc.createDataChannel("km-chat", DataChannel.Init().apply {
                    ordered = true; negotiated = false
                })
                dataChannels[peerId] = channel
                registerDataChannel(peerId, channel)
                LogBuffer.log("RTC-06", "DataChannel creado para $peerId")

                val constraints = MediaConstraints().apply {
                    mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "false"))
                    mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveVideo", "false"))
                }
                LogBuffer.log("RTC-07", "Creando Offer para $peerId")
                pc.createOffer(object : SdpObserver {
                    override fun onCreateSuccess(sdp: SessionDescription) {
                        try {
                            LogBuffer.log("RTC-08", "Offer creada para $peerId")
                            pc.setLocalDescription(setSdpObserver(peerId, sdp), sdp)
                        } catch (t: Throwable) {
                            android.util.Log.e("RTC", "createOffer onCreateSuccess crash", t)
                            LogBuffer.log("RTC-08", "CRASH: ${t.message}")
                        }
                    }
                    override fun onSetSuccess() {
                        try { LogBuffer.log("RTC-08", "Offer setLocal success $peerId") } catch (_: Throwable) {}
                    }
                    override fun onCreateFailure(msg: String) {
                        try { LogBuffer.log("RTC-08", "Offer fail $peerId: $msg") } catch (_: Throwable) {}
                    }
                    override fun onSetFailure(msg: String) {
                        try { LogBuffer.log("RTC-08", "SetLocal fail $peerId: $msg") } catch (_: Throwable) {}
                    }
                }, constraints)
            } catch (t: Throwable) {
                android.util.Log.e("RTC", "createOffer crash $peerId", t)
                LogBuffer.log("RTC-07", "CRASH createOffer $peerId: ${t.message}")
            }
        }
    }

    fun handleSdp(peerId: String, sdp: String, type: String) {
        if (destroyed) return
        handler.post {
            val pc = peers[peerId] ?: run {
                LogBuffer.log("RTC", "No hay PC para handleSdp $peerId (type=$type)")
                return@post
            }
            try {
                val sdpType = if (type.equals("offer", true)) SessionDescription.Type.OFFER
                              else SessionDescription.Type.ANSWER
                LogBuffer.log("RTC-10", "SetRemoteDescription ${sdpType} para $peerId")
                pc.setRemoteDescription(object : SdpObserver {
                    override fun onSetSuccess() {
                        try {
                            LogBuffer.log("RTC-11", "RemoteDescription seteada para $peerId")
                            if (sdpType == SessionDescription.Type.OFFER) {
                                val constraints = MediaConstraints().apply {
                                    mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "false"))
                                    mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveVideo", "false"))
                                }
                                LogBuffer.log("RTC-11", "Creando Answer para $peerId")
                                pc.createAnswer(object : SdpObserver {
                                    override fun onCreateSuccess(answer: SessionDescription) {
                                        try {
                                            LogBuffer.log("RTC-12", "Answer creada para $peerId")
                                            pc.setLocalDescription(setSdpObserver(peerId, answer), answer)
                                        } catch (t: Throwable) {
                                            android.util.Log.e("RTC", "createAnswer onCreateSuccess crash", t)
                                            LogBuffer.log("RTC-12", "CRASH: ${t.message}")
                                        }
                                    }
                                    override fun onSetSuccess() {
                                        try { LogBuffer.log("RTC-12", "Answer setLocal success $peerId") } catch (_: Throwable) {}
                                    }
                                    override fun onCreateFailure(msg: String) {
                                        try { LogBuffer.log("RTC-11", "Answer fail $peerId: $msg") } catch (_: Throwable) {}
                                    }
                                    override fun onSetFailure(msg: String) {
                                        try { LogBuffer.log("RTC-11", "SetLocal answer fail $peerId: $msg") } catch (_: Throwable) {}
                                    }
                                }, constraints)
                            }
                        } catch (t: Throwable) {
                            android.util.Log.e("RTC", "setRemote onSetSuccess crash $peerId", t)
                            LogBuffer.log("RTC-11", "CRASH: ${t.message}")
                        }
                    }
                    override fun onCreateSuccess(sdp: SessionDescription?) {}
                    override fun onCreateFailure(msg: String) {}
                    override fun onSetFailure(msg: String) {
                        try { LogBuffer.log("RTC-10", "SetRemote fail $peerId: $msg") } catch (_: Throwable) {}
                    }
                }, SessionDescription(sdpType, sdp))
            } catch (t: Throwable) {
                android.util.Log.e("RTC", "handleSdp crash $peerId", t)
                LogBuffer.log("RTC-10", "CRASH handleSdp $peerId: ${t.message}")
            }
        }
    }

    fun handleIce(peerId: String, sdp: String, sdpMid: String, sdpMLineIndex: Int) {
        if (destroyed) return
        handler.post {
            val pc = peers[peerId] ?: run {
                LogBuffer.log("RTC-14", "No hay PC para ICE de $peerId")
                return@post
            }
            try {
                pc.addIceCandidate(IceCandidate(sdpMid, sdpMLineIndex, sdp))
                LogBuffer.log("RTC-14", "ICE añadido a $peerId")
            } catch (t: Throwable) {
                android.util.Log.e("RTC", "handleIce crash $peerId", t)
                LogBuffer.log("RTC-14", "CRASH handleIce $peerId: ${t.message}")
            }
        }
    }

    fun sendMessage(peerId: String, data: ByteArray) {
        if (destroyed) return
        handler.post {
            try {
                val dc = dataChannels[peerId]
                if (dc != null && dc.state() == DataChannel.State.OPEN) {
                    dc.send(DataChannel.Buffer(ByteBuffer.wrap(data), false))
                }
            } catch (_: Exception) {}
        }
    }

    fun sendJson(peerId: String, json: org.json.JSONObject) {
        if (destroyed) return
        val session = sessions.computeIfAbsent(peerId) { PeerSession() }
        val seq = session.txSeq.getAndIncrement()
        json.put("session", session.sessionId)
        json.put("seq", seq)
        if (json.optString("type") == "msg") {
            val msgId = json.optString("id", "")
            if (msgId.isNotEmpty()) {
                session.pendingAcks[seq] = PendingAck(msgId, System.currentTimeMillis())
            }
        }
        handler.post {
            try {
                val dc = dataChannels[peerId]
                if (dc != null && dc.state() == DataChannel.State.OPEN) {
                    dc.send(DataChannel.Buffer(
                        ByteBuffer.wrap(json.toString().toByteArray(Charsets.UTF_8)), false))
                }
            } catch (_: Exception) {}
        }
    }

    fun isConnected(peerId: String): Boolean {
        if (destroyed) return false
        val dc = dataChannels[peerId]
        return dc != null && dc.state() == DataChannel.State.OPEN
    }

    fun closePeer(peerId: String) {
        if (destroyed) return
        sessions.remove(peerId)
        handler.post {
            try {
                LogBuffer.log("RTC", "Cerrando PC $peerId")
                dataChannels.remove(peerId)?.close()
                peers.remove(peerId)?.close()
            } catch (_: Exception) {}
        }
    }

    fun destroy() {
        destroyed = true
        sessions.clear()
        handler.post {
            try {
                dataChannels.values.forEach { it.close() }; dataChannels.clear()
                peers.values.forEach { it.close() }; peers.clear()
                factory = null; thread.quitSafely()
            } catch (_: Exception) {}
        }
    }

    fun getLastPongTime(peerId: String): Long = sessions[peerId]?.lastPongTime ?: 0L
    fun getRtt(peerId: String): Long = sessions[peerId]?.rtt ?: -1L

    private fun handleProtocolMessage(peerId: String, json: org.json.JSONObject, rawBytes: ByteArray) {
        val session = sessions.computeIfAbsent(peerId) { PeerSession() }
        val rxSeq = json.optLong("seq", -1L)
        if (rxSeq >= 0) {
            session.rxSeq.updateAndGet { old -> maxOf(old, rxSeq) }
        }
        when (json.optString("type")) {
            "msg" -> {
                val acks = org.json.JSONArray().put(rxSeq)
                sendJson(peerId, org.json.JSONObject().apply {
                    put("type", "ack"); put("acks", acks)
                })
                onMessage?.invoke(peerId, json.toString().toByteArray(Charsets.UTF_8))
            }
            "ack" -> {
                val acks = json.optJSONArray("acks")
                if (acks != null) {
                    for (i in 0 until acks.length()) {
                        val ackedSeq = acks.getLong(i)
                        session.pendingAcks.remove(ackedSeq)?.let { pending ->
                            onAcknowledged?.invoke(peerId, pending.msgId)
                        }
                    }
                }
            }
            "ping" -> {
                val pingT = json.optLong("t", 0L)
                sendJson(peerId, org.json.JSONObject().apply {
                    put("type", "pong"); put("t", pingT)
                })
            }
            "pong" -> {
                val pongT = json.optLong("t", 0L)
                if (pongT > 0) {
                    session.rtt = System.currentTimeMillis() - pongT
                }
                session.lastPongTime = System.currentTimeMillis()
            }
            "hello" -> {
                session.protocolVersion = json.optInt("protocol", 1)
                val feats = json.optJSONArray("features")
                if (feats != null) {
                    session.features.clear()
                    for (i in 0 until feats.length()) {
                        session.features.add(feats.optString(i, ""))
                    }
                }
                LogBuffer.log("RTC-HLO", "Peer $peerId proto=${session.protocolVersion} feats=${session.features}")
                onSessionReady?.invoke(peerId)
            }
            else -> {
                onMessage?.invoke(peerId, rawBytes)
            }
        }
    }

    private fun peerObserver(peerId: String) = object : PeerConnection.Observer {
        override fun onIceCandidate(candidate: IceCandidate) {
            if (destroyed) return
            try {
                val parts = candidate.sdp.split(" ")
                val typeIdx = parts.indexOfFirst { it == "typ" }
                val ctype = if (typeIdx >= 0 && typeIdx + 1 < parts.size) parts[typeIdx + 1] else "?"
                val addr = parts.getOrNull(4) ?: "?"
                val port = parts.getOrNull(5) ?: "?"
                LogBuffer.log("RTC-13", "ICE $peerId: $ctype $addr:$port")
                onIceCandidate?.invoke(peerId, candidate.sdp, candidate.sdpMid, candidate.sdpMLineIndex)
            } catch (t: Throwable) {
                android.util.Log.e("RTC", "onIceCandidate crash $peerId", t)
                LogBuffer.log("RTC-13", "CRASH onIceCandidate $peerId: ${t.message}")
            }
        }
        override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>) {
            if (destroyed) return
            try { LogBuffer.log("RTC", "ICE removidos $peerId") } catch (_: Throwable) {}
        }
        override fun onIceConnectionChange(state: PeerConnection.IceConnectionState) {
            if (destroyed) return
            try {
                LogBuffer.log("RTC-15", "ICE state $peerId: $state")
                when (state) {
                    PeerConnection.IceConnectionState.CONNECTED -> onConnected?.invoke(peerId)
                    PeerConnection.IceConnectionState.FAILED, PeerConnection.IceConnectionState.CLOSED -> {
                        LogBuffer.log("RTC-15", "ICE $state $peerId")
                        onDisconnected?.invoke(peerId)
                    }
                    PeerConnection.IceConnectionState.DISCONNECTED -> {
                        onDisconnected?.invoke(peerId)
                    }
                    else -> {}
                }
            } catch (t: Throwable) {
                android.util.Log.e("RTC", "onIceConnectionChange crash $peerId", t)
                LogBuffer.log("RTC-15", "CRASH onIceConnectionChange $peerId: ${t.message}")
            }
        }
        override fun onConnectionChange(state: PeerConnection.PeerConnectionState) {
            if (destroyed) return
            try { LogBuffer.log("RTC-15", "Conn $peerId: $state") } catch (_: Throwable) {}
        }
        override fun onIceConnectionReceivingChange(receiving: Boolean) {
            if (destroyed) return
            try { LogBuffer.log("RTC", "ICE recv $peerId: $receiving") } catch (_: Throwable) {}
        }
        override fun onIceGatheringChange(state: PeerConnection.IceGatheringState) {
            if (destroyed) return
            try { LogBuffer.log("RTC", "ICE gather $peerId: $state") } catch (_: Throwable) {}
        }
        override fun onSignalingChange(state: PeerConnection.SignalingState) {
            if (destroyed) return
            try { LogBuffer.log("RTC", "Signal $peerId: $state") } catch (_: Throwable) {}
        }
        override fun onAddStream(stream: MediaStream?) {}
        override fun onRemoveStream(stream: MediaStream?) {}
        override fun onDataChannel(channel: DataChannel?) {
            if (destroyed) return
            try {
                if (channel != null) {
                    LogBuffer.log("RTC-17", "DC entrante $peerId: ${channel.label()}")
                    dataChannels[peerId] = channel; registerDataChannel(peerId, channel)
                }
            } catch (t: Throwable) {
                android.util.Log.e("RTC", "onDataChannel crash $peerId", t)
                LogBuffer.log("RTC-17", "CRASH onDataChannel $peerId: ${t.message}")
            }
        }
        override fun onRenegotiationNeeded() {
            if (destroyed) return
            try { LogBuffer.log("RTC", "Reneg $peerId") } catch (_: Throwable) {}
        }
        override fun onAddTrack(track: RtpReceiver?, streams: Array<out MediaStream>?) {}
    }

    private fun registerDataChannel(peerId: String, dc: DataChannel) {
        try {
            dc.registerObserver(object : DataChannel.Observer {
                override fun onMessage(buffer: DataChannel.Buffer) {
                    if (destroyed) return
                    try {
                        val bytes = ByteArray(buffer.data.remaining()).also { buffer.data.get(it) }
                        val text = String(bytes, Charsets.UTF_8)
                        if (text.startsWith("{")) {
                            handleProtocolMessage(peerId, org.json.JSONObject(text), bytes)
                        } else {
                            onMessage?.invoke(peerId, bytes)
                        }
                    } catch (t: Throwable) {
                        android.util.Log.e("RTC", "DC msg crash $peerId", t)
                        LogBuffer.log("RTC", "CRASH DC msg $peerId: ${t.message}")
                    }
                }
                override fun onStateChange() {
                    if (destroyed) return
                    try {
                        if (dc.state() == DataChannel.State.OPEN) {
                            LogBuffer.log("RTC-17", "DC OPEN $peerId")
                            sendJson(peerId, org.json.JSONObject().apply {
                                put("type", "hello")
                                put("protocol", 2)
                                put("features", org.json.JSONArray(listOf("ack", "ping")))
                            })
                            onConnected?.invoke(peerId)
                        } else if (dc.state() == DataChannel.State.CLOSED) {
                            LogBuffer.log("RTC-17", "DC CLOSED $peerId")
                            dataChannels.remove(peerId)
                            sessions.remove(peerId)
                            onDisconnected?.invoke(peerId)
                        } else {
                            LogBuffer.log("RTC-17", "DC $peerId: ${dc.state()}")
                        }
                    } catch (t: Throwable) {
                        android.util.Log.e("RTC", "DC state crash $peerId", t)
                        LogBuffer.log("RTC-17", "CRASH DC state $peerId: ${t.message}")
                    }
                }
                override fun onBufferedAmountChange(prev: Long) {}
            })
        } catch (t: Throwable) {
            android.util.Log.e("RTC", "registerDC crash $peerId", t)
            LogBuffer.log("RTC", "CRASH registerDC $peerId: ${t.message}")
        }
    }

    private fun setSdpObserver(peerId: String, sdp: SessionDescription) = object : SdpObserver {
        override fun onSetSuccess() {
            try {
                LogBuffer.log("WebRTC", "$peerId SDP ${sdp.type} set")
                onSdpReady?.invoke(peerId, sdp.description, sdp.type.canonicalForm())
            } catch (t: Throwable) {
                android.util.Log.e("RTC", "setSdpObserver onSetSuccess crash $peerId", t)
                LogBuffer.log("WebRTC", "CRASH setSdpObserver $peerId: ${t.message}")
            }
        }
        override fun onSetFailure(msg: String) {
            try { LogBuffer.log("WebRTC", "setSdp fail $peerId: $msg") } catch (_: Throwable) {}
        }
        override fun onCreateSuccess(sdp: SessionDescription?) {}
        override fun onCreateFailure(msg: String) {}
    }

    private fun generateTurnUser(): String {
        val ts = (System.currentTimeMillis() / 1000) + 3600
        return "$ts:keymessage"
    }

    private fun generateTurnPass(user: String): String {
        val mac = Mac.getInstance("HmacSHA1")
        mac.init(SecretKeySpec("b09e5f2c64d13dc90a345de8a9f05d09".toByteArray(), "HmacSHA1"))
        return Base64.encodeToString(mac.doFinal(user.toByteArray()), Base64.NO_WRAP)
    }
}
