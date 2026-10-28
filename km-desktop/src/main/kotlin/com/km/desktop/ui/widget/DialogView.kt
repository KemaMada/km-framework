package com.km.desktop.ui.widget

import com.km.desktop.ui.ConfirmKind
import com.km.desktop.ui.DialogChoice
import com.km.desktop.ui.Overlay
import com.km.desktop.ui.Sanitizer

/**
 * La PINTURA de un dialogo modal.
 *
 * ## EL MODELO DECIDE, ESTA CLASE DIBUJA
 *
 * `Overlay.Confirm` sigue siendo la unica fuente de verdad de que hay una capa
 * modal y de que opcion esta marcada ([DialogChoice]). Aqui solo se convierte
 * en lineas de texto: titulo, detalle, botones y la tecla que los activa.
 *
 * ## LA OPCION MARCADA SE VE SIN COLOR
 *
 * `>` delante del boton marcado; la linea de pistas dice que `Enter` acepta lo
 * marcado y `Esc` cancela. Un Enter reflejo en una accion destructiva cae en
 * `No`, que es justo la seguridad que pide R17.
 */
object DialogView {

    fun confirm(overlay: Overlay.Confirm, ascii: Boolean): List<String> = buildList {
        add(overlay.title)
        overlay.detail?.takeIf { it.isNotBlank() }?.let { add(Sanitizer.text(it)) }
        add("")
        add(choices(overlay.selection, ascii))
        add(if (ascii) "Enter acepta lo marcado | Esc cancela sin cambios" else
            "Enter acepta lo marcado \u00b7 Esc cancela sin cambios")
        if (overlay.kind == ConfirmKind.RESTORE_IDENTITY) {
            add("sobrescribir borra la identidad actual y sus sesiones")
        }
    }

    /** `> [ Si ]   [ No ]`: el marcador es el mismo glifo en todos los idiomas. */
    fun choices(selection: DialogChoice, ascii: Boolean = false): String {
        val primario = if (selection == DialogChoice.PRIMARY) "> " + side(DialogChoice.PRIMARY, ascii) else "  " + side(DialogChoice.PRIMARY, ascii)
        val secundario = if (selection == DialogChoice.SECONDARY) "> " + side(DialogChoice.SECONDARY, ascii) else "  " + side(DialogChoice.SECONDARY, ascii)
        return primario + "   " + secundario
    }

    /**
     * El texto EXACTO de un boton, con o sin acento segun `--ascii`.
     *
     * Lo usan el compositor (para localizar la region clicable) y [choices]:
     * una sola fuente, de modo que el boton que se pinta y el que se pulsa no
     * pueden discrepar.
     */
    fun side(choice: DialogChoice, ascii: Boolean = false): String = when (choice) {
        DialogChoice.PRIMARY -> if (ascii) "[ Si ]" else "[ S\u00ed ]"
        DialogChoice.SECONDARY -> "[ No ]"
    }

    fun offline(overlay: Overlay.Offline, ascii: Boolean): List<String> = buildList {
        add("estado " + overlay.link.glyph + " " + overlay.link.label)
        add(
            "intento " + overlay.attempt +
                (overlay.nextRetryInMs?.let { if (ascii) " | proximo en " + it + " ms" else
                    " \u00b7 proximo en " + it + " ms" } ?: ""),
        )
        add("ultimo error " + (overlay.lastError?.let { Sanitizer.truncate(it, 40) } ?: "\u2014"))
        add("")
        add("El relay NO guarda mensajes: la cola es local")
        add("y vive mientras el proceso siga abierto.")
        add("")
        add("[ESC] seguir")
    }
}
