package com.example.keymessage.contact

import com.km.crypto.Ed25519Impl
import com.km.identity.CarrierCodec
import com.km.identity.LocalDeviceKeyFactory
import com.km.identity.SelfBundleFactory
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/**
 * G1 — Un bundle fabricado por el ESCRITORIO verifica con el importador de la app.
 *
 * ## QUE CIERRA
 *
 * El criterio cruzado de la spec: "desktop -> Android". El escritorio fabrica su
 * bundle con [SelfBundleFactory] (km-core) y lo publica en el portador comun. La
 * app lo importa por SU punto de entrada ([ContactImporter]), que desde G1
 * delega en el codec compartido. Lo que se afirma aqui es que el documento que
 * produce el escritorio entra sin rechazo de portador y con la MISMA raiz.
 *
 * ## POR QUE NO SE PRUEBA CON UN PROCESO DE ESCRITORIO
 *
 * Porque la app no puede depender de `km-desktop` (es un binario, no una
 * libreria) y el escritorio no puede ejecutar la suite Android. Lo que SI comparte
 * es el framework: si el bundle se fabrica en km-core y se lee en km-core, la
 * prueba del camino real es esta. El E2E con dos procesos cubre el resto.
 */
class DesktopBundleInteropTest {

    private fun bundleDeEscritorio(): SelfBundleFactory.Exported {
        // Material de un "escritorio": misma fabrica, mismas primitivas.
        val firma = Ed25519Impl().generateKeyPair()
        val keys = LocalDeviceKeyFactory(firma.publicKey).generate(3)
        return SelfBundleFactory.export(firma.publicKey, firma.privateKey, keys)
    }

    @Test
    fun `un bundle del escritorio importa con su raiz y su deviceId`() {
        val exportado = bundleDeEscritorio()

        val contacto = ContactImporter.import(exportado.text)

        assertEquals(exportado.identityId, contacto.identityRoot)
        assertEquals(exportado.deviceId, contacto.subjectDeviceId)
        assertEquals(0, exportado.oneTimePreKeyCount, "el portador del escritorio viaja con 0 OPK")
        assertEquals(exportado.byteLength, CarrierCodec.bytes(exportado.text))
        assertNotNull(contacto.subjectSigningKey)
    }

    @Test
    fun `el escritorio puede importar lo que exporta la app`() {
        val identity = com.example.keymessage.identity.IdentityMaterialFactory().generate()
        val localKeys = com.example.keymessage.identity.LocalKeyFactory(identity.keyPair.publicKey).generate(3)
        val propio = SelfBundleExporter.export(identity, localKeys)

        // El mismo punto de lectura que usaria el escritorio.
        val bundle = com.km.identity.CarrierCodec.decode(propio.text, identity.keyPair.publicKey)
        assertEquals(identity.identityId.value.length, 64)
        assertEquals(propio.byteLength, CarrierCodec.bytes(propio.text))
        assertNotNull(bundle.subjectSigningKey)
    }

    @Test
    fun `los goldens de Android siguen importando por el punto unico`() {
        fun golden(nombre: String): String =
            javaClass.getResourceAsStream("/carriers/$nombre")!!.readBytes().toString(Charsets.ISO_8859_1)

        val s8 = ContactImporter.import(golden("s8plus-1e9a73f21347.km"))
        assertEquals("1e9a73f21347", s8.identityRoot.value.take(12))
    }
}
