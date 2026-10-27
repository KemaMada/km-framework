package com.km.desktop.ui

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * 3.5 — QR con zxing: el portador real cabe a 85x43 y NO a 80x24.
 *
 * ## POR QUE 469 BYTES
 *
 * Es el tamano medido del portador real de Android (KCE + deflate + ISO-8859-1,
 * 0 OPK). El design #437 y la enmienda #439 fijaron la geometria: 469 B en modo
 * byte fuerzan version 15-L (77 modulos) y, con margen 4, 85 columnas por 43
 * filas con medias celdas.
 *
 * ## EL TEST ES EL CONTRATO
 *
 * No se mide "que pinta algo": se mide que el umbral es el que la spec enmienda.
 * Si alguien "mejora" el QR para que quepa en 80x24, este test se pone rojo: o
 * cambio el portador (prohibido) o el minimo medido ya no es 85x43.
 */
class QrRendererTest {

    /** 469 bytes que NO caen en modo alfanumerico (minusculas + simbolos). */
    private fun portadorReal(): String =
        buildString { while (length < 469) append("aB3-_") }.take(469)

    @Test
    fun `el portador de 469 B cabe exactamente a 85x43`() {
        val layout = QrRenderer.layout(portadorReal(), Size(85, 43))

        assertTrue(layout is QrLayout.Fits, "debe caber a 85x43: $layout")
        val lineas = (layout as QrLayout.Fits).lines
        assertEquals(43, lineas.size, "43 filas de media celda")
        lineas.forEach { assertEquals(85, Sanitizer.width(it), "cada linea mide 85 columnas") }
    }

    @Test
    fun `el portador de 469 B NO cabe a 80x24 y declara el minimo`() {
        val layout = QrRenderer.layout(portadorReal(), Size(80, 24))

        assertTrue(layout is QrLayout.TooLarge, "80x24 no puede contener el QR: $layout")
        val minimo = layout as QrLayout.TooLarge
        assertEquals(85, minimo.minColumns)
        assertEquals(43, minimo.minRows)
    }

    @Test
    fun `un portador vacio no intenta codificar`() {
        val layout = QrRenderer.layout("", Size(200, 100))
        assertEquals(QrLayout.TooLarge(QrRenderer.MIN_COLUMNS, QrRenderer.MIN_ROWS), layout)
    }

    @Test
    fun `las medias celdas pinta dos modulos por fila`() {
        val matrix = QrRenderer.matrixOf("hola")
        val lineas = QrRenderer.render(matrix)
        assertEquals((matrix.height + 1) / 2, lineas.size)
        lineas.forEach { assertEquals(matrix.width, Sanitizer.width(it)) }
        // Un QR real tiene modulos oscuros: si todas las lineas fueran espacios,
        // el render no estaria leyendo la matriz.
        assertTrue(lineas.any { it.contains("\u2588") || it.contains("\u2580") || it.contains("\u2584") })
    }

    @Test
    fun `--ascii no pinta QR y lo dice`() {
        val estado = ShellState(
            screens = ScreenStack.of(Screen.INICIO, Screen.COMPARTIR),
            viewport = Size(100, 50),
            share = ShareView(carrier = portadorReal(), byteLength = 469),
        )
        val texto = ShellRenderer.render(estado, ascii = true).joinToString("\n") { it.text }
        assertTrue(texto.contains("QR desactivado en --ascii"), texto)
        assertFalse(texto.contains("\u2588"), "sin bloques de QR en --ascii")
    }

    /**
     * SUGGESTION-3 de #446: el layout estaba medido, el RENDER no.
     *
     * `layout` decia Fits a 85x43 mientras la pantalla recortaba el QR dentro de
     * la caja. Este test mide la pantalla, que es lo que el usuario ve.
     */
    @Test
    fun `el render de COMPARTIR a 85x43 conserva las 43 filas del QR`() {
        val carrier = portadorReal()
        val viewport = Size(QrRenderer.MIN_COLUMNS, QrRenderer.MIN_ROWS)
        val estado = ShellState(
            screens = ScreenStack.of(Screen.INICIO, Screen.COMPARTIR),
            viewport = viewport,
            share = ShareView(carrier = carrier, byteLength = 469, oneTimePreKeys = 0),
        )

        val esperado = (QrRenderer.layout(carrier, viewport) as QrLayout.Fits).lines
        val lineas = ShellRenderer.render(estado)
        val texto = lineas.joinToString("\n") { it.text }

        assertEquals(43, esperado.size)
        assertEquals(esperado, lineas.map { it.text }, "ni una fila recortada ni sustituida")
        assertFalse(texto.contains("no cabe"), texto)
        assertFalse(texto.contains("lineas)"), texto)
    }
}
