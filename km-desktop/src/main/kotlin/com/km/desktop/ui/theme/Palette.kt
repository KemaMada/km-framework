package com.km.desktop.ui.theme

/** Un color RGB de 8 bits por canal. */
data class Rgb(val r: Int, val g: Int, val b: Int) {
    init {
        require(r in 0..255 && g in 0..255 && b in 0..255) { "RGB fuera de rango: $r,$g,$b" }
    }
}

/**
 * Un rol en las tres paletas que se pueden emitir.
 *
 * [ansi16] es el codigo de primer plano; para un rol de FONDO el escritor usa
 * `ansi16 + 10` (40..47), que es la convencion SGR. [index256] es el indice de
 * la paleta de 256 colores.
 */
data class ColorSpec(val rgb: Rgb, val index256: Int, val ansi16: Int)

/**
 * Los colores de la TUI, mapeados desde la app Android.
 *
 * `warning` no existe en Android y se declara aqui; el resto son los mismos
 * tokens (#FF4444 marca, #00DDDD acento, #E0E0E0 texto, #8A8A8A apagado,
 * #44FF44 exito, #333333 borde, #1A1A1A elevado). Un tema configurable es
 * M7/F4: esto es la paleta por defecto, no un lenguaje de temas.
 */
object Palette {

    fun of(role: ColorRole): ColorSpec = when (role) {
        ColorRole.TEXT -> ColorSpec(Rgb(224, 224, 224), 255, 37)
        ColorRole.MUTED -> ColorSpec(Rgb(138, 138, 138), 245, 90)
        ColorRole.BRAND -> ColorSpec(Rgb(255, 68, 68), 203, 91)
        ColorRole.ACCENT -> ColorSpec(Rgb(0, 221, 221), 51, 36)
        ColorRole.SUCCESS -> ColorSpec(Rgb(68, 255, 68), 82, 32)
        ColorRole.DANGER -> ColorSpec(Rgb(255, 68, 68), 196, 31)
        ColorRole.WARNING -> ColorSpec(Rgb(245, 167, 66), 214, 33)
        ColorRole.BORDER -> ColorSpec(Rgb(51, 51, 51), 238, 90)
        ColorRole.BG_RAISED -> ColorSpec(Rgb(26, 26, 26), 236, 40)
        ColorRole.BG_SELECT -> ColorSpec(Rgb(38, 79, 120), 24, 44)
        // El QR no usa la paleta: su par es FIJO (30;47) en el escritor.
        ColorRole.QR_FG -> ColorSpec(Rgb(0, 0, 0), 16, 30)
        ColorRole.QR_BG -> ColorSpec(Rgb(255, 255, 255), 231, 47)
    }

    /** Un rol de fondo se emite con el codigo de fondo, no con el de texto. */
    fun isBackground(role: ColorRole): Boolean =
        role == ColorRole.BG_RAISED || role == ColorRole.BG_SELECT
}
