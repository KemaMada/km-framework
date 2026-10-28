package com.km.desktop.ui.theme

/**
 * El estilo de una celda: color de texto, color de fondo y atributos.
 *
 * Sin fuente y sin alineacion: eso es layout, no estilo. El estilo viaja por
 * la rejilla hasta `shell/ScreenWriter`, que es el unico que lo traduce a SGR.
 */
data class Style(
    val fg: ColorRole? = null,
    val bg: ColorRole? = null,
    val attrs: Set<Attr> = emptySet(),
) {

    /** El estilo del QR: contraste FIJO, nunca un token de tema. */
    val isQr: Boolean get() = bg == ColorRole.QR_BG

    /** Este estilo con [attr] anadido, sin duplicar. */
    fun with(attr: Attr): Style = copy(attrs = attrs + attr)

    companion object {
        val NONE: Style = Style()

        /** El foco: reverse, ademas del glifo que ya va en el texto. */
        val FOCUS: Style = Style(attrs = setOf(Attr.REVERSE))

        val QR: Style = Style(fg = ColorRole.QR_FG, bg = ColorRole.QR_BG)

        val BRAND: Style = Style(fg = ColorRole.BRAND, attrs = setOf(Attr.BOLD))
        val ACCENT: Style = Style(fg = ColorRole.ACCENT, attrs = setOf(Attr.BOLD))
        val MUTED: Style = Style(fg = ColorRole.MUTED)
        val SUCCESS: Style = Style(fg = ColorRole.SUCCESS, attrs = setOf(Attr.BOLD))
        val WARNING: Style = Style(fg = ColorRole.WARNING, attrs = setOf(Attr.BOLD))
        val DANGER: Style = Style(fg = ColorRole.DANGER, attrs = setOf(Attr.BOLD))
        val RAISED: Style = Style(bg = ColorRole.BG_RAISED)
    }
}
