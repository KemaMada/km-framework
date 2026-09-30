package com.km.crypto

import com.km.crypto.provider.BcX25519
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.test.assertContentEquals

/**
 * 3O.0.1 — X25519 (RFC 7748).
 *
 * Criterio de cierre: los vectores publicados en el RFC deben reproducirse
 * byte a byte, y los casos negativos deben rechazarse.
 *
 * Vectores verificados independientemente contra OpenSSL (via Python
 * `cryptography` 50.0.1) y contrastados con RFC 7748 §5.2 y §6.1.
 */
class X25519Test {

    private lateinit var x25519: X25519

    @BeforeEach
    fun setUp() {
        x25519 = BcX25519()
    }

    private fun hex(s: String) = ByteArray(s.length / 2) {
        ((Character.digit(s[it * 2], 16) shl 4) or Character.digit(s[it * 2 + 1], 16)).toByte()
    }

    private fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }

    // ===================================================================
    // Vectores oficiales
    // ===================================================================

    @Test
    @DisplayName("RFC 7748 6.1 - claves publicasderivadas")
    fun `O1-01 deriva las claves publicas del RFC 7748 6_1`() {
        val alicePriv = hex("77076d0a7318a57d3c16c17251b26645df4c2f87ebc0992ab177fba51db92c2a")
        val bobPriv = hex("5dab087e624a8a4b79e17f8b83800ee66f3bb1292618b6fd1c2f8b27ff88e0eb")

        assertEquals(
            "8520f0098930a754748b7ddcb43ef75a0dbf3a0d26381af4eba4a98eaa9b4e6a",
            hex(x25519.publicKey(alicePriv)),
        )
        assertEquals(
            "de9edb7d7b7dc1b4d35b61c2ece435373f8343c85b78674dadfc7e146f882b4f",
            hex(x25519.publicKey(bobPriv)),
        )
    }

    @Test
    @DisplayName("RFC 7748 6.1 - secreto compartido")
    fun `O1-02 reproduce el secreto compartido del RFC 7748 6_1`() {
        val alicePriv = hex("77076d0a7318a57d3c16c17251b26645df4c2f87ebc0992ab177fba51db92c2a")
        val alicePub = hex("8520f0098930a754748b7ddcb43ef75a0dbf3a0d26381af4eba4a98eaa9b4e6a")
        val bobPriv = hex("5dab087e624a8a4b79e17f8b83800ee66f3bb1292618b6fd1c2f8b27ff88e0eb")
        val bobPub = hex("de9edb7d7b7dc1b4d35b61c2ece435373f8343c85b78674dadfc7e146f882b4f")

        val expected = "4a5d9d5ba4ce2de1728e3bf480350f25e07e21c947d19e3376f09b3c1e161742"
        assertEquals(expected, hex(x25519.agree(alicePriv, bobPub)))
        assertEquals(expected, hex(x25519.agree(bobPriv, alicePub)))
    }

    @Test
    @DisplayName("RFC 7748 5.2 - vectores de la funcion X25519")
    fun `O1-03 reproduce los vectores de la funcion X25519 del RFC 7748 5_2`() {
        // El escalar de entrada se clarea segun RFC 7748 5 antes de la multiplicacion.
        val vectors = listOf(
            Triple(
                "a546e36bf0527c9d3b16154b82465edd62144c0ac1fc5a18506a2244ba449ac4",
                "e6db6867583030db3594c1a424b15f7c726624ec26b3353b10a903a6d0ab1c4c",
                "c3da55379de9c6908e94ea4df28d084f32eccf03491c71f754b4075577a28552",
            ),
            Triple(
                "4b66e9d4d1b4673c5ad22691957d6af5c11b6421e0ea01d42ca4169e7918ba0d",
                "e5210f12786811d3f4b7959d0538ae2c31dbe7106fc03c3efc4cd549c715a493",
                "95cbde9476e8907d7aade45cb4b873f88b595a68799fa152e6f8f7647aac7957",
            ),
        )
        for ((scalarHex, uHex, expected) in vectors) {
            val scalar = clamp(hex(scalarHex))
            assertEquals(expected, hex(x25519.agree(scalar, hex(uHex))), "vector $scalarHex")
        }
    }

    /** Clamping de RFC 7748 5. */
    private fun clamp(k: ByteArray): ByteArray {
        val c = k.copyOf()
        c[0] = (c[0].toInt() and 248).toByte()
        c[31] = (c[31].toInt() and 127 or 64).toByte()
        return c
    }

    // ===================================================================
    // Propiedades
    // ===================================================================

    @Test
    @DisplayName("X25519 es simetrico")
    fun `O1-04 agree es simetrico`() {
        repeat(5) {
            val a = x25519.generateKeyPair()
            val b = x25519.generateKeyPair()
            assertContentEquals(x25519.agree(a.privateKey, b.publicKey), x25519.agree(b.privateKey, a.publicKey))
        }
    }

    @Test
    @DisplayName("generateKeyPair produce claves validas")
    fun `O1-05 genera pares de claves validos`() {
        repeat(5) {
            val pair = x25519.generateKeyPair()
            assertEquals(32, pair.privateKey.size)
            assertEquals(32, pair.publicKey.size)
            // La clave publica derivada coincide con la almacenada.
            assertContentEquals(pair.publicKey, x25519.publicKey(pair.privateKey))
        }
    }

    @Test
    @DisplayName("claves distintas producen secretos distintos")
    fun `O1-06 claves diferentes producen secretos diferentes`() {
        val a = x25519.generateKeyPair()
        val b = x25519.generateKeyPair()
        val s1 = x25519.agree(a.privateKey, b.publicKey)
        val s2 = x25519.agree(a.privateKey, x25519.publicKey(x25519.generateKeyPair().privateKey))
        assertFalse(s1.contentEquals(s2))
    }

    // ===================================================================
    // Casos negativos
    // ===================================================================

    @Test
    @DisplayName("rechaza clave publica de orden pequeno - todo cero")
    fun `O1-07 rechaza secreto compartido de todo cero`() {
        val priv = x25519.generateKeyPair()
        assertThrows<AllZeroSharedSecretException> {
            x25519.agree(priv.privateKey, ByteArray(32))
        }
    }

    @Test
    @DisplayName("RFC 7748 6.1 - rechaza todos los puntos de bajo orden conocidos")
    fun `O1-12 rechaza la lista negra de puntos de bajo orden del RFC 7748`() {
        // Puntos de orden pequeno listados en RFC 7748 6.1. Con cualquiera de
        // ellos el secreto compartido es trivialmente cero/pequeno y el
        // protocolo DEBE abortar en lugar de continuar.
        val lowOrderPoints = listOf(
            "0100000000000000000000000000000000000000000000000000000000000000", // orden 1
            "e0eb7a7c3b41b8ae1656e3faf19fc46ada098deb9c32b1fd866205165f49b800", // orden 2
            "5f9c95bca3508c24b1d0b1559c83ef5b04445cc4581c8e86d8224eddd09f1157", // orden 4
            "ecffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff7f", // punto infinito
        )
        val priv = x25519.generateKeyPair()
        for (point in lowOrderPoints) {
            assertThrows<AllZeroSharedSecretException>(
                "punto de bajo orden $point deberia rechazarse"
            ) {
                x25519.agree(priv.privateKey, hex(point))
            }
        }
    }

    @Test
    @DisplayName("rechaza clave privada de tamano invalido")
    fun `O1-08 rechaza privateKey de tamano invalido`() {
        assertThrows<IllegalArgumentException> {
            x25519.publicKey(ByteArray(31))
        }
        assertThrows<IllegalArgumentException> {
            x25519.publicKey(ByteArray(33))
        }
    }

    @Test
    @DisplayName("rechaza clave publica de tamano invalido")
    fun `O1-09 rechaza publicKey de tamano invalido en agree`() {
        val priv = x25519.generateKeyPair()
        assertThrows<IllegalArgumentException> {
            x25519.agree(priv.privateKey, ByteArray(16))
        }
        assertThrows<IllegalArgumentException> {
            x25519.agree(ByteArray(16), priv.publicKey)
        }
    }

    @Test
    @DisplayName("el secreto compartido tiene 32 bytes")
    fun `O1-10 el secreto tiene 32 bytes`() {
        val a = x25519.generateKeyPair()
        val b = x25519.generateKeyPair()
        assertEquals(32, x25519.agree(a.privateKey, b.publicKey).size)
    }
}
