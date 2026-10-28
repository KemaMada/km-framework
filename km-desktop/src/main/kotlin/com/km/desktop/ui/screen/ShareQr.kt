package com.km.desktop.ui.screen

import com.km.desktop.ui.LinePalette
import com.km.desktop.ui.QrLayout
import com.km.desktop.ui.QrRenderer
import com.km.desktop.ui.ShellState
import com.km.desktop.ui.render.BodyRow

/**
 * El QR del portador PROPIO, o el aviso honesto de por que no se pinta.
 *
 * ## POR QUE UN SOLO SITIO
 *
 * COMPARTIR e IMPORTAR (origen `OWN_QR`) muestran el mismo codigo. Tener dos
 * copias del mismo `when` invitaba a que una dijera "no cabe" con otro umbral.
 *
 * ## CONTRASTE FIJO
 *
 * Las filas del QR se marcan con [LinePalette.QR]: el escritor las pinta negro
 * sobre blanco (`30;47`) sin mirar el tema. El glifo `█ ▀ ▄` representa modulos
 * OSCUROS, asi que con el tema habitual el codigo saldria invertido y ninguna
 * camara lo leeria.
 *
 * Sin color o con `--ascii` NO se pinta un QR a medias: se dice por que y se
 * ofrecen las salidas que si funcionan (texto, `.km`).
 */
internal object ShareQr {

    fun body(state: ShellState, ascii: Boolean, color: Boolean): List<BodyRow> {
        val carrier = state.share?.carrier ?: return listOf(BodyRow("aun no hay portador"))
        if (ascii) return listOf(BodyRow("QR no disponible en --ascii"))
        if (!color) return listOf(BodyRow("el QR necesita color: usa [T] texto o [W] guardar .km"))
        return when (val layout = QrRenderer.layout(carrier, state.viewport)) {
            is QrLayout.Fits -> layout.lines.map { BodyRow(it, palette = LinePalette.QR) }
            is QrLayout.TooLarge -> listOf(
                BodyRow(
                    "el QR no cabe: minimo " + layout.minColumns + "x" + layout.minRows +
                        "; usa [C] copiar o [W] guardar .km",
                ),
            )
        }
    }
}
