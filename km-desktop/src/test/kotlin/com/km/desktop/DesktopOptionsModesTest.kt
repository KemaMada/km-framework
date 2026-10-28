package com.km.desktop

import com.km.desktop.shell.ShellMode
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * 1.4 — Seleccion de modo y variante ASCII.
 *
 * ## QUE SE AFIRMA
 *
 * - El defecto es TUI.
 * - `--accessible` y `--no-tui` eligen sus modos.
 * - `--ascii` es ortogonal: combina con cualquier modo.
 * - Dos modos a la vez es un ERROR de uso, no "gana el ultimo".
 * - `usage()` menciona los cuatro flags.
 */
class DesktopOptionsModesTest {

    @Test
    fun `por defecto TUI sin ascii`() {
        val opciones = DesktopOptions.parse(emptyArray())

        assertEquals(ShellMode.TUI, opciones.mode, "la TUI de pantallas es el defecto de G2")
        assertFalse(opciones.ascii)
    }

    @Test
    fun `no-tui elige el REPL de G1`() {
        assertEquals(ShellMode.LINE, DesktopOptions.parse(arrayOf("--no-tui")).mode)
    }

    @Test
    fun `accessible elige salida lineal y combina con ascii`() {
        val opciones = DesktopOptions.parse(arrayOf("--accessible", "--ascii"))

        assertEquals(ShellMode.ACCESSIBLE, opciones.mode)
        assertTrue(opciones.ascii, "ascii no es un modo: se combina con el elegido")
    }

    @Test
    fun `dos modos a la vez es un error de uso`() {
        val fallo = assertThrows(IllegalArgumentException::class.java) {
            DesktopOptions.parse(arrayOf("--tui", "--no-tui"))
        }
        assertTrue(
            fallo.message?.contains("--no-tui") == true,
            "el error nombra la opcion que sobra: ${fallo.message}",
        )
        assertThrows(IllegalArgumentException::class.java) {
            DesktopOptions.parse(arrayOf("--accessible", "--no-tui"))
        }
    }

    @Test
    fun `usage menciona los modos y ascii`() {
        val usage = DesktopOptions.usage()
        listOf("--tui", "--accessible", "--no-tui", "--ascii", "--theme").forEach { flag ->
            assertTrue(usage.contains(flag), "usage() debe mencionar '$flag'")
        }
    }

    /**
     * `--theme mono` es la capacidad de color minima, no un tema configurable.
     *
     * MONO no emite SGR: la jerarquia la hacen los glifos. `color` y `auto` son
     * el defecto (decide `NO_COLOR`/`TERM`/`COLORTERM`).
     */
    @Test
    fun `theme mono apaga el color y los valores desconocidos fallan`() {
        assertTrue(DesktopOptions.parse(arrayOf("--theme", "mono")).themeMono, "mono")
        assertFalse(DesktopOptions.parse(arrayOf("--theme", "color")).themeMono, "color")
        assertFalse(DesktopOptions.parse(arrayOf("--theme", "auto")).themeMono, "auto")
        assertFalse(DesktopOptions.parse(emptyArray()).themeMono, "defecto")
        assertThrows(IllegalArgumentException::class.java) {
            DesktopOptions.parse(arrayOf("--theme", "neon"))
        }
        assertThrows(IllegalArgumentException::class.java) {
            DesktopOptions.parse(arrayOf("--theme"))
        }
    }

    /**
     * CONTROL NEGATIVO: repetir el MISMO modo no es un conflicto.
     *
     * Si `elegirModo` rechazara cualquier segunda aparicion, `--no-tui --no-tui`
     * fallaria sin motivo y un script que compone flags se romperia.
     */
    @Test
    fun `control negativo repetir el mismo modo esta permitido`() {
        assertEquals(
            ShellMode.LINE,
            DesktopOptions.parse(arrayOf("--no-tui", "--no-tui")).mode,
        )
    }
}
