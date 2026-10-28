package com.km.desktop.ui.screen

import com.km.desktop.ui.FocusField
import com.km.desktop.ui.LogLevel
import com.km.desktop.ui.MouseIntent
import com.km.desktop.ui.Sanitizer
import com.km.desktop.ui.ShellReducer
import com.km.desktop.ui.ShellState
import com.km.desktop.ui.layout.ListId
import com.km.desktop.ui.layout.RowRegion
import com.km.desktop.ui.layout.Scroll
import com.km.desktop.ui.render.BodyRow
import com.km.desktop.ui.theme.Glyphs
import com.km.desktop.ui.theme.Style

/**
 * DIAGNOSTICO: transporte, cola, alcance del P2P y logs filtrados.
 *
 * La linea de ALCANCE no es decorativa: `P2P[o]` describe el enlace AL RELAY y
 * no la presencia del par; confundirlos hace buscar el problema en el sitio
 * equivocado.
 *
 * ## M3: LOS LOGS SON UNA LISTA CON VENTANA
 *
 * El filtro se aplica UNA vez (`ShellState.visibleLogs`), y esa misma coleccion
 * es la que se mueve, se cuenta y se pinta. `[n/m]` solo aparece cuando la lista
 * filtrada no cabe.
 *
 * ## M5: FOCO Y NIVEL SIN ROMPER LA LECTURA
 *
 * Cada linea lleva `>` si es la enfocada (dos columnas de margen para que el
 * texto no se mueva) y el nivel va en el glifo (`!` error, `·` aviso). El color
 * por rol solo refuerza: en MONO se lee igual.
 */
internal object DiagnosticoView {

    fun body(state: ShellState, ascii: Boolean, rows: Int, width: Int): List<BodyRow> {
        val status = state.status
        val g = Glyphs(ascii)
        val cells = ArrayList<BodyRow>()
        cells += BodyRow(
            "transporte " + status.link.glyph + " " + status.link.label +
                "  endpoint " + Sanitizer.truncate(status.endpoint ?: "\u2014", 40),
            style = Style.NONE,
        )
        cells += BodyRow(
            "relay " + (status.relay?.take(16) ?: "\u2014") +
                "  intento " + status.attempt +
                "  sesiones " + status.sessions +
                "  claves " + status.ackKeys,
            style = Style.MUTED,
        )
        status.nextRetryInMs?.let {
            cells += BodyRow("proximo intento en " + it + " ms", style = Style.WARNING)
        }
        status.lastError?.let {
            cells += BodyRow("! ultimo error " + Sanitizer.truncate(it, 44), style = Style.DANGER)
        }
        cells += BodyRow("cola: local en memoria (sin consulta desde la UI); el relay NO guarda")
        cells += BodyRow(
            "ALCANCE: P2P es el enlace AL RELAY; NO es presencia del par.",
            separator = true,
        )
        val filtro = state.logFilter ?: "TODOS"
        val visibles = state.visibleLogs()
        val scroll = state.scrollOf(ListId.LOGS)
        val badge = Scroll.indicator(scroll.offset, rows, visibles.size)
        cells += BodyRow(
            "LOGS  filtro: " + filtro + "   [" + state.logs.size + "/" + ShellReducer.MAX_LOGS + "]" +
                (if (badge == null) "" else " " + badge),
            separator = true,
        )
        if (visibles.isEmpty()) {
            cells += BodyRow("sin lineas para este filtro", style = Style.MUTED)
        } else {
            for (i in Scroll.window(visibles.size, scroll.offset, rows)) {
                val log = visibles[i]
                val foco = state.focus.field == FocusField.LIST && i == state.focus.index
                val marca = if (foco) g.focus + " " else "  "
                val nivel = when (log.level) {
                    LogLevel.ERROR -> g.error + " "
                    LogLevel.WARN -> g.warning + " "
                    LogLevel.INFO -> "  "
                }
                val estilo = when (log.level) {
                    LogLevel.ERROR -> Style.DANGER
                    LogLevel.WARN -> Style.WARNING
                    LogLevel.INFO -> Style.NONE
                }
                cells += BodyRow(
                    marca + nivel + Sanitizer.truncate(log.text, (width - 4).coerceAtLeast(8)),
                    focus = foco,
                    style = estilo,
                    regions = listOf(RowRegion(0, null, MouseIntent.SelectLog(i))),
                )
            }
        }
        cells += BodyRow("sin payload, sin claves, sin frase", separator = true)
        cells += BodyRow("[F] filtrar  [C] copiar  [X] exportar  [ESC] volver", style = Style.MUTED)
        return cells
    }
}
