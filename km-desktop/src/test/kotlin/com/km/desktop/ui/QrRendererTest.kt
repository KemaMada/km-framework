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
            tabs = TabStacks.of(Screen.INICIO, Screen.COMPARTIR),
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
            tabs = TabStacks.of(Screen.INICIO, Screen.COMPARTIR),
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

    // ------------------------------------------------------------------
    // Contraste: la paleta QR viaja del renderer al escritor
    // ------------------------------------------------------------------

    private fun estadoCompartir(viewport: Size, carrier: String = portadorReal()) = ShellState(
        tabs = TabStacks.of(Screen.INICIO, Screen.COMPARTIR),
        viewport = viewport,
        share = ShareView(carrier = carrier, byteLength = 469, oneTimePreKeys = 0),
    )

    /**
     * Sin marco (85x43) las 43 filas son QR. Con marco (100x50) solo las filas
     * del QR: la cabecera y las acciones NO se marcan, porque invertirlas
     * pintaria de blanco texto que no es del codigo.
     */
    @Test
    fun `solo las filas del QR llevan la paleta QR`() {
        val minimo = ShellRenderer.render(estadoCompartir(Size(85, 43)))
        assertEquals(43, minimo.size)
        assertTrue(
            minimo.all { it.palette == LinePalette.QR },
            "a 85x43 no hay cabecera ni acciones: las 43 filas son QR",
        )

        val conMarco = ShellRenderer.render(estadoCompartir(Size(100, 50)))
        val qr = conMarco.filter { it.palette == LinePalette.QR }
        assertEquals(43, qr.size, "43 filas de QR tambien dentro de la caja")
        assertTrue(
            conMarco.first().text.contains("MI CONTACTO"),
            "el borde superior con titulo va primero: ${conMarco.first().text}",
        )
        assertEquals(LinePalette.DEFAULT, conMarco.first().palette, "el borde no se invierte")
        val cabecera = conMarco.first { it.text.contains("portador ") }
        assertEquals(LinePalette.DEFAULT, cabecera.palette, "la cabecera no se invierte")
        val acciones = conMarco.first { it.text.contains("[C] copiar") }
        assertTrue(acciones.text.contains("[C] copiar"), "las acciones van al pie: ${acciones.text}")
        assertEquals(LinePalette.DEFAULT, acciones.palette, "las acciones no se invierten")
        assertTrue(
            qr.any { it.text.contains("\u2588") || it.text.contains("\u2580") || it.text.contains("\u2584") },
            "las filas marcadas son del QR (el margen de silencio puede ser solo espacios)",
        )
    }

    /**
     * El ancho NO cambia: la marca es una etiqueta de paleta, no ANSI metido en
     * el texto. Si hubiera secuencias dentro, `Sanitizer.width` mediria de mas o
     * `Box` las contaria como columnas.
     */
    @Test
    fun `las filas del QR no llevan ANSI y miden el ancho del viewport`() {
        val viewport = Size(100, 50)
        val lineas = ShellRenderer.render(estadoCompartir(viewport))
        val qr = lineas.filter { it.palette == LinePalette.QR }
        assertEquals(43, qr.size)
        qr.forEach { linea ->
            assertFalse(linea.text.contains('\u001b'), "ANSI dentro del texto medido: ${linea.text}")
            assertEquals(viewport.columns, Sanitizer.width(linea.text), "ancho intacto")
        }
    }

    @Test
    fun `a 84x42 el QR no cabe y se declara el minimo 85x43`() {
        val lineas = ShellRenderer.render(estadoCompartir(Size(84, 42)))
        val texto = lineas.joinToString("\n") { it.text }

        assertTrue(lineas.none { it.palette == LinePalette.QR }, "sin QR: no cabe")
        assertTrue(texto.contains("minimo 85x43"), texto)
        assertTrue(texto.contains("no cabe"), texto)
        assertFalse(texto.contains("\u2588"), "ni un bloque de QR")
    }

    /**
     * Sin color no hay QR: los bloques saldrian invertidos. Se dice que hace
     * falta color y se ofrecen las salidas que si funcionan.
     */
    @Test
    fun `sin color no se pintan bloques de QR y se avisa`() {
        val lineas = ShellRenderer.render(estadoCompartir(Size(100, 50)), color = false)
        val texto = lineas.joinToString("\n") { it.text }

        assertTrue(lineas.none { it.palette == LinePalette.QR }, "ninguna fila QR")
        assertTrue(texto.contains("el QR necesita color"), texto)
        assertTrue(texto.contains("[T] texto") && texto.contains("[W] guardar .km"), texto)
        assertFalse(texto.contains("\u2588"), "sin bloques de QR")
    }
}
