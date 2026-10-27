package com.km.desktop

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * 3.1 — UN SOLO escritor del terminal, medido en el BYTECODE.
 *
 * ## QUE PINZA
 *
 * - `ui/` y `client/` no pueden referenciar `PrintWriter` ni `PrintStream`: si
 *   pudieran, un handler tendria una via para escribir en medio del frame. El
 *   unico que imprime es `shell/ScreenWriter`.
 * - `ui/` no importa JLine: el modelo se prueba sin terminal.
 * - La secuencia de pantalla alternativa (`ESC[?1049h`) vive en UNA clase. Dos
 *   duenos del terminal es exactamente el fallo que esta fase cierra.
 *
 * ## CONTROL POSITIVO Y NEGATIVO
 *
 * El escaner se prueba con un buffer sintetizado que SI contiene el simbolo y
 * con otro que no: sin eso, "cero escritores" seria indistinguible de un escaner
 * roto.
 */
class TuiSingleWriterTest {

    private fun raiz(): File {
        val main = Class.forName("com.km.desktop.DesktopMainKt")
        return File(main.protectionDomain.codeSource.location.toURI())
    }

    private fun clases(paquete: String): List<File> {
        val dir = File(raiz(), paquete.replace('.', '/'))
        check(dir.isDirectory) { "el build no produjo ${dir.path}" }
        return dir.walkTopDown().filter { it.isFile && it.name.endsWith(".class") }.toList()
    }

    private fun contiene(bytes: ByteArray, simbolo: String): Boolean {
        val aguja = simbolo.toByteArray(Charsets.ISO_8859_1)
        if (aguja.isEmpty() || aguja.size > bytes.size) return false
        outer@ for (i in 0..bytes.size - aguja.size) {
            for (j in aguja.indices) if (bytes[i + j] != aguja[j]) continue@outer
            return true
        }
        return false
    }

    private fun contiene(clase: File, simbolo: String): Boolean = contiene(clase.readBytes(), simbolo)

    private val escrituraDirecta = listOf("java/io/PrintWriter", "java/io/PrintStream")

    @Test
    fun `ui y client no escriben en el terminal`() {
        val culpables = (clases("com.km.desktop.ui") + clases("com.km.desktop.client"))
            .filter { clase -> escrituraDirecta.any { contiene(clase, it) } }
            .map { "${it.name} -> ${escrituraDirecta.filter { s -> contiene(it, s) }}" }

        assertTrue(
            culpables.isEmpty(),
            "solo ScreenWriter escribe el terminal; ui/ y client/ publican eventos:\n" +
                culpables.joinToString("\n") { "  $it" },
        )
    }

    @Test
    fun `ui no depende de JLine`() {
        val culpables = clases("com.km.desktop.ui").filter { contiene(it, "org/jline") }
        assertTrue(
            culpables.isEmpty(),
            "el modelo puro se prueba sin terminal: ${culpables.map { it.name }}",
        )
    }

    @Test
    fun `en shell solo ScreenWriter y el REPL tienen un writer`() {
        val conWriter = clases("com.km.desktop.shell")
            .filter { contiene(it, "java/io/PrintWriter") }
            .map { it.name }
            .toSet()

        assertEquals(
            setOf("ScreenWriter.class", "LineModeShell.class"),
            conWriter,
            "JLineShell reduce y pinta a traves de ScreenWriter; el REPL historico conserva el suyo",
        )
    }

    @Test
    fun `la pantalla alternativa la pide UNA sola clase`() {
        val conAlternativa = clases("com.km.desktop")
            .filter { contiene(it, "\u001b[?1049h") }
            .map { it.name }

        assertEquals(
            listOf("ScreenWriter.class"),
            conAlternativa,
            "dos duenos del terminal es el fallo que la TUI cierra",
        )
    }

    @Test
    fun `control positivo el escaner SI ve los simbolos`() {
        escrituraDirecta.forEach { simbolo ->
            assertTrue(contiene("x$simbolo:y".toByteArray(Charsets.ISO_8859_1), simbolo))
        }
        assertTrue(contiene("\u001b[?1049h".toByteArray(Charsets.ISO_8859_1), "\u001b[?1049h"))
    }

    @Test
    fun `control negativo el escaner NO inventa simbolos`() {
        val buffer = "com/km/desktop/ui/ShellRenderer".toByteArray(Charsets.ISO_8859_1)
        escrituraDirecta.forEach { simbolo ->
            assertTrue(!contiene(buffer, simbolo), "no debe ver $simbolo")
        }
        assertTrue(!contiene(buffer, "org/jline"))
    }
}
