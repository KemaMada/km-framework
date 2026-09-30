package com.example.keymessage.network.dht

import android.util.Base64
import kotlinx.coroutines.*
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.URL
import java.security.MessageDigest
import kotlin.math.min

class DhtNode(
    private val publicKey: String,
    private val bootstrapUrl: String = "http://13.140.155.230:8080",
    private val k: Int = 8
) {
    val nodeId: String = hashNodeId(publicKey)
    private val kbuckets = KBuckets(k)
    private val localStore = mutableMapOf<String, String>()
    private var serverSocket: DatagramSocket? = null
    private var serverPort = 0
    private var publicIp = ""
    private var publicPort = 0
    private var scope: CoroutineScope? = null
    private var isRunning = false

    var onMessageReceived: ((fromNodeId: String, text: String) -> Unit)? = null
    var onPeerFound: ((nodeId: String) -> Unit)? = null

    companion object {
        fun hashNodeId(key: String): String {
            val digest = MessageDigest.getInstance("SHA-256")
            val hash = digest.digest(key.toByteArray())
            return hash.take(20).joinToString("") { "%02x".format(it) }
        }
    }

    fun start(scope: CoroutineScope) {
        this.scope = scope
        if (isRunning) return
        isRunning = true

        scope.launch(Dispatchers.IO) {
            try {
                serverSocket = DatagramSocket(0)
                serverPort = serverSocket!!.localPort
                // Discover public address via STUN-like bootstrap request
                discoverPublicAddress()
                // Announce to bootstrap
                announce()
                // Start listener
                listen()
                // Periodic announce
                while (isActive) {
                    delay(60_000)
                    announce()
                }
            } catch (e: Exception) {
                isRunning = false
            }
        }

        scope.launch(Dispatchers.IO) {
            // Periodic bucket refresh
            while (isActive) {
                delay(30_000)
                refresh()
            }
        }
    }

    fun stop() {
        isRunning = false
        serverSocket?.close()
        serverSocket = null
    }

    private fun discoverPublicAddress() {
        try {
            val conn = URL("$bootstrapUrl/api/dht/stun").openConnection() as HttpURLConnection
            conn.connectTimeout = 3000
            val resp = conn.inputStream.bufferedReader().readText()
            conn.disconnect()
            val obj = JSONObject(resp)
            val detectedIp = obj.optString("ip", "")
            val detectedPort = obj.optInt("port", serverPort)
            if (detectedIp.isNotEmpty()) {
                publicIp = detectedIp
                publicPort = detectedPort
            }
        } catch (_: Exception) {
            publicIp = ""
            publicPort = serverPort
        }
    }

    private fun announce() {
        try {
            val json = JSONObject().apply {
                put("nodeId", nodeId)
                put("publicKey", publicKey)
                put("ip", publicIp)
                put("port", publicPort)
            }
            val conn = URL("$bootstrapUrl/api/dht/announce").openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.setRequestProperty("Content-Type", "application/json")
            conn.doOutput = true
            conn.outputStream.write(json.toString().toByteArray())
            val resp = conn.inputStream.bufferedReader().readText()
            conn.disconnect()
            // Bootstrap returns list of known peers
            val peers = JSONObject(resp).optJSONArray("peers")
            if (peers != null) {
                for (i in 0 until peers.length()) {
                    val peer = peers.getJSONObject(i)
                    val pid = peer.optString("nodeId", "")
                    if (pid != nodeId) {
                        kbuckets.add(pid, peer.optString("ip", ""), peer.optInt("port", 0))
                        onPeerFound?.invoke(pid)
                    }
                }
            }
        } catch (_: Exception) {}
    }

    private fun listen() {
        val sock = serverSocket ?: return
        val buf = ByteArray(65535)
        while (isRunning) {
            try {
                val packet = DatagramPacket(buf, buf.size)
                sock.receive(packet)
                val json = String(packet.data, packet.offset, packet.length).trim()
                handleMessage(json, packet.address.hostAddress, packet.port)
            } catch (_: Exception) { break }
        }
    }

    private fun handleMessage(json: String, fromIp: String, fromPort: Int) {
        val msg = DhtMessage.fromJson(json) ?: return
        // Update k-bucket for sender
        if (fromIp.isNotEmpty() && fromPort > 0) {
            kbuckets.add(msg.nodeId, fromIp, fromPort)
        }
        when (msg) {
            is DhtMessage.Ping -> sendUdp(DhtMessage.Pong(nodeId), fromIp, fromPort)
            is DhtMessage.Pong -> {} // just updated bucket above
            is DhtMessage.FindNode -> {
                val closest = kbuckets.findClosest(msg.targetNodeId, k)
                sendUdp(DhtMessage.Nodes(nodeId, closest.map { it.nodeId }), fromIp, fromPort)
            }
            is DhtMessage.Store -> {
                localStore[msg.key] = msg.value
                sendUdp(DhtMessage.Stored(nodeId, msg.key), fromIp, fromPort)
            }
            is DhtMessage.FindValue -> {
                val value = localStore[msg.key]
                if (value != null) {
                    sendUdp(DhtMessage.Value(nodeId, msg.key, value), fromIp, fromPort)
                } else {
                    val closest = kbuckets.findClosest(msg.key, k)
                    sendUdp(DhtMessage.Nodes(nodeId, closest.map { it.nodeId }), fromIp, fromPort)
                }
            }
            is DhtMessage.Value -> {
                // Received a stored message addressed to us
                val text = try {
                    String(Base64.decode(msg.value, Base64.NO_WRAP))
                } catch (_: Exception) { msg.value }
                if (msg.key == nodeId) {
                    onMessageReceived?.invoke(msg.nodeId, text)
                }
            }
            is DhtMessage.Nodes -> {
                // Add returned nodes to routing table
                for (nid in msg.nodes) {
                    if (nid != nodeId && !kbuckets.contains(nid)) {
                        // Try to ping to get their address
                        kbuckets.add(nid, "", 0)
                    }
                }
            }
            is DhtMessage.Stored -> {} // value stored successfully
        }
    }

    fun sendUdp(msg: DhtMessage, ip: String, port: Int) {
        try {
            val data = msg.toJson().toString().toByteArray()
            val packet = DatagramPacket(data, data.size, InetAddress.getByName(ip), port)
            serverSocket?.send(packet)
        } catch (_: Exception) {}
    }

    fun lookupPeer(targetNodeId: String) {
        scope?.launch(Dispatchers.IO) {
            // First try bootstrap
            try {
                val conn = URL("$bootstrapUrl/api/dht/lookup/${targetNodeId}").openConnection() as HttpURLConnection
                val resp = conn.inputStream.bufferedReader().readText()
                conn.disconnect()
                val obj = JSONObject(resp)
                val ip = obj.optString("ip", "")
                val port = obj.optInt("port", 0)
                if (ip.isNotEmpty() && port > 0) {
                    kbuckets.add(targetNodeId, ip, port)
                    return@launch
                }
            } catch (_: Exception) {}

            // Fallback: DHT lookup
            val closest = kbuckets.findClosest(targetNodeId, k)
            for (peer in closest) {
                if (peer.ip.isNotEmpty()) {
                    sendUdp(DhtMessage.FindNode(nodeId, targetNodeId), peer.ip, peer.port)
                }
            }
        }
    }

    fun sendDirect(targetNodeId: String, text: String) {
        scope?.launch(Dispatchers.IO) {
            val peer = kbuckets.get(targetNodeId)
            if (peer != null && peer.ip.isNotEmpty()) {
                val msg = DhtMessage.Store(
                    nodeId = nodeId,
                    key = targetNodeId,
                    value = Base64.encodeToString(text.toByteArray(), Base64.NO_WRAP)
                )
                sendUdp(msg, peer.ip, peer.port)
            } else {
                // Store in DHT (store on closest peers + bootstrap)
                storeInDht(targetNodeId, text)
            }
        }
    }

    private fun storeInDht(targetNodeId: String, text: String) {
        val encoded = Base64.encodeToString(text.toByteArray(), Base64.NO_WRAP)
        // Store on bootstrap as backup
        scope?.launch(Dispatchers.IO) {
            try {
                val json = JSONObject().apply {
                    put("fromNodeId", nodeId)
                    put("toNodeId", targetNodeId)
                    put("ciphertext", encoded)
                }
                val conn = URL("$bootstrapUrl/api/dht/store").openConnection() as HttpURLConnection
                conn.requestMethod = "POST"
                conn.setRequestProperty("Content-Type", "application/json")
                conn.doOutput = true
                conn.outputStream.write(json.toString().toByteArray())
                conn.disconnect()
            } catch (_: Exception) {}
        }
        // Also try to store on closest known peers
        val closest = kbuckets.findClosest(targetNodeId, k)
        for (peer in closest) {
            if (peer.ip.isNotEmpty()) {
                sendUdp(DhtMessage.Store(nodeId, targetNodeId, encoded), peer.ip, peer.port)
            }
        }
    }

    fun fetchMessages() {
        scope?.launch(Dispatchers.IO) {
            // Fetch from bootstrap
            try {
                val conn = URL("$bootstrapUrl/api/dht/fetch?nodeId=$nodeId").openConnection() as HttpURLConnection
                val resp = conn.inputStream.bufferedReader().readText()
                conn.disconnect()
                val arr = JSONObject(resp).optJSONArray("messages")
                if (arr != null) {
                    for (i in 0 until arr.length()) {
                        val obj = arr.getJSONObject(i)
                        val fromNodeId = obj.optString("fromNodeId", "")
                        val encoded = obj.optString("ciphertext", "")
                        val text = try {
                            String(Base64.decode(encoded, Base64.NO_WRAP))
                        } catch (_: Exception) { encoded }
                        onMessageReceived?.invoke(fromNodeId, text)
                    }
                }
            } catch (_: Exception) {}
            // Also query DHT for our own key
            val closest = kbuckets.findClosest(nodeId, k)
            for (peer in closest) {
                if (peer.ip.isNotEmpty()) {
                    sendUdp(DhtMessage.FindValue(nodeId, nodeId), peer.ip, peer.port)
                }
            }
        }
    }

    private fun refresh() {
        // Refresh a random bucket to maintain routing table
        val all = kbuckets.allEntries()
        for (peer in all) {
            if (peer.ip.isNotEmpty()) {
                sendUdp(DhtMessage.Ping(nodeId), peer.ip, peer.port)
            }
        }
    }

    data class BucketEntry(val nodeId: String, val ip: String, val port: Int, var lastSeen: Long = System.currentTimeMillis())
}

class KBuckets(private val k: Int) {
    private val buckets = Array<MutableList<DhtNode.BucketEntry>>(160) { mutableListOf() }

    fun add(nodeId: String, ip: String, port: Int) {
        val index = bucketIndex(nodeId)
        val bucket = buckets[index]
        val existing = bucket.indexOfFirst { it.nodeId == nodeId }
        if (existing >= 0) {
            bucket[existing] = bucket[existing].copy(ip = ip, port = port, lastSeen = System.currentTimeMillis())
        } else if (bucket.size < k) {
            bucket.add(DhtNode.BucketEntry(nodeId, ip, port))
        } else {
            // Replace least recently seen
            val oldest = bucket.minByOrNull { it.lastSeen } ?: return
            bucket.remove(oldest)
            bucket.add(DhtNode.BucketEntry(nodeId, ip, port))
        }
    }

    fun get(nodeId: String): DhtNode.BucketEntry? {
        val index = bucketIndex(nodeId)
        return buckets[index].find { it.nodeId == nodeId }
    }

    fun contains(nodeId: String): Boolean {
        val index = bucketIndex(nodeId)
        return buckets[index].any { it.nodeId == nodeId }
    }

    fun findClosest(targetNodeId: String, count: Int): List<DhtNode.BucketEntry> {
        val all = allEntries().sortedBy { xorDistance(targetNodeId, it.nodeId) }
        return all.take(count)
    }

    fun allEntries(): List<DhtNode.BucketEntry> = buckets.flatMap { it.toList() }

    private fun bucketIndex(nodeId: String): Int {
        val distance = xorDistance(nodeId, nodeId) // distance to self is 0
        return min(159, distance.countTrailingZeroBits())
    }

    private fun xorDistance(a: String, b: String): Long {
        // Simple XOR of first 8 bytes
        val aBytes = a.take(16).padEnd(16, '0')
        val bBytes = b.take(16).padEnd(16, '0')
        var result = 0L
        for (i in 0 until min(16, min(aBytes.length, bBytes.length))) {
            val xor = aBytes[i].digitToIntOrNull(16)?.xor(bBytes[i].digitToIntOrNull(16) ?: 0) ?: 0
            result = result shl 4 or xor.toLong()
        }
        return result
    }

    private fun Long.countTrailingZeroBits(): Int {
        if (this == 0L) return 64
        var n = this
        var count = 0
        while (n and 1L == 0L) { count++; n = n shr 1 }
        return count
    }
}
