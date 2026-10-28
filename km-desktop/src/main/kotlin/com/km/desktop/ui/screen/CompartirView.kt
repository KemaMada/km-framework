package com.km.desktop.ui.screen

import com.km.desktop.ui.Sanitizer
import com.km.desktop.ui.ShellState
import com.km.desktop.ui.render.BodyRow

/**
 * COMPARTIR: portador propio, QR centrado y acciones de ancho completo.
 *
 * ## EL QR LO CENTRA EL COMPOSITOR
 *
 * La cabecera y las acciones son filas de texto de ancho completo; el codigo lo
 * centra [com.km.desktop.ui.render.FrameComposer], que es el unico que conoce el
 * ancho de cada fila y el unico que puede garantizar que el fondo blanco cubra
 * solo las celdas del QR.
 *
 * ## EL PORTADOR EN TEXTO NO SE CENTRA NI SE TRUNCA
 *
 * Un `.km` envuelto en varias lineas se copia a mano: se pinta a la izquierda y
 * NUNCA se trunca.
 */
internal object CompartirView {

    fun body(state: ShellState, ascii: Boolean, color: Boolean): List<BodyRow> {
        val share = state.share
        val cells = ArrayList<BodyRow>()
        if (share == null || share.carrier.isEmpty()) {
            cells += BodyRow("generando portador\u2026")
            cells += BodyRow("[ESC] volver")
            return cells
        }
        cells += BodyRow("portador " + share.byteLength + " B \u00b7 opk " + share.oneTimePreKeys)
        if (ascii) {
            cells += BodyRow("QR desactivado en --ascii: usa [C] copiar o [W] guardar .km")
        } else if (!color) {
            cells += BodyRow("el QR necesita color: usa [T] texto o [W] guardar .km")
        } else {
            cells += ShareQr.body(state, ascii, color)
        }
        if (share.showText) {
            Sanitizer.lines(share.carrier).forEach { cells += BodyRow(it) }
        }
        cells += BodyRow("[C] copiar (OSC 52, no confirmable)  [W] .km  [T] texto  [ESC] volver")
        return cells
    }
}
