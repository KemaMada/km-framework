package com.km.crypto

import com.km.model.IdentityId
import com.km.model.NodeIdentity
import com.km.protocol.*
import com.km.storage.InMemoryMessageStore
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import kotlin.test.assertContentEquals

/**
 * Regression tests for Increment 3E:
 *
 * identityId (SHA-256 derived hash, 64 hex chars)
 *   ≠
 * publicKey (Ed25519 key material, 32 bytes)
 *
 * These tests prove the two cannot be confused after the fix.
 */
class IdentityPublicKeyRegressionTest {

    private val ed25519 = Ed25519Impl()
    private val verifier = SignatureVerifierImpl(ed25519)

    // --- helpers -------------------------------------------------------------

    /** Key pair con semilla determinista para tests reproducibles. */
    private fun keyPair(label: String) = ed25519.keyPairFromSeed(
        label.padEnd(32, 'x').toByteArray().copyOf(32)
    )

    private val alice = keyPair("alice")
    private val bob = keyPair("bob")
    private val aliceId = IdentityId("a1b2c3d4e5f6a7b8c9d0e1f2a3b4c5d6e7f8a9b0c1d2e3f4a5b6c7d8e9f0a1b2")
    private val bobId = IdentityId("b0b1b2b3b4b5b6b7b8b9c0c1c2c3c4c5c6c7c8c9d0d1d2d3d4d5d6d7d8d9e0e1e2")

    // ===================================================================
    // SignatureVerifier — SignedMessage
    // ===================================================================

    @Test
    fun `firma valida con publicKey correcta devuelve true`() {
        val data = "hello".toByteArray()
        val sig = ed25519.sign(alice.privateKey, data)
        val msg = SignedMessage(
            identityId = aliceId,
            publicKey = alice.publicKey,
            data = data,
            signature = sig,
        )
        assertTrue(verifier.verifyMessage(msg))
    }

    @Test
    fun `identityId usado como publicKey falla por tamanio incorrecto`() {
        val data = "hello".toByteArray()
        val sig = ed25519.sign(alice.privateKey, data)
        // Esto era el bug: identityId.value.toByteArray() produce 64 bytes ASCII
        val identityIdBytes = aliceId.value.toByteArray()
        assertEquals(64, identityIdBytes.size) // 64 hex chars → 64 ASCII bytes

        val msgConBug = SignedMessage(
            identityId = aliceId,
            publicKey = identityIdBytes, // ← QUEDAR IMPOSIBLE POR DISENO ahora lanza
            data = data,
            signature = sig,
        )
        assertThrows(IllegalArgumentException::class.java) {
            verifier.verifyMessage(msgConBug)
        }
    }

    @Test
    fun `firma invalida devuelve false`() {
        val data = "hello".toByteArray()
        val sig = ed25519.sign(alice.privateKey, data)
        val msg = SignedMessage(
            identityId = aliceId,
            publicKey = alice.publicKey,
            data = "tampered".toByteArray(),
            signature = sig,
        )
        assertFalse(verifier.verifyMessage(msg))
    }

    @Test
    fun `publicKey de otra identidad devuelve false`() {
        val data = "hello".toByteArray()
        val sig = ed25519.sign(alice.privateKey, data)
        val msg = SignedMessage(
            identityId = aliceId,
            publicKey = bob.publicKey, // ← clave de Bob
            data = data,
            signature = sig,
        )
        assertFalse(verifier.verifyMessage(msg))
    }

    @Test
    fun `identityId correcto pero publicKey incorrecta devuelve false`() {
        val data = "hello".toByteArray()
        val sig = ed25519.sign(alice.privateKey, data)
        val msg = SignedMessage(
            identityId = bobId, // identityId de Bob
            publicKey = alice.publicKey, // pero clave de Alice
            data = data,
            signature = sig,
        )
        // La firma fue hecha por Alice, publicKey es de Alice → verifica
        // A pesar de identityId incorrecto, la criptografia es correcta.
        // Esto demuestra que identityId y publicKey son independientes:
        // la verificacion usa publicKey, no identityId.
        assertTrue(verifier.verifyMessage(msg))
    }

    @Test
    fun `publicKey de longitud incorrecta lanza require`() {
        val data = "test".toByteArray()
        val sig = ed25519.sign(alice.privateKey, data)
        val msgCon33 = SignedMessage(
            identityId = aliceId,
            publicKey = ByteArray(33), // ni 32 bytes
            data = data,
            signature = sig,
        )
        assertThrows(IllegalArgumentException::class.java) {
            verifier.verifyMessage(msgCon33)
        }
    }

    // ===================================================================
    // SignatureVerifier — SignedAck
    // ===================================================================

    @Test
    fun `Ack firmado con publicKey correcta devuelve true`() {
        val data = "ack-data".toByteArray()
        val sig = ed25519.sign(bob.privateKey, data)
        val ack = SignedAck(
            identityId = bobId,
            publicKey = bob.publicKey,
            data = data,
            signature = sig,
        )
        assertTrue(verifier.verifyAck(ack))
    }

    @Test
    fun `Ack firmado con publicKey incorrecta devuelve false`() {
        val data = "ack-data".toByteArray()
        val sig = ed25519.sign(bob.privateKey, data)
        val ack = SignedAck(
            identityId = bobId,
            publicKey = alice.publicKey,
            data = data,
            signature = sig,
        )
        assertFalse(verifier.verifyAck(ack))
    }

    // ===================================================================
    // AckManagerImpl — inyecta publicKey resolver
    // ===================================================================

    @Test
    fun `AckManager verifica con publicKey correcta`() {
        val store = InMemoryMessageStore()
        val mgr = AckManagerImpl(store, ed25519, senderPublicKey = { _ -> bob.publicKey })

        val ackData = "ack-data".toByteArray()
        val sig = ed25519.sign(bob.privateKey, ackData)

        // Crear mensaje en el store para que onAckReceived no lo ignore
        val msg = com.km.model.Message(
            messageId = com.km.model.MessageId.random(),
            from = bobId,
            to = aliceId,
            payload = "payload".toByteArray(),
            timestamp = 1000L,
        )
        store.insert(msg).getOrThrow()

        val ack = com.km.model.Ack(
            from = bobId,
            to = aliceId,
            originalMessageId = msg.messageId,
            status = com.km.model.AckStatus.DELIVERED,
            timestamp = 2000L,
            signature = sig.bytes,
        )
        try {
            mgr.onAckReceived(ack).getOrThrow()
        } catch (e: Exception) {
            fail("onAckReceived no debe lanzar: ${e.message}")
        }
    }

    @Test
    fun `AckManager sin publicKey conocida salta verificacion silenciosamente`() {
        val store = InMemoryMessageStore()
        val mgr = AckManagerImpl(store, ed25519) // sin resolvedor → { null }

        val ackData = "ack-data".toByteArray()
        val sig = ed25519.sign(bob.privateKey, ackData)

        val msg = com.km.model.Message(
            messageId = com.km.model.MessageId.random(),
            from = bobId,
            to = aliceId,
            payload = "p".toByteArray(),
            timestamp = 1000L,
        )
        store.insert(msg).getOrThrow()

        val ack = com.km.model.Ack(
            from = bobId,
            to = aliceId,
            originalMessageId = msg.messageId,
            status = com.km.model.AckStatus.DELIVERED,
            timestamp = 2000L,
            signature = sig.bytes,
        )
        try {
            mgr.onAckReceived(ack).getOrThrow()
        } catch (e: Exception) {
            fail("debe completarse sin excepcion (salta verificacion): ${e.message}")
        }
    }

    // ===================================================================
    // Ed25519Impl — require de tamanio de clave
    // ===================================================================

    @Test
    fun `Ed25519 verify con publicKey de 64 bytes lanza require`() {
        val data = "test".toByteArray()
        val sig = ed25519.sign(alice.privateKey, data)
        val tooLong = ByteArray(64) { 0x41.toByte() } // lo que producia identityId.value.toByteArray()
        assertThrows(IllegalArgumentException::class.java) {
            ed25519.verify(tooLong, data, sig)
        }
    }

    @Test
    fun `Ed25519 verify con publicKey de 0 bytes lanza require`() {
        val data = "test".toByteArray()
        val sig = ed25519.sign(alice.privateKey, data)
        assertThrows(IllegalArgumentException::class.java) {
            ed25519.verify(ByteArray(0), data, sig)
        }
    }

    // ===================================================================
    // NodeRuntimeImpl.fromKeyPair
    // ===================================================================

    @Test
    fun `NodeIdentity fromKeyPair deriva nodeId correctamente`() {
        val nodeId = com.km.identity.KmIds.identityId(alice.publicKey).toHexString()
        val identity = NodeIdentity.fromKeyPair(alice.publicKey, "alice-node")
        assertEquals(nodeId, identity.nodeId.value)
        assertContentEquals(alice.publicKey, identity.publicKey)
        assertEquals("alice-node", identity.nodeName)
    }

    @Test
    fun `NodeRuntimeImpl fromKeyPair invoca NodeIdentity fromKeyPair`() {
        // Verifica que NodeRuntimeImpl.fromKeyPair delega correctamente
        // SIGNALING no requiere dependencias externas
        val node = com.km.node.NodeRuntimeImpl.fromKeyPair(
            alice.publicKey, alice.privateKey, nodeName = "n",
            capabilities = setOf(com.km.model.NodeCapability.SIGNALING),
        )
        assertEquals("n", node.identity.nodeName)
    }

    @Test
    fun `NodeRuntime fromKeyPair con clave incorrecta lanza`() {
        assertThrows(IllegalArgumentException::class.java) {
            com.km.node.NodeRuntimeImpl.fromKeyPair(
                ByteArray(33), alice.privateKey,
            )
        }
    }
}