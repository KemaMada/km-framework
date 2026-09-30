package com.keymessage.core.model

import com.keymessage.core.crypto.Ed25519Impl
import com.keymessage.core.kmid.KmIds
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import kotlin.test.assertContentEquals

class NodeModelTest {

    private val ed25519 = Ed25519Impl()

    @Test
    fun `fromKeyPair deriva nodeId correctamente`() {
        val keyPair = ed25519.generateKeyPair()
        val identity = NodeIdentity.fromKeyPair(keyPair.publicKey)
        val expectedHex = KmIds.identityId(keyPair.publicKey).toHexString()

        assertEquals(expectedHex, identity.nodeId.value)
        assertEquals(keyPair.publicKey.contentHashCode(), identity.publicKey.contentHashCode())
        assertContentEquals(keyPair.publicKey, identity.publicKey)
        assertNull(identity.nodeName)
    }

    @Test
    fun `fromKeyPair con nodeName`() {
        val keyPair = ed25519.generateKeyPair()
        val identity = NodeIdentity.fromKeyPair(keyPair.publicKey, nodeName = "test-node")
        assertEquals("test-node", identity.nodeName)
        assertEquals(KmIds.identityId(keyPair.publicKey).toHexString(), identity.nodeId.value)
    }

    @Test
    fun `fromKeyPair con clave de 33 bytes falla`() {
        assertThrows(IllegalArgumentException::class.java) {
            NodeIdentity.fromKeyPair(ByteArray(33))
        }
    }

    @Test
    fun `NodeIdentity con identityId arbitrario no coincide con derivacion`() {
        // Construir directamente con un nodeId que NO coincide con la derivacion
        val pub = ed25519.generateKeyPair().publicKey
        val wrongId = IdentityId("a1b2c3d4e5f6a7b8c9d0e1f2a3b4c5d6e7f8a9b0c1d2e3f4a5b6c7d8e9f0a1b2")
        val node = NodeIdentity(wrongId, pub)

        val expected = KmIds.identityId(pub).toHexString()
        assertNotEquals(expected, node.nodeId.value)
    }

    @Test
    fun `relay capabilities flag computed properties`() {
        val pub = ed25519.generateKeyPair().publicKey
        val alice = NodeIdentity.fromKeyPair(pub)
        val full = RelayInfo(
            identity = alice,
            endpoint = NodeEndpoint("websocket", "wss://203.0.113.10:443/ws"),
            capabilities = setOf(NodeCapability.RELAY, NodeCapability.SIGNALING, NodeCapability.STORE_AND_FORWARD),
            limits = RelayLimits()
        )
        assertTrue(full.canRelay)
        assertTrue(full.canStoreAndForward)

        val relayOnly = full.copy(capabilities = setOf(NodeCapability.RELAY))
        assertTrue(relayOnly.canRelay)
        assertFalse(relayOnly.canStoreAndForward)
    }

    @Test
    fun `relay limits have published defaults`() {
        val limits = RelayLimits()
        assertEquals(65536L, limits.maxMessageSize)
        assertEquals(10000, limits.maxStoredMessages)
        assertEquals(1073741824L, limits.maxStorageBytes)
        assertEquals(604800000L, limits.maxMessageAgeMs)
        assertEquals(512, limits.maxConnections)
    }

    @Test
    fun `node identity value equality ignores array identity`() {
        val kp = ed25519.generateKeyPair()
        val a = NodeIdentity.fromKeyPair(kp.publicKey)
        val b = NodeIdentity.fromKeyPair(kp.publicKey, nodeName = "dup")
        // Same pubKey but different nodeName — should NOT be equal
        assertNotEquals(a, b)
        // Same pubKey and same nodeName
        val c = NodeIdentity.fromKeyPair(kp.publicKey)
        assertEquals(a, c)
        assertEquals(a.hashCode(), c.hashCode())
    }

    @Test
    fun `comparing identities ordering uses value`() {
        val low = IdentityId("a1")
        val high = IdentityId("a2")
        assertTrue(low < high)
    }

    @Test
    fun `identityIdHexToBytes convierte hex a bytes`() {
        val hex = "abcd"
        val expected = byteArrayOf(0xAB.toByte(), 0xCD.toByte())
        assertContentEquals(expected, identityIdHexToBytes(hex))
    }

    @Test
    fun `identityIdHexToBytes con longitud impar falla`() {
        assertThrows(IllegalArgumentException::class.java) {
            identityIdHexToBytes("abc")
        }
    }

    @Test
    fun `NodeAnnouncement verifyWireBytes ok con firma valida`() {
        val kp = ed25519.generateKeyPair()
        val identity = NodeIdentity.fromKeyPair(kp.publicKey, nodeName = "node-test")

        // Firmar como lo haria el codec: JSON sobre el payload
        val signable = """{"messageId":"${java.util.UUID.randomUUID()}","timestamp":9999999999000,"nodeId":"${identity.nodeId.value}","publicKey":"test"}""".toByteArray()
        val signature = ed25519.sign(kp.privateKey, signable)

        val ann = NodeAnnouncement(
            messageId = MessageId(java.util.UUID.randomUUID()),
            timestamp = 9999999999000L,
            protocolVersion = "2.0",
            identity = identity,
            endpoints = emptyList(),
            capabilities = setOf(NodeCapability.CLIENT),
            signature = signature.bytes,
        )

        // Verificar con now=9999999999000 (mismo timestamp)
        try {
            ann.verifyWireBytes(signable, now = 9999999999000L).getOrThrow()
        } catch (e: Exception) {
            fail("debe verificar con firma valida: ${e.message}")
        }
    }

    @Test
    fun `NodeAnnouncement rejecta identityId incorrecto`() {
        val kp = ed25519.generateKeyPair()
        val wrongId = IdentityId("ffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff")
        val identity = NodeIdentity(wrongId, kp.publicKey, nodeName = "fake")

        val signable = "{}".toByteArray()
        val signature = ed25519.sign(kp.privateKey, signable)

        val ann = NodeAnnouncement(
            messageId = MessageId(java.util.UUID.randomUUID()),
            timestamp = 9999999999000L,
            protocolVersion = "2.0",
            identity = identity,
            endpoints = emptyList(),
            capabilities = setOf(NodeCapability.CLIENT),
            signature = signature.bytes,
        )
        val e = assertThrows(NodeAnnouncementVerificationException::class.java) {
            ann.verifyWireBytes(signable, now = 9999999999000L).getOrThrow()
        }
        assertEquals("IDENTITY_ID_MISMATCH", e.code)
    }

    @Test
    fun `NodeAnnouncement rejecta firma invalida`() {
        val kp = ed25519.generateKeyPair()
        val identity = NodeIdentity.fromKeyPair(kp.publicKey)

        val signable = "{}".toByteArray()
        val ann = NodeAnnouncement(
            messageId = MessageId(java.util.UUID.randomUUID()),
            timestamp = 9999999999000L,
            protocolVersion = "2.0",
            identity = identity,
            endpoints = emptyList(),
            capabilities = setOf(NodeCapability.CLIENT),
            signature = ByteArray(64) { 0xAA.toByte() }, // firma basura
        )
        val e = assertThrows(NodeAnnouncementVerificationException::class.java) {
            ann.verifyWireBytes(signable, now = 9999999999000L).getOrThrow()
        }
        assertEquals("INVALID_SIGNATURE", e.code)
    }

    @Test
    fun `NodeAnnouncement rejecta timestamp futuro`() {
        val kp = ed25519.generateKeyPair()
        val identity = NodeIdentity.fromKeyPair(kp.publicKey)

        val signable = "{}".toByteArray()
        val signature = ed25519.sign(kp.privateKey, signable)

        val ann = NodeAnnouncement(
            messageId = MessageId(java.util.UUID.randomUUID()),
            timestamp = 9999999999999L,
            protocolVersion = "2.0",
            identity = identity,
            endpoints = emptyList(),
            capabilities = setOf(NodeCapability.CLIENT),
            signature = signature.bytes,
        )
        val e = assertThrows(NodeAnnouncementVerificationException::class.java) {
            ann.verifyWireBytes(signable, now = 9999999999000L).getOrThrow() // now < timestamp
        }
        assertEquals("TIMESTAMP_IN_FUTURE", e.code)
    }

    @Test
    fun `NodeAnnouncement rejecta timestamp demasiado viejo`() {
        val kp = ed25519.generateKeyPair()
        val identity = NodeIdentity.fromKeyPair(kp.publicKey)

        val signable = "{}".toByteArray()
        val signature = ed25519.sign(kp.privateKey, signable)

        val ann = NodeAnnouncement(
            messageId = MessageId(java.util.UUID.randomUUID()),
            timestamp = 9999999999000L,
            protocolVersion = "2.0",
            identity = identity,
            endpoints = emptyList(),
            capabilities = setOf(NodeCapability.CLIENT),
            signature = signature.bytes,
        )
        val e = assertThrows(NodeAnnouncementVerificationException::class.java) {
            ann.verifyWireBytes(signable, now = 9999999999000L + 400_000L, maxAgeMs = 300_000L).getOrThrow()
        }
        assertEquals("TIMESTAMP_TOO_OLD", e.code)
    }

    @Test
    fun `announcement requires path store only when capability present`() {
        val pub = ed25519.generateKeyPair().publicKey
        val alice = NodeIdentity.fromKeyPair(pub)
        val store = NodeAnnouncement(
            messageId = MessageId(java.util.UUID.randomUUID()),
            timestamp = 1721827200000L,
            protocolVersion = "2.0",
            identity = alice,
            endpoints = listOf(NodeEndpoint("websocket", "wss://203.0.113.10:443/ws")),
            capabilities = setOf(NodeCapability.RELAY, NodeCapability.STORE_AND_FORWARD),
            limits = RelayLimits()
        )
        assertTrue(NodeCapability.STORE_AND_FORWARD in store.capabilities)
        assertNotNull(store.limits)
    }
}