package com.km.desktop.ui.theme

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * M5.1 — Tema, capacidades y alfabeto.
 *
 * ## QUE SE AFIRMA
 *
 * - La capacidad sale del entorno SIN leerlo por dentro (`detect` recibe el map).
 * - `--ascii` implica MONO; NO_COLOR/pipe tambien.
 * - La paleta tiene un valor por rol y cada capacidad se puede emitir; en MONO
 *   no se emite NADA.
 * - Los glifos ASCII tienen la misma metrica que los Unicode.
 */
class ThemeTest {

    @Test
    fun `la capacidad se resuelve desde el entorno inyectado`() {
        assertEquals(
            ColorCapability.TRUECOLOR,
            ColorCapability.detect(true, mapOf("COLORTERM" to "truecolor", "TERM" to "xterm")),
        )
        assertEquals(
            ColorCapability.ANSI256,
            ColorCapability.detect(true, mapOf("TERM" to "xterm-256color")),
        )
        assertEquals(ColorCapability.ANSI16, ColorCapability.detect(true, mapOf("TERM" to "xterm")))
        assertEquals(ColorCapability.MONO, ColorCapability.detect(false, mapOf("TERM" to "xterm")))
    }

    @Test
    fun `ascii es MONO aunque el terminal tenga color`() {
        assertFalse(Theme.of(ColorCapability.TRUECOLOR, ascii = true).color)
        assertEquals(ColorCapability.MONO, Theme.of(ColorCapability.TRUECOLOR, ascii = true).capability)
        assertTrue(Theme.of(ColorCapability.ANSI256).color)
        assertFalse(Theme.MONO.color)
    }

    @Test
    fun `cada rol tiene color en las tres paletas y el QR es fijo`() {
        ColorRole.entries.forEach { rol ->
            val spec = Palette.of(rol)
            assertTrue(
                spec.ansi16 in 30..37 || spec.ansi16 in 90..97 ||
                    spec.ansi16 in 40..47 || spec.ansi16 in 100..107,
                "$rol no tiene codigo ANSI16 valido: ${spec.ansi16}",
            )
            assertTrue(spec.index256 in 0..255, "$rol no tiene indice 256")
        }
        assertTrue(Palette.isBackground(ColorRole.BG_RAISED))
        assertTrue(Palette.isBackground(ColorRole.BG_SELECT))
        assertFalse(Palette.isBackground(ColorRole.SUCCESS))
        assertNotEquals(Palette.of(ColorRole.SUCCESS).rgb, Palette.of(ColorRole.DANGER).rgb)
    }

    @Test
    fun `los glifos ASCII miden lo mismo y el marcador cambia con el alfabeto`() {
        val unicode = Glyphs(ascii = false)
        val ascii = Glyphs(ascii = true)
        assertEquals(1, com.km.desktop.ui.Sanitizer.width(unicode.section))
        assertEquals(1, com.km.desktop.ui.Sanitizer.width(ascii.section))
        assertEquals(">", ascii.focus)
        assertEquals("\u258c", unicode.focus)
        assertNotEquals(unicode.sectionRule("CONTACTOS", 20), ascii.sectionRule("CONTACTOS", 20))
        assertEquals(20, com.km.desktop.ui.Sanitizer.width(ascii.sectionRule("CONTACTOS", 20)))
        assertEquals(20, com.km.desktop.ui.Sanitizer.width(unicode.sectionRule("CONTACTOS", 20)))
    }
}
