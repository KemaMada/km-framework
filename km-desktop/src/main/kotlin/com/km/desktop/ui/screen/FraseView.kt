package com.km.desktop.ui.screen

import com.km.desktop.ui.Sanitizer
import com.km.desktop.ui.ShellState
import com.km.desktop.ui.render.BodyRow

/**
 * FRASE: alcance de la recuperacion y las 24 palabras en cuatro columnas.
 *
 * Port literal. La frase solo se pinta si [com.km.desktop.ui.PhraseView.revealed]:
 * `words` vacio es el estado normal, porque el buffer se pide tras confirmar y se
 * borra al ocultar o salir.
 */
internal object FraseView {

    fun body(state: ShellState, ascii: Boolean): List<BodyRow> {
        val phrase = state.phrase
        val cells = ArrayList<BodyRow>()
        cells += BodyRow("ALCANCE: restaura la IDENTIDAD solamente.")
        cells += BodyRow("NO restaura contactos, sesiones ni mensajes.")
        cells += BodyRow("Los contactos se vuelven a intercambiar (QR/.km).", separator = true)
        if (!phrase.revealed) {
            cells += BodyRow("la frase esta OCULTA; [V] la muestra tras confirmar")
        } else if (phrase.words.isEmpty()) {
            cells += BodyRow("cargando frase\u2026")
        } else {
            val columnas = 4
            val filas = (phrase.words.size + columnas - 1) / columnas
            for (fila in 0 until filas) {
                val sb = StringBuilder()
                for (col in 0 until columnas) {
                    val i = col * filas + fila
                    val palabra = phrase.words.getOrNull(i) ?: continue
                    sb.append((i + 1).toString().padStart(2, '0')).append(' ')
                        .append(Sanitizer.truncate(palabra, 13).padEnd(14))
                }
                cells += BodyRow(sb.toString())
            }
        }
        cells += BodyRow("la frase NO va a logs, historial ni .km", separator = true)
        cells += BodyRow("[V] ver (confirmar)  [C] copiar (aviso)  [O] ocultar  [R] restaurar  [ESC] volver")
        return cells
    }
}
