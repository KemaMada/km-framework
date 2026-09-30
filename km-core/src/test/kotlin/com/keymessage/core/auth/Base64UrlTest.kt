package com.keymessage.core.auth

import com.keymessage.core.auth.Base64Url.DecodeError
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.test.assertContentEquals

/**
 * Tests estrictos de Base64URL -- RFC 4648 seccion 5.
 *
 * 3F.1 -- PRIMERA CAPA: no se debe poder construir ni una linea de codec
 * ni un transcript si Base64URL no es deterministico y estricto.
 */
class Base64UrlTest {

    // ===================================================================
    // Round-trip: vectores de RFC 4648
    // ===================================================================

    @Test
    fun `RFC 4648 test vector f empty`() {
        assertContentEquals(ByteArray(0), Base64Url.decode(""))
        assertEquals("", Base64Url.encode(ByteArray(0)))
    }

    @Test
    fun `RFC 4648 test vector f`() {
        assertContentEquals(byteArrayOf(0x66), Base64Url.decode("Zg"))
        assertEquals("Zg", Base64Url.encode(byteArrayOf(0x66)))
    }

    @Test
    fun `RFC 4648 test vector fo`() {
        assertContentEquals(byteArrayOf(0x66, 0x6F), Base64Url.decode("Zm8"))
        assertEquals("Zm8", Base64Url.encode(byteArrayOf(0x66, 0x6F)))
    }

    @Test
    fun `RFC 4648 test vector foo`() {
        assertContentEquals(byteArrayOf(0x66, 0x6F, 0x6F), Base64Url.decode("Zm9v"))
        assertEquals("Zm9v", Base64Url.encode(byteArrayOf(0x66, 0x6F, 0x6F)))
    }

    @Test
    fun `RFC 4648 test vector foob`() {
        assertContentEquals(byteArrayOf(0x66, 0x6F, 0x6F, 0x62), Base64Url.decode("Zm9vYg"))
        assertEquals("Zm9vYg", Base64Url.encode(byteArrayOf(0x66, 0x6F, 0x6F, 0x62)))
    }

    @Test
    fun `RFC 4648 test vector fooba`() {
        assertContentEquals(byteArrayOf(0x66, 0x6F, 0x6F, 0x62, 0x61), Base64Url.decode("Zm9vYmE"))
        assertEquals("Zm9vYmE", Base64Url.encode(byteArrayOf(0x66, 0x6F, 0x6F, 0x62, 0x61)))
    }

    @Test
    fun `RFC 4648 test vector foobar`() {
        assertContentEquals(byteArrayOf(0x66, 0x6F, 0x6F, 0x62, 0x61, 0x72), Base64Url.decode("Zm9vYmFy"))
        assertEquals("Zm9vYmFy", Base64Url.encode(byteArrayOf(0x66, 0x6F, 0x6F, 0x62, 0x61, 0x72)))
    }

    @Test
    fun `round-trip 32 bytes (Ed25519 public key)`() {
        val data = ByteArray(32) { it.toByte() }
        val encoded = Base64Url.encode(data)
        assertEquals(43, encoded.length) // 32 bytes -> 43 chars sin padding
        assertContentEquals(data, Base64Url.decode(encoded))
    }

    @Test
    fun `round-trip 64 bytes (Ed25519 signature)`() {
        val data = ByteArray(64) { (it * 3).toByte() }
        val encoded = Base64Url.encode(data)
        assertEquals(86, encoded.length) // 64 bytes -> 86 chars sin padding
        assertContentEquals(data, Base64Url.decode(encoded))
    }

    @Test
    fun `round-trip 16 bytes (nonce)`() {
        val data = ByteArray(16) { (it * 7).toByte() }
        val encoded = Base64Url.encode(data)
        assertEquals(22, encoded.length) // 16 bytes -> 22 chars sin padding
        assertContentEquals(data, Base64Url.decode(encoded))
    }

    // ===================================================================
    // Alfabeto URL-safe
    // ===================================================================

    @Test
    fun `encode usa guion y subrayado en vez de mas y barra`() {
        // Con 0xFB 0xFF: en base64 normal serian '+/8', en base64url son '-_8'
        val data = byteArrayOf(0xFB.toByte(), 0xFF.toByte())
        val encoded = Base64Url.encode(data)
        assertEquals("-_8", encoded)
        assertFalse(encoded.contains('+'))
        assertFalse(encoded.contains('/'))
    }

    // ===================================================================
    // Rechazo estricto de decode
    // ===================================================================

    @Test
    fun `padding not allowed`() {
        assertThrows<DecodeError.PaddingNotAllowed> { Base64Url.decode("Zm9vYmFy=") }
        assertThrows<DecodeError.PaddingNotAllowed> { Base64Url.decode("Zm9vYmFy==") }
        assertThrows<DecodeError.PaddingNotAllowed> { Base64Url.decode("Zm9vYmF===") }
    }

    @Test
    fun `caracter fuera del alfabeto rechazado`() {
        assertThrows<DecodeError.InvalidChar> { Base64Url.decode("Zm9v+YmFy") }
        assertThrows<DecodeError.InvalidChar> { Base64Url.decode("Zm9v/YmFy") }
        assertThrows<DecodeError.InvalidChar> { Base64Url.decode("Zm9v YmFy") }
        assertThrows<DecodeError.InvalidChar> { Base64Url.decode("Zm9v\nYmFy") }
        assertThrows<DecodeError.InvalidChar> { Base64Url.decode("Zm9v%YmFy") }
    }

    @Test
    fun `longitud imposible len mod 4 igual a 1 rechazada`() {
        assertThrows<DecodeError.ImpossibleLength> { Base64Url.decode("Z") }
        assertThrows<DecodeError.ImpossibleLength> { Base64Url.decode("Zm9vY") }
        assertThrows<DecodeError.ImpossibleLength> { Base64Url.decode("Zm9vYmFyZ") }
    }

    @Test
    fun `caracter con code point mayor de 127 rechazado`() {
        assertThrows<DecodeError.InvalidChar> { Base64Url.decode("Zm9v\u00E1YmFy") }
    }

    @Test
    fun `isValid devuelve true para string valido`() {
        assertTrue(Base64Url.isValid("Zm9vYmFy"))
        assertTrue(Base64Url.isValid(""))
        assertTrue(Base64Url.isValid("Zg"))
    }

    @Test
    fun `isValid devuelve false para string invalido`() {
        assertFalse(Base64Url.isValid("Zm9vYmFy="))
        assertFalse(Base64Url.isValid("Zm9v+YmFy"))
        assertFalse(Base64Url.isValid("Z"))
    }

    // ===================================================================
    // 32 bytes de Ed25519 -> 43 chars, sin padding
    // ===================================================================

    @Test
    fun `encode 32 bytes produce 43 caracteres sin padding`() {
        val encoded = Base64Url.encode(ByteArray(32))
        assertEquals(43, encoded.length)
        assertFalse(encoded.contains('='))
    }

    @Test
    fun `encode 64 bytes produce 86 caracteres sin padding`() {
        val encoded = Base64Url.encode(ByteArray(64))
        assertEquals(86, encoded.length)
        assertFalse(encoded.contains('='))
    }
}