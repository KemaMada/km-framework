package com.km.desktop.ui.screen

import com.km.desktop.ui.ImportDraft
import com.km.desktop.ui.ImportSource
import com.km.desktop.ui.FocusField
import com.km.desktop.ui.Sanitizer
import com.km.desktop.ui.ShellState
import com.km.desktop.ui.render.BodyRow

/**
 * IMPORTAR: los tres portadores (texto, fichero `.km`, mi QR).
 *
 * Port literal de la pantalla actual. El QR propio se delega en [ShareQr], que
 * es el MISMO camino que COMPARTIR: dos codigos distintos para el mismo
 * portador seria un segundo codificador.
 */
internal object ImportarView {

    fun body(state: ShellState, ascii: Boolean, color: Boolean): List<BodyRow> {
        val draft = state.importDraft ?: ImportDraft()
        val cells = ArrayList<BodyRow>()
        cells += BodyRow("1 texto  2 fichero .km  3 ver mi QR")
        cells += BodyRow(
            "origen: " + when (draft.source) {
                ImportSource.TEXT -> "TEXTO (pega una linea o un bloque)"
                ImportSource.FILE -> "FICHERO .km (ISO-8859-1, tope 64 KiB, sin recortar)"
                ImportSource.OWN_QR -> "MI QR (esta pantalla no escanea QR)"
            },
            separator = true,
        )
        when (draft.source) {
            ImportSource.TEXT -> {
                cells += BodyRow("portador:", focus = state.focus.field == FocusField.FIELD)
                val texto = draft.text
                if (texto.isEmpty()) {
                    cells += BodyRow("> ", focus = state.focus.field == FocusField.FIELD)
                } else {
                    Sanitizer.lines(texto).forEachIndexed { i, linea ->
                        cells += BodyRow((if (i == 0) "> " else "  ") + linea)
                    }
                    cells += BodyRow("(" + texto.length + " caracteres)")
                }
            }

            ImportSource.FILE -> {
                cells += BodyRow("ruta:", focus = state.focus.field == FocusField.FIELD)
                cells += BodyRow("> " + draft.path + "\u2588", focus = state.focus.field == FocusField.FIELD)
                cells += BodyRow("se lee tal cual: sin trim y con tope de 64 KiB en LECTURA")
            }

            ImportSource.OWN_QR -> {
                cells += BodyRow("tu portador (0 OPK) para que lo lea el movil")
                cells += ShareQr.body(state, ascii, color)
            }
        }
        draft.rejection?.let { cells += BodyRow("RECHAZADO " + it) }
        cells += BodyRow("[ENTER] importar   [ESC] volver")
        return cells
    }
}
