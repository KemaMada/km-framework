package com.keymessage.core.crypto

import com.keymessage.core.crypto.provider.BcX25519
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.test.assertContentEquals

/**
 * 3Q.5.2a — Representacion de la clave DH que sobrevive a un round-trip.
 *
 * AUDITORIA PREVIA (why este archivo existe)
 *
 * Antes de escribir nada se audito que clave usa ya el proyecto:
 *
 *  - `X25519KeyPair` (`crypto/X25519.kt`) es la UNICA representacion de par
 *    X25519 de km-core. La usan X3DH (`InitiatorKeyMaterial`,
 *    `ResponderKeyMaterial`), el bootstrap del ratchet y las pruebas.
 *  - `BcX25519.generateKeyPair()` construye el par como
 *    `X25519KeyPair(aleatorio, publicKey(aleatorio))`: la publica SIEMPRE es la
 *    derivacion de la privada. Es el contrato declarado de `X25519`, y lo
 *    fija `X25519Test` (O1-05).
 *  - NO existe ninguna serializacion de claves privadas en el repositorio. Lo
 *    unico que viaja son claves PUBLICAS: en crudo dentro del header de
 *    SecureFrame (32 B, offsets fijos, enteros grandes) y en base64url dentro
 *    de los codecs JSON.
 *
 * Conclusion: no habia nada que reutilizar para el estado privado, asi que la
 * representacion se ha creado EN TORNO al tipo que ya existia, sin inventar
 * otro formato de clave. Y se apoya en la unica propiedad que el proyecto ya
 * garantizaba de sus claves: `publicKey(privateKey)`.
 *
 * POR QUE ESTAS PRUEBAS USAN EL BACKEND REAL
 *
 * Lo que se comprueba aqui es el CONTRATO de la primitiva (`publicKey(sk)` es
 * determinista, coincide con el par generado, y rechaza longitudes invalidas),
 * asi que el doble de prueba seria inappropriate: probaria el doble, no el
 * contrato.
 */
class DerivedX25519KeyPairTest {

    private lateinit var x25519: X25519

    @BeforeEach
    fun setUp() {
        x25519 = BcX25519()
    }

    private fun hex(s: String) = ByteArray(s.length / 2) {
        ((Character.digit(s[it * 2], 16) shl 4) or Character.digit(s[it * 2 + 1], 16)).toByte()
    }

    // ===================================================================
    // Round-trip
    // ===================================================================

    @Test
    @DisplayName("KEY-01 el round-trip devuelve el MISMO par, mitad privada y publica")
    fun `KEY-01 round-trip devuelve el mismo par`() {
        // El round-trip es la propiedad de la que depende toda la costura de
        // persistencia: si al volver de una foto la clave publica fuese
        // distinta, el receptor veria una DH distinta y derivaria otras claves
        // de cadena. Seria un fallo de seguridad, no un fallo de pruebas.
        repeat(20) {
            val original = x25519.generateKeyPair()
            val roundTrip = DerivedX25519KeyPair.from(original, x25519).toKeyPair()

            assertContentEquals(original.privateKey, roundTrip.privateKey, "escalar privado")
            assertContentEquals(original.publicKey, roundTrip.publicKey, "clave publica")
        }
    }

    @Test
    @DisplayName("KEY-02 RFC 7748: del escalar publicado a la clave publicada")
    fun `KEY-02 vectores del RFC 7748`() {
        // Si la representacion derivase la publica de cualquier otra manera,
        // estos vectores la delatarian. Son los mismos que usa `X25519Test`
        // (RFC 7748 6.1), y fijan la correspondencia desde material published.
        val alicePriv = hex("77076d0a7318a57d3c16c17251b26645df4c2f87ebc0992ab177fba51db92c2a")
        val bobPriv = hex("5dab087e624a8a4b79e17f8b83800ee66f3bb1292618b6fd1c2f8b27ff88e0eb")

        val alice = DerivedX25519KeyPair.derive(alicePriv, x25519)
        val bob = DerivedX25519KeyPair.derive(bobPriv, x25519)

        assertEquals(
            "8520f0098930a754748b7ddcb43ef75a0dbf3a0d26381af4eba4a98eaa9b4e6a",
            alice.publicKeyBytes().joinToString("") { "%02x".format(it) },
        )
        assertEquals(
            "de9edb7d7b7dc1b4d35b61c2ece435373f8343c85b78674dadfc7e146f882b4f",
            bob.publicKeyBytes().joinToString("") { "%02x".format(it) },
        )
        // Y el round-trip de lo derivado vuelve a derivar lo mismo.
        assertContentEquals(alice.publicKeyBytes(), DerivedX25519KeyPair.from(alice.toKeyPair(), x25519).publicKeyBytes())
    }

    @Test
    @DisplayName("KEY-03 la clave publica es SIEMPRE la derivacion de la privada")
    fun `KEY-03 la publica es f de la privada`() {
        repeat(20) {
            val state = DerivedX25519KeyPair.derive(x25519.generateKeyPair().privateKey, x25519)
            assertContentEquals(
                x25519.publicKey(state.privateKeyBytes()),
                state.publicKeyBytes(),
                "la publica no puede ser otra cosa que f(la privada)",
            )
        }
    }

    @Test
    @DisplayName("KEY-04 derivar dos veces del mismo escalar da los mismos bytes")
    fun `KEY-04 la derivacion es determinista`() {
        val scalar = x25519.generateKeyPair().privateKey
        val a = DerivedX25519KeyPair.derive(scalar, x25519)
        val b = DerivedX25519KeyPair.derive(scalar, x25519)
        assertEquals(a, b, "el mismo escalar es el mismo estado")
        assertEquals(a.hashCode(), b.hashCode())
        assertContentEquals(a.publicKeyBytes(), b.publicKeyBytes())
    }

    // ===================================================================
    // Rechazo de material incoherente
    // ===================================================================

    @Test
    @DisplayName("KEY-05 rechaza un par cuya publica no deriva de su privada")
    fun `KEY-05 rechaza un par incoherente`() {
        // Este es el fallo que la representacion existe para hacer imposible
        // en silencio. Un par con mitades incoherentes daria un secreto DH
        // distinto del que su propia clave publica anuncia.
        val a = x25519.generateKeyPair()
        val b = x25519.generateKeyPair()
        val incoherente = X25519KeyPair(a.privateKey, b.publicKey)

        assertThrows<IllegalArgumentException> {
            DerivedX25519KeyPair.from(incoherente, x25519)
        }
    }

    @Test
    @DisplayName("KEY-06 rechaza escalares de longitud invalida")
    fun `KEY-06 rechaza longitudes invalidas`() {
        assertThrows<IllegalArgumentException> {
            DerivedX25519KeyPair.derive(ByteArray(31), x25519)
        }
        assertThrows<IllegalArgumentException> {
            DerivedX25519KeyPair.derive(ByteArray(33), x25519)
        }
    }

    // ===================================================================
    // Aislamiento del estado
    // ===================================================================

    @Test
    @DisplayName("KEY-07 las mitades se entregan por copia: el estado no se puede alterar")
    fun `KEY-07 copias defensivas`() {
        // Un estado que se pudiera modificar desde fuera dejaria de ser la foto
        // que se tomo: dos sesiones restauradas de la misma foto podrian
        // acabar con claves distintas.
        val state = DerivedX25519KeyPair.from(x25519.generateKeyPair(), x25519)
        val original = state.toKeyPair()

        state.privateKeyBytes().fill(0)
        state.publicKeyBytes().fill(0)

        assertContentEquals(original.privateKey, state.privateKeyBytes(), "el escalar sigue intacto")
        assertContentEquals(original.publicKey, state.publicKeyBytes(), "la publica sigue intacta")

        // Dos llamadas seguidas devuelven arrays distintos.
        assertNotSame(state.privateKeyBytes(), state.privateKeyBytes())
        assertNotSame(state.toKeyPair(), state.toKeyPair())
    }

    @Test
    @DisplayName("KEY-08 toString no imprime el escalar privado")
    fun `KEY-08 toString no filtra el escalar`() {
        val pair = x25519.generateKeyPair()
        val printed = DerivedX25519KeyPair.from(pair, x25519).toString()

        val privateHex = pair.privateKey.joinToString("") { "%02x".format(it) }
        assertFalse(printed.contains(privateHex), "el escalar no puede acabar en un log")
        assertTrue(
            printed.contains(pair.publicKey.joinToString("") { "%02x".format(it) }),
            "la publica si es util para diagnosticar",
        )
    }
}
