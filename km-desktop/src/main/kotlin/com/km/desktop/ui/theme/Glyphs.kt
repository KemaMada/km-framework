package com.km.desktop.ui.theme

import com.km.desktop.ui.Sanitizer

/**
 * El alfabeto de la TUI: los pocos glifos que el rediseño necesita.
 *
 * ## MISMAS METRICAS EN ASCII
 *
 * Cada glifo Unicode tiene un equivalente ASCII de ANCHO 1. No es un modo
 * degradado distinto: es la misma pantalla con otro alfabeto, para terminales
 * sin Unicode o para quien lo prefiera.
 *
 * ## NO ES COLOR
 *
 * El marcador de seccion, el foco y el estado se distinguen por FORMA. En MONO
 * y en `--ascii` la pantalla sigue diciendo lo mismo (R15/R21).
 */
class Glyphs(val ascii: Boolean) {

    val focus: String = if (ascii) ">" else "\u258c"          // ▌
    val section: String = if (ascii) "#" else "\u258c"        // ▌
    val vertical: String = if (ascii) "|" else "\u2502"       // │
    val longVertical: String = if (ascii) "|" else "\u2503"   // ┃
    val rule: String = if (ascii) "-" else "\u2500"           // ─
    val bullet: String = if (ascii) "*" else "\u00b7"         // ·
    val info: String = if (ascii) "i" else "\u00b7"           // ·
    val success: String = if (ascii) "+" else "\u2713"        // ✓
    val warning: String = "!"
    val error: String = "!"

    /** Los cuatro fotogramas del spinner; en ASCII no se usa braille. */
    fun spinner(frame: Int): String = if (ascii) {
        "|/-\\"[(frame.mod(4))].toString()
    } else {
        "\u280b\u2819\u2839\u2838\u283c\u2834\u2826\u2827"[(frame.mod(8))].toString()
    }

    /** Un rotulo de seccion: `▌ CONTACTOS ───…` o `# CONTACTOS ---…`. */
    fun sectionRule(title: String, width: Int): String {
        if (width <= 0) return ""
        val limpio = Sanitizer.text(title).trim()
        if (limpio.isEmpty()) return rule.repeat(width)
        val prefijo = section + " "
        val hueco = (width - Sanitizer.width(prefijo) - Sanitizer.width(limpio) - 1).coerceAtLeast(0)
        val texto = prefijo + limpio + " " + rule.repeat(hueco)
        return Sanitizer.truncate(texto, width)
    }

    /** `[n/m]` textual: sobrevive a MONO y a `--ascii`. */
    fun scrollBadge(indicador: String?): String = indicador ?: ""
}
