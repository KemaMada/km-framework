package com.km.desktop.contact

import com.km.desktop.support.SecureTestIdentities
import com.km.identity.KmIdVerificationException
import com.km.model.IdentityId
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * 2.1 — Importar un bundle es VERIFICAR, y solo verificar.
 *
 * ## LOS TRES CASOS
 *
 *  - Bundle valido -> `identityRoot` igual a la raiz que lo firma.
 *  - UN byte alterado del sobre -> `KmIdVerificationException`. El byte se
 *    altera en el TEXTO base64url, no en el mapa decodificado: es lo que llega
 *    por QR o por teclado, y alterarlo ahi es lo que rompe la firma.
 *  - Raiz esperada DISTINTA -> `KmIdVerificationException`. Es la proteccion
 *    contra importar el bundle equivocado cuando se ha pedido uno concreto.
 */
class ContactImporterTest {

    private val parBob = SecureTestIdentities.deviceWithRoot("bob")
    private val parMallory = SecureTestIdentities.deviceWithRoot("mallory")
    private val bob: SecureTestIdentities.Device get() = parBob.device
    private val bobRoot: SecureTestIdentities.IdentityRoot get() = parBob.root
    private val mallory: SecureTestIdentities.Device get() = parMallory.device
    private val malloryRoot: SecureTestIdentities.IdentityRoot get() = parMallory.root

    private fun bundleDeBob(): String {
        bob.issueOneTimePreKey(SecureTestIdentities.ONE_TIME_PREKEY_ID)
        return bobRoot.bundleTextFor(
            devices = listOf(bob),
            subject = bob,
            oneTimePreKeyIds = listOf(SecureTestIdentities.ONE_TIME_PREKEY_ID),
        )
    }

    @Test
    fun `un bundle valido se importa con la raiz que lo firma`() {
        val contacto = ContactImporter.import(bundleDeBob())

        assertEquals(
            bobRoot.identityId.value,
            contacto.identityRoot.value,
            "la identidad del contacto es su RAIZ",
        )
        assertNotEquals(
            bob.deviceId.toHexPublico(),
            contacto.identityRoot.value,
            "y NO el deviceId: la clave de ruteo es la RAIZ, no el dispositivo",
        )
        assertEquals(bob.deviceId.toHexPublico(), contacto.subjectDeviceId, "el deviceId se guarda aparte")
        assertTrue(contacto.fingerprint.isNotEmpty(), "y hay una huella para mostrar")
    }

    @Test
    fun `un byte alterado del sobre se RECHAZA`() {
        val texto = bundleDeBob()
        // El sobre es base64url: se altera UN caracter en el medio, y a una
        // posicion que no es ni el primero ni el ultimo.
        val corte = texto.length / 2
        val alterado = texto.substring(0, corte) +
            (if (texto[corte] == 'A') 'B' else 'A') +
            texto.substring(corte + 1)

        val fallo = assertThrows(KmIdVerificationException::class.java) {
            ContactImporter.import(alterado)
        }
        assertTrue(fallo.code.isNotEmpty(), "el rechazo trae un codigo, no un 'error': ${fallo.code}")
    }

    @Test
    fun `una raiz esperada distinta se RECHAZA`() {
        val fallo = assertThrows(KmIdVerificationException::class.java) {
            ContactImporter.import(bundleDeBob(), expectedIdentityRoot = malloryRoot.keyPair.publicKey)
        }
        assertEquals(
            "IDENTITY_MISMATCH",
            fallo.code,
            "el rechazo trae el codigo del verificador, no un 'error' generico: ${fallo.message}",
        )
    }

    @Test
    fun `la raiz esperada CORRECTA se acepta`() {
        val contacto = ContactImporter.import(bundleDeBob(), expectedIdentityRoot = bobRoot.keyPair.publicKey)

        assertEquals(bobRoot.identityId, contacto.identityRoot, "la comprobacion no rechaza al bundle correcto")
    }

    @Test
    fun `un sobre vacio se RECHAZA sin llegar al verificador`() {
        assertThrows(IllegalArgumentException::class.java) { ContactImporter.import("   ") }
    }

    @Test
    fun `el registro deduplica por raiz y reverifica al guardar`() {
        val libro = InMemoryContactBook()
        val contacto = ContactImporter.import(bundleDeBob())

        libro.upsert(contacto)
        libro.upsert(contacto)
        assertEquals(1, libro.all().size, "importar dos veces el mismo contacto no duplica la conversacion")

        val guardado = requireNotNull(libro.find(contacto.identityRoot)) {
            "el contacto guardado se busca por su raiz"
        }
        assertEquals(contacto.identityRoot, guardado.identityRoot, "y conserva la misma clave de ruteo")
        assertEquals(
            bob.deviceId.toHexPublico(),
            guardado.subjectDeviceId,
            "la clave publica de firma tambien sobrevive al almacenamiento",
        )

        // Un contacto guardado con un sobre que ya no verifica NO entra: el
        // registro reverifica en vez de confiar en el objeto que le llega.
        assertThrows(Exception::class.java) { libro.upsert(contacto.copy(bundleText = "no-es-un-sobre")) }

        libro.remove(contacto.identityRoot)
        assertNull(libro.find(contacto.identityRoot), "tras borrar no queda")
    }

    /**
     * CONTROL: dos bundles distintos dan contactos distintos.
     *
     * El pretesto de "el byte alterado se rechaza": si los dos sobres fueran
     * iguales, alterarlo no probaria nada.
     */
    @Test
    fun `control dos bundles distintos dan contactos distintos`() {
        val bundleA = bundleDeBob()
        val bundleB = malloryRoot.bundleTextFor(listOf(mallory), mallory, emptyList())

        assertNotEquals(bundleA, bundleB, "los dos sobres son de verdad distintos")
        assertNotEquals(
            ContactImporter.import(bundleA).identityRoot,
            ContactImporter.import(bundleB).identityRoot,
            "y dan contactos distintos",
        )
    }

    /**
     * CONTROL NEGATIVO: el bundle REAL de esta arnes tiene firma, no ceros.
     *
     * Sin esto, "un byte alterado se rechaza" seria verdad tambien si el
     * verificador rechazara TODO,_bundle bueno incluido. Se comprueba que el
     * camino bueno pasa y que el malo es el que falla.
     */
    @Test
    fun `control negativo el verificador acepta lo bueno y rechaza lo roto, no todo`() {
        val bueno = bundleDeBob()
        assertNotNull(ContactImporter.import(bueno), "el bundle real verifica")

        val corte = bueno.length / 2
        val roto = bueno.substring(0, corte) +
            (if (bueno[corte] == 'A') 'B' else 'A') +
            bueno.substring(corte + 1)
        assertThrows(KmIdVerificationException::class.java) { ContactImporter.import(roto) }
    }

    /** Hex de un `ByteArray` en el test, sin tocar el `internal` del main. */
    private fun ByteArray.toHexPublico(): String = joinToString("") { "%02x".format(it) }
}

/** Compueba que el tipo de contacto no imprime el sobre. */
class VerifiedContactToStringTest {

    @Test
    fun `el toString del contacto NO vuelca el sobre ni la clave`() {
        val par = SecureTestIdentities.deviceWithRoot("bob")
        val texto = par.root.bundleTextFor(listOf(par.device), par.device, emptyList())
        val contacto = ContactImporter.import(texto)

        val impreso = contacto.toString()
        assertTrue(contacto.subjectSigningKey !in impreso, "la clave de firma no sale en el toString")
        assertTrue(texto.take(24) !in impreso, "ni el sobre entero")
        assertEquals("VerifiedContact(${contacto.fingerprint})", impreso)
    }

    @Test
    fun `la huella es un prefijo corto de la raiz, no la raiz entera`() {
        val par = SecureTestIdentities.deviceWithRoot("bob")
        val contacto = ContactImporter.import(par.root.bundleTextFor(listOf(par.device), par.device, emptyList()))

        assertTrue(
            contacto.identityRoot.value.startsWith(contacto.fingerprint.removeSuffix("…")),
            "la huella es el principio de la identidad",
        )
        assertTrue(contacto.fingerprint.length < contacto.identityRoot.value.length, "y es CORTA")
    }

    /** El identificador es lo que se indexa; que no sea vacio es lo que importa. */
    @Test
    fun `la identidad del contacto es un IdentityId de 64 hex`() {
        val par = SecureTestIdentities.deviceWithRoot("bob")
        val contacto = ContactImporter.import(par.root.bundleTextFor(listOf(par.device), par.device, emptyList()))

        assertEquals(64, contacto.identityRoot.value.length, "64 hex, como exige el dialecto")
        assertTrue(
            contacto.identityRoot.value.all { it in "0123456789abcdef" },
            "y todos son hex: ${contacto.identityRoot}",
        )
        assertEquals(
            par.root.identityId,
            IdentityId(contacto.identityRoot.value),
            "y es la identidad que el verificador firmo",
        )
    }
}