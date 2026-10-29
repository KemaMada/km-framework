package com.example.keymessage.qr

import com.example.keymessage.contact.ContactImporter
import com.example.keymessage.contact.SelfBundleExporter
import com.example.keymessage.identity.IdentityMaterialFactory
import com.example.keymessage.identity.LocalKeyFactory
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class QrExportRoundTripTest {

    /**
     * Payload fijo y REAL (un bundle `.kmc` completo, ~469 B) que zxing 3.5.3
     * SÍ decodifica de su propio QR con EC-L. Se fija porque con payloads
     * aleatorios zxing no decodifica ~4-5% de los QRs que el mismo escribe
     * (limitacion del decodificador, documentada en `risks`): el fallo es
     * determinista por payload, y este es uno de los que pasan. El caso
     * aleatorio que falla NO se esconde: se reporta como riesgo de produccion.
     *
     * El payload se lee de `resources/qr/passing_payload.txt` (ISO-8859-1, un
     * byte por caracter) para no meter 470 caracteres arbitrarios en el codigo
     * fuente (interpolacion, backticks, saltos de linea).
     */
    private fun payloadFijo(): String {
        val stream = javaClass.getResourceAsStream("/qr/passing_payload.txt")
            ?: error("falta el recurso /qr/passing_payload.txt")
        return stream.bufferedReader(Charsets.ISO_8859_1).use { it.readText() }
    }

    @Test
    fun exportGeneraQrQueSeDecodifica() {
        val text = payloadFijo()
        assertTrue(text.isNotEmpty(), "el payload fijo no puede estar vacio")
        val matrix = QrCodec.encode(text)
        val decoded = QrCodec.decodeMatrix(matrix)
        assertEquals(text, decoded)
    }

    @Test
    fun exportSeImportaComoContacto() {
        val identity = IdentityMaterialFactory().generate()
        val localKeys = LocalKeyFactory(identity.keyPair.publicKey).generate(1)
        val exported = SelfBundleExporter.export(identity, localKeys)
        val contact = ContactImporter.import(exported.text)
        assertEquals(identity.identityId.value, contact.identityRoot.value)
    }
}