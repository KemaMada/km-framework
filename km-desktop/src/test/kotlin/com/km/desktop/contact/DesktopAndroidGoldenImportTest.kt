package com.km.desktop.contact

import com.km.crypto.Ed25519Impl
import com.km.desktop.client.ClientEvent
import com.km.desktop.client.ClientEvents
import com.km.desktop.client.ContactService
import com.km.identity.CarrierCodec
import com.km.identity.ContactImportException
import com.km.identity.ContactIntake
import com.km.identity.InMemoryContactRegistry
import com.km.identity.KmIds
import com.km.identity.LocalDeviceKeyFactory
import com.km.identity.RejectionCodes
import com.km.identity.SelfBundleFactory
import com.km.model.IdentityId
import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * El `.km` REAL de Android importa en el escritorio.
 *
 * Hasta G1 el importador del escritorio esperaba base64url SIN comprimir y
 * rechazaba estos dos ficheros con `ENVELOPE_MALFORMED`. Este test es el criterio
 * de interoperabilidad: los MISMOS bytes que produjo un telefono, leidos por el
 * codigo de producto del escritorio.
 *
 * Los dos goldens solo prueban DECODE/IMPORT: no traen clave privada, asi que no
 * se puede abrir sesion con ellos. El E2E usa identidades generadas.
 */
class DesktopAndroidGoldenImportTest {

    private fun golden(nombre: String): String =
        javaClass.getResourceAsStream("/carriers/$nombre")!!.readBytes().toString(Charsets.ISO_8859_1)

    private fun servidor(identidad: IdentityId = identidadLocal()): Pair<ContactService, ClientEvents> {
        val events = ClientEvents()
        val registry = InMemoryContactRegistry()
        val intake = ContactIntake(registry) { identidad }
        val service = ContactService(
            events = events,
            contacts = registry,
            intake = intake,
            stack = { null },
            sessionRegistry = { null },
            identity = { null },
            deviceKeys = { null },
        )
        return service to events
    }

    private fun identidadLocal(): IdentityId {
        val firma = Ed25519Impl().generateKeyPair()
        return IdentityId(KmIds.identityId(firma.publicKey).joinToString("") { "%02x".format(it) })
    }

    @Test
    fun `los dos portadores de Android importan y dan la MISMA raiz que su nombre`() {
        val (service, eventos) = servidor()

        val s8 = service.importText(golden("s8plus-1e9a73f21347.km"))
        val medium = service.importText(golden("mediumphone-aeb864889b56.km"))

        assertEquals("1e9a73f21347", s8.identityRoot.value.take(12))
        assertEquals("aeb864889b56", medium.identityRoot.value.take(12))
        assertEquals(2, service.all().size)
        // La raiz se DERIVA de la clave publica, no se copia del texto.
        val bundle = CarrierCodec.decode(golden("s8plus-1e9a73f21347.km"))
        assertEquals(
            IdentityId(KmIds.identityId(bundle.identityRoot).joinToString("") { "%02x".format(it) }),
            s8.identityRoot,
        )
        assertTrue(eventos.tail().any { it.contains("contacto") }, "el import publica un evento, no imprime")
    }

    @Test
    fun `importar el mismo golden dos veces no duplica`() {
        val (service, _) = servidor()
        service.importText(golden("s8plus-1e9a73f21347.km"))
        service.importText(golden("s8plus-1e9a73f21347.km"))
        assertEquals(1, service.all().size)
    }

    @Test
    fun `un golden con un byte alterado se rechaza con codigo estable`() {
        val texto = golden("s8plus-1e9a73f21347.km")
        val corte = texto.length / 2
        val alterado = texto.substring(0, corte) + (if (texto[corte] == 'A') 'B' else 'A') + texto.substring(corte + 1)

        val fallo = assertFailsWith<ContactImportException> { servidor().first.importText(alterado) }
        assertTrue(fallo.code.isNotEmpty())
        assertTrue(fallo.code != RejectionCodes.IMPORT_FAILED, "codigo propio: ${fallo.code}")
    }

    @Test
    fun `un bundle generado por el ESCRITORIO importa por el mismo camino`(@TempDir dir: Path) {
        val firma = Ed25519Impl().generateKeyPair()
        val keys = LocalDeviceKeyFactory(firma.publicKey).generate(3)
        val exportado = SelfBundleFactory.export(firma.publicKey, firma.privateKey, keys)

        val ruta = dir.resolve("own.km")
        Files.write(ruta, CarrierCodec.toBytes(exportado.text))

        val (service, _) = servidor()
        val contacto = service.importFile(ruta)

        assertEquals(exportado.identityId, contacto.identityRoot)
        assertEquals(exportado.deviceId, contacto.subjectDeviceId)
        assertEquals(0, exportado.oneTimePreKeyCount, "el bundle de QR viaja con 0 OPK")
        assertNotNull(service.find(contacto.identityRoot))
    }

    @Test
    fun `un km por encima de 64 KiB se rechaza sin cargarlo`(@TempDir dir: Path) {
        val ruta = dir.resolve("enorme.km")
        Files.write(ruta, ByteArray(70 * 1024) { 0x41 })
        val fallo = assertFailsWith<ContactImportException> { servidor().first.importFile(ruta) }
        assertEquals(RejectionCodes.FILE_TOO_LARGE, fallo.code)
    }

    @Test
    fun `un km vacio se rechaza como EMPTY_ENVELOPE`(@TempDir dir: Path) {
        val ruta = dir.resolve("vacio.km")
        Files.write(ruta, ByteArray(0))
        val fallo = assertFailsWith<ContactImportException> { servidor().first.importFile(ruta) }
        assertEquals(RejectionCodes.EMPTY_ENVELOPE, fallo.code)
    }
}
