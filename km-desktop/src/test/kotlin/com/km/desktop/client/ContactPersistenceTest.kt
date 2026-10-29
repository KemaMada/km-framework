package com.km.desktop.client

import com.km.desktop.ContactStore
import com.km.desktop.PeerKeyDirectory
import com.km.identity.ContactIntake
import com.km.identity.InMemoryContactRegistry
import com.km.identity.KmIds
import com.km.model.IdentityId
import com.km.transport.contact.ContactExchangeCoordinator
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.assertContentEquals
import kotlin.test.assertNotNull

/**
 * F2 — el registro ACEPTADO se persiste, y un reinicio lo recupera.
 *
 * ## POR QUE AQUI Y NO SOLO EN [com.km.desktop.ContactStoreTest]
 *
 * Porque el fallo no era que faltara un serializador: era que NADIE llamaba a
 * guardar. Estos tests usan el [ContactService] de producto —el mismo que la
 * TUI— para que "import aceptado -> contacts.json" y "contact_exchange ->
 * contacts.json" queden atados al camino real, y para pinzar que la clave de
 * firma se escribe en `peers.json` (antes solo lo hacia el comando `peer`).
 */
class ContactPersistenceTest {

    @TempDir
    lateinit var dir: Path

    private val localIdentity = IdentityId(
        KmIds.identityId(ByteArray(32) { it.toByte() }).joinToString("") { "%02x".format(it) },
    )

    private fun golden(nombre: String): String =
        javaClass.getResourceAsStream("/carriers/$nombre")!!.readBytes().toString(Charsets.ISO_8859_1)

    private inner class Fixture {
        val contactsPath = dir.resolve("contacts.json")
        val peersPath = dir.resolve("peers.json")
        val registry = InMemoryContactRegistry()
        val store = ContactStore(contactsPath)
        val peerKeys = PeerKeyDirectory(peersPath)
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
            contactStore = { store },
        )
        val coordinator = ContactExchangeCoordinator(
            conocido = { emisor -> registry.find(emisor) != null },
            esPrekey = { wire -> com.km.frame.PrekeyFrame.esPrekey(wire) },
        )
    }

    @Test
    fun `un import aceptado escribe contacto y clave de firma`() {
        val f = Fixture()
        val contacto = f.service.importText(golden("s8plus-1e9a73f21347.km"))

        assertTrue(java.nio.file.Files.isRegularFile(f.contactsPath), "contacts.json escrito")
        assertTrue(java.nio.file.Files.isRegularFile(f.peersPath), "peers.json escrito")

        // "Reinicio": almacenes nuevos sobre los MISMOS ficheros.
        val contactosTrasReinicio = InMemoryContactRegistry()
        assertEquals(1, ContactStore(f.contactsPath).loadInto(contactosTrasReinicio))
        val recuperado = assertNotNull(contactosTrasReinicio.find(contacto.identityRoot))
        assertContentEquals(contacto.bundleText.toByteArray(Charsets.ISO_8859_1),
            recuperado.bundleText.toByteArray(Charsets.ISO_8859_1))

        val clavesTrasReinicio = PeerKeyDirectory(f.peersPath).load()
        val clave = assertNotNull(
            clavesTrasReinicio.signingKeyOf(contacto.identityRoot),
            "la clave de ACK sobrevive al reinicio",
        )
        assertContentEquals(contacto.subjectSigningKey.hexToBytes(), clave)
    }

    @Test
    fun `un contact_exchange aceptado tambien persiste`() {
        val f = Fixture()
        val texto = golden("mediumphone-aeb864889b56.km")
        val root = com.km.desktop.contact.ContactImporter.import(texto).identityRoot

        assertTrue(f.service.receiveBundle(root, texto, f.coordinator))

        val contactosTrasReinicio = InMemoryContactRegistry()
        assertEquals(1, ContactStore(f.contactsPath).loadInto(contactosTrasReinicio))
        assertNotNull(contactosTrasReinicio.find(root))
    }

    @Test
    fun `un portador rechazado no escribe NADA en disco`() {
        val f = Fixture()
        runCatching { f.service.importText("esto no es un portador") }
        val store = ContactStore(f.contactsPath)
        store.loadInto(InMemoryContactRegistry())
        assertEquals(0, store.size())
        assertTrue(!java.nio.file.Files.exists(f.contactsPath))
        assertTrue(!java.nio.file.Files.exists(f.peersPath))
    }

    private fun String.hexToBytes(): ByteArray = ByteArray(length / 2) { i ->
        ((Character.digit(this[i * 2], 16) shl 4) or Character.digit(this[i * 2 + 1], 16)).toByte()
    }
}
