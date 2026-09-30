package com.example.keymessage.network.dht

import kotlinx.coroutines.*
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap

class PeerConnection(
    private val myNodeId: String
) {
    private var serverSocket: ServerSocket? = null
    private var serverPort = 0
    private var scope: CoroutineScope? = null
    private val connections = ConcurrentHashMap<String, TcpPeer>()
    private var isRunning = false

    var onMessageReceived: ((fromNodeId: String, text: String) -> Unit)? = null

    data class TcpPeer(
        val nodeId: String,
        val socket: Socket,
        val reader: BufferedReader,
        val writer: OutputStreamWriter
    )

    fun start(scope: CoroutineScope): Int {
        this.scope = scope
        if (isRunning) return serverPort
        isRunning = true
        try {
            serverSocket = ServerSocket(0)
            serverPort = serverSocket!!.localPort
            scope.launch(Dispatchers.IO) {
                acceptConnections()
            }
        } catch (e: Exception) {
            isRunning = false
        }
        return serverPort
    }

    fun stop() {
        isRunning = false
        for ((_, peer) in connections) {
            try { peer.socket.close() } catch (_: Exception) {}
        }
        connections.clear()
        serverSocket?.close()
        serverSocket = null
    }

    private fun acceptConnections() {
        val ss = serverSocket ?: return
        while (isRunning) {
            try {
                val socket = ss.accept()
                scope?.launch(Dispatchers.IO) {
                    handleIncoming(socket)
                }
            } catch (_: Exception) { break }
        }
    }

    private fun handleIncoming(socket: Socket) {
        try {
            val reader = BufferedReader(InputStreamReader(socket.getInputStream()))
            val writer = OutputStreamWriter(socket.getOutputStream())
            // First message should be node ID handshake
            val handshake = reader.readLine() ?: return
            val parts = handshake.split("|")
            if (parts.size < 2 || parts[0] != "HELLO") {
                socket.close()
                return
            }
            val remoteNodeId = parts[1]
            writer.write("HELLO_OK|$myNodeId\n")
            writer.flush()

            connections[remoteNodeId] = TcpPeer(remoteNodeId, socket, reader, writer)

            // Read messages
            while (isRunning) {
                val line = reader.readLine() ?: break
                if (line.startsWith("MSG|")) {
                    val content = line.removePrefix("MSG|")
                    onMessageReceived?.invoke(remoteNodeId, content)
                }
            }
        } catch (_: Exception) {}
        finally {
            try { socket.close() } catch (_: Exception) {}
        }
    }

    suspend fun connect(ip: String, port: Int, remoteNodeId: String): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                val socket = Socket()
                socket.connect(InetSocketAddress(ip, port), 5000)
                val reader = BufferedReader(InputStreamReader(socket.getInputStream()))
                val writer = OutputStreamWriter(socket.getOutputStream())
                writer.write("HELLO|$myNodeId\n")
                writer.flush()
                val response = reader.readLine() ?: return@withContext false
                if (!response.startsWith("HELLO_OK|")) {
                    socket.close(); return@withContext false
                }
                connections[remoteNodeId] = TcpPeer(remoteNodeId, socket, reader, writer)
                true
            } catch (e: Exception) { false }
        }
    }

    fun send(remoteNodeId: String, text: String): Boolean {
        val peer = connections[remoteNodeId] ?: return false
        return try {
            peer.writer.write("MSG|$text\n")
            peer.writer.flush()
            true
        } catch (e: Exception) {
            connections.remove(remoteNodeId)
            false
        }
    }

    fun isConnected(remoteNodeId: String): Boolean {
        val peer = connections[remoteNodeId] ?: return false
        return try {
            !peer.socket.isClosed && peer.socket.isConnected
        } catch (_: Exception) { false }
    }
}
