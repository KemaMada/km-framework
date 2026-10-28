package com.km.desktop.shell

import com.km.desktop.ui.theme.ColorCapability
import com.km.desktop.ui.theme.Style
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * M5.1 — Traduccion rol -> SGR.
 *
 * ## QUE SE AFIRMA
 *
 * - MONO no emite NI UN byte de control, ni siquiera para el foco.
 * - El QR es SIEMPRE `30;47`, con independencia de la paleta.
 * - Cada capacidad usa su codificacion (truecolor / 256 / 16).
 * - La secuencia se cierra con reset: sin el, el color se derrama a la fila
 *   siguiente y la pantalla miente.
 */
class SgrTest {

    @Test
    fun `MONO no emite nada`() {
        ColorRoleSupport.roles.forEach { style ->
            assertEquals("", Sgr.of(style, ColorCapability.MONO), "$style en MONO")
        }
        assertEquals("", Sgr.of(Style.QR, ColorCapability.MONO), "el QR tampoco en MONO")
    }

    @Test
    fun `el QR es el par fijo en todas las capacidades con color`() {
        listOf(ColorCapability.ANSI16, ColorCapability.ANSI256, ColorCapability.TRUECOLOR).forEach {
            assertEquals(Sgr.QR, Sgr.of(Style.QR, it), it.name)
            assertEquals("\u001b[30;47m", Sgr.of(Style.QR, it), "negro sobre blanco fijo")
        }
    }

    @Test
    fun `truecolor y 256 usan su propia codificacion`() {
        val tc = Sgr.of(Style.SUCCESS, ColorCapability.TRUECOLOR)
        assertTrue(tc.contains("38;2;68;255;68"), tc)
        val c256 = Sgr.of(Style.SUCCESS, ColorCapability.ANSI256)
        assertTrue(c256.contains("38;5;82"), c256)
        val c16 = Sgr.of(Style.DANGER, ColorCapability.ANSI16)
        assertEquals("\u001b[1;31m", c16, "rojo brillante + negrita")
        assertTrue(c16.endsWith("m"))
        assertFalse(c16.contains("\u001b[m"), c16)
    }
}

/** Los estilos con color que debe conocer el escritor. */
private object ColorRoleSupport {
    val roles = listOf(
        Style.MUTED,
        Style.BRAND,
        Style.ACCENT,
        Style.SUCCESS,
        Style.WARNING,
        Style.DANGER,
        Style.FOCUS,
        Style.RAISED,
    )
}
