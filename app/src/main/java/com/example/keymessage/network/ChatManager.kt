package com.example.keymessage.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import okhttp3.*
import org.json.JSONObject
import com.example.keymessage.model.ChatMessage
import com.example.keymessage.model.MessageStatus
import com.example.keymessage.model.AppState
import com.example.keymessage.network.dht.DhtNode
import com.example.keymessage.crypto.Crypto
import com.example.keymessage.util.LogBuffer
import java.util.concurrent.TimeUnit

class ChatManager(
    private val appState: AppState,
    private val context: Context
) {
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var webSocket: WebSocket? = null
    private var reconnectJob: Job? = null
    private var webRtc: WebRtcManager? = null
    private var reconnectDelay = 1000L
    private val heartbeats = mutableMapOf<String, Job>()
    private var networkCallbackRegistered = false

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        private var lastTransport: Int? = null
        override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
            val transport = when {
                caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> NetworkCapabilities.TRANSPORT_WIFI
                caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> NetworkCapabilities.TRANSPORT_CELLULAR
                caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> NetworkCapabilities.TRANSPORT_ETHERNET
                else -> -1
            }
            if (lastTransport != null && lastTransport != transport) {
                LogBuffer.log("NET", "Transport changed: $lastTransport → $transport")
                scope.launch { onNetworkChanged() }
            }
            lastTransport = transport
        }
    }

    private val _messages = MutableStateFlow<Map<String, List<ChatMessage>>>(emptyMap())
    val messages: StateFlow<Map<String, List<ChatMessage>>> = _messages

    private val _onlineUsers = MutableStateFlow<Set<String>>(emptySet())
    val onlineUsers: StateFlow<Set<String>> = _onlineUsers

    private val _connectionStatus = MutableStateFlow(false)
    val connectionStatus: StateFlow<Boolean> = _connectionStatus

    private var myNodeId: String = ""
    private var myPublicKey: String = ""
    private var myPrivateKey: String = ""

    private val client = OkHttpClient.Builder()
        .readTimeout(0, TimeUnit.SECONDS)
        .build()

    init { loadAllMessages() }

    private fun loadAllMessages() {
        val map = mutableMapOf<String, List<ChatMessage>>()
        for (cid in appState.getAllContactIds()) map[cid] = appState.getMessages(cid)
        _messages.value = map
    }

    fun connect() {
        LogBuffer.log("NET", "connect() called")
        // Detect duplicate connect: log source
        try { throw java.lang.Exception("connect stacktrace") } catch (e: java.lang.Exception) {
            android.util.Log.d("RTC", "connect() stacktrace", e)
        }
        reconnectJob?.cancel()
        val identity = appState.identity
        if (identity.publicKey.isEmpty()) return
        myNodeId = DhtNode.hashNodeId(identity.publicKey)
        myPublicKey = identity.publicKey
        myPrivateKey = appState.privateKey

        webRtc?.destroy()
        webRtc = WebRtcManager(context).apply {
            onMessage = { peerId, data -> try { onDataChannelMsg(peerId, data) } catch (_: Exception) {} }
            onConnected = { p -> scope.launch { try { onPeerConnected(p) } catch (_: Exception) {} } }
            onDisconnected = { p -> scope.launch { try { onPeerDisconnected(p) } catch (_: Exception) {} } }
            onSdpReady = { p, sdp, type ->
                sendSignaling(p, JSONObject().apply { put("sdp", sdp); put("type", type) }.toString(), "sdp")
                if (type == "offer") LogBuffer.log("RTC-09", "Offer enviada a $p")
                else LogBuffer.log("RTC-12", "Answer enviada a $p")
            }
            onIceCandidate = { p, sdp, mid, idx ->
                sendSignaling(p, JSONObject().apply { put("candidate", sdp); put("sdpMid", mid); put("sdpMLineIndex", idx) }.toString(), "ice")
                LogBuffer.log("RTC-13", "ICE generado para $p")
            }
            onAcknowledged = { p, msgId ->
                scope.launch { try { updateMessageStatus(msgId, MessageStatus.SENT) } catch (_: Exception) {} }
            }
            onSessionReady = { p ->
                scope.launch { try { retryQueuedMessages(p) } catch (_: Exception) {} }
            }
            init()
        }
        registerNetworkCallback()
        connectSignaling()
    }

    private fun registerNetworkCallback() {
        if (networkCallbackRegistered) return
        try {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val request = NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .build()
            cm.registerNetworkCallback(request, networkCallback)
            networkCallbackRegistered = true
            LogBuffer.log("NET", "Network callback registered")
        } catch (e: Exception) {
            LogBuffer.log("NET", "Network callback error: ${e.message}")
        }
    }

    // ─── WebSocket Signaling ───

    private fun connectSignaling() {
        if (myNodeId.isEmpty()) return
        val url = "ws://13.140.155.230:3001/ws?nodeId=$myNodeId"
        val request = Request.Builder().url(url).build()
        webSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                _connectionStatus.value = true
                reconnectDelay = 1000L
                LogBuffer.log("SIG", "Connected to relay")
            }
            override fun onMessage(webSocket: WebSocket, text: String) = handleWsMsg(text)
            override fun onClosed(ws: WebSocket, code: Int, reason: String) {
                _connectionStatus.value = false; _onlineUsers.value = emptySet()
                if (ws === webSocket) scheduleReconnect(ws)
            }
            override fun onFailure(ws: WebSocket, t: Throwable, response: Response?) {
                _connectionStatus.value = false; _onlineUsers.value = emptySet()
                if (ws === webSocket) scheduleReconnect(ws)
            }
        })
    }

    private fun handleWsMsg(text: String) {
        try {
            val json = JSONObject(text)
            when (json.optString("type")) {
                "peers" -> {
                    val list = json.optJSONArray("list")
                    if (list != null) {
                        val peers = mutableSetOf<String>()
                        for (i in 0 until list.length()) {
                            val nid = list.optJSONObject(i).optString("nodeId", "")
                            peers.add(nid); onPeerOnline(nid)
                        }
                        _onlineUsers.value = peers
                    }
                }
                "peer_online" -> {
                    val nid = json.optString("nodeId", "")
                    if (nid.isNotEmpty()) { _onlineUsers.value = _onlineUsers.value + nid; onPeerOnline(nid) }
                }
                "peer_offline" -> {
                    val nid = json.optString("nodeId", "")
                    if (nid.isNotEmpty()) {
                        _onlineUsers.value = _onlineUsers.value - nid
                        webRtc?.closePeer(nid)
                    }
                }
                "sdp" -> {
                    val from = json.optString("from", ""); val data = json.optString("data", "")
                    if (from.isNotEmpty() && data.isNotEmpty()) {
                        val sdpJson = JSONObject(data)
                        val sdpType = sdpJson.optString("type", "offer")
                        if (sdpType == "offer") LogBuffer.log("RTC-10", "Offer recibida de $from")
                        else LogBuffer.log("RTC-12", "Answer recibida de $from")
                        webRtc?.createPeerConnection(from)
                        webRtc?.handleSdp(from, sdpJson.optString("sdp", ""), sdpType)
                    }
                }
                "ice" -> {
                    val from = json.optString("from", ""); val data = json.optString("data", "")
                    if (from.isNotEmpty() && data.isNotEmpty()) {
                        LogBuffer.log("RTC-14", "ICE recibido de $from")
                        val ice = JSONObject(data)
                        webRtc?.handleIce(from, ice.optString("candidate", ""), ice.optString("sdpMid", ""), ice.optInt("sdpMLineIndex", 0))
                    }
                }
                "contact_exchange" -> {
                    val data = json.optString("data", "")
                    if (data.isNotEmpty()) handleContactExchange(data)
                }
            }
        } catch (_: Exception) {}
    }

    private fun onPeerOnline(peerId: String) {
        if (peerId == myNodeId) { LogBuffer.log("RTC-01", "peer_online self ignored"); return }
        LogBuffer.log("RTC-01", "peer_online recibido de $peerId")
        if (appState.getContacts().none { it.id == peerId }) {
            LogBuffer.log("RTC-02", "contacto NO encontrado para $peerId, posponiendo")
            return
        }
        LogBuffer.log("RTC-02", "contacto encontrado para $peerId")
        webRtc?.createPeerConnection(peerId)
        if (myNodeId < peerId) {
            LogBuffer.log("RTC-07", "Mi nodeId < peer, creando Offer para $peerId")
            webRtc?.createOffer(peerId)
        } else {
            LogBuffer.log("RTC", "Mi nodeId > peer, esperando Offer de $peerId")
        }
    }

    // ─── Send via signaling relay ───

    private fun sendSignaling(target: String, data: String, dataType: String) {
        webSocket?.send(JSONObject().apply {
            put("target", target); put("data", data); put("dataType", dataType); put("id", "")
        }.toString())
    }

    // ─── Message send ───

    fun sendMessage(contactUserId: String, text: String) {
        val contact = appState.getContacts().find { it.id == contactUserId }
        val encrypted = if (contact != null && contact.publicKey.isNotEmpty()) {
            try { Crypto.encryptMessage(text, contact.publicKey) } catch (_: Exception) { text }
        } else text

        val msgId = java.util.UUID.randomUUID().toString()
        val msg = ChatMessage(id = msgId, senderId = myNodeId, receiverId = contactUserId,
            text = text, isMine = true, status = MessageStatus.SENDING)
        addMessage(contactUserId, msg)
        persistMessage(contactUserId, msg)

        if (webRtc?.isConnected(contactUserId) == true) {
            val payload = JSONObject().apply { put("type", "msg"); put("data", encrypted); put("id", msgId) }
            webRtc?.sendJson(contactUserId, payload)
            LogBuffer.log("MSG", "Sent via WebRTC to $contactUserId")
        } else {
            LogBuffer.log("MSG", "Queued for $contactUserId (no WebRTC)")
        }
    }

    fun sendContactExchange(targetNodeId: String, myName: String, myPublicKey: String) {
        sendSignaling(targetNodeId, "[CONTACT_EXCHANGE]$myName|$myPublicKey", "contact_exchange")
        LogBuffer.log("SIG", "Contact exchange sent to $targetNodeId")
        checkContactOnline(targetNodeId)
    }

    fun checkContactOnline(peerId: String) {
        if (peerId == myNodeId || peerId.isEmpty()) return
        if (peerId in _onlineUsers.value) {
            LogBuffer.log("RTC", "Manual trigger onPeerOnline para $peerId")
            onPeerOnline(peerId)
        } else {
            LogBuffer.log("RTC", "$peerId no está online, no se puede iniciar WebRTC")
        }
    }

    // ─── WebRTC callbacks ───

    private fun onDataChannelMsg(peerId: String, data: ByteArray) {
        try {
            val json = JSONObject(String(data, Charsets.UTF_8))
            when (json.optString("type")) {
                "msg" -> {
                    val rawData = json.optString("data", ""); val msgId = json.optString("id", "")
                    if (rawData.isNotEmpty()) onMessageReceived(peerId, rawData, msgId)
                }
            }
        } catch (_: Exception) {}
    }

    private fun onPeerConnected(peerId: String) {
        LogBuffer.log("RTC-16", "ICE CONNECTED con $peerId")
        startHeartbeat(peerId)
    }

    private fun onPeerDisconnected(peerId: String) {
        LogBuffer.log("RTC", "ICE desconectado de $peerId")
        heartbeats[peerId]?.cancel()
        heartbeats.remove(peerId)
    }

    private fun startHeartbeat(peerId: String) {
        heartbeats[peerId]?.cancel()
        heartbeats[peerId] = scope.launch {
            while (isActive) {
                delay(15_000)
                if (webRtc?.isConnected(peerId) != true) continue
                val pingTime = System.currentTimeMillis()
                webRtc?.sendJson(peerId, JSONObject().apply {
                    put("type", "ping"); put("t", pingTime)
                })
                delay(5_000)
                if (webRtc?.getLastPongTime(peerId) ?: 0L < pingTime) {
                    LogBuffer.log("RTC-HB", "Heartbeat timeout for $peerId, closing")
                    webRtc?.closePeer(peerId)
                    return@launch
                }
                val rtt = webRtc?.getRtt(peerId) ?: -1L
                if (rtt >= 0) LogBuffer.log("RTC-HB", "RTT $peerId: ${rtt}ms")
            }
        }
    }

    // ─── Incoming message ───

    private fun onMessageReceived(fromNodeId: String, rawData: String, msgId: String) {
        val text = try { Crypto.decryptMessage(rawData, myPrivateKey) } catch (_: Exception) { rawData }

        if (text.startsWith("[CONTACT_EXCHANGE]")) { handleContactExchange(text); return }

        if (appState.getContacts().none { it.id == fromNodeId }) {
            appState.addContact(com.example.keymessage.model.Contact(
                id = fromNodeId, name = fromNodeId.take(12), publicKey = ""))
        }

        val msg = ChatMessage(id = msgId, senderId = fromNodeId, receiverId = myNodeId,
            text = text, timestamp = System.currentTimeMillis(), isMine = false, status = MessageStatus.DELIVERED)
        addMessage(fromNodeId, msg); persistMessage(fromNodeId, msg)
    }

    private fun handleContactExchange(data: String) {
        val parts = data.removePrefix("[CONTACT_EXCHANGE]").split("|")
        if (parts.size >= 2) {
            val senderNodeId = DhtNode.hashNodeId(parts[1])
            val added = appState.getContacts().none { it.id == senderNodeId }
            if (added) {
                appState.addContact(com.example.keymessage.model.Contact(
                    id = senderNodeId, name = parts[0], publicKey = parts[1]))
                LogBuffer.log("CONTACT", "Auto-added ${parts[0]}")
            }
            if (senderNodeId in _onlineUsers.value) {
                onPeerOnline(senderNodeId)
            }
        }
    }

    private fun retryQueuedMessages(targetNodeId: String) {
        val contact = appState.getContacts().find { it.id == targetNodeId } ?: return
        val msgs = _messages.value[targetNodeId] ?: return
        for (msg in msgs) {
            if (msg.status == MessageStatus.SENDING && msg.isMine) {
                val encrypted = if (contact.publicKey.isNotEmpty()) {
                    try { Crypto.encryptMessage(msg.text, contact.publicKey) } catch (_: Exception) { msg.text }
                } else msg.text
                val payload = JSONObject().apply { put("type", "msg"); put("data", encrypted); put("id", msg.id) }
                webRtc?.sendJson(targetNodeId, payload)
                LogBuffer.log("MSG", "Retried to $targetNodeId")
            }
        }
    }

    // ─── Message state ───

    fun getMessagesForContact(contactId: String) = _messages.value[contactId] ?: emptyList()

    private fun addMessage(contactId: String, msg: ChatMessage) {
        val current = _messages.value.toMutableMap()
        val list = (current[contactId] ?: emptyList()).toMutableList()
        val idx = list.indexOfFirst { it.id == msg.id }
        if (idx >= 0) list[idx] = msg else list.add(msg)
        current[contactId] = list; _messages.value = current
    }

    private fun updateMessageStatus(msgId: String, status: MessageStatus) {
        val current = _messages.value.toMutableMap()
        for ((contactId, msgs) in current) {
            val idx = msgs.indexOfFirst { it.id == msgId }
            if (idx >= 0) {
                val updated = msgs.toMutableList()
                updated[idx] = updated[idx].copy(status = status)
                current[contactId] = updated
                persistMessage(contactId, updated[idx]); break
            }
        }
        _messages.value = current
    }

    private fun persistMessage(contactId: String, msg: ChatMessage) {
        val list = appState.getMessages(contactId)
        val idx = list.indexOfFirst { it.id == msg.id }
        if (idx >= 0) list[idx] = msg else list.add(msg)
        appState.saveMessages(contactId, list)
    }

    private fun onNetworkChanged() {
        LogBuffer.log("NET", "Network changed, restarting WebRTC")
        reconnectJob?.cancel()
        heartbeats.values.forEach { it.cancel() }; heartbeats.clear()
        try { webSocket?.close(1000, "Network changed") } catch (_: Exception) {}
        webSocket = null
        _onlineUsers.value = emptySet()
        reconnectDelay = 1000L
        connect()
    }

    private fun scheduleReconnect(staleWs: WebSocket? = null) {
        reconnectJob?.cancel()
        reconnectJob = scope.launch {
            try {
                delay(reconnectDelay)
                if (staleWs != null && staleWs !== webSocket) {
                    LogBuffer.log("SIG", "Ignoring stale reconnect")
                    return@launch
                }
                reconnectDelay = (reconnectDelay * 2).coerceAtMost(60_000L)
                connect()
            } catch (_: Exception) {}
        }
    }

    fun disconnect() {
        reconnectJob?.cancel()
        heartbeats.values.forEach { it.cancel() }; heartbeats.clear()
        try { webSocket?.close(1000, "Client closing") } catch (_: Exception) {}
        webSocket = null
        webRtc?.destroy(); webRtc = null
        if (networkCallbackRegistered) {
            try {
                val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
                cm.unregisterNetworkCallback(networkCallback)
            } catch (_: Exception) {}
            networkCallbackRegistered = false
        }
        _connectionStatus.value = false; _onlineUsers.value = emptySet()
    }
}
