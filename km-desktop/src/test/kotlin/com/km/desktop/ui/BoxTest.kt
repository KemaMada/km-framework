package com.km.desktop.ui

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * 2.6 — Las cajas son RECTAS y miden lo que dicen medir.
 *
 * ## EL INVARIANTE
 *
 * Toda linea mide exactamente el ancho pedido. Si una mide de menos la caja se
 * ve mellada; si mide de mas se sale del terminal y desplaza el resto. Por eso
 * cada test comprueba el ancho con [Sanitizer.width], no `length`: los caracteres
 * CJK ocupan dos celdas y son el caso que rompe un `length`.
 *
 * ## LO QUE NO SE PINTA
 *
 * Esquinas redondeadas, pills y tarjetas: la gramatica visual es la de la TUI
 * Android (`┌ ┐ └ ┘ │ ├ ┤ ─`). El test lo niega explicitamente.
 */
class BoxTest {

    private fun assertAncho(lineas: List<String>, ancho: Int) {
        lineas.forEachIndexed { i, linea ->
            assertEquals(ancho, Sanitizer.width(linea), "linea $i mide distinto de $ancho: [$linea]")
        }
    }

    @Test
    fun `la caja completa mide exactamente el ancho`() {
        val caja = Box.frame("KeyMessage", listOf("hola", "x".repeat(100), ""), 40)

        assertEquals(5, caja.size)
        assertAncho(caja, 40)
        assertTrue(caja.first().startsWith("\u250C"), "esquina superior izquierda recta")
        assertTrue(caja.first().endsWith("\u2510"), "esquina superior derecha recta")
        assertTrue(caja.last().startsWith("\u2514"))
        assertTrue(caja.last().endsWith("\u2518"))
    }

    @Test
    fun `el titulo va en el borde y la linea larga se recorta`() {
        val caja = Box.frame("KeyMessage", listOf("x".repeat(100)), 40)

        assertTrue(caja.first().contains("KeyMessage"))
        assertTrue(caja[1].contains("\u2026"), "el recorte se declara con elipsis")
        assertAncho(caja, 40)
    }

    @Test
    fun `CJK y emoji no rompen la caja`() {
        val caja = Box.frame("", listOf("\u6f22\u5b57", "\uD83D\uDE00 hola"), 20)

        assertAncho(caja, 20)
    }

    @Test
    fun `ninguna esquina es redondeada`() {
        val glifos = Box.frame("x", listOf("y"), 20).joinToString("")

        for (redondeada in listOf("\u256D", "\u256E", "\u256F", "\u2570", "\u25CF")) {
            assertFalse(glifos.contains(redondeada), "aparece un glifo redondeado: $redondeada")
        }
    }

    @Test
    fun `el modo ASCII usa el mismo alfabeto de marco`() {
        val caja = Box.frame("KeyMessage", listOf("hola"), 30, ascii = true)

        assertAncho(caja, 30)
        assertTrue(caja.first().startsWith("+"))
        assertTrue(caja.first().endsWith("+"))
        assertTrue(caja.all { linea -> linea.none { it in "\u2500\u2502\u250C\u2510\u2514\u2518" } })
    }

    @Test
    fun `el separador con titulo tambien mide`() {
        val linea = Box.separator(40, "CONTACTOS")

        assertEquals(40, Sanitizer.width(linea))
        assertTrue(linea.startsWith("\u251C"))
        assertTrue(linea.endsWith("\u2524"))
        assertTrue(linea.contains("CONTACTOS"))
    }

    @Test
    fun `una inyeccion de terminal no rompe ni mueve la caja`() {
        val linea = Box.line("hola\u001B[2J", 20)

        assertEquals(20, Sanitizer.width(linea))
        assertTrue(linea.contains("^["), "el control se ve como texto")
        assertFalse(linea.contains('\u001B'))
    }

    @Test
    fun `menos de cuatro columnas no cabe un marco`() {
        assertThrows(IllegalArgumentException::class.java) { Box.top("", 3) }
        assertThrows(IllegalArgumentException::class.java) { Box.line("x", 3) }
    }

    @Test
    fun `el aviso de terminal pequena dice el minimo`() {
        assertNull(Box.tooSmall(Size(60, 20)))
        assertNull(Box.tooSmall(Size(120, 40)))

        val aviso = Box.tooSmall(Size(59, 20))
        assertTrue(aviso!!.contains("60x20"))
        assertTrue(aviso.contains("--no-tui"))
    }
}
