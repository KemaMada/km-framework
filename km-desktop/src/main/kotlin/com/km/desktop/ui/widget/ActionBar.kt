package com.km.desktop.ui.widget

import com.km.desktop.ui.Intent
import com.km.desktop.ui.MouseIntent
import com.km.desktop.ui.Sanitizer
import com.km.desktop.ui.layout.RowRegion
import com.km.desktop.ui.render.BodyRow
import com.km.desktop.ui.theme.Glyphs
import com.km.desktop.ui.theme.Style

/**
 * El PIE de acciones, desde una tabla `(etiqueta, Intent)`.
 *
 * ## POR QUE UNA TABLA Y NO UN STRING
 *
 * Porque cada accion tiene que ser CLICABLE sin inventar una segunda lista de
 * coordenadas: el texto y sus regiones salen del MISMO recorrido. Antes el pie
 * era una cadena montada a mano con `+`, y el raton habria necesitado repetirla
 * (y desincronizarse a la primera tecla nueva).
 *
 * ## EL TEXTO NO CAMBIA
 *
 * El formato es exactamente el de siempre: `· [N] nuevo  [C] compartir ...`,
 * separado por dos espacios. Los 21 goldens `f2-*` lo comprueban.
 *
 * ## REGION SOLO SI CABE ENTERA
 *
 * Una etiqueta recortada por el ancho no es clicable: se declara con su ancho y
 * el compositor la descarta si no entra entera. Pulsar media palabra haria algo
 * que el usuario no ha leido.
 */
object ActionBar {

    /** Una accion visible del pie: su texto exacto y el intento del keymap. */
    data class Action(val label: String, val intent: Intent)

    /** Separacion entre acciones: la misma de siempre. */
    const val SEP: String = "  "

    fun row(actions: List<Action>, width: Int, ascii: Boolean): BodyRow {
        val bullet = Glyphs(ascii).bullet
        val texto = actions.joinToString(SEP) { it.label }
        val linea = "$bullet " + texto
        var cursor = Sanitizer.width(bullet) + 1
        val regiones = ArrayList<RowRegion>(actions.size)
        actions.forEach { accion ->
            val ancho = Sanitizer.width(accion.label)
            regiones += RowRegion(cursor, ancho, MouseIntent.Act(accion.intent))
            cursor += ancho + Sanitizer.width(SEP)
        }
        return BodyRow(
            Sanitizer.truncate(linea, width.coerceAtLeast(0)),
            style = Style.MUTED,
            regions = regiones,
        )
    }
}
