package com.example.keymessage.network

import com.keymessage.core.crypto.Ed25519Impl
import com.keymessage.core.model.IdentityId
import com.keymessage.core.protocol.ConnectionState
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

class RelayClientIdempotencyTest {

    private lateinit var server: MockWebServer
    private lateinit var client: RelayClient

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        val ed25519 = Ed25519Impl()
        val keyPair = ed25519.generateKeyPair()
        client = RelayClient(
            url = server.url("/ws").toString(),
            localIdentity = IdentityId("a1b2c3d4e5f6a7b8c9d0e1f2a3b4c5d6e7f8a9b0c"),
            localPrivateKey = keyPair.privateKey,
            localPublicKey = keyPair.publicKey,
            relayIdentityId = IdentityId("f9e8d7c6b5a4f3e2d1c0b9a8f7e6d5c4b3a2f1e0"),
            ed25519 = ed25519,
            maxReconnectDelayMs = 500
        )
    }

    @After
    fun tearDown() {
        client.disconnect()
        server.shutdown()
    }

    private fun awaitConnections(expected: Int, timeoutMs: Long = 5000) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (server.requestCount < expected) {
            if (System.currentTimeMillis() > deadline) {
                fail("Timed out waiting for $expected connection(s), got ${server.requestCount}")
            }
            Thread.sleep(20)
        }
        Thread.sleep(100)
    }

    @Test
    fun `start once opens a single connection`() {
        client.connect().getOrThrow()
        awaitConnections(1)
        assertNotEquals(ConnectionState.DISCONNECTED, client.connectionState())
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `start twice keeps a single connection`() {
        client.connect().getOrThrow()
        client.connect().getOrThrow()
        awaitConnections(1)
        assertEquals(1, server.requestCount)
        assertNotEquals(ConnectionState.DISCONNECTED, client.connectionState())
    }

    @Test
    fun `stop twice is a no-op and ends disconnected`() {
        client.connect().getOrThrow()
        awaitConnections(1)
        client.disconnect()
        client.disconnect()
        assertEquals(ConnectionState.DISCONNECTED, client.connectionState())
    }

    @Test
    fun `start stop start reconnects`() {
        client.connect().getOrThrow()
        awaitConnections(1)
        client.disconnect()
        assertEquals(ConnectionState.DISCONNECTED, client.connectionState())

        client.connect().getOrThrow()
        awaitConnections(2)
        assertEquals(2, server.requestCount)
        assertNotEquals(ConnectionState.DISCONNECTED, client.connectionState())
    }

    @Test
    fun `start stop stop start reconnects`() {
        client.connect().getOrThrow()
        awaitConnections(1)
        client.disconnect()
        client.disconnect()
        assertEquals(ConnectionState.DISCONNECTED, client.connectionState())

        client.connect().getOrThrow()
        awaitConnections(2)
        assertEquals(2, server.requestCount)
        assertNotEquals(ConnectionState.DISCONNECTED, client.connectionState())
    }

    @Test
    fun `concurrent connect calls open a single socket`() {
        val threads = (1..10).map {
            Thread {
                client.connect()
            }
        }
        threads.forEach { it.start() }
        threads.forEach { it.join(5000) }

        awaitConnections(1)
        assertEquals(1, server.requestCount)
    }
}
