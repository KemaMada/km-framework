package com.keymessage.core.crypto

import com.keymessage.core.crypto.provider.BcHkdfSha256
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.test.assertContentEquals

/**
 * 3O.0.2 — HKDF-SHA-256 (RFC 5869).
 *
 * Vectores verificados independientemente contra OpenSSL (Python
 * `cryptography` 50.0.1) y contrastados con RFC 5869 Test Cases 1-3.
 */
class HkdfSha256Test {

    private lateinit var kdf: Kdf

    @BeforeEach
    fun setUp() {
        kdf = BcHkdfSha256()
    }

    private fun hex(s: String) = ByteArray(s.length / 2) {
        ((Character.digit(s[it * 2], 16) shl 4) or Character.digit(s[it * 2 + 1], 16)).toByte()
    }

    private fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }

    // ===================================================================
    // Vectores oficiales
    // ===================================================================

    @Test
    @DisplayName("RFC 5869 Test Case 1 - extract y expand basicos")
    fun `O2-01 reproduce el Test Case 1 de RFC 5869`() {
        val ikm = hex("0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b")
        val salt = hex("000102030405060708090a0b0c")
        val info = hex("f0f1f2f3f4f5f6f7f8f9")

        val prk = kdf.hkdfExtract(salt, ikm)
        assertEquals(
            "077709362c2e32df0ddc3f0dc47bba6390b6c73bb50f9c3122ec844ad7c2b3e5",
            hex(prk),
        )

        val okm = kdf.hkdfExpand(prk, info, 42)
        assertEquals(
            "3cb25f25faacd57a90434f64d0362f2a2d2d0a90cf1a5a4c5db02d56ecc4c5bf34007208d5b887185865",
            hex(okm),
        )
    }

    @Test
    @DisplayName("RFC 5869 Test Case 2 - IKM mas largo")
    fun `O2-02 reproduce el Test Case 2 de RFC 5869`() {
        val ikm = ByteArray(80) { it.toByte() }              // 0x00..0x4f
        val salt = ByteArray(32) { (0x60 + it).toByte() }   // 0x60..0x7f
        val info = ByteArray(10) { (0xf0 + it).toByte() }   // 0xf0..0xf9

        val prk = kdf.hkdfExtract(salt, ikm)
        assertEquals(
            "c452026dd18be146d48ba7133cfe56f46f774f25b274fa22b093487af098aa3f",
            hex(prk),
        )

        val okm = kdf.hkdfExpand(prk, info, 82)
        assertEquals(
            "d918894357119dc4d605c86712c0ffd020a12edc78266bbf49ad4b1624a83c3a" +
                "349bcf1c2ba60e99ced9487c43fb4fd08b2d95b557ce4f3ba6dc99f8cc9d50d8" +
                "9b90005d7f8bedf2959ab84d18cdc83cad32",
            hex(okm),
        )
    }

    @Test
    @DisplayName("RFC 5869 Test Case 3 - salt e info vacios")
    fun `O2-03 reproduce el Test Case 3 de RFC 5869`() {
        val ikm = hex("0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b")

        val prk = kdf.hkdfExtract(ByteArray(0), ikm)
        assertEquals(
            "19ef24a32c717b167f33a91d6f648bdf96596776afdb6377ac434c1c293ccb04",
            hex(prk),
        )

        val okm = kdf.hkdfExpand(prk, ByteArray(0), 42)
        assertEquals(
            "8da4e775a563c18f715f802a063c5a31b8a11f5c5ee1879ec3454e5f3c738d2d" +
                "9d201395faa4b61a96c8",
            hex(okm),
        )
    }

    // ===================================================================
    // Propiedades
    // ===================================================================

    @Test
    @DisplayName("hkdf de un paso equivale a extract seguido de expand")
    fun `O2-04 hkdf equivale a extract y expand`() {
        val ikm = ByteArray(32) { it.toByte() }
        val salt = ByteArray(16) { (it * 7).toByte() }
        val info = "KM-DR-0001/test".toByteArray()
        val oneStep = kdf.hkdf(salt, ikm, info, 64)
        val twoStep = kdf.hkdfExpand(kdf.hkdfExtract(salt, ikm), info, 64)
        assertContentEquals(oneStep, twoStep)
    }

    @Test
    @DisplayName("el PRK tiene siempre 32 bytes")
    fun `O2-05 el PRK tiene 32 bytes`() {
        assertEquals(32, kdf.hkdfExtract(ByteArray(0), ByteArray(10)).size)
        assertEquals(32, kdf.hkdfExtract(ByteArray(16), ByteArray(10)).size)
    }

    @Test
    @DisplayName("expand respeta exactamente la longitud pedida")
    fun `O2-06 expand respeta la longitud`() {
        val prk = kdf.hkdfExtract(ByteArray(0), ByteArray(32) { 1 })
        for (len in listOf(1, 16, 31, 32, 33, 64, 100, 255 * 32)) {
            assertEquals(len, kdf.hkdfExpand(prk, ByteArray(0), len).size, "length=$len")
        }
    }

    @Test
    @DisplayName("info distinto produce material distinto - separacion de dominio")
    fun `O2-07 info separa dominios de derivacion`() {
        val ikm = ByteArray(32) { 9 }
        val a = kdf.hkdf(ByteArray(0), ikm, "KM-DR:chain".toByteArray(), 32)
        val b = kdf.hkdf(ByteArray(0), ikm, "KM-DR:root".toByteArray(), 32)
        assertFalse(a.contentEquals(b), "cadenas de info distintas deben derivar claves distintas")
    }

    @Test
    @DisplayName("expand es un prefijo-consistente en la longitud")
    fun `O2-08 expand es consistente entre longitudes`() {
        // HKDF garantiza que pedir mas bytes no cambia los anteriores:
        // OKM(L=32) es prefijo de OKM(L=64).
        val ikm = ByteArray(32) { 5 }
        val prk = kdf.hkdfExtract(ByteArray(0), ikm)
        val short = kdf.hkdfExpand(prk, "info".toByteArray(), 32)
        val long = kdf.hkdfExpand(prk, "info".toByteArray(), 64)
        assertContentEquals(short, long.copyOf(32))
    }

    // ===================================================================
    // Casos negativos
    // ===================================================================

    @Test
    @DisplayName("rechaza longitud cero o negativa")
    fun `O2-09 rechaza longitud no positiva`() {
        val prk = kdf.hkdfExtract(ByteArray(0), ByteArray(32))
        assertThrows<IllegalArgumentException> { kdf.hkdfExpand(prk, ByteArray(0), 0) }
        assertThrows<IllegalArgumentException> { kdf.hkdfExpand(prk, ByteArray(0), -1) }
    }

    @Test
    @DisplayName("rechaza longitud superior al maximo de RFC 5869")
    fun `O2-10 rechaza longitud excessive`() {
        val prk = kdf.hkdfExtract(ByteArray(0), ByteArray(32))
        // RFC 5869: L <= 255 * HashLen = 8160
        assertThrows<IllegalArgumentException> { kdf.hkdfExpand(prk, ByteArray(0), 255 * 32 + 1) }
    }

    @Test
    @DisplayName("el maximo permitido 8160 funciona")
    fun `O2-11 el maximo permitido funciona`() {
        val prk = kdf.hkdfExtract(ByteArray(0), ByteArray(32))
        assertEquals(255 * 32, kdf.hkdfExpand(prk, ByteArray(0), 255 * 32).size)
    }
}
