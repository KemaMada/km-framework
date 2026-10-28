package com.km.desktop.shell

import com.km.desktop.ui.theme.Attr
import com.km.desktop.ui.theme.ColorCapability
import com.km.desktop.ui.theme.Palette
import com.km.desktop.ui.theme.Style

/**
 * Traduce un [Style] semantic a una secuencia SGR.
 *
 * ## ESTA ES LA UNICA CAPA QUE EMITE COLOR
 *
 * `ui/` nombra roles (`SUCCESS`, `DANGER`, `BG_RAISED`) y nunca un numero ANSI.
 * Aqui se resuelve la capacidad: truecolor, 256, 16 o MONO. En [MONO] el
 * resultado es la cadena VACIA, de modo que apagar el color no deja secuencias
 * huerfanas ni cambia una celda.
 *
 * ## EL QR NO ES UN ROL DE TEMA
 *
 * Su par es FIJO (`30;47`, negro sobre blanco) con independencia de la paleta:
 * un QR invertido no lo lee ninguna camara. [QR] no se puede sobrescribir.
 */
object Sgr {

    /** Negro sobre blanco: el contraste FIJO del QR. */
    const val QR: String = "\u001b[30;47m"

    /** Vuelve al tema del terminal. */
    const val RESET: String = "\u001b[0m"

    fun of(style: Style, capability: ColorCapability): String {
        if (!capability.sgr) return ""
        if (style.isQr) return QR
        if (style == Style.NONE) return ""
        val codigos = ArrayList<String>(4)
        style.attrs.forEach { attr ->
            when (attr) {
                Attr.BOLD -> codigos += "1"
                Attr.DIM -> codigos += "2"
                Attr.REVERSE -> codigos += "7"
            }
        }
        style.fg?.let { codigos += color(it, capability, fondo = false) }
        style.bg?.let { codigos += color(it, capability, fondo = true) }
        if (codigos.isEmpty()) return ""
        return "\u001b[" + codigos.joinToString(";") + "m"
    }

    private fun color(
        role: com.km.desktop.ui.theme.ColorRole,
        capability: ColorCapability,
        fondo: Boolean,
    ): String {
        val spec = Palette.of(role)
        return when (capability) {
            ColorCapability.TRUECOLOR -> {
                val base = if (fondo) "48;2;" else "38;2;"
                base + spec.rgb.r + ";" + spec.rgb.g + ";" + spec.rgb.b
            }

            ColorCapability.ANSI256 -> (if (fondo) "48;5;" else "38;5;") + spec.index256

            ColorCapability.ANSI16 -> {
                val base = spec.ansi16
                if (fondo) {
                    // Los roles de fondo ya traen 40/44; un rol de texto como
                    // fondo se desplaza 10 (30..37 -> 40..47).
                    if (Palette.isBackground(role)) base.toString() else (base + 10).toString()
                } else {
                    base.toString()
                }
            }

            ColorCapability.MONO -> ""
        }
    }
}
