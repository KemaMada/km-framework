package com.km.desktop

import com.km.identity.InMemoryContactRegistry
import com.km.identity.VerifiedContact
import com.km.desktop.contact.ContactImporter
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermission
import kotlin.test.assertContentEquals
import kotlin.test.assertNotNull

/**
 * F2 — `contacts.json`: round-trip, atomicidad, permisos y corrupcion.
 *
 * ## QUE SE AFIRMA
 *
 * - Lo que se guarda es lo que se vuelve a verificar al leer: raiz, clave de
 *   firma, dispositivo y alias sobreviven a un reinicio.
 * - La escritura es ATOMICA: el destino no se toca hasta el renombrado, y un
 *   fallo del renombrado deja el original intacto y sin temporales huerfanos.
 * - El fichero queda en `600`.
 * - Un fichero corrupto NO se borra y NO se pisa; se informa con codigo estable.
 */
class ContactStoreTest {

    @TempDir
    lateinit var dir: Path

    private fun golden(nombre: String): String =
        javaClass.getResourceAsStream("/carriers/$nombre")!!.readBytes().toString(Charsets.ISO_8859_1)

    private fun contacto(nombre: String = "s8plus-1e9a73f21347.km", alias: String = "ana"): VerifiedContact =
        ContactImporter.import(golden(nombre)).copy(displayName = alias)

    private fun store(): ContactStore = ContactStore(dir.resolve("contacts.json"))

    @Test
    fun `round-trip conserva raiz clave de firma dispositivo alias y bundle`() {
        val original = contacto()
        assertTrue(store().save(original))

        val registro = InMemoryContactRegistry()
        assertEquals(1, store().loadInto(registro))

        val recuperado = assertNotNull(registro.find(original.identityRoot))
        assertEquals(original.identityRoot, recuperado.identityRoot)
        assertEquals("ana", recuperado.displayName)
        assertEquals(original.subjectDeviceId, recuperado.subjectDeviceId)
        assertEquals(original.subjectSigningKey, recuperado.subjectSigningKey)
        assertEquals(original.bundleText, recuperado.bundleText)

        val record = store().also { it.loadInto(InMemoryContactRegistry()) }.records().single()
        assertEquals(original.identityRoot.value, record.identityId)
        assertEquals(original.subjectDeviceId, record.deviceId)
        assertContentEquals(original.subjectSigningKey.hexToBytes(), record.signingKey)
    }

    @Test
    fun `guardar dos veces el mismo contacto no duplica ni pisa addedAt`() {
        var ahora = 1_000L
        val s = ContactStore(dir.resolve("contacts.json"), clock = { ahora })
        val original = contacto()
        assertTrue(s.save(original))
        ahora = 2_000L
        assertTrue(s.save(original.copy(displayName = "ana-2")))

        val relectura = ContactStore(dir.resolve("contacts.json"))
        relectura.loadInto(InMemoryContactRegistry())
        val registros = relectura.records()
        assertEquals(1, registros.size)
        assertEquals(1000L, registros.single().addedAt, "addedAt es de la PRIMERA aceptacion")
        assertEquals("ana-2", registros.single().alias)
    }

    @Test
    fun `la escritura es atomica - el destino no cambia si el renombrado falla`() {
        val destino = dir.resolve("contacts.json")
        val primero = contacto()
        assertTrue(ContactStore(destino).save(primero))
        val antes = Files.readAllBytes(destino)

        // El renombrado se sustituye por uno que comprueba DOS cosas y falla:
        // (1) el destino AUN tiene el contenido anterior, (2) el temporal ya
        // tiene el documento nuevo. Eso es exactamente "escribir aparte y
        // renombrar", no "truncar y escribir encima".
        val segundo = contacto("mediumphone-aeb864889b56.km", "bea")
        val roto = ContactStore(
            destino,
            mover = { desde, hasta ->
                assertContentEquals(antes, Files.readAllBytes(hasta), "el destino no se escribe antes del rename")
                assertTrue(Files.readAllBytes(desde).isNotEmpty(), "el temporal ya tiene el documento")
                throw IOException("rename simulado")
            },
        )
        roto.loadInto(InMemoryContactRegistry())

        assertFalse(roto.save(segundo))
        assertEquals(ContactStore.CODE_WRITE_FAILED, roto.lastError)
        assertContentEquals(antes, Files.readAllBytes(destino), "el original queda intacto")
        assertTrue(
            Files.list(dir).use { flujo -> flujo.noneMatch { it.fileName.toString().endsWith(".tmp") } },
            "sin temporales huerfanos",
        )

        // Y un guardado normal SI deja el fichero nuevo y sin temporales.
        assertTrue(ContactStore(destino).save(segundo))
        assertTrue(Files.list(dir).use { flujo -> flujo.noneMatch { it.fileName.toString().endsWith(".tmp") } })
    }

    @Test
    fun `el fichero queda en 600`() {
        val destino = dir.resolve("contacts.json")
        assertTrue(ContactStore(destino).save(contacto()))
        if (Files.getFileStore(destino).supportsFileAttributeView("posix")) {
            assertEquals(
                setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE),
                Files.getPosixFilePermissions(destino),
            )
        }
    }

    @Test
    fun `un fichero corrupto se informa con codigo estable y NO se borra ni se pisa`() {
        val destino = dir.resolve("contacts.json")
        val basura = "{ esto no es json".toByteArray()
        Files.write(destino, basura)

        val store = ContactStore(destino)
        val registro = InMemoryContactRegistry()
        assertEquals(0, store.loadInto(registro))
        assertEquals(ContactStore.CODE_CORRUPT, store.lastError)
        assertTrue(store.writesBlocked())

        assertFalse(store.save(contacto()), "no se pisa un fichero que no se pudo cargar")
        assertContentEquals(basura, Files.readAllBytes(destino), "los datos originales siguen ahi")
        assertTrue(Files.isRegularFile(destino))
    }

    @Test
    fun `una entrada que no verifica se descarta y bloquea la reescritura`() {
        val destino = dir.resolve("contacts.json")
        val original = contacto()
        assertTrue(ContactStore(destino).save(original))

        // Se altera el bundle guardado (Base64 valido, contenido distinto): el
        // verificador tiene que rechazarlo al releer.
        val texto = Files.readString(destino)
        val alterado = texto.replace(
            Regex("\"bundle\":\"[^\"]+\""),
            "\"bundle\":\"" + java.util.Base64.getEncoder()
                .encodeToString("basura".toByteArray()) + "\"",
        )
        Files.writeString(destino, alterado)

        val store = ContactStore(destino)
        assertEquals(0, store.loadInto(InMemoryContactRegistry()))
        assertEquals(1, store.skipped)
        assertEquals(ContactStore.CODE_INVALID_ENTRY, store.lastError)
        assertFalse(store.save(original))
        assertEquals(alterado, Files.readString(destino))
    }

    private fun String.hexToBytes(): ByteArray = ByteArray(length / 2) { i ->
        ((Character.digit(this[i * 2], 16) shl 4) or Character.digit(this[i * 2 + 1], 16)).toByte()
    }
}
