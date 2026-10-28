package com.km.desktop.ui.screen

import com.km.desktop.ui.FocusField
import com.km.desktop.ui.Sanitizer
import com.km.desktop.ui.ShellState
import com.km.desktop.ui.render.BodyRow

/**
 * RESTAURAR: frase de recuperacion, validacion y confirmacion escrita.
 *
 * Port literal. La confirmacion no se sustituye por un "si/no": sobrescribir la
 * identidad exige escribir `SOBRESCRIBIR`, y eso se ve en la pantalla.
 */
internal object RestaurarView {

    fun body(state: ShellState, ascii: Boolean): List<BodyRow> {
        val phrase = state.phrase
        val cells = ArrayList<BodyRow>()
        cells += BodyRow("restaura SOLO la identidad; las sesiones y contactos se pierden")
        cells += BodyRow("pega la frase (24 palabras):", separator = true)
        val entrada = phrase.restoreInput
        cells += BodyRow(
            "> " + Sanitizer.truncate(entrada, state.viewport.columns - 6) + "\u2588",
            focus = state.focus.field == FocusField.FIELD,
        )
        val validez = phrase.validation
        cells += BodyRow(
            if (validez == null) "valida: sin comprobar"
            else "valida: " + validez.wordCount + "/24 \u00b7 checksum " + if (validez.checksumOk) "OK" else "FALLA",
        )
        cells += BodyRow("identidad actual: " + (phrase.identityNow ?: "desconocida"))
        cells += BodyRow("escribe SOBRESCRIBIR para confirmar:", separator = true)
        cells += BodyRow(
            "> " + Sanitizer.truncate(phrase.confirmation, state.viewport.columns - 6) + "\u2588",
            focus = state.focus.field == FocusField.CONFIRM,
        )
        cells += BodyRow("[ENTER] restaurar   [ESC] cancelar")
        return cells
    }
}
