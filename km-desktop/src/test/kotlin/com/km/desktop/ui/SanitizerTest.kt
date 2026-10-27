package com.km.desktop.ui

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * 2.5 — El saneo es la puerta por la que NO pasa una inyeccion de terminal.
 *
 * ## LOS DOS CASOS DE LA SPEC R3
 *
 * `ESC[2J` (borrar pantalla) y `ESC]52;c;...` (escribir el portapapeles) tienen
 * que quedar como TEXTO INERTE. No se borran en silencio: se escriben en
 * notacion caret, para que se vea que habia un control y no se confunda con
 * contenido.
 *
 * ## EL ANCHO
 *
 * Se mide despues de sanear. Un texto con una secuencia de control mide lo que
 * se ve, no lo que ocupa la secuencia: si no, la caja se rompe.
 */
class SanitizerTest {

    @Test
    fun `ESC y CSI quedan como texto inerte con notacion caret`() {
        assertEquals("^[[2J", Sanitizer.text("\u001B[2J"))
        assertEquals("^[[31mrojo", Sanitizer.text("\u001B[31mrojo"))
    }

    @Test
    fun `OSC 52 no puede escribir el portapapeles`() {
        val ataque = "\u001B]52;c;YWJj\u0007"
        val saneado = Sanitizer.text(ataque)

        assertEquals("^[]52;c;YWJj^G", saneado)
        assertFalse(saneado.contains('\u001B'), "no queda ningun ESC activo")
        assertFalse(saneado.contains('\u0007'), "no queda ningun BEL activo")
    }

    @Test
    fun `los C0 restantes se notan y los C1 se escriben como escape`() {
        assertEquals("^@", Sanitizer.text("\u0000"), "NUL")
        assertEquals("^?", Sanitizer.text("\u007F"), "DEL")
        assertEquals("\\u009B", Sanitizer.text("\u009B"), "CSI C1")
    }

    @Test
    fun `CR LF y TAB son separacion, no control`() {
        assertEquals("a b c d", Sanitizer.text("a\nb\rc\td"))
        assertEquals(listOf("a", "b", "c"), Sanitizer.lines("a\nb\r\nc"))
    }

    @Test
    fun `los bidi y zero-width se eliminan`() {
        assertEquals("ab", Sanitizer.text("a\u202Eb"))
        assertEquals("ab", Sanitizer.text("a\u200Bb"))
        assertFalse(Sanitizer.text("a\u2066b").contains('\u2066'))
    }

    @Test
    fun `el ancho es el de un terminal monoespaciado`() {
        assertEquals(4, Sanitizer.width("hola"))
        assertEquals(3, Sanitizer.width("ano"), "sin acentos en el codigo")
        assertEquals(1, Sanitizer.width("\u00e1"), "acento: una celda")
        assertEquals(2, Sanitizer.width("\u6f22"), "CJK: dos celdas")
        assertEquals(2, Sanitizer.width("\uD83D\uDE00"), "emoji: dos celdas")
        assertEquals(1, Sanitizer.width("e\u0301"), "combinante: cero celdas")
    }

    @Test
    fun `truncar respeta el ancho y nunca lo supera`() {
        assertEquals("abc", Sanitizer.truncate("abc", 4), "cabe: no se toca")
        assertEquals("abc\u2026", Sanitizer.truncate("abcdef", 4))
        assertEquals(4, Sanitizer.width(Sanitizer.truncate("abcdef", 4)))
        // Dos CJK (4 celdas) + elipsis (1) = 5: la 'a' siguiente no cabe.
        assertEquals("\u6f22\u5b57\u2026", Sanitizer.truncate("\u6f22\u5b57abc", 5))
        assertEquals("", Sanitizer.truncate("abc", 0))
    }

    @Test
    fun `truncar con elipsis ASCII`() {
        val corto = Sanitizer.truncate("abcdef", 5, Sanitizer.ASCII_ELLIPSIS)

        assertEquals(5, Sanitizer.width(corto))
        assertEquals("ab...", corto)
    }

    @Test
    fun `pad deja exactamente las columnas pedidas`() {
        assertEquals("ab   ", Sanitizer.pad("ab", 5))
        assertEquals("abc\u2026", Sanitizer.pad("abcdef", 4))
        assertEquals(5, Sanitizer.width(Sanitizer.pad("ab", 5)))
    }

    @Test
    fun `hasControl detecta lo que el saneo cambia`() {
        assertTrue(Sanitizer.hasControl("hola\u001B[2J"))
        assertFalse(Sanitizer.hasControl("hola"))
    }
}
