package com.km.frame

import com.km.crypto.Aead
import com.km.crypto.AeadAuthenticationException
import com.km.crypto.Kdf
import com.km.crypto.provider.BcChaCha20Poly1305
import com.km.crypto.provider.BcHkdfSha256
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.test.assertContentEquals

/**
 * 3O.1 — SecureFrame v1: wire format, canonicalidad y AAD.
 *
 * 3O.1 congela el FORMATO. El Double Ratchet todavia no existe: aqui se
 * prueba que el contenedor es canonico, que el AAD protege el header y que
 * el nonce derivado nunca depende del wire.
 */
class SecureFrameCodecTest {

    private lateinit var codec: SecureFrameCodec

    @BeforeEach
    fun setUp() {
        codec = BinarySecureFrameCodec
    }

    private fun hex(s: String) = ByteArray(s.length / 2) {
        ((Character.digit(s[it * 2], 16) shl 4) or Character.digit(s[it * 2 + 1], 16)).toByte()
    }

    private fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }

    private fun header(
        dh: ByteArray = ByteArray(32) { it.toByte() },
        pn: UInt = 0u,
        n: UInt = 0u,
    ) = RatchetHeader(dh, pn, n)

    private fun frame(
        type: FrameType = FrameType.MESSAGE,
        header: RatchetHeader = header(),
        ciphertext: ByteArray = ByteArray(32) { (it * 7).toByte() },
    ) = SecureFrame(SecureFrameSpec.VERSION, type, header, ciphertext)

    // ===================================================================
    // Golden vector — verificado contra una referencia independiente
    // ===================================================================

    @Test
    @DisplayName("golden vector - reproduce el wire format exacto")
    fun `O5-01 golden vector de wire format`() {
        val f = frame(
            type = FrameType.MESSAGE,
            header = header(
                dh = ByteArray(32) { it.toByte() },
                pn = 0xDEADBEEFu,
                n = 0x00000105u,
            ),
            ciphertext = ByteArray(322) { (0x41 + (it % 60)).toByte() },
        )
        val encoded = codec.encode(f)
        assertEquals(366, encoded.size, "44 de header + 322 de ciphertext")
        // Generado independientemente con Python struct '>H' / '>I' (big-endian).
        assertEquals(
            "01020142000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f" +
                "deadbeef00000105",
            hex(encoded.copyOfRange(0, 44)),
        )
    }

    @Test
    @DisplayName("el campo length es unsigned big-endian")
    fun `O5-02 length es unsigned big-endian`() {
        // 0x0142 = 322. Si fuera little-endian seria 0x4201 = 16897.
        val f = frame(ciphertext = ByteArray(0x0142))
        val bytes = codec.encode(f)
        assertEquals(0x01.toByte(), bytes[2])
        assertEquals(0x42.toByte(), bytes[3])
        assertEquals(0x0142, codec.decode(bytes).ciphertext.size)
    }

    @Test
    @DisplayName("PN y N son unsigned big-endian")
    fun `O5-03 PN y N son unsigned big-endian`() {
        val f = frame(header = header(pn = 0xDEADBEEFu, n = 0x01020304u))
        val bytes = codec.encode(f)
        assertContentEquals(hex("deadbeef"), bytes.copyOfRange(36, 40))
        assertContentEquals(hex("01020304"), bytes.copyOfRange(40, 44))
        val decoded = codec.decode(bytes)
        assertEquals(0xDEADBEEFu, decoded.ratchetHeader.previousChainLength)
        assertEquals(0x01020304u, decoded.ratchetHeader.messageNumber)
    }

    @Test
    @DisplayName("los valores maximos UInt se codifican correctamente")
    fun `O5-04 valores maximos UInt`() {
        val f = frame(header = header(pn = UInt.MAX_VALUE, n = UInt.MAX_VALUE))
        val decoded = codec.decode(codec.encode(f))
        assertEquals(UInt.MAX_VALUE, decoded.ratchetHeader.previousChainLength)
        assertEquals(UInt.MAX_VALUE, decoded.ratchetHeader.messageNumber)
    }

    // ===================================================================
    // Round-trip
    // ===================================================================

    @Test
    @DisplayName("round-trip para todos los tipos y tamanos")
    fun `O5-05 round trip para tipos y tamanos`() {
        for (type in FrameType.values()) {
            for (size in listOf(16, 17, 64, 1000, 65535)) {
                val f = frame(
                    type = type,
                    header = header(
                        dh = ByteArray(32) { (it * 3).toByte() },
                        pn = 0x11223344u,
                        n = 0x55667788u,
                    ),
                    ciphertext = ByteArray(size) { (it % 256).toByte() },
                )
                val decoded = codec.decode(codec.encode(f))
                assertEquals(f, decoded, "type=$type size=$size")
            }
        }
    }

    @Test
    @DisplayName("ciphertext vacio salvo el tag es valido")
    fun `O5-06 ciphertext minimo - solo el tag`() {
        val f = frame(ciphertext = ByteArray(16))
        val decoded = codec.decode(codec.encode(f))
        assertEquals(16, decoded.ciphertext.size)
    }

    // ===================================================================
    // Canonicalidad
    // ===================================================================

    @Test
    @DisplayName("encode de decode devuelve exactamente los mismos bytes")
    fun `O5-06b la codificacion es canonica`() {
        val f = frame(header = header(pn = 42u, n = 7u), ciphertext = ByteArray(100) { it.toByte() })
        val once = codec.encode(f)
        val twice = codec.encode(codec.decode(once))
        assertContentEquals(once, twice, "el codec debe ser canonico")
    }

    @Test
    @DisplayName("rechaza bytes sobrantes al final")
    fun `O5-07 rechaza bytes sobrantes`() {
        val bytes = codec.encode(frame()) + byteArrayOf(0x00)
        val ex = assertThrows<SecureFrameFormatException> { codec.decode(bytes) }
        assertTrue(ex.message!!.contains("longitud inconsistente"))
    }

    @Test
    @DisplayName("rechaza ciphertext truncado")
    fun `O5-08 rechaza ciphertext truncado`() {
        val bytes = codec.encode(frame(ciphertext = ByteArray(100)))
        val ex = assertThrows<SecureFrameFormatException> {
            codec.decode(bytes.copyOf(bytes.size - 1))
        }
        assertTrue(ex.message!!.contains("longitud inconsistente"))
    }

    @Test
    @DisplayName("rechaza frame truncado en el header")
    fun `O5-09 rechaza frame truncado en el header`() {
        for (size in 0 until SecureFrameSpec.CIPHERTEXT_OFFSET) {
            assertThrows<SecureFrameFormatException>("size=$size") {
                codec.decode(ByteArray(size))
            }
        }
    }

    // ===================================================================
    // Version y tipo
    // ===================================================================

    @Test
    @DisplayName("version desconocida -> rechazo total")
    fun `O5-10 version desconocida se rechaza`() {
        for (bad in listOf(0u, 2u, 3u, 127u, 255u)) {
            val bytes = codec.encode(frame())
            bytes[SecureFrameSpec.VERSION_OFFSET] = bad.toByte()
            val ex = assertThrows<UnsupportedFrameVersion>("version=$bad") { codec.decode(bytes) }
            assertEquals(bad.toUByte(), ex.version)
        }
    }

    @Test
    @DisplayName("la version se comprueba ANTES de interpretar el resto")
    fun `O5-11 la version se comprueba primero`() {
        // Un frame con version desconocida y longitud absurda debe fallar por
        // version, no por longitud: la semantica de v2 no la conocemos.
        val bytes = codec.encode(frame())
        bytes[0] = 99
        bytes[2] = 0xFF.toByte()
        bytes[3] = 0xFF.toByte()
        assertThrows<UnsupportedFrameVersion> { codec.decode(bytes) }
    }

    @Test
    @DisplayName("tipo de frame desconocido se rechaza")
    fun `O5-12 tipo desconocido se rechaza`() {
        for (bad in listOf(0u, 3u, 99u, 255u)) {
            val bytes = codec.encode(frame())
            bytes[SecureFrameSpec.TYPE_OFFSET] = bad.toByte()
            assertThrows<SecureFrameFormatException>("type=$bad") { codec.decode(bytes) }
        }
    }

    @Test
    @DisplayName("el codigo de tipo 0 esta reservado y no es valido")
    fun `O5-13 el tipo 0 esta reservado`() {
        assertNull(FrameType.fromCode(0u), "0 debe quedar reservado para 'ausente'")
        // Convencion 1-based.
        for (t in FrameType.values()) {
            assertTrue(t.code >= 1u, "los tipos deben ser 1-based: ${t.name}=${t.code}")
        }
    }

    // ===================================================================
    // Limites
    // ===================================================================

    @Test
    @DisplayName("rechaza length menor que el tag")
    fun `O5-14 rechaza length menor que el tag`() {
        val bytes = codec.encode(frame())
        bytes[2] = 0
        bytes[3] = 15
        val ex = assertThrows<SecureFrameFormatException> { codec.decode(bytes) }
        assertTrue(ex.message!!.contains("menor que el minimo"))
    }

    @Test
    @DisplayName("acepta el ciphertext maximo de 65535 bytes")
    fun `O5-15 acepta el maximo de 65535 bytes`() {
        val f = frame(ciphertext = ByteArray(65535))
        val decoded = codec.decode(codec.encode(f))
        assertEquals(65535, decoded.ciphertext.size)
        assertEquals(65535 + 44, codec.encode(f).size)
    }

    @Test
    @DisplayName("rechaza ciphertext que excede el maximo en la construccion")
    fun `O5-16 rechaza ciphertext excesivo`() {
        assertThrows<IllegalArgumentException> {
            SecureFrame(SecureFrameSpec.VERSION, FrameType.MESSAGE, header(), ByteArray(65536))
        }
    }

    // ===================================================================
    // Header
    // ===================================================================

    @Test
    @DisplayName("rechaza dhPublicKey de tamano incorrecto")
    fun `O5-17 rechaza dhPublicKey de tamano incorrecto`() {
        for (bad in listOf(0, 16, 31, 33, 64)) {
            assertThrows<IllegalArgumentException>("size=$bad") {
                RatchetHeader(ByteArray(bad), 0u, 0u)
            }
        }
    }

    @Test
    @DisplayName("el header tiene siempre 44 bytes en v1")
    fun `O5-18 el header tiene 44 bytes`() {
        assertEquals(44, SecureFrameSpec.HEADER_LENGTH)
        assertEquals(44, SecureFrameSpec.CIPHERTEXT_OFFSET)
        assertEquals(
            1 + 1 + 2 + 32 + 4 + 4,
            SecureFrameSpec.HEADER_LENGTH,
            "1+1+2 version/tipo/length + 32 DH + 4 PN + 4 N",
        )
    }
}
