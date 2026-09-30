package com.keymessage.core.sf

import com.keymessage.core.crypto.AeadAuthenticationException
import com.keymessage.core.crypto.provider.BcChaCha20Poly1305
import com.keymessage.core.crypto.provider.BcHkdfSha256
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.test.assertContentEquals

/**
 * 3O.1 — SecureFrameProtector: AAD, derivacion de clave y nonce.
 *
 * Verifica las propiedades de seguridad del contenedor:
 * - el header COMPLETO esta autenticado (ningun campo es mutable)
 * - el nonce se deriva y NO viaja por wire
 * - la messageKey es de un solo uso
 */
class SecureFrameProtectorTest {

    private lateinit var protector: SecureFrameProtector
    private lateinit var codec: SecureFrameCodec

    @BeforeEach
    fun setUp() {
        protector = SecureFrameProtector(BcChaCha20Poly1305(), BcHkdfSha256())
        codec = BinarySecureFrameCodec
    }

    private fun mkKey(seed: Int) = ByteArray(32) { ((it * 31 + seed) and 0xFF).toByte() }

    private fun header(
        dh: ByteArray = ByteArray(32) { (it + 1).toByte() },
        pn: UInt = 3u,
        n: UInt = 7u,
    ) = RatchetHeader(dh, pn, n)

    // ===================================================================
    // Round-trip
    // ===================================================================

    @Test
    @DisplayName("round trip completo: proteger, serializar, deserializar, desproteger")
    fun `O6-01 round trip completo`() {
        val plaintext = "mensaje secreto de KeyMessage".toByteArray()
        val frame = protector.protect(FrameType.MESSAGE, header(), mkKey(1), plaintext)
        val wire = codec.encode(frame)
        val decoded = codec.decode(wire)
        assertContentEquals(plaintext, protector.unprotect(decoded, mkKey(1)))
    }

    @Test
    @DisplayName("round trip para plaintexts de varios tamanos")
    fun `O6-02 round trip para tamanos variables`() {
        for (size in listOf(0, 1, 15, 16, 17, 1000, 60000)) {
            val plaintext = ByteArray(size) { (it % 256).toByte() }
            val frame = protector.protect(FrameType.MESSAGE, header(), mkKey(2), plaintext)
            val decoded = codec.decode(codec.encode(frame))
            assertContentEquals(
                plaintext, protector.unprotect(decoded, mkKey(2)), "size=$size"
            )
        }
    }

    @Test
    @DisplayName("el ciphertext incluye el tag")
    fun `O6-03 el ciphertext incluye el tag`() {
        val plaintext = "x".repeat(100).toByteArray()
        val frame = protector.protect(FrameType.MESSAGE, header(), mkKey(3), plaintext)
        assertEquals(plaintext.size + SecureFrameSpec.TAG_LENGTH, frame.ciphertext.size)
    }

    // ===================================================================
    // El header esta autenticado: NINGUN campo es mutable
    // ===================================================================

    @Test
    @DisplayName("alterar messageNumber invalida la autenticacion")
    fun `O6-04 messageNumber esta autenticado`() {
        val frame = protector.protect(FrameType.MESSAGE, header(n = 7u), mkKey(4), "dato".toByteArray())
        val tampered = frame.copy(
            ratchetHeader = frame.ratchetHeader.copy(messageNumber = 8u)
        )
        assertThrows<AeadAuthenticationException> { protector.unprotect(tampered, mkKey(4)) }
    }

    @Test
    @DisplayName("alterar previousChainLength invalida la autenticacion")
    fun `O6-05 PN esta autenticado`() {
        val frame = protector.protect(FrameType.MESSAGE, header(pn = 3u), mkKey(5), "dato".toByteArray())
        val tampered = frame.copy(
            ratchetHeader = frame.ratchetHeader.copy(previousChainLength = 4u)
        )
        assertThrows<AeadAuthenticationException> { protector.unprotect(tampered, mkKey(5)) }
    }

    @Test
    @DisplayName("alterar dhPublicKey invalida la autenticacion")
    fun `O6-06 dhPublicKey esta autenticado`() {
        val frame = protector.protect(FrameType.MESSAGE, header(), mkKey(6), "dato".toByteArray())
        val badDh = frame.ratchetHeader.dhPublicKey.copyOf().also { it[0] = (it[0] + 1).toByte() }
        val tampered = frame.copy(ratchetHeader = frame.ratchetHeader.copy(dhPublicKey = badDh))
        assertThrows<AeadAuthenticationException> { protector.unprotect(tampered, mkKey(6)) }
    }

    @Test
    @DisplayName("alterar type invalida la autenticacion")
    fun `O6-07 type esta autenticado`() {
        val frame = protector.protect(FrameType.MESSAGE, header(), mkKey(7), "dato".toByteArray())
        val tampered = frame.copy(type = FrameType.PREKEY)
        assertThrows<AeadAuthenticationException> { protector.unprotect(tampered, mkKey(7)) }
    }

    @Test
    @DisplayName("alterar el ciphertext invalida la autenticacion")
    fun `O6-08 ciphertext esta autenticado`() {
        val frame = protector.protect(FrameType.MESSAGE, header(), mkKey(8), "dato".toByteArray())
        val badCt = frame.ciphertext.copyOf().also { it[0] = (it[0] + 1).toByte() }
        assertThrows<AeadAuthenticationException> {
            protector.unprotect(frame.copy(ciphertext = badCt), mkKey(8))
        }
    }

    @Test
    @DisplayName("alterar el tag invalida la autenticacion")
    fun `O6-09 el tag esta autenticado`() {
        val frame = protector.protect(FrameType.MESSAGE, header(), mkKey(9), "dato".toByteArray())
        val badCt = frame.ciphertext.copyOf()
        badCt[badCt.size - 1] = (badCt[badCt.size - 1] + 1).toByte()
        assertThrows<AeadAuthenticationException> {
            protector.unprotect(frame.copy(ciphertext = badCt), mkKey(9))
        }
    }

    @Test
    @DisplayName("alterar length en el wire invalida la autenticacion")
    fun `O6-10 length esta autenticado`() {
        // El campo length forma parte del AAD. Reencodificar un frame con
        // ciphertext de otra longitud debe cambiar el AAD y fallar.
        val frame = protector.protect(FrameType.MESSAGE, header(), mkKey(10), "dato".toByteArray())
        val stretched = frame.copy(ciphertext = frame.ciphertext + ByteArray(5))
        assertThrows<AeadAuthenticationException> { protector.unprotect(stretched, mkKey(10)) }
    }

    @Test
    @DisplayName("version es inmutable: el codec solo emite la version soportada")
    fun `O6-11 no se puede codificar otra version`() {
        val frame = protector.protect(FrameType.MESSAGE, header(), mkKey(11), "dato".toByteArray())
        assertThrows<IllegalArgumentException> { codec.encode(frame.copy(version = 2u)) }
    }

    // ===================================================================
    // La messageKey es de un solo uso
    // ===================================================================

    @Test
    @DisplayName("una messageKey distinta no descifra")
    fun `O6-12 messageKey incorrecta falla`() {
        val frame = protector.protect(FrameType.MESSAGE, header(), mkKey(12), "secreto".toByteArray())
        assertThrows<AeadAuthenticationException> { protector.unprotect(frame, mkKey(99)) }
    }

    @Test
    @DisplayName("messageKey distinta produce ciphertext distinto (no determinista entre claves)")
    fun `O6-13 claves distintas producen ciphertext distintos`() {
        val a = protector.protect(FrameType.MESSAGE, header(), mkKey(20), "mismo".toByteArray())
        val b = protector.protect(FrameType.MESSAGE, header(), mkKey(21), "mismo".toByteArray())
        assertFalse(a.ciphertext.contentEquals(b.ciphertext))
    }

    @Test
    @DisplayName("la misma messageKey con el mismo plaintext es determinista")
    fun `O6-14 la misma messageKey es determinista`() {
        val a = protector.protect(FrameType.MESSAGE, header(), mkKey(22), "mismo".toByteArray())
        val b = protector.protect(FrameType.MESSAGE, header(), mkKey(22), "mismo".toByteArray())
        assertContentEquals(a.ciphertext, b.ciphertext)
    }

    // ===================================================================
    // El nonce se deriva y NO viaja
    // ===================================================================

    @Test
    @DisplayName("el nonce NO aparece en el wire")
    fun `O6-15 el nonce no viaja por wire`() {
        val plaintext = "contenido".toByteArray()
        val frame = protector.protect(FrameType.MESSAGE, header(), mkKey(30), plaintext)
        val wire = codec.encode(frame)
        val nonce = protector.deriveNonce(mkKey(30))
        // El nonce son 12 bytes; no puede aparecer dentro del wire.
        assertFalse(
            indexOf(wire, nonce) >= 0,
            "el nonce no debe aparecer en el wire",
        )
        // Y el tamano del wire es exactamente header + ciphertext.
        assertEquals(
            SecureFrameSpec.HEADER_LENGTH + plaintext.size + SecureFrameSpec.TAG_LENGTH,
            wire.size,
        )
    }

    @Test
    @DisplayName("el nonce derivado tiene 12 bytes y es unico por messageKey")
    fun `O6-16 el nonce es de 12 bytes y unico por messageKey`() {
        val n1 = protector.deriveNonce(mkKey(40))
        val n2 = protector.deriveNonce(mkKey(41))
        assertEquals(12, n1.size)
        assertEquals(12, n2.size)
        assertFalse(n1.contentEquals(n2), "messageKeys distintas -> nonces distintos")
    }

    @Test
    @DisplayName("el nonce NO se usa como messageKey (separacion de dominio)")
    fun `O6-17 dominio separado entre clave y nonce`() {
        val key = mkKey(50)
        val aeadKey = protector.deriveAeadKey(key)
        val nonce = protector.deriveNonce(key)
        assertEquals(32, aeadKey.size)
        assertEquals(12, nonce.size)
        // Dominios distintos: el nonce no es un prefijo de la clave ni al reves.
        assertFalse(nonce.contentEquals(aeadKey.copyOf(12)), "clave y nonce deben separarse")
        // Y el propio HKDF distingue las dos info strings.
        assertNotEquals(SecureFrameSpec.INFO_AEAD_KEY, SecureFrameSpec.INFO_NONCE)
    }

    @Test
    @DisplayName("el nonce derivado NO es la messageKey sin transformar")
    fun `O6-18 el nonce no es la messageKey directa`() {
        val key = mkKey(60)
        assertFalse(protector.deriveNonce(key).contentEquals(key))
    }

    @Test
    @DisplayName("rechaza messageKey de tamano incorrecto")
    fun `O6-19 rechaza messageKey invalida`() {
        for (bad in listOf(0, 16, 31, 33)) {
            assertThrows<IllegalArgumentException>("size=$bad") {
                protector.deriveAeadKey(ByteArray(bad))
            }
            assertThrows<IllegalArgumentException>("size=$bad") {
                protector.deriveNonce(ByteArray(bad))
            }
        }
    }

    // ===================================================================
    // AAD
    // ===================================================================

    @Test
    @DisplayName("el AAD son los 44 bytes del header")
    fun `O6-20 el AAD son los 44 bytes del header`() {
        val frame = protector.protect(FrameType.MESSAGE, header(), mkKey(70), "dato".toByteArray())
        val aad = BinarySecureFrameCodec.authenticatedHeader(frame)
        assertEquals(SecureFrameSpec.HEADER_LENGTH, aad.size)
        // Los primeros bytes del wire son exactamente el AAD.
        val wire = codec.encode(frame)
        assertContentEquals(aad, wire.copyOf(SecureFrameSpec.HEADER_LENGTH))
    }

    @Test
    @DisplayName("el AAD incluye el type correcto")
    fun `O6-21 el AAD incluye el type`() {
        val aadMsg = BinarySecureFrameCodec.authenticatedHeader(
            FrameType.MESSAGE, header(), 32
        )
        val aadPre = BinarySecureFrameCodec.authenticatedHeader(
            FrameType.PREKEY, header(), 32
        )
        assertFalse(aadMsg.contentEquals(aadPre), "MESSAGE y PREKEY deben tener AAD distinto")
        assertEquals(FrameType.MESSAGE.code.toByte(), aadMsg[1])
        assertEquals(FrameType.PREKEY.code.toByte(), aadPre[1])
    }

    // ===================================================================
    // Helpers
    // ===================================================================

    private fun indexOf(haystack: ByteArray, needle: ByteArray): Int {
        if (needle.isEmpty() || needle.size > haystack.size) return -1
        outer@ for (i in 0..haystack.size - needle.size) {
            for (j in needle.indices) {
                if (haystack[i + j] != needle[j]) continue@outer
            }
            return i
        }
        return -1
    }
}
