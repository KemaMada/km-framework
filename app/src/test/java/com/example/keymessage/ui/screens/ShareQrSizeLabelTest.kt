package com.example.keymessage.ui.screens

import com.example.keymessage.contact.BundleCompression
import com.example.keymessage.contact.SelfBundleExporter
import com.example.keymessage.identity.IdentityMaterialFactory
import com.example.keymessage.identity.LocalKeyFactory
import com.example.keymessage.util.KMLog
import com.example.keymessage.util.LogSink
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * La etiqueta de `MI QR` dice el tamaño que el usuario tiene que creer.
 *
 * ## EL BUG
 *
 * `ShareQrScreen` pintaba `bytes=${e.byteLength}` con `byteLength` medido en
 * UTF-8, mientras el payload es el texto ISO-8859-1 que emite
 * `SelfBundleExporter`: 720 en vez de 469 en la evidencia de ADB v5. Los dos
 * numeros describen documentos distintos —el mismo texto codificado de dos
 * maneras— y el que decide si un QR se escanea es el segundo.
 *
 * ## LO QUE SE AFIRMA
 *
 * Que la etiqueta, el `byteLength` del emisor y la linea de log cuentan LO MISMO
 * con la regla del portador, y que esa regla NO es la de UTF-8. Sin la ultima
 * parte, un test que solo compruebe "la etiqueta coincide con el log" pasaria
 * igual con las dos medidas equivocadas: el numero inflado seria consistente y
 * el bug volveria.
 *
 * Los datos NO son constantes: sale un bundle de verdad, con claves de verdad,
 * y se comparan dos exportaciones distintas —con y sin one-time prekeys— para
 * que el numero no pueda casar por accidente con un literal.
 */
class ShareQrSizeLabelTest {

    private data class Linea(val message: String)

    /** Una exportacion, su medida del portador y las lineas de log que emitio. */
    private data class Medida(
        val exportado: SelfBundleExporter.Exported,
        val bytesPortador: Int,
        val lineas: List<String>,
    )

    /** Exporta con las tres medidas a la vez. */
    private fun exportar(maxOneTimePreKeys: Int = SelfBundleExporter.QR_ONE_TIME_PREKEYS): Medida {
        val identity = IdentityMaterialFactory().generate()
        val localKeys = LocalKeyFactory(identity.keyPair.publicKey).generate(3)
        val grabador = mutableListOf<Linea>()
        val anterior = KMLog.active()
        try {
            KMLog.install(object : LogSink {
                override fun log(level: KMLog.Level, tag: String, message: String) {
                    grabador += Linea(message)
                }
            })
            val exportado = SelfBundleExporter.export(
                identity,
                localKeys,
                maxOneTimePreKeys = maxOneTimePreKeys,
            )
            return Medida(
                exportado = exportado,
                bytesPortador = exportado.text.toByteArray(Charsets.ISO_8859_1).size,
                lineas = grabador.map { it.message },
            )
        } finally {
            KMLog.install(anterior)
        }
    }

    @Test
    fun `la etiqueta dice los bytes ISO-8859-1 del portador y no los de UTF-8`() {
        val (exportado, bytesPortador, _) = exportar()

        assertEquals(
            bytesPortador,
            exportado.byteLength,
            "byteLength tiene que ser el tamano del portador: ${exportado.text.length} caracteres",
        )
        assertEquals("bytes=$bytesPortador opk=0", etiquetaBundle(exportado))

        val utf8 = exportado.text.toByteArray(Charsets.UTF_8).size
        assertTrue(
            utf8 != exportado.byteLength,
            "el payload tiene bytes por encima de 0x7F, asi que UTF-8 tiene que dar OTRO numero " +
                "(iso=${exportado.byteLength} utf8=$utf8); si dan igual, este caso no prueba nada",
        )
        val kce = BundleCompression.decompress(exportado.text)
        assertEquals(
            bytesPortador,
            BundleCompression.carrierBytes(BundleCompression.compress(kce)),
            "y el numero es el de los bytes DEFLADOS que salen por el portador, no el del KCE " +
                "descomprimido (${kce.size} B): el otro nodo lee $bytesPortador y espera ${kce.size}",
        )
        assertTrue(
            kce.size > exportado.byteLength,
            "el KCE descomprimido pesa MAS que el portador: si no, comprimir no serviria de nada",
        )
    }

    @Test
    fun `el numero de la etiqueta es el MISMO que emite el log del exporter`() {
        val (exportado, bytesPortador, lineas) = exportar()

        val contacto = lineas.single { it.contains("[CONTACT]") && it.contains("bundle propio emitido") }
        assertTrue(
            contacto.contains("bytes=$bytesPortador"),
            "la linea de log tiene que llevar el tamano del portador: $contacto",
        )
        assertTrue(contacto.contains("bytes=${exportado.byteLength}"), "y el que ve la etiqueta: $contacto")
    }

    @Test
    fun `dos bundles distintos dan dos tamanos distintos, y ninguno es el de UTF-8`() {
        val (sinOpk, bytesSinOpk, _) = exportar(maxOneTimePreKeys = 0)
        val (conOpk, bytesConOpk, _) = exportar(maxOneTimePreKeys = 3)

        assertTrue(
            sinOpk.text != conOpk.text,
            "dos bundles de material distinto no pueden llevar el mismo texto",
        )
        assertTrue(
            bytesSinOpk != bytesConOpk,
            "y con OPK de mas el portador tiene que crecer: sin=$bytesSinOpk con=$bytesConOpk",
        )
        assertEquals("bytes=$bytesConOpk opk=3", etiquetaBundle(conOpk))
        assertTrue(
            conOpk.text.toByteArray(Charsets.UTF_8).size != conOpk.byteLength,
            "tampoco el segundo caso cae en la coincidencia de UTF-8",
        )
    }

    @Test
    fun `el nombre del fichero lleva el prefijo de 16 del identityId y sin elipsis`() {
        // La identidad sale de una clave NUEVA cada vez, y el `identityId` son 64
        // hex: no puede caber en 16 ni parecerse a un literal escrito a mano.
        val identity = IdentityMaterialFactory().generate()
        val hex = identity.identityId.value

        val nombre = nombreFicheroContacto(hex)

        assertEquals("contact-${hex.take(16)}.km", nombre)
        assertTrue(hex.length > 16, "el pretesto: un identityId completo tiene que ser MAS largo que el prefijo (${hex.length})")
        assertTrue(nombre.startsWith("contact-"), "el nombre lo identifica como un bundle de contacto")
        assertTrue(nombre.endsWith(".km"), "y lleva la extension que el otro extremo espera")
        assertEquals(16 + "contact-.km".length, nombre.length, "16 hex de huella y nada mas")
        assertTrue(
            !nombre.contains("..."),
            "el nombre NO lleva puntos suspensivos: un truncado de traza los deja dentro del fichero ($nombre)",
        )
        assertTrue(
            !nombre.contains(hex.drop(16)),
            "ni el resto de la identidad completa: el nombre lleva la huella, no la clave entera",
        )
    }

    @Test
    fun `el nombre del fichero distingue dos identidades y no revienta con una corta`() {
        // El pretesto: sin dos identidades distintas, "el nombre lleva la huella"
        // pasaria tambien con un prefijo constante.
        val a = IdentityMaterialFactory().generate().identityId.value
        val b = IdentityMaterialFactory().generate().identityId.value
        assertNotEquals(a.take(16), b.take(16), "el pretesto: dos identidades distintas tienen prefijos distintos")

        assertNotEquals(nombreFicheroContacto(a), nombreFicheroContacto(b))

        // Un `identityId` mas corto que el prefijo no revienta ni pierde la
        // extension: `take` devuelve lo entero.
        val corto = "cafe1234"
        assertEquals("contact-cafe1234.km", nombreFicheroContacto(corto))
        assertEquals("contact-.km", nombreFicheroContacto(""), "y uno vacio tampoco")
    }

    @Test
    fun `carrierBytes cuenta un byte por caracter aunque el texto no sea ASCII`() {
        // Determinista y sin criptografia: 600 bytes que alternan 0xFF y 'A' se
        // comprimen a poco, asi que el texto sale con la mitad de sus caracteres
        // por encima de 0x7F. En UTF-8, 0xFF vale dos bytes y 'A' uno: 900, que
        // es el numero inflado que se vio en la pantalla.
        val crudo = ByteArray(600) { if (it % 2 == 0) 0xFF.toByte() else 0x41 }
        val texto = String(crudo, Charsets.ISO_8859_1)

        assertEquals(600, BundleCompression.carrierBytes(texto))
        assertEquals(600, texto.length, "un caracter, un byte: ISO-8859-1")
        assertEquals(900, texto.toByteArray(Charsets.UTF_8).size, "y UTF-8 cuenta los altos por dos")
    }
}