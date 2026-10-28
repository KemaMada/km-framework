package com.km.desktop.ui.widget

import com.km.desktop.ui.Sanitizer
import com.km.desktop.ui.Toast
import com.km.desktop.ui.ToastVariant
import com.km.desktop.ui.render.BodyRow
import com.km.desktop.ui.theme.Glyphs
import com.km.desktop.ui.theme.Style

/**
 * El TOAST: aviso efimero de una accion, en la banda de estado.
 *
 * ## NO ES UN SEGUNDO ESCRITOR
 *
 * Es una fila MAS del mismo frame, encima del pie. No hay linea suelta que
 * empuje el layout ni que se cuele entre dos repintados.
 *
 * ## SIN PROMESAS
 *
 * Copiar no confirma que el terminal copiara (OSC 52 no es confirmable) y
 * enviar no confirma entrega. El texto lo dice: `sin ACK` y `en cola local`.
 *
 * ## EL COLOR NO ES LA SEÑAL
 *
 * `i`/`+`/`!` va delante en TODOS los modos; el rol solo refuerza. En MONO y
 * `--ascii` el toast sigue diciendo lo mismo (R19/R21).
 */
object ToastView {

    fun row(toast: Toast, ascii: Boolean): BodyRow {
        val g = Glyphs(ascii)
        val glifo = when (toast.variant) {
            ToastVariant.INFO -> if (ascii) "i" else g.info
            ToastVariant.SUCCESS -> if (ascii) "+" else g.success
            ToastVariant.WARNING -> g.warning
            ToastVariant.ERROR -> g.error
        }
        val cuerpo = toast.title + (toast.message?.let { " " + (if (ascii) "|" else "\u00b7") + " " + it } ?: "")
        return BodyRow(Sanitizer.text(glifo + " " + cuerpo), style = styleOf(toast.variant))
    }

    fun styleOf(variant: ToastVariant): Style = when (variant) {
        ToastVariant.INFO -> Style.MUTED
        ToastVariant.SUCCESS -> Style.SUCCESS
        ToastVariant.WARNING -> Style.WARNING
        ToastVariant.ERROR -> Style.DANGER
    }
}
