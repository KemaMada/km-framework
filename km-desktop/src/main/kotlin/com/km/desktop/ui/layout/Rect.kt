package com.km.desktop.ui.layout

/**
 * Un RECTANGULO de celdas, con origen arriba-izquierda.
 *
 * ## POR QUE CELDAS Y NO PIXELES
 *
 * Porque todo el modelo de la TUI esta medido con [com.km.desktop.ui.Sanitizer.width]:
 * un glifo ancho ocupa dos celdas y una caja que mida "de mas" desplaza la
 * pantalla. Un rectangulo en celdas es la unica geometria que puede compararse
 * con lo que se pinta.
 *
 * ## ES UN VALOR, NO UN ESTADO
 *
 * No vive en [com.km.desktop.ui.ShellState]: el layout se calcula por frame a
 * partir del viewport. Guardar rectangulos en el estado seria una segunda
 * verdad sobre donde esta cada cosa (y una que se queda vieja al redimensionar).
 *
 * `w`/`h` pueden ser 0 (una banda que no se pinta); nunca negativos.
 */
data class Rect(
    val x: Int,
    val y: Int,
    val w: Int,
    val h: Int,
) {
    init {
        require(x >= 0) { "x negativo: $x" }
        require(y >= 0) { "y negativo: $y" }
        require(w >= 0) { "ancho negativo: $w" }
        require(h >= 0) { "alto negativo: $h" }
    }

    /** Columna inmediatamente a la derecha (exclusiva). */
    val right: Int get() = x + w

    /** Fila inmediatamente debajo (exclusiva). */
    val bottom: Int get() = y + h

    /** No ocupa ninguna celda: no se pinta. */
    val isEmpty: Boolean get() = w == 0 || h == 0

    /** `true` si la celda ([column], [row]) cae dentro. */
    fun contains(column: Int, row: Int): Boolean =
        column >= x && column < right && row >= y && row < bottom
}
