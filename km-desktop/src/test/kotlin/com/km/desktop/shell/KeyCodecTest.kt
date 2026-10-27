package com.km.desktop.shell

import com.km.desktop.ui.Key
import com.km.desktop.ui.KeyModifier
import com.km.desktop.ui.KeyStroke
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/**
 * 3.1 — Decodificacion de teclas del terminal.
 *
 * ## POR QUE SE PRUEBA SIN PTY
 *
 * Porque lo que puede fallar no es `read()`: es el mapeo. Un `ESC [ A` que se
 * convierte en `Esc` deja al usuario sin flechas, y una secuencia desconocida
 * convertida en "algo" mueve el foco solo. Aqui se afirma el mapeo exacto.
 */
class KeyCodecTest {

    @Test
    fun `las flechas y edicion se decodifican`() {
        assertEquals(KeyStroke(Key.Up), KeyCodec.decodeSequence("\u001b[A"))
        assertEquals(KeyStroke(Key.Down), KeyCodec.decodeSequence("\u001b[B"))
        assertEquals(KeyStroke(Key.Right), KeyCodec.decodeSequence("\u001b[C"))
        assertEquals(KeyStroke(Key.Left), KeyCodec.decodeSequence("\u001b[D"))
        assertEquals(KeyStroke(Key.Home), KeyCodec.decodeSequence("\u001b[H"))
        assertEquals(KeyStroke(Key.End), KeyCodec.decodeSequence("\u001b[F"))
        assertEquals(KeyStroke(Key.Delete), KeyCodec.decodeSequence("\u001b[3~"))
        assertEquals(KeyStroke(Key.PageUp), KeyCodec.decodeSequence("\u001b[5~"))
        assertEquals(KeyStroke(Key.PageDown), KeyCodec.decodeSequence("\u001b[6~"))
    }

    @Test
    fun `Shift-Tab llega como CSI Z`() {
        assertEquals(KeyStroke.shift(Key.Tab), KeyCodec.decodeSequence("\u001b[Z"))
    }

    @Test
    fun `un modificador no cambia la tecla base`() {
        assertEquals(KeyStroke(Key.Up), KeyCodec.decodeSequence("\u001b[1;5A"))
    }

    @Test
    fun `ESC solo es Escape y ESC con letra es Alt`() {
        assertEquals(KeyStroke(Key.Escape), KeyCodec.decodeSequence("\u001b"))
        assertEquals(KeyStroke.alt('x'), KeyCodec.decodeSequence("\u001bx"))
    }

    @Test
    fun `Alt-Enter es el salto de linea del compositor`() {
        assertEquals(
            KeyStroke(Key.Enter, setOf(KeyModifier.ALT)),
            KeyCodec.decodeSequence("\u001b\r"),
        )
    }

    @Test
    fun `los controles basicos`() {
        assertEquals(KeyStroke.ctrl('c'), KeyCodec.decode(3))
        assertEquals(KeyStroke.ctrl('l'), KeyCodec.decode(12))
        assertEquals(KeyStroke.ctrl('v'), KeyCodec.decode(22))
        assertEquals(KeyStroke(Key.Enter), KeyCodec.decode(13))
        assertEquals(KeyStroke(Key.Enter), KeyCodec.decode(10))
        assertEquals(KeyStroke(Key.Tab), KeyCodec.decode(9))
        assertEquals(KeyStroke(Key.Backspace), KeyCodec.decode(127))
        assertEquals(KeyStroke(Key.Backspace), KeyCodec.decode(8))
    }

    @Test
    fun `una tecla imprimible y una Unicode`() {
        assertEquals(KeyStroke.char('a'), KeyCodec.decode('a'.code))
        assertEquals(KeyStroke.char('\u00f1'), KeyCodec.decode('ñ'.code))
    }

    @Test
    fun `una secuencia desconocida NO se convierte en tecla`() {
        assertNull(KeyCodec.decodeSequence("\u001b[99~"))
        assertNull(KeyCodec.decodeSequence("\u001b[?25h"))
        assertNull(KeyCodec.decode(-1))
    }

    @Test
    fun `control negativo una tecla cualquiera no es una flecha`() {
        // El mapping no "adivina" por el ultimo caracter: un texto normal no es
        // una secuencia.
        assertNull(KeyCodec.decodeSequence("A"))
        assertEquals(KeyStroke.char('A'), KeyCodec.decode('A'.code))
    }
}
