package com.keymessage.core.crypto

import com.keymessage.core.crypto.provider.BcChaCha20Poly1305
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.test.assertContentEquals

/**
 * 3O.0.3 — ChaCha20-Poly1305 AEAD (RFC 8439).
 *
 * Vectores verificados independientemente contra OpenSSL (Python
 * `cryptography` 50.0.1) y contrastados con RFC 8439 2.8.2.
 *
 * CONTRATO CRITICO: un descifrado NO autenticado no es un descifrado.
 * [decrypt] nunca devuelve texto plano cuando la verificacion falla.
 */
class ChaCha20Poly1305Test {

    private lateinit var aead: Aead

    @BeforeEach
    fun setUp() {
        aead = BcChaCha20Poly1305()
    }

    private fun hex(s: String) = ByteArray(s.length / 2) {
        ((Character.digit(s[it * 2], 16) shl 4) or Character.digit(s[it * 2 + 1], 16)).toByte()
    }

    private fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }

    // ===================================================================
    // Vectores oficiales
    // ===================================================================

    @Test
    @DisplayName("RFC 8439 2.8.2 - vector AEAD completo")
    fun `O3-01 reproduce el vector AEAD de RFC 8439 2_8_2`() {
        val key = ByteArray(32) { (0x80 + it).toByte() }
        val nonce = hex("070000004041424344454647")
        val aad = hex("50515253c0c1c2c3c4c5c6c7")
        val plaintext = (
            "Ladies and Gentlemen of the class of '99: If I could offer you " +
                "only one tip for the future, sunscreen would be it."
            ).toByteArray()

        val out = aead.encrypt(key, nonce, plaintext, aad)
        assertEquals(
            "d31a8d34648e60db7b86afbc53ef7ec2a4aded51296e08fea9e2b5a736ee62d6" +
                "3dbea45e8ca9671282fafb69da92728b1a71de0a9e060b2905d6a5b67ecd3b36" +
                "92ddbd7f2d778b8c9803aee328091b58fab324e4fad675945585808b4831d7bc" +
                "3ff4def08e4b7a9de576d26586cec64b6116" +
                "1ae10b594f09e26a7e902ecbd0600691",
            hex(out),
        )
        // Y el descifrado recupera exactamente el texto plano.
        assertContentEquals(plaintext, aead.decrypt(key, nonce, out, aad))
    }

    @Test
    @DisplayName("el ciphertext incluye el tag de 16 bytes")
    fun `O3-02 el ciphertext incluye el tag`() {
        val key = ByteArray(32) { 1 }
        val nonce = ByteArray(12) { 2 }
        val pt = "hola".toByteArray()
        assertEquals(pt.size + 16, aead.encrypt(key, nonce, pt, ByteArray(0)).size)
        assertEquals(16, aead.tagLength)
    }

    // ===================================================================
    // Round-trip
    // ===================================================================

    @Test
    @DisplayName("round-trip con AAD y sin AAD")
    fun `O3-03 round trip con y sin AAD`() {
        val key = ByteArray(32) { it.toByte() }
        val nonce = ByteArray(12) { (it * 3).toByte() }
        val pt = "mensaje privado de KeyMessage".toByteArray()
        for (aad in listOf(ByteArray(0), "header".toByteArray(), ByteArray(64) { it.toByte() })) {
            val ct = aead.encrypt(key, nonce, pt, aad)
            assertContentEquals(pt, aead.decrypt(key, nonce, ct, aad))
        }
    }

    @Test
    @DisplayName("round-trip con texto plano vacio y grande")
    fun `O3-04 round trip con tamanos extremos`() {
        val key = ByteArray(32) { 3 }
        val nonce = ByteArray(12) { 4 }
        for (size in listOf(0, 1, 63, 64, 65, 1024, 65536)) {
            val pt = ByteArray(size) { (it % 251).toByte() }
            val ct = aead.encrypt(key, nonce, pt, "aad".toByteArray())
            assertContentEquals(pt, aead.decrypt(key, nonce, ct, "aad".toByteArray()), "size=$size")
        }
    }

    @Test
    @DisplayName("round-trip con todos los valores de byte")
    fun `O3-05 round trip con todos los valores de byte`() {
        val key = ByteArray(256) { it.toByte() }.copyOf(32)
        val nonce = ByteArray(12) { (it * 11).toByte() }
        val pt = ByteArray(256) { it.toByte() }
        assertContentEquals(pt, aead.decrypt(key, nonce, aead.encrypt(key, nonce, pt, ByteArray(0)), ByteArray(0)))
    }

    @Test
    @DisplayName("el cifrado es determinista para key, nonce y plaintext dados")
    fun `O3-06 el cifrado es determinista`() {
        val key = ByteArray(32) { 7 }
        val nonce = ByteArray(12) { 8 }
        val pt = "determinismo".toByteArray()
        val a = aead.encrypt(key, nonce, pt, ByteArray(0))
        val b = aead.encrypt(key, nonce, pt, ByteArray(0))
        assertContentEquals(a, b)
    }

    // ===================================================================
    // Casos negativos: NINGUN descifrado no autenticado
    // ===================================================================

    @Test
    @DisplayName("clave incorrecta no produce texto plano")
    fun `O3-07 clave incorrecta falla la autenticacion`() {
        val key = ByteArray(32) { 1 }
        val nonce = ByteArray(12) { 2 }
        val pt = "secreto".toByteArray()
        val ct = aead.encrypt(key, nonce, pt, ByteArray(0))
        val wrongKey = key.copyOf().also { it[0] = (it[0] + 1).toByte() }
        assertThrows<AeadAuthenticationException> {
            aead.decrypt(wrongKey, nonce, ct, ByteArray(0))
        }
    }

    @Test
    @DisplayName("nonce incorrecto no produce texto plano")
    fun `O3-08 nonce incorrecto falla la autenticacion`() {
        val key = ByteArray(32) { 1 }
        val nonce = ByteArray(12) { 2 }
        val ct = aead.encrypt(key, nonce, "secreto".toByteArray(), ByteArray(0))
        val wrongNonce = nonce.copyOf().also { it[0] = (it[0] + 1).toByte() }
        assertThrows<AeadAuthenticationException> {
            aead.decrypt(key, wrongNonce, ct, ByteArray(0))
        }
    }

    @Test
    @DisplayName("AAD alterado no produce texto plano")
    fun `O3-09 AAD alterado falla la autenticacion`() {
        val key = ByteArray(32) { 1 }
        val nonce = ByteArray(12) { 2 }
        val ct = aead.encrypt(key, nonce, "secreto".toByteArray(), "header-v1".toByteArray())
        assertThrows<AeadAuthenticationException> {
            aead.decrypt(key, nonce, ct, "header-v2".toByteArray())
        }
    }

    @Test
    @DisplayName("AAD omitido no produce texto plano")
    fun `O3-10 AAD omitido falla la autenticacion`() {
        val key = ByteArray(32) { 1 }
        val nonce = ByteArray(12) { 2 }
        val ct = aead.encrypt(key, nonce, "secreto".toByteArray(), "header".toByteArray())
        assertThrows<AeadAuthenticationException> {
            aead.decrypt(key, nonce, ct, ByteArray(0))
        }
    }

    @Test
    @DisplayName("ciphertext alterado falla la autenticacion")
    fun `O3-11 ciphertext alterado falla la autenticacion`() {
        val key = ByteArray(32) { 1 }
        val nonce = ByteArray(12) { 2 }
        val pt = ByteArray(64) { it.toByte() }
        val ct = aead.encrypt(key, nonce, pt, ByteArray(0))
        for (i in intArrayOf(0, 1, 31, 32, 63)) {
            val tampered = ct.copyOf()
            tampered[i] = (tampered[i] + 1).toByte()
            assertThrows<AeadAuthenticationException>("byte $i alterado") {
                aead.decrypt(key, nonce, tampered, ByteArray(0))
            }
        }
    }

    @Test
    @DisplayName("tag truncado falla la autenticacion")
    fun `O3-12 tag truncado falla la autenticacion`() {
        val key = ByteArray(32) { 1 }
        val nonce = ByteArray(12) { 2 }
        // Texto plano de 64 bytes: truncaciones de 1..16 siguen dejando un
        // ciphertext estructuralmente valido, de modo que lo que se prueba
        // es la verificacion de autenticacion, no la validacion de tamano.
        val ct = aead.encrypt(key, nonce, ByteArray(64) { it.toByte() }, ByteArray(0))
        for (truncate in 1..16) {
            val tampered = ct.copyOf(ct.size - truncate)
            assertTrue(tampered.size >= 16, "el ciphertext truncado aun debe caber el tag")
            assertThrows<AeadAuthenticationException>("truncado en $truncate") {
                aead.decrypt(key, nonce, tampered, ByteArray(0))
            }
        }
    }

    @Test
    @DisplayName("ciphertext menor que el tag se rechaza por tamano")
    fun `O3-13 ciphertext menor que el tag se rechaza`() {
        val key = ByteArray(32) { 1 }
        val nonce = ByteArray(12) { 2 }
        assertThrows<IllegalArgumentException> {
            aead.decrypt(key, nonce, ByteArray(8), ByteArray(0))
        }
    }

    // ===================================================================
    // Validacion de parametros
    // ===================================================================

    @Test
    @DisplayName("rechaza clave de tamano incorrecto")
    fun `O3-14 rechaza clave de tamano incorrecto`() {
        val nonce = ByteArray(12)
        for (bad in listOf(0, 16, 31, 33, 64)) {
            assertThrows<IllegalArgumentException>("key=$bad") {
                aead.encrypt(ByteArray(bad), nonce, ByteArray(0), ByteArray(0))
            }
        }
    }

    @Test
    @DisplayName("rechaza nonce de tamano incorrecto")
    fun `O3-15 rechaza nonce de tamano incorrecto`() {
        val key = ByteArray(32)
        for (bad in listOf(0, 8, 11, 13, 16)) {
            assertThrows<IllegalArgumentException>("nonce=$bad") {
                aead.encrypt(key, ByteArray(bad), ByteArray(0), ByteArray(0))
            }
        }
    }

    @Test
    @DisplayName("parametros congelados de SecureFrame v1")
    fun `O3-16 parametros congelados`() {
        assertEquals("ChaCha20-Poly1305", aead.algorithm)
        assertEquals(32, aead.keyLength)
        assertEquals(12, aead.nonceLength)
        assertEquals(16, aead.tagLength)
    }
}
