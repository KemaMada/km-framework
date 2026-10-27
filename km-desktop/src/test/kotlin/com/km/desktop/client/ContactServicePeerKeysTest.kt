package com.km.desktop.client

import com.km.desktop.PeerKeyDirectory
import com.km.identity.CarrierCodec
import com.km.identity.ContactImportException
import com.km.identity.ContactIntake
import com.km.identity.InMemoryContactRegistry
import com.km.identity.KmIds
import com.km.model.IdentityId
import com.km.transport.contact.ContactExchangeCoordinator
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * El registro de la clave de FIRMA del contacto, en el servicio de producto.
 *
 * ## QUE SE AFIRMA (F7.1)
 *
 * - Un import ACEPTADO deja la clave en [PeerKeyDirectory], que es de donde sale
 *   el resolver de ACK del nucleo. Antes de F7 habia que registrarla a mano y el
 *   REPL no lo hacia: un `ACK` autentico no se podia verificar.
 * - Un portador RECHAZADO no escribe NADA: ni contacto ni clave. Una clave de un
 *   bundle que no verifico autenticaria `ACK` de una identidad ajena.
 * - Re-importar el mismo bundle es idempotente: la misma clave, no una segunda.
 *
 * Se usan los portadores REALES de Android: son los que el usuario pega.
 */
class ContactServicePeerKeysTest {

    private val localIdentity = IdentityId(KmIds.identityId(ByteArray(32) { it.toByte() })
        .joinToString("") { "%02x".format(it) })

    private fun golden(nombre: String): String =
        javaClass.getResourceAsStream("/carriers/$nombre")!!.readBytes().toString(Charsets.ISO_8859_1)

    private inner class Fixture(
        val peerKeys: PeerKeyDirectory = PeerKeyDirectory(),
    ) {
        val registry = InMemoryContactRegistry()
        val events = ClientEvents()
        val service = ContactService(
            events = events,
            contacts = registry,
            intake = ContactIntake(registry) { localIdentity },
            stack = { null },
            sessionRegistry = { null },
            identity = { null },
            deviceKeys = { null },
            peerKeys = { peerKeys },
        )

        /** El coordinador solo hace falta en el camino ACEPTADO de `receiveBundle`. */
        val coordinator = ContactExchangeCoordinator(
            conocido = { emisor -> registry.find(emisor) != null },
            esPrekey = { wire -> com.km.frame.PrekeyFrame.esPrekey(wire) },
        )
    }

    @Test
    fun `importText aceptado registra la clave de firma bajo la raiz`() {
        val f = Fixture()
        val text = golden("s8plus-1e9a73f21347.km")

        val contacto = f.service.importText(text)

        val registrada = assertNotNull(
            f.peerKeys.signingKeyOf(contacto.identityRoot),
            "el import registra la clave con la que el contacto firma sus ACK",
        )
        val delBundle = CarrierCodec.decode(text).subjectSigningKey
        assertTrue(registrada.contentEquals(delBundle), "es la clave del bundle verificado, byte a byte")
        assertEquals(setOf(contacto.identityRoot), f.peerKeys.known())
    }

    @Test
    fun `re-importar el mismo bundle es idempotente`() {
        val f = Fixture()
        val text = golden("mediumphone-aeb864889b56.km")

        val primero = f.service.importText(text)
        val clavePrimera = assertNotNull(f.peerKeys.signingKeyOf(primero.identityRoot))
        val segundo = f.service.importText(text)

        assertEquals(primero.identityRoot, segundo.identityRoot)
        val claveSegunda = assertNotNull(f.peerKeys.signingKeyOf(segundo.identityRoot))
        assertTrue(clavePrimera.contentEquals(claveSegunda), "misma clave, no una segunda")
        assertEquals(1, f.peerKeys.known().size, "un solo registro")
        assertEquals(1, f.service.all().size, "y un solo contacto")
    }

    @Test
    fun `un portador rechazado no escribe contacto ni clave`() {
        val f = Fixture()

        val fallo = assertFailsWith<ContactImportException> {
            f.service.importText("esto no es un portador")
        }

        assertEquals(CarrierCodec.CODE_MALFORMED, fallo.code)
        assertTrue(f.peerKeys.known().isEmpty(), "cero claves: el rechazo no envenena el directorio")
        assertEquals(0, f.service.all().size, "y cero contactos")
    }

    @Test
    fun `un byte alterado de un golden tampoco registra clave`() {
        val f = Fixture()
        val text = golden("s8plus-1e9a73f21347.km")
        val alterado = text.toCharArray().let { chars ->
            // Un caracter del cuerpo comprimido, no el primero: cambiar el
            // primero puede dejar un envelope ilegible que igual se rechaza.
            chars[chars.size / 2] = if (chars[chars.size / 2] == 'A') 'B' else 'A'
            String(chars)
        }

        assertFailsWith<ContactImportException> { f.service.importText(alterado) }
        assertTrue(f.peerKeys.known().isEmpty(), "un bundle que no verifica no registra clave")
        assertEquals(0, f.service.all().size)
    }

    @Test
    fun `receiveBundle aceptado registra la clave del emisor`() {
        val f = Fixture()
        val text = golden("s8plus-1e9a73f21347.km")
        val emisor = IdentityId(
            KmIds.identityId(CarrierCodec.decode(text).identityRoot).joinToString("") { "%02x".format(it) },
        )

        assertTrue(f.service.receiveBundle(emisor, text, f.coordinator))

        assertNotNull(f.peerKeys.signingKeyOf(emisor), "el contact_exchange aceptado registra la clave")
        assertEquals(1, f.service.all().size)
    }

    @Test
    fun `receiveBundle con raiz ajena no registra clave`() {
        val f = Fixture()
        val text = golden("s8plus-1e9a73f21347.km")
        val impostor = IdentityId("cd".repeat(32))

        assertTrue(!f.service.receiveBundle(impostor, text, f.coordinator), "root != from se rechaza")

        assertTrue(f.peerKeys.known().isEmpty(), "cero claves para un bundle ajeno")
        assertEquals(0, f.service.all().size, "y cero contactos")
    }
}
