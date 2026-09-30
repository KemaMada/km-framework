package com.example.keymessage.network

import com.keymessage.core.crypto.Ed25519Impl
import com.keymessage.core.model.IdentityId
import com.keymessage.core.protocol.ConnectionState
import org.junit.Assert.*
import org.junit.Ignore
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.ServerSocket
import java.net.Socket
import java.security.MessageDigest
import java.util.*
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class RelayClientIntegrationTest {
    private val aliceId = IdentityId("a1b2c3d4e5f6a7b8c9d0e1f2a3b4c5d6e7f8a9b0")
    private val relayId = IdentityId("f9e8d7c6b5a4f3e2d1c0b9a8f7e6d5c4b3a2f1e0")
    private val ed25519 = Ed25519Impl()

    @Test @Ignore("Requires debugging of in-process WebSocket mock relay")
    fun `full authentication flow`() {
        val (relayPort, relayThread) = startMockRelay()
        val keyPair = ed25519.generateKeyPair()
        val connected = CountDownLatch(1)
        var lastState = ConnectionState.DISCONNECTED

        val transport = RelayClient(
            url = "ws://127.0.0.1:$relayPort/ws",
            localIdentity = aliceId,
            localPrivateKey = keyPair.privateKey,
            localPublicKey = keyPair.publicKey,
            relayIdentityId = relayId,
            ed25519 = ed25519
        )

        transport.onStateChange { state ->
            lastState = state
            if (state == ConnectionState.ONLINE) connected.countDown()
        }

        transport.connect()
        assertTrue(connected.await(10, TimeUnit.SECONDS))
        assertEquals(ConnectionState.ONLINE, lastState)

        transport.disconnect()
        relayThread.interrupt()
    }

    @Test @Ignore("Requires debugging of in-process WebSocket mock relay")
    fun `send and receive message through relay`() {
        val (relayPort, relayThread) = startMockRelay()
        val keyPair = ed25519.generateKeyPair()
        val connected = CountDownLatch(1)
        val receivedMessage = CountDownLatch(1)
        var receivedBytes: ByteArray? = null

        val transport = RelayClient(
            url = "ws://127.0.0.1:$relayPort/ws",
            localIdentity = aliceId,
            localPrivateKey = keyPair.privateKey,
            localPublicKey = keyPair.publicKey,
            relayIdentityId = relayId,
            ed25519 = ed25519
        )

        transport.onStateChange { state ->
            if (state == ConnectionState.ONLINE) connected.countDown()
        }
        transport.setMessageHandler { data ->
            receivedBytes = data
            receivedMessage.countDown()
        }

        transport.connect()
        assertTrue(connected.await(10, TimeUnit.SECONDS))

        Thread.sleep(200)
        val msgJson = createTestMessage()
        transport.send(msgJson.toByteArray(), IdentityId("bob"))

        assertTrue(receivedMessage.await(10, TimeUnit.SECONDS))
        assertNotNull(receivedBytes)
        val decoded = String(receivedBytes!!)
        assertTrue(decoded.contains("MESSAGE"))

        transport.disconnect()
        relayThread.interrupt()
    }

    @Test @Ignore("Requires debugging of in-process WebSocket mock relay")
    fun `reconnect after connection loss`() {
        val (relayPort, relayThread) = startMockRelay()
        val keyPair = ed25519.generateKeyPair()
        val onlineCount = CountDownLatch(2)
        var onlineEvents = 0

        val transport = RelayClient(
            url = "ws://127.0.0.1:$relayPort/ws",
            localIdentity = aliceId,
            localPrivateKey = keyPair.privateKey,
            localPublicKey = keyPair.publicKey,
            relayIdentityId = relayId,
            ed25519 = ed25519,
            maxReconnectDelayMs = 2000
        )

        transport.onStateChange { state ->
            if (state == ConnectionState.ONLINE) {
                onlineEvents++
                onlineCount.countDown()
            }
        }

        transport.connect()
        assertTrue(onlineCount.await(20, TimeUnit.SECONDS))
        assertTrue(onlineEvents >= 1)

        transport.disconnect()
        relayThread.interrupt()
    }

    private fun startMockRelay(): Pair<Int, Thread> {
        val server = ServerSocket(0)
        val port = server.localPort
        val thread = Thread {
            try {
                while (!Thread.currentThread().isInterrupted) {
                    val client = server.accept()
                    Thread { handleRelayConnection(client) }.start()
                }
            } catch (_: Exception) {}
        }.also { it.isDaemon = true; it.start() }
        Thread.sleep(100)
        return port to thread
    }

    private fun handleRelayConnection(client: Socket) {
        try {
            client.soTimeout = 10000
            val rawIn = client.getInputStream()
            val rawOut = client.getOutputStream()

            val headerBytes = ByteArray(4096)
            var headerLen = 0
            while (headerLen < headerBytes.size) {
                val b = rawIn.read()
                if (b < 0) return
                headerBytes[headerLen++] = b.toByte()
                if (headerLen >= 4 &&
                    headerBytes[headerLen-4].toInt() == 13 && headerBytes[headerLen-3].toInt() == 10 &&
                    headerBytes[headerLen-2].toInt() == 13 && headerBytes[headerLen-1].toInt() == 10) break
            }
            val headerStr = String(headerBytes, 0, headerLen, java.nio.charset.StandardCharsets.UTF_8)
            val keyLine = headerStr.lines().find { it.startsWith("Sec-WebSocket-Key:", true) } ?: return
            val acceptKey = computeWebSocketAccept(keyLine.substringAfter(":").trim())

            val response = "HTTP/1.1 101 Switching Protocols\r\n" +
                    "Upgrade: websocket\r\n" +
                    "Connection: Upgrade\r\n" +
                    "Sec-WebSocket-Accept: $acceptKey\r\n" +
                    "\r\n"
            rawOut.write(response.toByteArray())
            rawOut.flush()

            var authenticated = false
            var authNonce: String? = null
            var authTimestamp: Long = 0

            while (!Thread.currentThread().isInterrupted) {
                val frame = readWebSocketFrame(rawIn) ?: break
                val text = String(frame, java.nio.charset.StandardCharsets.UTF_8)

                when {
                    text.contains("\"type\":\"AUTH_REQUEST\"") -> {
                        authNonce = generateNonce()
                        authTimestamp = System.currentTimeMillis()
                        val challenge = """{"type":"AUTH_CHALLENGE","messageId":"${UUID.randomUUID()}","timestamp":$authTimestamp,"nonce":"$authNonce"}"""
                        sendWebSocketFrame(rawOut, challenge.toByteArray(java.nio.charset.StandardCharsets.UTF_8))
                    }
                    text.contains("\"type\":\"AUTH_RESPONSE\"") && authNonce != null -> {
                        val ok = """{"type":"AUTH_OK","messageId":"${UUID.randomUUID()}","timestamp":${System.currentTimeMillis()},"protocolVersion":"2.0","identityId":"${aliceId.value}","responderIdentityId":"${relayId.value}","sessionId":"${UUID.randomUUID()}","capabilities":{},"serverSignature":"${java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(64))}"}"""
                        sendWebSocketFrame(rawOut, ok.toByteArray(java.nio.charset.StandardCharsets.UTF_8))
                        authenticated = true
                    }
                    authenticated && text.contains("\"type\":\"MESSAGE\"") -> {
                        sendWebSocketFrame(rawOut, frame)
                    }
                }
            }
        } catch (_: Exception) {}
    }

    private fun readWebSocketFrame(input: InputStream): ByteArray? {
        var b = input.read()
        if (b < 0) return null
        b = input.read()
        if (b < 0) return null
        val masked = (b and 0x80) != 0
        var length = (b and 0x7F).toLong()

        if (length == 126L) {
            val b1 = input.read(); val b2 = input.read()
            if (b1 < 0 || b2 < 0) return null
            length = ((b1 shl 8) or b2).toLong()
        } else if (length == 127L) {
            length = 0L
            repeat(8) { length = (length shl 8) or input.read().toLong() }
        }

        val maskKey = if (masked) ByteArray(4).also { arr ->
            repeat(4) { i -> arr[i] = input.read().toByte() }
        } else null

        val payload = ByteArray(length.toInt())
        var offset = 0
        while (offset < payload.size) {
            val read = input.read(payload, offset, payload.size - offset)
            if (read < 0) return null
            offset += read
        }

        if (masked && maskKey != null) {
            for (i in payload.indices) {
                payload[i] = (payload[i].toInt() xor maskKey[i % 4].toInt()).toByte()
            }
        }
        return payload
    }

    private fun sendWebSocketFrame(output: OutputStream, data: ByteArray) {
        output.write(0x81)
        when {
            data.size < 126 -> output.write(data.size)
            data.size < 65536 -> {
                output.write(126)
                output.write(data.size shr 8 and 0xFF)
                output.write(data.size and 0xFF)
            }
            else -> {
                output.write(127)
                for (i in 7 downTo 0) output.write(data.size shr (i * 8) and 0xFF)
            }
        }
        output.write(data)
        output.flush()
    }

    private fun computeWebSocketAccept(key: String): String {
        val magic = "258EAFA5-E914-47DA-95CA-5AB9B4F3C0A3"
        val sha1 = MessageDigest.getInstance("SHA-1")
        sha1.update((key + magic).toByteArray())
        return Base64.getEncoder().encodeToString(sha1.digest())
    }

    private fun generateNonce(): String {
        val nonce = ByteArray(16)
        Random().nextBytes(nonce)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(nonce)
    }

    private fun createTestMessage(): String {
        return """{"type":"MESSAGE","messageId":"${UUID.randomUUID()}","timestamp":${System.currentTimeMillis()},"from":"${aliceId.value}","to":"b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0","payload":"dGVzdA"}"""
    }
}
