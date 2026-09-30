package com.km.crypto

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class Ed25519Test {
    private val ed25519: Ed25519 = Ed25519Impl()

    @Test
    fun `generateKeyPair produces valid key material`() {
        val keyPair = ed25519.generateKeyPair()
        assertEquals(32, keyPair.privateKey.size)
        assertNotNull(keyPair.publicKey)
    }

    @Test
    fun `sign and verify roundtrip succeeds`() {
        val keyPair = ed25519.generateKeyPair()
        val data = "hello".toByteArray()
        val signature = ed25519.sign(keyPair.privateKey, data)
        assertTrue(ed25519.verify(keyPair.publicKey, data, signature))
    }

    @Test
    fun `verify rejects wrong key`() {
        val keyPair1 = ed25519.generateKeyPair()
        val keyPair2 = ed25519.generateKeyPair()
        val data = "hello".toByteArray()
        val signature = ed25519.sign(keyPair1.privateKey, data)
        assertFalse(ed25519.verify(keyPair2.publicKey, data, signature))
    }

    @Test
    fun `verify rejects tampered data`() {
        val keyPair = ed25519.generateKeyPair()
        val data = "hello".toByteArray()
        val signature = ed25519.sign(keyPair.privateKey, data)
        val tampered = "hEllo".toByteArray()
        assertFalse(ed25519.verify(keyPair.publicKey, tampered, signature))
    }

    @Test
    fun `verify rejects empty signature`() {
        val keyPair = ed25519.generateKeyPair()
        val data = "hello".toByteArray()
        assertFalse(ed25519.verify(keyPair.publicKey, data, Signature(ByteArray(64))))
    }
}
