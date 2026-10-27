package com.km.desktop

import java.io.File
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * G1 — El escritorio NO decodifica el portador ni habla el sobre KCE.
 *
 * ## QUE PINZA
 *
 * En el BYTECODE de produccion de `com.km.desktop` no pueden aparecer:
 *
 *  - `java/util/zip/Deflater` ni `java/util/zip/Inflater`: el deflate del
 *    portador vive en km-core (`CarrierCodec`);
 *  - `com/km/identity/ContactBundle` ni `com/km/identity/Kce`: el orden
 *    `decompress -> validate -> verify` es UNO y esta en el framework.
 *
 * Hasta G1 el importador del escritorio llamaba a `ContactBundle.decodeAndVerify`
 * y leia otro formato; esta guarda impide que vuelva a entrar una segunda
 * lectura del portador.
 *
 * ## POR QUE BYTECODE
 *
 * Porque un grep por fuente se pone rojo con un comentario que explique el
 * defecto. El pool de constantes no lleva comentarios.
 */
class DesktopCarrierIsSharedTest {

    private fun clasesDeEscritorio(): List<File> {
        val main = Class.forName("com.km.desktop.DesktopMainKt")
        val raiz = File(main.protectionDomain.codeSource.location.toURI())
        val paquete = File(raiz, "com/km/desktop")
        check(paquete.isDirectory) { "el build no produjo ${paquete.path}" }
        return paquete.walkTopDown().filter { it.isFile && it.name.endsWith(".class") }.toList()
    }

    private fun contieneEn(bytes: ByteArray, simbolo: String): Boolean {
        val aguja = simbolo.toByteArray(Charsets.ISO_8859_1)
        if (aguja.isEmpty() || aguja.size > bytes.size) return false
        outer@ for (i in 0..bytes.size - aguja.size) {
            for (j in aguja.indices) {
                if (bytes[i + j] != aguja[j]) continue@outer
            }
            return true
        }
        return false
    }

    private fun contiene(clase: File, simbolo: String): Boolean = contieneEn(clase.readBytes(), simbolo)

    private val prohibidos = listOf(
        "java/util/zip/Deflater",
        "java/util/zip/Inflater",
        "com/km/identity/ContactBundle",
        "com/km/identity/Kce",
    )

    @Test
    fun `el deflate y la verificacion del sobre SOLO viven en km-core`() {
        val culpables = clasesDeEscritorio().filter { clase ->
            prohibidos.any { contiene(clase, it) }
        }.map { clase -> "${clase.name} -> ${prohibidos.filter { contiene(clase, it) }}" }

        assertTrue(
            culpables.isEmpty(),
            "el portador se lee en km-core (CarrierCodec): el escritorio no repite el orden.\n" +
                culpables.joinToString("\n") { "  $it" },
        )
    }

    @Test
    fun `el escritorio SI usa el codec compartido`() {
        // El otro lado: que la guarda de arriba no pase por vacio. El importador
        // del escritorio tiene que llamar a CarrierCodec.
        val conCodec = clasesDeEscritorio().filter { contiene(it, "com/km/identity/CarrierCodec") }
        assertTrue(conCodec.isNotEmpty(), "el escritorio importa y exporta con CarrierCodec")
    }

    @Test
    fun `control positivo el escaner VE cada simbolo prohibido`() {
        for (simbolo in prohibidos) {
            assertTrue(
                contieneEn("prefijo:$simbolo:sufijo".toByteArray(Charsets.ISO_8859_1), simbolo),
                "el escaner debe ver $simbolo",
            )
        }
    }

    @Test
    fun `control negativo el escaner NO inventa simbolos`() {
        val limpio = "prefijo:com/km/identity/CarrierCodec:sufijo".toByteArray(Charsets.ISO_8859_1)
        assertTrue(!contieneEn(limpio, "java/util/zip/Deflater"), "no ve un simbolo que no esta")
    }
}
