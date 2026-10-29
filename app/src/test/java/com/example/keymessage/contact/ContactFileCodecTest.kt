package com.example.keymessage.contact

import com.example.keymessage.identity.IdentityMaterialFactory
import com.example.keymessage.identity.LocalKeyFactory
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * El `.km` es UN documento, no un formato nuevo: esto afirma que escribir a un
 * `OutputStream` y releer de un `InputStream` devuelve EXACTAMENTE lo mismo que
 * ya viaja por el QR, y que todo lo que no vale sale con CODIGO y no con crash.
 *
 * ## POR QUE ESTOS DATOS Y NO UN PAYLOAD CONSTANTE
 *
 * Un literal de 470 caracteres no demuestra nada de este codec: pasaria igual con
 * cualquier transformacion que fuera identidad sobre ese caso. Los tres textos de
 * aqui salen de [SelfBundleExporter.export] con material NUEVO cada vez —claves de
 * Ed25519 y X25519 de verdad— y con 0, 1 y 3 one-time prekeys, asi que tienen
 * TAMANOS DISTINTOS y bytes por encima de `0x7F`. Los tres se comparan ademas
 * contra `toByteArray(ISO_8859_1)`, que es el contrato del portador: un round-trip
 * simetrico en UTF-8 TAMBIEN volveria verde, y por eso la igualdad byte a byte es
 * la afirmacion que hace el trabajo.
 */
class ContactFileCodecTest {

    /** Una exportacion real: material nuevo y [opks] one-time prekeys. */
    private fun exportar(opks: Int): SelfBundleExporter.Exported {
        val identity = IdentityMaterialFactory().generate()
        val localKeys = LocalKeyFactory(identity.keyPair.publicKey).generate(5)
        return SelfBundleExporter.export(identity, localKeys, maxOneTimePreKeys = opks)
    }

    /** Escribe el portador a un buffer, como haria SAF con un `Uri`. */
    private fun escribir(text: String): ByteArray =
        ByteArrayOutputStream().also { ContactFileCodec.write(it, text) }.toByteArray()

    /**
     * `InputStream` que CUENTA los bytes que entrega.
     *
     * Es lo que permite afirmar "el tope se aplica sin leer el cuerpo entero": el
     * `ByteArrayInputStream` no deja ver cuanto se ha consumeido, y un `read` que
     * se traga 4 MB antes de rechazarlos tambien estaria "correcto" salvo por la
     * memoria que gasto.
     */
    private class CountingInputStream(private val origen: InputStream) : InputStream() {
        var entregados: Int = 0
            private set

        override fun read(): Int {
            val b = origen.read()
            if (b >= 0) entregados += 1
            return b
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            val n = origen.read(b, off, len)
            if (n > 0) entregados += n
            return n
        }

        override fun available(): Int = origen.available()
    }

    /** Flujo sintetico de [tamano] bytes, NO constante y no todos iguales. */
    private fun sinteticos(tamano: Int): ByteArray = ByteArray(tamano) { ((it * 31 + 7) and 0xFF).toByte() }

    /** `OutputStream`/`InputStream` que FALLAN si alguien los cierra. */
    private class NoCerrable : OutputStream() {
        var cerrado = false
            private set

        override fun write(b: Int) = Unit

        override fun write(b: ByteArray, off: Int, len: Int) = Unit

        override fun close() {
            cerrado = true
        }
    }

    // ---------------------------------------------------------------- 2.2

    @Test
    fun `el round trip devuelve el MISMO texto byte a byte en los tres tamanos`() {
        val exportados = listOf(exportar(0), exportar(1), exportar(3))

        assertEquals(
            3,
            exportados.map { it.byteLength }.distinct().size,
            "los tres ejemplos tienen que pesar distinto, o no prueban nada: ${exportados.map { it.byteLength }}",
        )

        exportados.forEach { exportado ->
            val bytes = escribir(exportado.text)

            assertContentEquals(
                exportado.text.toByteArray(Charsets.ISO_8859_1),
                bytes,
                "lo escrito NO es el portador ISO-8859-1 (${exportado.byteLength} B)",
            )
            assertEquals(
                exportado.byteLength,
                bytes.size,
                "y el fichero tiene que medir lo que dice la etiqueta de MI QR",
            )
            assertNotEquals(
                exportado.text.toByteArray(Charsets.UTF_8).size,
                bytes.size,
                "el pretesto: si UTF-8 diera el mismo numero, la igualdad byte a byte no distinguiria nada",
            )

            val releido = ContactFileCodec.read(ByteArrayInputStream(bytes))
            assertEquals(exportado.text, releido, "el texto releido no es el escrito")
            assertContentEquals(bytes, releido.toByteArray(Charsets.ISO_8859_1), "ni byte a byte")
        }
    }

    @Test
    fun `el portador releido lo verifica ContactImporter con la raiz que lo firma`() {
        // El `.km` es UN camino de verificacion, no una via paralela: lo releido
        // entra por `ContactImporter.import` y sale con la MISMA raiz, el mismo
        // dispositivo y la MISMA clave de firma que el texto que se exporto.
        listOf(0, 1, 3).forEach { opks ->
            val exportado = exportar(opks)
            val releido = ContactFileCodec.read(ByteArrayInputStream(escribir(exportado.text)))

            val porTexto = ContactImporter.import(exportado.text)
            val porFichero = ContactImporter.import(releido)

            assertEquals(porTexto.identityRoot, porFichero.identityRoot, "raiz distinta con $opks OPK")
            assertEquals(porTexto.subjectDeviceId, porFichero.subjectDeviceId, "subjectDeviceId distinto con $opks OPK")
            assertEquals(porTexto.subjectSigningKey, porFichero.subjectSigningKey, "subjectSigningKey distinta con $opks OPK")
            assertEquals(exportado.identityId.value, porFichero.identityRoot.value, "y es la raiz que lo firma")
        }
    }

    // ---------------------------------------------------------------- 2.3

    @Test
    fun `el tope exacto se acepta y uno mas se RECHAZA con FILE_TOO_LARGE`() {
        val enElTope = sinteticos(ContactFileCodec.MAX_BYTES)
        assertEquals(
            ContactFileCodec.MAX_BYTES,
            ContactFileCodec.read(ByteArrayInputStream(enElTope)).length,
            "exactamente $ContactFileCodec se acepta: el limite es '>' y no '>='",
        )

        val unoMas = sinteticos(ContactFileCodec.MAX_BYTES + 1)
        val fallo = assertFailsWith<ContactFileCodec.CodecException> {
            ContactFileCodec.read(ByteArrayInputStream(unoMas))
        }
        assertEquals(ContactFileCodec.CODE_OVERSIZED, fallo.code, "el codec lanza su codigo")
        assertEquals("FILE_TOO_LARGE", rejectionCodeOf(fallo), "y la pantalla ve el codigo ESTABLE de la spec")
    }

    @Test
    fun `el rechazo por tamano NO lee el cuerpo entero`() {
        val enorme = sinteticos(ContactFileCodec.MAX_BYTES * 8)
        val contador = CountingInputStream(ByteArrayInputStream(enorme))

        val fallo = assertFailsWith<ContactFileCodec.CodecException> {
            ContactFileCodec.read(contador)
        }

        assertEquals("FILE_TOO_LARGE", rejectionCodeOf(fallo))
        assertTrue(
            contador.entregados < enorme.size,
            "ha leido ${contador.entregados} de ${enorme.size} B: el tope tiene que cortar en lectura, " +
                "no leer el fichero entero para rechazarlo despues",
        )
        // El margen es el del bloque de lectura, no uno arbitrario: el `read` de un
        // `InputStream` entrega en trozos, asi que el corte cae como mucho en el
        // siguiente bloque completo.
        assertTrue(
            contador.entregados <= ContactFileCodec.MAX_BYTES + 8 * 1024,
            "y no mas de un bloque por encima del tope: leidos ${contador.entregados}",
        )
    }

    @Test
    fun `un fallo de lectura del stream sale con su codigo, no con una excepcion suelta`() {
        val roto = object : InputStream() {
            override fun read(): Int = throw IOException("disco roto")
        }

        val fallo = assertFailsWith<ContactFileCodec.CodecException> { ContactFileCodec.read(roto) }

        assertEquals(ContactFileCodec.CODE_READ_FAILED, fallo.code)
        assertEquals(
            ContactFileCodec.CODE_READ_FAILED,
            rejectionCodeOf(fallo),
            "un fallo de I/O tambien tiene que ser un codigo que la pantalla pueda pintar",
        )
    }

    // ---------------------------------------------------------------- 2.4

    @Test
    fun `un fichero de cero bytes se RECHAZA con EMPTY_ENVELOPE`() {
        // Decision D1: leer 0 bytes NO es un error del codec —devuelve el texto
        // vacio— y el RECHAZO lo emite `ContactImporter`, que es quien ya juzga el
        // sobre vacio. El camino se afirma entero, no por partes.
        val leido = ContactFileCodec.read(ByteArrayInputStream(ByteArray(0)))

        assertEquals("", leido, "leer un fichero vacio devuelve el texto vacio")
        val fallo = assertFailsWith<IllegalArgumentException> { ContactImporter.import(leido) }
        assertEquals("EMPTY_ENVELOPE", rejectionCodeOf(fallo))
    }

    @Test
    fun `un byte alterado a mitad del stream se RECHAZA con ENVELOPE_MALFORMED`() {
        val original = exportar(1).text
        val bytes = original.toByteArray(Charsets.ISO_8859_1)
        val corte = bytes.size / 2
        val alterado = bytes.copyOf()
        // Se altera a un byte con valor alto para que no pueda coincidir con el
        // original, y se comprueba que SI ha cambiado.
        alterado[corte] = (alterado[corte].toInt() xor 0xFF).toByte()
        assertNotEquals(original, String(alterado, Charsets.ISO_8859_1), "el pretesto: el byte tiene que haber cambiado")

        val releido = ContactFileCodec.read(ByteArrayInputStream(alterado))
        val fallo = assertFailsWith<Exception> { ContactImporter.import(releido) }

        assertEquals("ENVELOPE_MALFORMED", rejectionCodeOf(fallo), "un portador corrupto se rechaza por su sobre: $fallo")
    }

    @Test
    fun `rejectionCodeOf traduce los codigos del codec y no toca los anteriores`() {
        // El total: los codigos del codec salen traducidos a los ESTABLES de la
        // pantalla, un codigo nuevo del codec pasa tal cual, y las ramas que ya
        // estaban siguen igual.
        assertEquals(
            "FILE_TOO_LARGE",
            rejectionCodeOf(ContactFileCodec.CodecException(ContactFileCodec.CODE_OVERSIZED, "grande")),
        )
        assertEquals(
            "EMPTY_ENVELOPE",
            rejectionCodeOf(ContactFileCodec.CodecException(ContactFileCodec.CODE_EMPTY, "vacio")),
        )
        assertEquals(
            ContactFileCodec.CODE_READ_FAILED,
            rejectionCodeOf(ContactFileCodec.CodecException(ContactFileCodec.CODE_READ_FAILED, "io")),
            "un codigo que el codec aun no emite tambien tiene que llegar con nombre propio, no como IMPORT_FAILED",
        )
        assertEquals(
            "EMPTY_ENVELOPE",
            rejectionCodeOf(IllegalArgumentException("el sobre del contacto esta vacio")),
            "la rama del sobre vacio NO cambia",
        )
        assertEquals(
            "ENVELOPE_MALFORMED",
            rejectionCodeOf(com.km.identity.Kce.KceException("ENVELOPE_MALFORMED")),
            "ni la del sobre malformado",
        )
        assertEquals(
            "INVALID_SIGNATURE",
            rejectionCodeOf(com.km.identity.KmIdVerificationException("INVALID_SIGNATURE")),
            "ni la del verificador de firmas",
        )
        assertEquals("IMPORT_FAILED", rejectionCodeOf(IllegalStateException("vaya")), "ni el cajon de sastre")
    }

    // ---------------------------------------------------------------- 2.5

    @Test
    fun `un salto de linea final se CONSERVA y el import lo acepta`() {
        // El `trim()` destructivo es la trampa de aqui: `decompress` YA tolera el
        // `\n` colgante (obs #292/#293), asi que normalizarlo en el codec no es
        // "ser mas tolerante", es cambiar el documento. El texto sale intacto.
        val exportado = exportar(0)
        val conNl = exportado.text + "\n"
        val releido = ContactFileCodec.read(ByteArrayInputStream(escribir(conNl)))

        assertTrue(releido.endsWith("\n"), "el `\\n` final se conserva: el codec no normaliza")
        assertEquals(conNl, releido)

        val contacto = ContactImporter.import(releido)
        assertEquals(conNl, contacto.bundleText, "y `bundleText` lo guarda tal cual")
        assertEquals(exportado.identityId.value, contacto.identityRoot.value, "y el sobre verifica")
    }

    @Test
    fun `un portador re-codificado a UTF-8 se RECHAZA y NUNCA se acepta`() {
        // Un editor o un canal de transferencia guardan el `.km` en UTF-8, que es lo
        // que hacen por defecto. Eso produce OTRO documento, no el mismo: el
        // codec lo lee sin quejarse —los bytes son legibles— y el RECHAZO lo
        // emite la verificacion.
        val exportado = exportar(1)
        val iso = exportado.text.toByteArray(Charsets.ISO_8859_1)
        val utf8 = exportado.text.toByteArray(Charsets.UTF_8)
        assertNotEquals(
            iso.size,
            utf8.size,
            "el pretesto: si ISO-8859-1 y UTF-8 dieran los mismos bytes, este caso no probaria nada",
        )
        assertTrue(!iso.contentEquals(utf8), "y los bytes tienen que diferir, no solo el numero")

        val releido = ContactFileCodec.read(ByteArrayInputStream(utf8))
        assertNotEquals(
            exportado.text,
            releido,
            "releer bytes re-codificados NO puede devolver el documento original",
        )

        val fallo = assertFailsWith<Exception> { ContactImporter.import(releido) }
        val codigo = rejectionCodeOf(fallo)
        assertTrue(codigo.isNotBlank(), "y el rechazo tiene codigo, no es un 'error'")
        assertEquals("ENVELOPE_MALFORMED", codigo, "el portador re-codificado no verifica: $fallo")
    }

    // ---------------------------------------------------------------- D1: no cerrar

    @Test
    fun `el codec NO cierra el OutputStream ni el InputStream`() {
        // El SAF cierra su `ParcelFileDescriptor`; si el codec cerrara tambien,
        // el segundo cierre se come la excepcion real. El cierre es del llamador.
        val salida = NoCerrable()
        ContactFileCodec.write(salida, "portador")
        assertTrue(!salida.cerrado, "write no cierra: el cierre es del que abre el Uri")

        val entrada = object : InputStream() {
            private val dentro = ByteArrayInputStream("portador".toByteArray(Charsets.ISO_8859_1))
            override fun read(): Int = dentro.read()
            override fun read(b: ByteArray, off: Int, len: Int): Int = dentro.read(b, off, len)
            var cerrado = false
                private set

            override fun close() {
                cerrado = true
            }
        }
        assertEquals("portador", ContactFileCodec.read(entrada))
        assertTrue(!entrada.cerrado, "read no cierra: el cierre es del que abre el Uri")
    }
}