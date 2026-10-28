package com.km.desktop.ui.layout

import com.km.desktop.ui.Screen
import com.km.desktop.ui.Size
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * M1.1/M1.2 — La geometria del frame y del contenido.
 *
 * ## QUE SE AFIRMA
 *
 * - [Chrome.BOX] reproduce la caja actual: 1 fila arriba, `rows-2` de cuerpo, 1
 *   abajo. Es la condicion de la paridad de M2.
 * - Las bandas de [Chrome.BANDS] son contiguas, no se solapan y suman el alto.
 * - El contenido se centra y respeta `maxWidth` y el padding adaptativo.
 * - Con pocas filas se degrada quitando bandas, nunca con alturas negativas.
 * - `bodyRows` es la unica fuente del alto de una lista.
 */
class LayoutTest {

    private fun assertBandasContiguas(frame: Frame, alto: Int) {
        val bandas = frame.bands.filter { !it.isEmpty }
        assertEquals(0, bandas.first().y, "la primera banda empieza en 0")
        bandas.zipWithNext().forEach { (a, b) ->
            assertEquals(a.bottom, b.y, "hueco o solape entre $a y $b")
        }
        assertEquals(alto, bandas.last().bottom, "las bandas cubren el alto exacto")
    }

    @Test
    fun `BOX es la caja actual en los tres tamanos del gate`() {
        listOf(Size(60, 20), Size(80, 24), Size(120, 40)).forEach { viewport ->
            val frame = Layout.frame(viewport, Chrome.BOX)
            assertEquals(Rect(0, 0, viewport.columns, 1), frame.header, "borde superior")
            assertEquals(
                Rect(0, 1, viewport.columns, viewport.rows - 2),
                frame.body,
                "cuerpo ${viewport.columns}x${viewport.rows}",
            )
            assertEquals(
                Rect(0, viewport.rows - 1, viewport.columns, 1),
                frame.status,
                "borde inferior",
            )
            assertTrue(frame.footer.isEmpty, "BOX no tiene pie")
            // bodyRows usa SIEMPRE el chrome de la pantalla (M5: BANDS), no el
            // frame de BOX que se acaba de construir.
            assertEquals(viewport.rows - 4, Layout.bodyRows(Screen.INICIO, viewport))
            assertBandasContiguas(frame, viewport.rows)
        }
    }

    @Test
    fun `BANDS reparte cabecera cuerpo estado y pie sin solapes`() {
        val frame = Layout.frame(Size(120, 40), Chrome.BANDS)
        assertEquals(2, frame.header.h, "cabecera de dos filas")
        assertEquals(1, frame.status.h, "barra de estado de una fila")
        assertEquals(1, frame.footer.h, "pie de una fila")
        assertEquals(36, frame.body.h, "el resto es cuerpo")
        assertEquals(2, frame.body.y)
        assertEquals(38, frame.status.y)
        assertEquals(39, frame.footer.y)
        assertBandasContiguas(frame, 40)

        val ochenta = Layout.frame(Size(80, 24), Chrome.BANDS)
        assertEquals(20, ochenta.body.h)
        assertBandasContiguas(ochenta, 24)
    }

    @Test
    fun `NONE es una sola region`() {
        val frame = Layout.frame(Size(85, 43), Chrome.NONE)
        assertEquals(Rect(0, 0, 85, 43), frame.body)
        assertTrue(frame.header.isEmpty)
        assertTrue(frame.status.isEmpty)
        assertTrue(frame.footer.isEmpty)
        // bodyRows usa el chrome de la pantalla (M5: BANDS): 43 - 4 bandas. El
        // chrome NONE es del camino sin marco del QR.
        assertEquals(39, Layout.bodyRows(Screen.COMPARTIR, Size(85, 43)))
    }

    @Test
    fun `pocas filas degradan bandas y nunca dan alturas negativas`() {
        val tres = Layout.frame(Size(80, 3), Chrome.BANDS)
        assertEquals(2, tres.header.h)
        assertEquals(1, tres.body.h)
        assertEquals(0, tres.status.h)
        assertEquals(0, tres.footer.h)
        assertBandasContiguas(tres, 3)

        val dos = Layout.frame(Size(80, 2), Chrome.BOX)
        assertEquals(1, dos.header.h)
        assertEquals(0, dos.body.h)
        assertEquals(1, dos.status.h)

        val una = Layout.frame(Size(80, 1), Chrome.BOX)
        assertEquals(1, una.header.h)
        assertTrue(una.body.isEmpty)
        assertTrue(una.status.isEmpty)

        val cero = Layout.frame(Size(80, 0), Chrome.BOX)
        assertTrue(cero.bands.all { it.isEmpty })
    }

    @Test
    fun `el padding es de una celda y no come el ancho util`() {
        assertEquals(1, Layout.padding(60), "una celda a cada lado, tambien al minimo")
        assertEquals(1, Layout.padding(69))
        assertEquals(1, Layout.padding(70))
        assertEquals(1, Layout.padding(120))
        assertEquals(0, Layout.padding(1), "sin sitio para margen")
    }

    @Test
    fun `el contenido es de ancho completo y solo se centra con maxWidth`() {
        val completo = Layout.content(Size(120, 40), null)
        assertEquals(118, completo.w, "120 menos el padding de una celda")
        assertEquals(1, completo.x)

        val formulario = Layout.content(Size(120, 40), Layout.MAX_WIDTH_FORM)
        assertEquals(60, formulario.w)
        assertEquals(30, formulario.x, "el formulario si se centra: 30 a cada lado")

        val minimo = Layout.content(Size(60, 20), null)
        assertEquals(58, minimo.w)
        assertEquals(1, minimo.x)

        // El QR manda sobre el ancho: sin padding, 85 y 89 caben exactos.
        assertEquals(Size(89, 47).columns, Layout.contentFor(Screen.COMPARTIR, Size(89, 47)).w)
        assertEquals(0, Layout.contentFor(Screen.COMPARTIR, Size(89, 47)).x)
    }

    @Test
    fun `maxWidth solo limita los formularios y el QR no se limita`() {
        assertEquals(null, Layout.maxWidth(Screen.INICIO), "lista de chats de ancho completo")
        assertEquals(null, Layout.maxWidth(Screen.DIAGNOSTICO))
        assertEquals(null, Layout.maxWidth(Screen.CONVERSACION), "el hilo usa todo el ancho")
        assertEquals(60, Layout.maxWidth(Screen.FRASE))
        assertEquals(60, Layout.maxWidth(Screen.RESTAURAR))
        assertEquals(null, Layout.maxWidth(Screen.COMPARTIR), "el QR manda sobre el ancho")
        assertEquals(null, Layout.maxWidth(Screen.IMPORTAR))
        assertEquals(Chrome.BANDS, Layout.chrome(Screen.INICIO), "M5 pinta por bandas en las siete")
    }

    @Test
    fun `bodyRows es el alto del cuerpo y nunca negativo`() {
        val viewport = Size(80, 24)
        assertEquals(Layout.frame(viewport, Layout.chrome(Screen.INICIO)).body.h, Layout.bodyRows(Screen.INICIO, viewport))
        assertFalse(Layout.bodyRows(Screen.INICIO, Size(80, 1)) < 0)
        assertEquals(0, Layout.bodyRows(Screen.INICIO, Size(80, 1)))
    }
}
