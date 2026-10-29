package com.example.keymessage.identity

import com.km.identity.KmIds
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import com.example.keymessage.util.toHex

/**
 * 1.1 — La identidad se deriva con la REGLA de KM-ID-0001, no con una regla de la
 * app.
 *
 * ## LO QUE SE AFIRMA
 *
 *  - `identityId = SHA-256("KM-ID-IDENTITY" || pub)`, en hex: 64 caracteres.
 *  - Dos semillas distintas dan identidades distintas, y la MISMA semilla repetida
 *    da SIEMPRE la misma. Lo segundo es lo que hace que un test pueda sembrar
 *    claves; sin determinismo, "dos claves distintas" solo probaria azar.
 *
 * ## POR QUE NO SE USA `deviceId`
 *
 * `KmIds` deriva tres identificadores de tres dominios distintos y ninguno es
 * alias de otro. `deviceId` es de la clave de FIRMA de un dispositivo; el
 * `identityId` es de la RAIZ de una identidad. Usar el primero para lo que el
 * framework enruta por el segundo daria dos claves de ruteo para la misma
 * conversacion —el fallo de p2, R-2.
 */
class IdentityDerivationTest {

    private val factory = IdentityMaterialFactory()

    /** Semilla NO constante: todos sus bytes distintos y en ascension. */
    private fun semilla(distintiva: Int): ByteArray = ByteArray(32) { ((it * 31 + distintiva * 7) % 251 + 3).toByte() }

    @Test
    fun `el identityId son 64 hex derivados de la clave publica`() {
        val material = factory.fromSeed(semilla(1))

        assertEquals(
            KmIds.identityId(material.keyPair.publicKey).toHex(),
            material.identityId.value,
            "la app deriva con KmIds.identityId, no con su propia regla",
        )
        assertEquals(64, material.identityId.value.length, "SHA-256 son 32 bytes: 64 hex")
        assertTrue(
            material.identityId.value.all { it in "0123456789abcdef" },
            "en minusculas y sin prefijo: ${material.identityId.value}",
        )
    }

    @Test
    fun `dos semillas distintas dan identidades distintas`() {
        val a = factory.fromSeed(semilla(1))
        val b = factory.fromSeed(semilla(2))

        assertNotEquals(a.identityId.value, b.identityId.value, "dos claves distintas, dos identidades")
        assertNotEquals(
            a.keyPair.privateKey.toHex(),
            b.keyPair.privateKey.toHex(),
            "y las semillas son distintas de verdad (pretesto)",
        )
    }

    @Test
    fun `la misma semilla repetida da la misma identidad`() {
        val primera = factory.fromSeed(semilla(9))
        val segunda = factory.fromSeed(semilla(9))

        assertEquals(
            primera.identityId.value,
            segunda.identityId.value,
            "la derivacion es determinista: es lo que hace que un test pueda sembrar claves",
        )
        assertTrue(
            primera.keyPair.publicKey.contentEquals(segunda.keyPair.publicKey),
            "y la clave publica tambien",
        )
    }

    @Test
    fun `el identityId NO es el deviceId del mismo par`() {
        val material = factory.fromSeed(semilla(3))

        assertNotEquals(
            KmIds.deviceId(material.keyPair.publicKey).toHex(),
            material.identityId.value,
            "identidad y dispositivo son dominios distintos: confundirlos rompe el ruteo",
        )
    }

    @Test
    fun `generar produce una identidad usable`() {
        val material = factory.generate()

        assertEquals(64, material.identityId.value.length, "una identidad generada tambien es un identityId completo")
        assertEquals(32, material.keyPair.privateKey.size, "la clave privada es la semilla de 32 bytes")
        assertEquals(32, material.keyPair.publicKey.size, "Ed25519: 32 bytes de clave publica")
    }

    @Test
    fun `dos generaciones producen claves distintas`() {
        val a = IdentityMaterialFactory().generate()
        val b = IdentityMaterialFactory().generate()

        assertNotEquals(
            a.keyPair.publicKey.toHex(),
            b.keyPair.publicKey.toHex(),
            "cada dispositivo genera su propia clave: dos identidades no pueden compartir clave de firma",
        )
        assertNotEquals(
            a.identityId.value,
            b.identityId.value,
            "y por tanto su identityId tambien es distinto",
        )
    }

    @Test
    fun `toString no imprime el material`() {
        val material = factory.fromSeed(semilla(5))
        val texto = material.toString()

        assertTrue(texto.contains(material.identityId.value), "identifica la identidad")
        assertTrue(
            !texto.contains(material.keyPair.privateKey.toHex()),
            "y no imprime la semilla, ni siquiera truncada",
        )
    }
}