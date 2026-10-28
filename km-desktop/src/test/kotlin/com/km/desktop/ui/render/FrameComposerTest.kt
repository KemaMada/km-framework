package com.km.desktop.ui.render

import com.km.desktop.ui.LinePalette
import com.km.desktop.ui.Sanitizer
import com.km.desktop.ui.Screen
import com.km.desktop.ui.Size
import com.km.desktop.ui.layout.Chrome
import com.km.desktop.ui.layout.Layout
import com.km.desktop.ui.layout.Rect
import com.km.desktop.ui.theme.Attr
import com.km.desktop.ui.theme.Style
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * M1.4 — El compositor: carcasa, cuerpo, aviso y overlay.
 *
 * ## QUE SE AFIRMA
 *
 * - La caja se compone celda a celda con el ancho de la rejilla, incluidos los
 *   rellenos (no quedan filas "sin verticales" que delatarian el marco).
 * - El overlay no SANGRA: ninguna fila sale del ancho del frame, y no se escribe
 *   fuera de su banda.
 * - El QR y el foco son estilos por celda; la paleta del QR gana.
 * - M5: un overlay mas alto que la pantalla se VENTANEA (nunca devuelve mas
 *   filas que el frame ni parte el marco).
 */
class FrameComposerTest {

    private val viewport = Size(80, 24)
    private val frame = Layout.frame(viewport, Chrome.BOX)
    private val todo = Rect(0, 0, viewport.columns, viewport.rows)

    private fun caja(
        size: Size = viewport,
        body: List<BodyRow>,
        banner: BodyRow? = null,
    ): Grid = FrameComposer.compose(
        frame = Layout.frame(size, Chrome.BOX),
        chrome = Chrome.BOX,
        content = Rect(0, 0, size.columns, size.rows),
        header = listOf(BodyRow("KeyMessage")),
        body = body,
        banner = banner,
    )

    private fun cuerpo(vararg filas: BodyRow) = caja(body = filas.toList())

    private fun anchoFila(grid: Grid, fila: Int) = Sanitizer.width(grid.rowText(fila))

    @Test
    fun `la caja se compone con el ancho exacto del viewport`() {
        val grid = cuerpo(BodyRow("hola"), BodyRow("adios"))
        assertEquals(viewport.rows, grid.height)
        (0 until grid.height).forEach { fila ->
            assertEquals(viewport.columns, anchoFila(grid, fila), "fila $fila")
        }
        assertTrue(grid.rowText(0).startsWith("\u250c KeyMessage"), grid.rowText(0))
        assertTrue(grid.rowText(viewport.rows - 1).startsWith("\u2514"))
        assertTrue(grid.rowText(1).startsWith("\u2502 hola"), grid.rowText(1))
        assertTrue(grid.rowText(1).endsWith("\u2502"))
    }

    @Test
    fun `las filas vacias del cuerpo llevan verticales`() {
        val grid = cuerpo(BodyRow("solo una"))
        val vacia = grid.rowText(10)
        assertTrue(vacia.startsWith("\u2502 ") && vacia.endsWith("\u2502"), "fila vacia con marco: '$vacia'")
        assertEquals(viewport.columns, anchoFila(grid, 10))
    }

    @Test
    fun `un separador se pinta como regla con titulo`() {
        val grid = cuerpo(BodyRow("CONTACTOS", separator = true))
        val fila = grid.rowText(1)
        assertTrue(fila.startsWith("\u251c CONTACTOS"), fila)
        assertTrue(fila.endsWith("\u2524"), fila)
    }

    @Test
    fun `el foco es reverse por celda y ademas lleva glifo en el texto`() {
        val grid = cuerpo(BodyRow("> ana", focus = true))
        assertTrue(grid[2, 1].style.attrs.contains(Attr.REVERSE), "la fila lleva reverse")
        assertTrue(grid.rowText(1).contains("> ana"), "el glifo va en el texto, no solo en el color")
    }

    @Test
    fun `el QR manda sobre el foco y no hereda el tema`() {
        val estilo = FrameComposer.styleOf(
            BodyRow("\u2588\u2588", focus = true, palette = LinePalette.QR),
        )
        assertTrue(estilo.isQr)
        assertFalse(estilo.attrs.contains(Attr.REVERSE), "el QR no se invierte con el foco")
        assertEquals(Style.QR, estilo)
    }

    @Test
    fun `el aviso ocupa la ultima fila del cuerpo y no mueve el layout`() {
        val sinAviso = caja(body = listOf(BodyRow("uno")))
        val conAviso = caja(body = listOf(BodyRow("uno")), banner = BodyRow("! fallo"))
        assertEquals(sinAviso.height, conAviso.height)
        val ultima = frame.body.bottom - 1
        assertTrue(conAviso.rowText(ultima).contains("! fallo"), conAviso.rowText(ultima))
        assertFalse(sinAviso.rowText(ultima).contains("! fallo"))
    }

    @Test
    fun `el overlay de 60 y 88 no sangra`() {
        listOf(60, 88).forEach { ancho ->
            val size = Size(ancho, 20)
            val base = caja(size, listOf(BodyRow("fondo")))
            val conOverlay = FrameComposer.paintOverlay(
                base,
                OverlayFrame("CONFIRMAR", listOf("titulo", "", "[ENTER] confirmar   [ESC] cancelar")),
                ascii = false,
            )
            assertEquals(20, conOverlay.height)
            (0 until conOverlay.height).forEach { fila ->
                assertEquals(ancho, anchoFila(conOverlay, fila), "overlay $ancho fila $fila")
            }
            val texto = (0 until conOverlay.height).joinToString("\n") { conOverlay.rowText(it) }
            assertTrue(texto.contains("CONFIRMAR"), "titulo del overlay")
            assertTrue(texto.contains("fondo"), "la pantalla de debajo se conserva")
        }
    }

    /**
     * M5: la ayuda a 40x6 no puede devolver 12 filas. Se ventanea con
     * `[n/m] PgUp/PgDn` y el alto del frame no cambia. Antes se devolvia la caja
     * entera y el escritor recortaba el marco.
     */
    @Test
    fun `un overlay mas alto que la pantalla se ventanea y no crece`() {
        val size = Size(40, 8)
        val base = caja(size, listOf(BodyRow("fondo")))
        val contenido = (1..10).map { "linea $it" }
        val conOverlay = FrameComposer.paintOverlay(base, OverlayFrame("AYUDA", contenido), ascii = false)

        assertEquals(8, conOverlay.height, "el overlay NO crece fuera del frame")
        (0 until conOverlay.height).forEach { fila ->
            assertEquals(40, anchoFila(conOverlay, fila), "fila $fila del overlay")
        }
        val texto = (0 until conOverlay.height).joinToString("\n") { conOverlay.rowText(it) }
        assertTrue(texto.contains("linea 1"), texto)
        assertTrue(texto.contains("PgUp/PgDn"), "declara que hay mas: $texto")
        assertTrue(texto.contains("\u2514"), "el marco se cierra: $texto")

        val abajo = FrameComposer.paintOverlay(
            base,
            OverlayFrame("AYUDA", contenido, offset = 7),
            ascii = false,
        )
        val textoAbajo = (0 until abajo.height).joinToString("\n") { abajo.rowText(it) }
        assertTrue(textoAbajo.contains("linea 10"), textoAbajo)
        assertFalse(textoAbajo.contains("linea 1 "), "la ventana se movio: $textoAbajo")
    }

    @Test
    fun `el overlay se centra y no escribe fuera de la banda`() {
        val base = caja(viewport, listOf(BodyRow("fondo")))
        val caja = com.km.desktop.ui.Box.frame("AYUDA", listOf("uno", "dos"), 80, false)
        val conOverlay = FrameComposer.paintOverlay(base, OverlayFrame("AYUDA", listOf("uno", "dos")), ascii = false)
        val inicio = ((24 - caja.size) / 2).coerceAtLeast(1)
        assertTrue(conOverlay.rowText(inicio).startsWith("\u250c AYUDA"), conOverlay.rowText(inicio))
        assertFalse(conOverlay.rowText(inicio - 1).contains("AYUDA"))
    }

    @Test
    fun `BANDS deja las bandas listas y el cuerpo sin marco`() {
        val bands = Layout.frame(viewport, Chrome.BANDS)
        val content = Layout.contentFor(Screen.INICIO, viewport)
        val grid = FrameComposer.compose(
            frame = bands,
            chrome = Chrome.BANDS,
            content = content,
            header = listOf(BodyRow("KeyMessage \u2503 INICIO"), BodyRow("INICIO  FRASE")),
            body = listOf(BodyRow("hola")),
            status = BodyRow("estado"),
            footer = BodyRow("teclas"),
        )
        assertEquals(24, grid.height)
        (0 until grid.height).forEach { assertEquals(80, anchoFila(grid, it)) }
        assertTrue(grid.rowText(bands.body.y).contains("hola"))
        assertFalse(grid.rowText(bands.body.y).contains("\u2502"), "sin caja en BANDS")
        assertTrue(grid.rowText(bands.status.y).contains("estado"))
        assertTrue(grid.rowText(bands.footer.y).contains("teclas"))
        assertTrue(grid.rowText(bands.header.y).startsWith(" ".repeat(content.x)))
    }

    @Test
    fun `NONE no pinta carcasa`() {
        val none = Layout.frame(Size(85, 43), Chrome.NONE)
        val grid = FrameComposer.compose(
            frame = none,
            chrome = Chrome.NONE,
            content = Rect(0, 0, 85, 43),
            body = listOf(BodyRow("\u2588\u2588", palette = LinePalette.QR)),
        )
        assertEquals(43, grid.height)
        assertFalse(grid.rowText(0).contains("\u250c"))
        // El QR se centra: la primera celda es aire y el codigo cae en el medio.
        assertFalse(grid[0, 0].style.isQr, "a la izquierda del QR no hay blanco")
        assertTrue(grid[41, 0].style.isQr, "el codigo empieza centrado en 85 columnas")
        assertEquals(Screen.COMPARTIR.ordinal >= 0, true)
    }
}
