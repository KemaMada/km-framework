package com.example.keymessage.network

import com.keymessage.core.crypto.Ed25519
import com.keymessage.core.codec.JsonRelayControlCodec
import com.keymessage.core.model.IdentityId
import com.keymessage.core.model.RelayExpiredNotice
import com.keymessage.core.model.StoredReceipt
import com.keymessage.core.node.RelayTransport
import com.keymessage.core.protocol.ConnectionState
import com.keymessage.core.protocol.Transport
import okhttp3.*
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.util.UUID
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

class RelayClient(
    private val url: String,
    private val localIdentity: IdentityId,
    private val localPrivateKey: ByteArray,
    private val localPublicKey: ByteArray,
    private val relayIdentityId: IdentityId,
    private val ed25519: Ed25519,
    private val maxReconnectDelayMs: Long = 30000
) : RelayTransport {

    private val client = OkHttpClient.Builder()
        .pingInterval(15, TimeUnit.SECONDS)
        .build()

    private val state = AtomicReference(ConnectionState.DISCONNECTED)
    @Volatile
    private var webSocket: WebSocket? = null

    private val messageHandlers = mutableListOf<(ByteArray) -> Unit>()
    private val ackHandlers = mutableListOf<(ByteArray) -> Unit>()
    private val storedHandlers = mutableListOf<(StoredReceipt) -> Unit>()
    private val relayExpiredHandlers = mutableListOf<(RelayExpiredNotice) -> Unit>()
    private val stateHandlers = mutableListOf<(ConnectionState) -> Unit>()
    private val peerOnlineHandlers = mutableListOf<(IdentityId) -> Unit>()
    private val peerOfflineHandlers = mutableListOf<(IdentityId) -> Unit>()

    private var reconnectAttempt = 0
    @Volatile
    private var executor: ScheduledExecutorService? = null
    private val pendingMessages = ConcurrentLinkedQueue<Pair<ByteArray, IdentityId>>()

    private var sessionId: String? = null
    private var expectedServerNonce: String? = null

    override fun connect(): Result<Unit> = runCatching {
        val current = state.get()
        if (current != ConnectionState.DISCONNECTED && current != ConnectionState.RECONNECTING) {
            return@runCatching
        }
        if (!state.compareAndSet(current, ConnectionState.CONNECTING)) {
            return@runCatching
        }
        ensureExecutor()
        reconnectAttempt = 0
        stateHandlers.forEach { it(ConnectionState.CONNECTING) }
        openConnection()
    }

    override fun disconnect() {
        reconnectAttempt = Int.MAX_VALUE
        webSocket?.close(1000, "client disconnect")
        webSocket = null
        executor?.shutdownNow()
        executor = null
        setState(ConnectionState.DISCONNECTED)
    }

    override fun send(data: ByteArray, to: IdentityId): Result<Unit> = runCatching {
        if (state.get() != ConnectionState.ONLINE) {
            pendingMessages.add(data to to)
            return@runCatching
        }
        webSocket?.send(String(data))
    }

    override fun isOnline(): Boolean = state.get() == ConnectionState.ONLINE

    override fun connectionState(): ConnectionState = state.get()

    override fun onStateChange(handler: (ConnectionState) -> Unit) {
        stateHandlers.add(handler)
    }

    override fun setMessageHandler(handler: (ByteArray) -> Unit) {
        messageHandlers.add(handler)
    }

    override fun setAckHandler(handler: (ByteArray) -> Unit) {
        ackHandlers.add(handler)
    }

    override fun setStoredHandler(handler: (StoredReceipt) -> Unit) {
        storedHandlers.add(handler)
    }

    override fun setRelayExpiredHandler(handler: (RelayExpiredNotice) -> Unit) {
        relayExpiredHandlers.add(handler)
    }

    override fun onPeerOnline(handler: (IdentityId) -> Unit) {
        peerOnlineHandlers.add(handler)
    }

    override fun onPeerOffline(handler: (IdentityId) -> Unit) {
        peerOfflineHandlers.add(handler)
    }

    private fun setState(newState: ConnectionState) {
        val old = state.getAndSet(newState)
        if (old != newState) {
            stateHandlers.forEach { it(newState) }
        }
    }

    private fun ensureExecutor() {
        if (executor == null || executor?.isShutdown == true) {
            executor = Executors.newSingleThreadScheduledExecutor { r ->
                Thread(r, "km-relay-client").also { it.isDaemon = true }
            }
        }
    }

    private fun openConnection() {
        val request = Request.Builder().url(url).build()
        webSocket = client.newWebSocket(request, RelayWebSocketListener())
    }

    private fun onWebSocketOpen() {
        if (!state.compareAndSet(ConnectionState.CONNECTING, ConnectionState.AUTHENTICATING)) {
            webSocket?.close(1000, "stale connection")
            return
        }
        stateHandlers.forEach { it(ConnectionState.AUTHENTICATING) }
        sendAuthRequest()
    }

    private fun onWebSocketMessage(text: String) {
        try {
            val json = JSONObject(text)
            val type = json.optString("type")

            when (type) {
                "AUTH_CHALLENGE" -> handleAuthChallenge(json)
                "AUTH_OK" -> handleAuthOk(json)
                "AUTH_FAIL" -> handleAuthFail(json)
                "PEER_ONLINE" -> handlePeerOnline(json)
                "PEER_OFFLINE" -> handlePeerOffline(json)
                "PING" -> handlePing(json)
                "RELAY_ERROR" -> handleRelayError(json)
                "STORED" -> handleStored(text)
                "RELAY_EXPIRED" -> handleRelayExpired(text)
                "MESSAGE" -> messageHandlers.forEach { it(text.toByteArray()) }
                "ACK" -> ackHandlers.forEach { it(text.toByteArray()) }
                else -> {}
            }
        } catch (e: Exception) {
        }
    }

    private fun onWebSocketClosed(normal: Boolean) {
        if (normal) {
            setState(ConnectionState.DISCONNECTED)
            return
        }
        scheduleReconnect()
    }

    private fun onWebSocketFailure() {
        scheduleReconnect()
    }

    private fun scheduleReconnect() {
        if (reconnectAttempt >= Int.MAX_VALUE) return
        val exec = executor ?: return
        setState(ConnectionState.RECONNECTING)
        reconnectAttempt++
        val delay = Math.min(
            (1000L * Math.pow(2.0, reconnectAttempt.toDouble())).toLong(),
            maxReconnectDelayMs
        )
        exec.schedule({ reconnectNow() }, delay, TimeUnit.MILLISECONDS)
    }

    private fun reconnectNow() {
        if (!state.compareAndSet(ConnectionState.RECONNECTING, ConnectionState.CONNECTING)) {
            return
        }
        webSocket?.close(1000, "reconnect")
        webSocket = null
        stateHandlers.forEach { it(ConnectionState.CONNECTING) }
        openConnection()
    }

    private fun sendAuthRequest() {
        val msg = JSONObject().apply {
            put("type", "AUTH_REQUEST")
            put("messageId", UUID.randomUUID().toString())
            put("timestamp", System.currentTimeMillis())
            put("protocolVersion", "2.0")
            put("identityId", localIdentity.value)
            put("publicKey", publicKeyBase64())
            put("responderIdentityId", relayIdentityId.value)
            put("capabilities", JSONObject().apply {
                put("serialization", org.json.JSONArray(listOf("json")))
            })
        }
        webSocket?.send(msg.toString())
    }

    private fun handleAuthChallenge(json: JSONObject) {
        val nonce = json.optString("nonce")
        if (nonce.isEmpty()) return
        expectedServerNonce = nonce

        val signature = computeAuthSignature(nonce, json.optLong("timestamp"))

        val msg = JSONObject().apply {
            put("type", "AUTH_RESPONSE")
            put("messageId", UUID.randomUUID().toString())
            put("timestamp", System.currentTimeMillis())
            put("signature", signature)
        }
        webSocket?.send(msg.toString())
    }

    private fun handleAuthOk(json: JSONObject) {
        sessionId = json.optString("sessionId")
        setState(ConnectionState.ONLINE)
        reconnectAttempt = 0
        drainPendingMessages()
    }

    private fun handleAuthFail(json: JSONObject) {
        val errorCode = json.optString("errorCode")
        val errorMessage = json.optString("errorMessage")
        setState(ConnectionState.DISCONNECTED)
        scheduleReconnect()
    }

    private fun handlePeerOnline(json: JSONObject) {
        val peerId = IdentityId(json.optString("identityId"))
        peerOnlineHandlers.forEach { it(peerId) }
    }

    private fun handlePeerOffline(json: JSONObject) {
        val peerId = IdentityId(json.optString("identityId"))
        peerOfflineHandlers.forEach { it(peerId) }
    }

    private fun handlePing(json: JSONObject) {
        val originalId = json.optString("messageId")
        val pong = JSONObject().apply {
            put("type", "PONG")
            put("messageId", UUID.randomUUID().toString())
            put("timestamp", System.currentTimeMillis())
            put("originalMessageId", originalId)
        }
        webSocket?.send(pong.toString())
    }

    private fun handleRelayError(json: JSONObject) {
    }

    private fun handleStored(text: String) {
        val receipt = JsonRelayControlCodec().decodeStored(text.toByteArray()).getOrNull() ?: return
        storedHandlers.forEach { it(receipt) }
    }

    private fun handleRelayExpired(text: String) {
        val notice = JsonRelayControlCodec().decodeRelayExpired(text.toByteArray()).getOrNull() ?: return
        relayExpiredHandlers.forEach { it(notice) }
    }

    private fun drainPendingMessages() {
        while (true) {
            val pending = pendingMessages.poll() ?: break
            webSocket?.send(String(pending.first))
        }
    }

    private fun computeAuthSignature(nonce: String, timestamp: Long): String {
        val nonceBytes = java.util.Base64.getUrlDecoder().decode(nonce)
        val tsBytes = ByteBuffer.allocate(8).putLong(timestamp).array()
        val relayIdBytes = relayIdentityId.value.toByteArray(Charsets.US_ASCII)
        val identityBytes = localIdentity.value.toByteArray(Charsets.US_ASCII)

        val transcript = ByteArrayOutputStream().apply {
            write(nonceBytes)
            write(tsBytes)
            write(relayIdBytes)
            write(identityBytes)
        }.toByteArray()

        val sig = ed25519.sign(localPrivateKey, transcript)
        return java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(sig.bytes)
    }

    private fun publicKeyBase64(): String {
        return java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(localPublicKey)
    }

    private inner class RelayWebSocketListener : WebSocketListener() {
        override fun onOpen(ws: WebSocket, response: Response) {
            onWebSocketOpen()
        }

        override fun onMessage(ws: WebSocket, text: String) {
            onWebSocketMessage(text)
        }

        override fun onClosing(ws: WebSocket, code: Int, reason: String) {
            onWebSocketClosed(code == 1000)
        }

        override fun onClosed(ws: WebSocket, code: Int, reason: String) {
            onWebSocketClosed(code == 1000)
        }

        override fun onFailure(ws: WebSocket, t: Throwable, response: Response?) {
            onWebSocketFailure()
        }
    }
}
