package com.km.desktop.ui.theme

/**
 * El TEMA activo: una capacidad mas el alfabeto elegido.
 *
 * No guarda pinta: no hay una instancia de tema por pantalla ni se mete en
 * `ShellState`. Es una constante que viaja al renderer y al escritor.
 */
data class Theme(
    val capability: ColorCapability = ColorCapability.ANSI16,
    val ascii: Boolean = false,
) {

    /** Se pueden emitir atributos. MONO y `--ascii` dicen que no. */
    val color: Boolean get() = capability.sgr && !ascii

    fun glyphs(): Glyphs = Glyphs(ascii)

    companion object {
        val MONO: Theme = Theme(ColorCapability.MONO)

        fun of(capability: ColorCapability, ascii: Boolean = false): Theme =
            Theme(if (ascii) ColorCapability.MONO else capability, ascii)
    }
}
