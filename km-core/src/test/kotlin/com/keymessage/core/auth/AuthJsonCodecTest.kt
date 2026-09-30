package com.keymessage.core.auth

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import kotlin.test.assertContentEquals

/**
 * Tests de AuthJsonCodec (3F.4).
 *
 * Verifica:
 * - round-trip JSON de cada tipo de mensaje
 * - campos faltantes lanzan
 * - tipo incorrecto lanza
 * - campos extra se ignoran
 * - reorden de campos no afecta el parseo
 */
class AuthJsonCodecTest {

    private val nonce16 = ByteArray(16) { it.toByte() }
    private val pubKey32 = ByteArray(32) { (it + 1).toByte() }
    private val sig64 = ByteArray(64) { (it * 2).toByte() }
    private val serverNonce16 = ByteArray(16) { (it + 16).toByte() }

    @Test
    fun `challenge round-trip`() {
        val original = AuthChallenge(
            nonce = nonce16,
            timestampMillis = 1_721_827_200_000L,
            version = 3,
            responderIdentityId = "f9e8d7c6b5a4f3e2d1c0b9a8f7e6d5c4b3a2f1e0a1b2c3d4e5f6a7b8c9d0e1f2",
        )
        val wire = AuthJsonCodec.challengeWire(original)
        val parsed = AuthJsonCodec.parseChallenge(wire)
        assertContentEquals(original.nonce, parsed.nonce)
        assertEquals(original.timestampMillis, parsed.timestampMillis)
        assertEquals(original.version, parsed.version)
        assertEquals(original.responderIdentityId, parsed.responderIdentityId)
    }

    @Test
    fun `response round-trip`() {
        val original = AuthResponse(
            identityId = "a1b2c3d4e5f6a7b8c9d0e1f2a3b4c5d6e7f8a9b0c1d2e3f4a5b6c7d8e9f0a1b2",
            publicKey = pubKey32,
            signature = sig64,
            protocolVersion = 3,
        )
        val wire = AuthJsonCodec.responseWire(original)
        val parsed = AuthJsonCodec.parseResponse(wire)
        assertEquals(original.identityId, parsed.identityId)
        assertContentEquals(original.publicKey, parsed.publicKey)
        assertContentEquals(original.signature, parsed.signature)
        assertEquals(original.protocolVersion, parsed.protocolVersion)
    }

    @Test
    fun `authOk round-trip`() {
        val original = AuthOk(
            sessionId = "b0a9f8e7d6c5b4a3f2e1d0c9b8a7f6e5d4c3b2a1",
            serverNonce = serverNonce16,
            responderPublicKey = pubKey32,
            signature = sig64,
        )
        val wire = AuthJsonCodec.authOkWire(original)
        val parsed = AuthJsonCodec.parseAuthOk(wire)
        assertEquals(original.sessionId, parsed.sessionId)
        assertContentEquals(original.serverNonce, parsed.serverNonce)
        assertContentEquals(original.responderPublicKey, parsed.responderPublicKey)
        assertContentEquals(original.signature, parsed.signature)
    }

    // ===================================================================
    // Campos faltantes lanzan
    // ===================================================================

    @Test
    fun `challenge sin nonce lanza`() {
        assertThrows(IllegalArgumentException::class.java) {
            AuthJsonCodec.parseChallenge("""{"timestamp":1,"version":3,"responderIdentityId":"${"0".repeat(64)}"}""")
        }
    }

    @Test
    fun `response sin publicKey lanza`() {
        assertThrows(IllegalArgumentException::class.java) {
            AuthJsonCodec.parseResponse("""{"identityId":"${"0".repeat(64)}","signature":"${"A".repeat(86)}","protocolVersion":3}""")
        }
    }

    @Test
    fun `authOk sin signature lanza`() {
        assertThrows(IllegalArgumentException::class.java) {
            AuthJsonCodec.parseAuthOk("""{"sessionId":"${"0".repeat(40)}","serverNonce":"${"A".repeat(22)}","publicKey":"${"A".repeat(43)}"}""")
        }
    }

    // ===================================================================
    // Campos extra se ignoran
    // ===================================================================

    @Test
    fun `challenge con campo extra se parsea correctamente`() {
        val json = """{"nonce":"${Base64Url.encode(nonce16)}","timestamp":1,"version":3,"responderIdentityId":"${"0".repeat(64)}","extra":"ignored"}"""
        val parsed = AuthJsonCodec.parseChallenge(json)
        assertNotNull(parsed)
    }

    // ===================================================================
    // Reorden de campos no afecta parseo
    // ===================================================================

    @Test
    fun `response con reorden de campos es equivalente`() {
        val original = AuthResponse(
            identityId = "a1b2c3d4e5f6a7b8c9d0e1f2a3b4c5d6e7f8a9b0c1d2e3f4a5b6c7d8e9f0a1b2",
            publicKey = pubKey32,
            signature = sig64,
            protocolVersion = 3,
        )
        val wire = AuthJsonCodec.responseWire(original)
        // Construir JSON con orden inverso de claves
        val reordered = """{"protocolVersion":3,"signature":"${Base64Url.encode(sig64)}","publicKey":"${Base64Url.encode(pubKey32)}","identityId":"${
            "a1b2c3d4e5f6a7b8c9d0e1f2a3b4c5d6e7f8a9b0c1d2e3f4a5b6c7d8e9f0a1b2"
        }"}"""
        val parsed = AuthJsonCodec.parseResponse(reordered)
        assertEquals(original.identityId, parsed.identityId)
        assertContentEquals(original.publicKey, parsed.publicKey)
        assertContentEquals(original.signature, parsed.signature)
    }

    // ===================================================================
    // JSON malformado
    // ===================================================================

    @Test
    fun `JSON no valido lanza`() {
        assertThrows(Exception::class.java) {
            AuthJsonCodec.parseChallenge("not json")
        }
    }

    @Test
    fun `JSON array lanza`() {
        assertThrows(IllegalArgumentException::class.java) {
            AuthJsonCodec.parseChallenge("[]")
        }
    }
}