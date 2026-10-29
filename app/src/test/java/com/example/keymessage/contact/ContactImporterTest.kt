package com.example.keymessage.contact

import com.km.identity.KmIdSchemaException
import com.km.identity.KmIdVerificationException
import com.km.model.IdentityId
import com.example.keymessage.support.TestWorld
import com.example.keymessage.util.KMLog
import com.example.keymessage.util.toHex
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 1.3 — Importar un bundle es VERIFICAR, y solo verificar.
 *
 * ## LOS TRES CASOS
 *
 *  - Bundle valido -> `identityRoot` igual a la raiz que lo firma.
 *  - UN byte alterado del sobre -> `KmIdVerificationException`. El byte se altera
 *    en el TEXTO base64url, no en el mapa decodificado: es lo que llega por QR o
 *    por teclado, y alterarlo ahi es lo que rompe la firma.
 *  - Raiz esperada DISTINTA -> `KmIdVerificationException`. Es la proteccion
 *    contra importar el bundle equivocado.
 */
class ContactImporterTest {

    private val parBob = TestWorld.deviceWithRoot("bob")
    private val parMallory = TestWorld.deviceWithRoot("mallory")
    private val bob: TestWorld.Device get() = parBob.device
    private val bobRoot: TestWorld.IdentityRoot get() = parBob.root
    private val mallory: TestWorld.Device get() = parMallory.device
    private val malloryRoot: TestWorld.IdentityRoot get() = parMallory.root

    private fun bundleDeBob(): String {
        bob.issueOneTimePreKey(TestWorld.BOB_ONE_TIME_PREKEY_ID)
        return bobRoot.bundleTextFor(
            devices = listOf(bob),
            subject = bob,
            oneTimePreKeyIds = listOf(TestWorld.BOB_ONE_TIME_PREKEY_ID),
        )
    }

    @Test
    fun `un bundle valido se importa con la raiz que lo firma`() {
        val contacto = ContactImporter.import(bundleDeBob())

        assertEquals(bobRoot.identityId.value, contacto.identityRoot.value, "la identidad del contacto es su RAIZ")
        assertNotEquals(
            IdentityId(bob.deviceId.toHex()).value,
            contacto.identityRoot.value,
            "y NO el deviceId: la clave de ruteo es la RAIZ, no el dispositivo",
        )
        assertEquals(bob.deviceId.toHex(), contacto.subjectDeviceId, "el deviceId se guarda aparte")
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

        val fallo = assertFailsWith<Exception> { ContactImporter.import(alterado) }
        assertTrue(fallo is com.km.identity.Kce.KceException || fallo is KmIdVerificationException, "el rechazo trae un codigo, no un 'error'")
    }

    @Test
    fun `una raiz esperada distinta se RECHAZA`() {
        val fallo = assertFailsWith<KmIdVerificationException> {
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

        assertEquals(bobRoot.identityId, contacto.identityRoot, "la comprobacion no rechaza alBundle correcto")
    }

    @Test
    fun `un sobre vacio se RECHAZA sin llegar al verificador`() {
        assertFailsWith<IllegalArgumentException> { ContactImporter.import("   ") }
    }

    /**
     * CRITICAL #3 — Un sobre MALFORMADO no es una `KmIdVerificationException` y
     * aun asi tiene que producir un rechazo VISIBLE.
     */
    @Test
    fun `un sobre malformado se RECHAZA con un codigo, no en silencio`() {
        // "no-es-un-sobre" no es base64url: falla al DECODIFICAR, antes de que
        // exista una firma que comprobar. La excepcion es `KceException`, NO
        // `KmIdVerificationException`.
        val fallo = assertFailsWith<Exception> { ContactImporter.import("no-es-un-sobre") }

        assertTrue(
            fallo !is KmIdVerificationException,
            "el pretesto: un sobre malformado NO es el verificador de firmas (${fallo::class.simpleName})",
        )
        val codigo = rejectionCodeOf(fallo)
        assertTrue(codigo.isNotBlank(), "aun asi tiene codigo: '$codigo'")
        assertTrue(
            codigo != "IMPORT_FAILED",
            "y el codigo viene del DECODIFICADOR, no del cajon de sastre: '$codigo'",
        )
    }

    @Test
    fun `todo fallo de importacion tiene un codigo y ninguno queda en null`() {
        // La tabla de fallos que la pantalla puede recibir. Si alguno diera `null`,
        // el detalle no se pintaria y pulsar IMPORTAR no cambiaria nada visible.
        val fallos = listOf(
            IllegalArgumentException("el sobre del contacto esta vacio"),
            com.km.identity.Kce.KceException("ENVELOPE_MALFORMED", "no es base64url"),
            com.km.identity.KmIdSchemaException("MISSING_FIELD", "falta algo"),
            KmIdVerificationException("INVALID_SIGNATURE", "la firma no cuadra"),
            IllegalStateException("vaya"),
        )

        fallos.forEach { fallo ->
            val codigo = rejectionCodeOf(fallo)
            assertTrue(codigo.isNotBlank(), "${fallo::class.simpleName} da codigo, no '$codigo'")
        }
        assertEquals("ENVELOPE_MALFORMED", rejectionCodeOf(com.km.identity.Kce.KceException("ENVELOPE_MALFORMED")))
        assertEquals("INVALID_SIGNATURE", rejectionCodeOf(KmIdVerificationException("INVALID_SIGNATURE")))
        assertEquals("IMPORT_FAILED", rejectionCodeOf(IllegalStateException("vaya")), "y hay un codigo por defecto, no null")
    }

    @Test
    fun `un sobre vacio tambien se registra en CONTACT`() {
        val emitidas = mutableListOf<String>()
        KMLog.clear()
        KMLog.install(object : com.example.keymessage.util.LogSink {
            override fun log(level: com.example.keymessage.util.KMLog.Level, tag: String, message: String) {
                emitidas += message
            }
        })
        try {
            runCatching { ContactImporter.import("   ") }
            assertTrue(
                emitidas.any { it.contains("[CONTACT]") && it.contains("EMPTY_ENVELOPE") },
                "pulsar IMPORTAR con el campo vacio deja rastro: $emitidas",
            )
        } finally {
            KMLog.install(com.example.keymessage.util.AndroidLogSink())
        }
    }

    @Test
    fun `un sobre malformado se registra en CONTACT`() {
        val emitidas = mutableListOf<String>()
        KMLog.clear()
        KMLog.install(object : com.example.keymessage.util.LogSink {
            override fun log(level: com.example.keymessage.util.KMLog.Level, tag: String, message: String) {
                emitidas += message
            }
        })
        try {
            runCatching { ContactImporter.import("no-es-un-sobre") }
            assertTrue(
                emitidas.any { it.contains("[CONTACT]") && it.contains("rechazado") },
                "un sobre malformado se registra como rechazo, no se traga: $emitidas",
            )
        } finally {
            KMLog.install(com.example.keymessage.util.AndroidLogSink())
        }
    }

    @Test
    fun `el registro deduplica por raiz y reverifica al guardar`() {
        val libro = InMemoryContactBook()
        val contacto = ContactImporter.import(bundleDeBob())

        libro.upsert(contacto)
        libro.upsert(contacto)
        assertEquals(1, libro.all().size, "importar dos veces el mismo contacto no duplica la conversacion")
        val guardado = assertNotNull(libro.find(contacto.identityRoot), "el contacto guardado se busca por su raiz")
        assertEquals(contacto.identityRoot, guardado.identityRoot, "y conserva la misma clave de ruteo")
        assertEquals(
            bob.deviceId.toHex(),
            guardado.subjectDeviceId,
            "la clave publica de firma tambien sobrevive al almacenamiento",
        )

        // Un contacto guardado con un sobre que ya no verifica NO entra: el
        // registro reverifica en vez de confiar en el objeto que le llega.
        val conSobreRoto = contacto.copy(bundleText = "no-es-un-sobre")
        assertFailsWith<Exception> { libro.upsert(conSobreRoto) }

        libro.remove(contacto.identityRoot)
        assertNull(libro.find(contacto.identityRoot), "tras borrar no queda")
    }

    @Test
    fun `un sobre con salto de linea final se acepta`() {
        val texto = bundleDeBob()
        val conNl = texto + "\n"
        val contacto = ContactImporter.import(conNl)

        assertEquals(conNl, contacto.bundleText, "el texto canonico conserva el salto de linea final")
        // Y reverifica al guardar: el '\\n' colgante no rompe la descompresion.
        val libro = InMemoryContactBook()
        libro.upsert(contacto)
        assertEquals(1, libro.all().size)
    }

    @Test
    fun `un sobre con espacios finales se acepta`() {
        val texto = bundleDeBob()
        val conEspacios = texto + "   "
        val contacto = ContactImporter.import(conEspacios)

        assertEquals(conEspacios, contacto.bundleText, "el texto canonico conserva los espacios finales")
        val libro = InMemoryContactBook()
        libro.upsert(contacto)
    }

    @Test
    fun `un payload cuyo deflate termina en whitespace se acepta`() {
        // Busca por fuerza bruta un sobre valido cuyo portador termine en un
        // caracter que `trim()` se comeria. Con el trim destructivo restaurado,
        // este caso falla con ENVELOPE_MALFORMED.
        var encontrado: String? = null
        for (i in 0 until 5000) {
            val par = TestWorld.deviceWithRoot("ws$i")
            val dev = par.device
            val root = par.root
            val texto = root.bundleTextFor(listOf(dev), dev, emptyList())
            if (texto.isNotEmpty() && texto.last().isWhitespace()) {
                encontrado = texto
                break
            }
        }
        assertNotNull(encontrado, "debe existir un payload cuyo portador termine en whitespace")
        val contacto = ContactImporter.import(encontrado!!)
        assertNotNull(contacto)
        assertEquals(encontrado, contacto.bundleText, "sin trim destructivo el portador se conserva intacto")
        // Reverificacion al guardar tambien pasa.
        val libro = InMemoryContactBook()
        libro.upsert(contacto)
        assertEquals(1, libro.all().size)
    }

    @Test
    fun `control dos bundle distintos dan contactos distintos`() {
        // El pretesto: si las dos raices dieran el mismo texto, "el byte alterado
        // se rechaza" no probaria nada.
        val bundleA = bundleDeBob()
        val bundleB = malloryRoot.bundleTextFor(listOf(mallory), mallory, emptyList())

        assertNotEquals(bundleA, bundleB, "los dos sobres son de verdad distintos")
        assertNotEquals(
            ContactImporter.import(bundleA).identityRoot,
            ContactImporter.import(bundleB).identityRoot,
            "y dan contactos distintos",
        )
    }
}