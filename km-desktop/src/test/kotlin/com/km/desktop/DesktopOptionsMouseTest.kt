package com.km.desktop

import com.km.desktop.shell.ShellMode
import com.km.desktop.ui.MouseMode
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * B0 — `--mouse` y `--wheel-rows`, con validacion de uso.
 *
 * ## LO QUE SE AFIRMA
 *
 * - defecto `auto` y 3 filas;
 * - los tres valores y el rango 1..10;
 * - un valor desconocido falla al PARSEAR (exit 2 en `main`), no al conectar;
 * - `--mouse=on` con `--accessible`/`--no-tui` es error de uso;
 * - `usage()` declara ambos flags y, cuando se conoce, el valor efectivo.
 */
class DesktopOptionsMouseTest {

    @Test
    fun `por defecto auto y tres filas`() {
        val opciones = DesktopOptions.parse(emptyArray())
        assertEquals(MouseMode.AUTO, opciones.mouse, "auto es el defecto")
        assertEquals(DesktopOptions.DEFAULT_WHEEL_ROWS, opciones.wheelRows)
        assertEquals(3, opciones.wheelRows)
    }

    @Test
    fun `acepta auto on y off sin distinguir mayusculas`() {
        assertEquals(MouseMode.AUTO, DesktopOptions.parse(arrayOf("--mouse", "auto")).mouse)
        assertEquals(MouseMode.ON, DesktopOptions.parse(arrayOf("--mouse", "on")).mouse)
        assertEquals(MouseMode.OFF, DesktopOptions.parse(arrayOf("--mouse", "off")).mouse)
        assertEquals(MouseMode.ON, DesktopOptions.parse(arrayOf("--mouse", "ON")).mouse)
        // La forma `=` es la que usa el README y R30 (`--mouse=off`).
        assertEquals(MouseMode.OFF, DesktopOptions.parse(arrayOf("--mouse=off")).mouse)
        assertEquals(MouseMode.ON, DesktopOptions.parse(arrayOf("--mouse=on")).mouse)
        assertEquals(MouseMode.AUTO, DesktopOptions.parse(arrayOf("--mouse=auto")).mouse)
    }

    @Test
    fun `un valor de mouse desconocido o ausente falla`() {
        assertThrows(IllegalArgumentException::class.java) {
            DesktopOptions.parse(arrayOf("--mouse", "hover"))
        }
        assertThrows(IllegalArgumentException::class.java) {
            DesktopOptions.parse(arrayOf("--mouse"))
        }
    }

    @Test
    fun `wheel-rows acepta 1 y 10 y rechaza el resto`() {
        assertEquals(1, DesktopOptions.parse(arrayOf("--wheel-rows", "1")).wheelRows)
        assertEquals(10, DesktopOptions.parse(arrayOf("--wheel-rows", "10")).wheelRows)
        assertEquals(5, DesktopOptions.parse(arrayOf("--wheel-rows=5")).wheelRows)
        listOf("0", "11", "-3", "tres", "").forEach { valor ->
            assertThrows(IllegalArgumentException::class.java, { DesktopOptions.parse(arrayOf("--wheel-rows", valor)) }, valor)
        }
        assertThrows(IllegalArgumentException::class.java) {
            DesktopOptions.parse(arrayOf("--wheel-rows"))
        }
        assertThrows(IllegalArgumentException::class.java) {
            DesktopOptions.parse(arrayOf("--wheel-rows=11"))
        }
    }

    /**
     * B0.4 / CONTROL NEGATIVO: hoy `--mouse=on --accessible` arrancaria.
     *
     * Tiene que ser un error de USO: el operador pidio raton en un modo que no
     * direcciona el cursor.
     */
    @Test
    fun `mouse on con accessible o no-tui es error de uso`() {
        assertThrows(IllegalArgumentException::class.java) {
            DesktopOptions.parse(arrayOf("--mouse", "on", "--accessible"))
        }
        assertThrows(IllegalArgumentException::class.java) {
            DesktopOptions.parse(arrayOf("--no-tui", "--mouse", "on"))
        }
        assertThrows(IllegalArgumentException::class.java) {
            DesktopOptions.parse(arrayOf("--mouse=on", "--accessible"))
        }
        // `auto` y `off` conviven con esos modos y se resuelven a OFF.
        assertEquals(
            MouseMode.AUTO,
            DesktopOptions.parse(arrayOf("--mouse", "auto", "--no-tui")).mouse,
        )
        assertEquals(
            MouseMode.OFF,
            DesktopOptions.parse(arrayOf("--accessible", "--mouse", "off")).mouse,
        )
        // `on` con la TUI es valido; el soporte real se comprueba al arrancar.
        assertEquals(
            ShellMode.TUI,
            DesktopOptions.parse(arrayOf("--mouse", "on", "--tui")).mode,
        )
    }

    @Test
    fun `usage declara mouse wheel-rows y el valor efectivo cuando se conoce`() {
        val usage = DesktopOptions.usage()
        assertTrue(usage.contains("--mouse <auto|on|off>"), usage)
        assertTrue(usage.contains("--wheel-rows <1..10>"), usage)
        assertTrue(usage.contains("--mouse=off"), "declara como recuperar la seleccion nativa")
        assertTrue(
            DesktopOptions.usage(mouse = true).contains("valor efectivo ahora): ON"),
            DesktopOptions.usage(mouse = true),
        )
        assertTrue(
            DesktopOptions.usage(mouse = false).contains("valor efectivo ahora): OFF"),
            DesktopOptions.usage(mouse = false),
        )
        assertFalse(DesktopOptions.usage().contains("valor efectivo ahora"), "sin dato no se inventa")
    }
}
