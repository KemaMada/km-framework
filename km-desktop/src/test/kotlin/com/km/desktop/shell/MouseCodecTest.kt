package com.km.desktop.shell

import com.km.desktop.ui.Key
import com.km.desktop.ui.KeyModifier
import com.km.desktop.ui.KeyStroke
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * B1 — El decodificador SGR 1006, sin pty.
 *
 * ## LO QUE SE AFIRMA
 *
 * - press, release, rueda y botones que no son el izquierdo;
 * - coordenadas de 3 y 4 digitos (el tope son 9999 celdas);
 * - una secuencia truncada o con basura devuelve `null` y NO inventa un evento;
 * - el MOTION (bit 32) y un release sin boton devuelven `null`: hover y arrastre
 *   quedan fuera de alcance y un multiplexor no puede convertirlos en clic;
 * - flechas, F-keys, Alt y Shift-Tab siguen decodificandose igual, y `KeyCodec`
 *   ignora `ESC[<` como defensa.
 */
class MouseCodecTest {

    @Test
    fun `press izquierdo convierte a celdas base 0`() {
        assertEquals(
            MouseEvent.Press(button = 0, column = 9, row = 4),
            MouseCodec.decode("\u001b[<0;10;5M"),
        )
    }

    @Test
    fun `release conserva el boton y la celda`() {
        assertEquals(
            MouseEvent.Release(button = 0, column = 9, row = 4),
            MouseCodec.decode("\u001b[<0;10;5m"),
        )
    }

    @Test
    fun `rueda arriba y abajo`() {
        assertEquals(MouseEvent.Wheel(up = true, column = 2, row = 1), MouseCodec.decode("\u001b[<64;3;2M"))
        assertEquals(MouseEvent.Wheel(up = false, column = 2, row = 1), MouseCodec.decode("\u001b[<65;3;2M"))
    }

    @Test
    fun `boton derecho y medio son Other, no Press`() {
        assertEquals(MouseEvent.Other(2, 0, 0), MouseCodec.decode("\u001b[<2;1;1M"))
        assertEquals(MouseEvent.Other(1, 0, 0), MouseCodec.decode("\u001b[<1;1;1M"))
        assertTrue(MouseCodec.decode("\u001b[<2;1;1M") !is MouseEvent.Press)
    }

    @Test
    fun `acepta coordenadas de tres y cuatro digitos`() {
        assertEquals(MouseEvent.Press(0, 99, 41), MouseCodec.decode("\u001b[<0;100;42M"))
        assertEquals(MouseEvent.Press(0, 999, 999), MouseCodec.decode("\u001b[<0;1000;1000M"))
        assertEquals(MouseEvent.Wheel(false, 999, 999), MouseCodec.decode("\u001b[<65;1000;1000M"))
    }

    @Test
    fun `el movimiento SGR no se decodifica como press`() {
        // Hover/arrastre (bit 32) queda fuera de alcance: aunque un multiplexor
        // reenvie motion, no puede convertirse en clic.
        assertNull(MouseCodec.decode("\u001b[<32;10;5M"), "motion con boton izquierdo: hover")
        assertNull(MouseCodec.decode("\u001b[<35;10;5M"), "motion sin boton (3 + 32)")
        assertNull(MouseCodec.decode("\u001b[<33;10;5M"), "motion con boton medio")
        assertNull(MouseCodec.decode("\u001b[<32;10;5m"), "motion con final m")
        assertNull(MouseCodec.decode("\u001b[<96;10;5M"), "rueda con bit de motion")
    }

    @Test
    fun `un release sin boton no corresponde a ningun press`() {
        assertNull(MouseCodec.decode("\u001b[<3;10;5m"))
    }

    @Test
    fun `una secuencia truncada se descarta`() {
        assertNull(MouseCodec.decode("\u001b[<0;10;5"))
        assertNull(MouseCodec.decode("\u001b[<"))
        assertNull(MouseCodec.decode("\u001b[<0;10;"))
    }

    @Test
    fun `basura y parametros fuera de rango se descartan`() {
        assertNull(MouseCodec.decode("\u001b[<;;;M"), "parametros vacios")
        assertNull(MouseCodec.decode("\u001b[<a;1;1M"), "no numerico")
        assertNull(MouseCodec.decode("\u001b[<0;10;5X"), "final desconocido")
        assertNull(MouseCodec.decode("\u001b[<0;10;5;6M"), "cuatro parametros")
        assertNull(MouseCodec.decode("\u001b[<0;10000;1M"), "5 digitos")
        assertNull(MouseCodec.decode("\u001b[<0;0;1M"), "coordenada 0: la terminal usa base 1")
        assertNull(MouseCodec.decode("\u001b[<0;1;0M"), "fila 0")
        assertNull(MouseCodec.decode("0;10;5M"), "sin cabecera")
    }

    /**
     * REGRESION: el desvio solo ocurre con `ESC[<`.
     *
     * Las flechas, los modificadores y Alt tienen que seguir saliendo por
     * `KeyCodec`; si el raton se comiera `ESC[`, la TUI perderia el teclado.
     */
    @Test
    fun `flechas F-keys Alt y Shift-Tab siguen intactos`() {
        assertEquals(KeyStroke(Key.Up), KeyCodec.decodeSequence("\u001b[A"))
        assertEquals(KeyStroke(Key.Down), KeyCodec.decodeSequence("\u001b[B"))
        assertEquals(KeyStroke(Key.Right), KeyCodec.decodeSequence("\u001b[C"))
        assertEquals(KeyStroke(Key.Left), KeyCodec.decodeSequence("\u001b[D"))
        assertEquals(KeyStroke(Key.Home), KeyCodec.decodeSequence("\u001b[1~"))
        assertEquals(KeyStroke(Key.End), KeyCodec.decodeSequence("\u001b[4~"))
        assertEquals(KeyStroke(Key.PageUp), KeyCodec.decodeSequence("\u001b[5~"))
        assertEquals(KeyStroke(Key.PageDown), KeyCodec.decodeSequence("\u001b[6~"))
        assertEquals(KeyStroke(Key.Up), KeyCodec.decodeSequence("\u001b[1;5A"))
        assertEquals(KeyStroke.shift(Key.Tab), KeyCodec.decodeSequence("\u001b[Z"))
        assertEquals(
            KeyStroke(Key.Char('x'), setOf(KeyModifier.ALT)),
            KeyCodec.decodeSequence("\u001bx"),
        )
        assertEquals(KeyStroke(Key.Escape), KeyCodec.decodeSequence("\u001b"))
        assertNull(KeyCodec.decodeSequence("\u001b[<0;10;5M"), "el SGR nunca es una tecla")
    }
}
