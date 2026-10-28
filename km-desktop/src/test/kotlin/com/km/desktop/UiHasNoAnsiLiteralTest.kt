package com.km.desktop

import java.io.File
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * GUARDA: `ui/` no lleva ni un literal ESC.
 *
 * ## POR QUE SOBRE EL FUENTE Y NO SOBRE EL BYTECODE
 *
 * El bytecode no sirve aqui: los `.class` estan llenos del byte 0x1B como
 * LONGITUD de entradas del pool de constantes (por ejemplo `\x01\x00\x1b`), de
 * modo que un escaner por bytes marcaria casi cualquier clase y la guarda
 * acabaria desactivada. Lo que distingue un ESC de pintura es su forma en el
 * fuente: `\u001B` (o el caracter crudo). Eso es lo que se mira.
 *
 * ## LA EXCEPCION, DECLARADA
 *
 * `ui/Osc52.kt` emite a proposito una secuencia OSC 52 para copiar al
 * portapapeles; es una capacidad de UI, no pintura de frame, y no mueve el
 * cursor ni toca la rejilla. Se excluye por NOMBRE de fichero: cualquier `.kt`
 * nuevo en `ui/` entra en la guarda sin tocar el test.
 *
 * ## CONTROL POSITIVO Y NEGATIVO
 *
 * Se prueba el detector con una cadena que SI lleva `\u001B` y con texto limpio:
 * sin eso, "cero literales" seria indistinguible de un detector que no mira.
 */
class UiHasNoAnsiLiteralTest {

    private val exclusion = "Osc52.kt"

    /** Los escapes de ESC que Kotlin admite, en cualquier caja. */
    private val patron = Regex("\\\\u001[bB]")

    /**
     * El arbol de fuentes de `ui/`.
     *
     * Gradle ejecuta los tests con el directorio del modulo como `workingDir`.
     * Si no aparece, el test FALLA en vez de dar verde mirando el vacio.
     */
    private fun fuentesUi(): List<File> {
        val dir = File("src/main/kotlin/com/km/desktop/ui")
        check(dir.isDirectory) { "no se encontro el arbol de fuentes ${dir.absolutePath}" }
        val fuentes = dir.walkTopDown().filter { it.isFile && it.name.endsWith(".kt") }.toList()
        check(fuentes.isNotEmpty()) { "la guarda no leyo ni un .kt de ${dir.absolutePath}" }
        return fuentes
    }

    private fun llevaEsc(texto: String): Boolean =
        patron.containsMatchIn(texto) || texto.contains('\u001b')

    @Test
    fun `ui no lleva literales ESC`() {
        val culpables = fuentesUi()
            .filter { it.name != exclusion }
            .filter { llevaEsc(it.readText()) }
            .map { it.name }

        assertTrue(
            culpables.isEmpty(),
            "ui/ no emite ANSI: el ESC vive en shell/ (excepcion declarada: $exclusion)\n" +
                culpables.joinToString("\n") { "  $it" },
        )
    }

    @Test
    fun `el detector ve un ESC y no marca texto limpio`() {
        assertTrue(llevaEsc("val x = \"\\u001b[2J\""), "control positivo: el escape se detecta")
        assertTrue(llevaEsc("\u001b]52;c;abc"), "control positivo: ESC crudo se detecta")
        assertFalse(llevaEsc("hola mundo"), "control negativo: texto limpio no marca")
        assertFalse(llevaEsc("usa ^[[2J inerte"), "un ESC impreso como texto no es un literal")
    }

    @Test
    fun `la excepcion de Osc52 existe y sigue siendo la unica`() {
        val fuentes = fuentesUi()
        assertTrue(
            fuentes.any { it.name == exclusion },
            "la excepcion declarada debe existir: ${fuentes.map { it.name }}",
        )
        val culpables = fuentes
            .filter { it.name != exclusion }
            .filter { llevaEsc(it.readText()) }
            .map { it.name }
        assertTrue(culpables.isEmpty(), "solo $exclusion puede llevar ESC: $culpables")
    }
}
