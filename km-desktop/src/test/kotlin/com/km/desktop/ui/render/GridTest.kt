package com.km.desktop.ui.render

import com.km.desktop.ui.Sanitizer
import com.km.desktop.ui.theme.Attr
import com.km.desktop.ui.theme.Style
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * M1.3 — La rejilla de celdas.
 *
 * ## QUE SE AFIRMA
 *
 * - Toda fila mide EXACTAMENTE el ancho de la rejilla, con ASCII, CJK y emoji.
 * - [Grid.text] es la unica puerta: sanea, de modo que `ESC[2J` se pinta inerte
 *   y el ancho no cambia (R3/R24). Es el control que impide "arreglar" el layout
 *   metiendo ANSI en el texto medido.
 * - Un glifo ancho ocupa dos celdas y su continuacion es vacia: no se parte, no
 *   se duplica y no hay media celda huerfana al sobrescribir.
 */
class GridTest {

    private fun anchoDeFila(texto: String) = Sanitizer.width(texto)

    @Test
    fun `toda fila mide el ancho de la rejilla`() {
        val casos = listOf(
            "hola",
            "日本語テスト",
            "emoji 😀 y texto",
            "e\u0301 combinante",
            "",
        )
        casos.forEach { crudo ->
            val grid = Grid(24, 1)
            grid.putRow(0, crudo)
            assertEquals(24, anchoDeFila(grid.rowText(0)), "ancho con '$crudo'")
            assertEquals(24, grid.row(0).size)
        }
    }

    @Test
    fun `un ESC se pinta inerte y no mueve el ancho`() {
        val texto = Grid.text("hola\u001b[2Jmundo", 20).joinToString("") { it.text }
        assertFalse(texto.contains('\u001b'), "ningun ESC crudo: $texto")
        assertTrue(texto.contains("^[[2J"), "la secuencia se ve en notacion caret: $texto")
        assertEquals(20, anchoDeFila(texto))
    }

    @Test
    fun `un glifo ancho ocupa dos celdas y la segunda es continuacion vacia`() {
        val celdas = Grid.text("日x", 4)
        assertEquals("日", celdas[0].text)
        assertTrue(celdas[0].isWide)
        assertTrue(celdas[1].continuation)
        assertEquals("", celdas[1].text, "la continuacion no lleva glifo")
        assertEquals("x", celdas[2].text)
        assertEquals(" ", celdas[3].text, "se rellena hasta el ancho")
        assertEquals(4, anchoDeFila(celdas.joinToString("") { it.text }))
    }

    @Test
    fun `un glifo ancho que no cabe entero se corta antes y no se parte`() {
        val celdas = Grid.text("日本", 3)
        assertEquals("日", celdas[0].text)
        assertTrue(celdas[1].continuation)
        assertEquals(" ", celdas[2].text, "no se pinta media celda del segundo glifo")
        assertEquals(3, anchoDeFila(celdas.joinToString("") { it.text }))
    }

    @Test
    fun `truncar no parte glifos ni mide de mas`() {
        assertEquals("abc", Grid.text("abcdef", 3).joinToString("") { it.text })
        assertEquals(3, Grid.text("abcdef", 3).size)
        assertEquals("", Grid.text("abc", 0).joinToString("") { it.text })
    }

    @Test
    fun `un combinante se pega al glifo anterior`() {
        val celdas = Grid.text("e\u0301x", 3)
        assertEquals("e\u0301", celdas[0].text, "el acento no ocupa celda propia")
        assertEquals("x", celdas[1].text)
        assertEquals(3, anchoDeFila(celdas.joinToString("") { it.text }), "el acento no suma ancho")
    }

    @Test
    fun `sobrescribir media celda ancha no deja un glifo huerfano`() {
        val grid = Grid(6, 1)
        grid.putRow(0, "日本")
        assertEquals("日", grid[0, 0].text)

        // Cae sobre la CONTINUACION del primer glifo: se borra el glifo entero.
        grid.put(1, 0, "x")
        assertEquals(" ", grid[0, 0].text, "sin medio glifo huerfano")
        assertEquals("x", grid[1, 0].text)

        // Cae sobre la PRIMERA celda: se borra su continuacion.
        val otro = Grid(6, 1)
        otro.putRow(0, "日本")
        otro.put(0, 0, "z")
        assertEquals("z", otro[0, 0].text)
        assertEquals(" ", otro[1, 0].text, "la continuacion se limpia")
        assertEquals(6, anchoDeFila(otro.rowText(0)))
    }

    @Test
    fun `el estilo viaja por celda y el QR se reconoce`() {
        val grid = Grid(10, 1)
        grid.putRow(0, "qr", Style.QR)
        assertTrue(grid[0, 0].style.isQr)
        assertTrue(grid[9, 0].style.isQr, "el relleno hereda el estilo de la fila")

        grid.putRow(0, "foco", Style.FOCUS)
        assertTrue(grid[0, 0].style.attrs.contains(Attr.REVERSE))
        assertFalse(grid[0, 0].style.isQr)
    }

    @Test
    fun `copy no comparte celdas con el original`() {
        val original = Grid(4, 1)
        original.putRow(0, "aaaa")
        val copia = original.copy()
        copia.putRow(0, "bbbb")
        assertEquals("aaaa", original.rowText(0))
        assertEquals("bbbb", copia.rowText(0))
    }

    @Test
    fun `una escritura fuera de la rejilla no inventa celdas`() {
        val grid = Grid(4, 2)
        assertEquals(0, grid.put(4, 0, "x"), "columna fuera")
        assertEquals(0, grid.put(0, 2, "x"), "fila fuera")
        assertEquals(4, anchoDeFila(grid.rowText(0)))
    }

    @Test
    fun `Grid of construye una rejilla con el ancho pedido`() {
        val grid = Grid.of(listOf("uno", "dos", "tres"), 8)
        assertEquals(3, grid.height)
        assertEquals(8, grid.width)
        (0 until 3).forEach { assertEquals(8, anchoDeFila(grid.rowText(it))) }
    }
}
