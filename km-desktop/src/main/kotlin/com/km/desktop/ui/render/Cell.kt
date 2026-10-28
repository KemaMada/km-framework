package com.km.desktop.ui.render

import com.km.desktop.ui.LinePalette
import com.km.desktop.ui.Sanitizer
import com.km.desktop.ui.theme.Attr
import com.km.desktop.ui.theme.Style

/**
 * Un TRAMO de una linea con un mismo estilo.
 *
 * `text` es el texto EXACTO de las celdas de ese tramo; concatenar todos los
 * tramos de una linea da su `text`. Asi el escritor solo tiene que intercalar
 * SGR entre tramos y no volver a medir nada.
 */
data class StyledSpan(
    val text: String,
    val style: Style = Style.NONE,
)

/**
 * Una CELDA de la rejilla.
 *
 * [text] y no un `Char` porque un glifo puede ser un par sustituto (emoji) o un
 * caracter combinante pegado al anterior. Guardar un `Char` obligaria a partir
 * justo eso.
 *
 * ## CONTINUACION
 *
 * Un glifo de ancho 2 ocupa DOS celdas: la primera lleva el glifo y la segunda
 * es una continuacion VACIA y marcada. Nunca se escribe sola —escribirla
 * duplicaria el glifo— y el ancho de la fila sigue cuadrando porque su texto es
 * cadena vacia.
 */
data class Cell(
    val text: String = " ",
    val style: Style = Style.NONE,
    val continuation: Boolean = false,
) {
    init {
        require(!continuation || text.isEmpty()) {
            "una continuacion no lleva glifo: '$text'"
        }
    }

    /** El glifo ocupa dos celdas y la siguiente es su continuacion. */
    val isWide: Boolean get() = !continuation && Sanitizer.width(text) == 2

    companion object {
        val BLANK: Cell = Cell()

        fun continuation(style: Style): Cell = Cell(text = "", style = style, continuation = true)
    }
}

/**
 * Una fila de CONTENIDO de una pantalla, antes de entrar en la rejilla.
 *
 * Es el equivalente semantico de la linea que pintaba el renderer antiguo:
 * [text] es texto CRUDO (la rejilla lo sanea al escribirlo), [focus] dice que la
 * fila lleva el foco, [separator] que se pinta como rotulo de seccion,
 * [palette] que necesita el contraste fijo del QR y [style] el rol semantico.
 *
 * Separar "lo que dice la pantalla" de "como se mide y se recorta" es lo que
 * permite que el compositor decida el ancho sin que cada pantalla lo adivine.
 *
 * ## [regions]: EL RATON COMO SEMANTICA (change B)
 *
 * Una fila puede declarar zonas clicables DENTRO de su propio texto, en
 * columnas relativas y con un [com.km.desktop.ui.MouseIntent]. La vista no
 * conoce coordenadas de pantalla: el compositor, que es el unico que pinta,
 * las convierte en `HitRegion` en el MISMO bucle. Si la fila no declara nada,
 * no es clicable.
 */
data class BodyRow(
    val text: String,
    val focus: Boolean = false,
    val separator: Boolean = false,
    val palette: LinePalette = LinePalette.DEFAULT,
    val style: Style = Style.NONE,
    val regions: List<com.km.desktop.ui.layout.RowRegion> = emptyList(),
)
