package com.km.desktop.ui

/**
 * CAJAS RECTAS con box-drawing Unicode, y su equivalente ASCII.
 *
 * ## POR QUE BORDES CUADRADOS
 *
 * Porque es la misma gramatica visual que la TUI Android: `┌ ┐ └ ┘ │ ├ ┤ ─`.
 * Cero esquinas redondeadas, cero pills, cero tarjetas. Una caja es un marco,
 * no un adorno, y su forma no cambia con el estado.
 *
 * ## EL INVARIANTE
 *
 * Toda linea que produce [Box] mide EXACTAMENTE [width] celdas segun
 * [Sanitizer.width]. Si una linea midiera de menos, la caja se ve mellada; si
 * midiera de mas, se sale del terminal y el resto de la pantalla se desplaza.
 * Por eso el contenido se SANEA, se TRUNCA y se RELLENA, en ese orden.
 *
 * ## ASCII
 *
 * `--ascii` sustituye los glifos por `+ - |` con las MISMAS medidas. No es un
 * modo degradado distinto: es la misma caja con otro alfabeto, para terminales
 * sin Unicode o para quien lo prefiera.
 */
object Box {

    /** Por debajo de 4 columnas no cabe ni el marco. */
    const val MIN_WIDTH: Int = 4

    private class Glyphs(ascii: Boolean) {
        val topLeft: String = if (ascii) "+" else "\u250C"      // ┌
        val topRight: String = if (ascii) "+" else "\u2510"     // ┐
        val bottomLeft: String = if (ascii) "+" else "\u2514"   // └
        val bottomRight: String = if (ascii) "+" else "\u2518"  // ┘
        val teeLeft: String = if (ascii) "+" else "\u251C"      // ├
        val teeRight: String = if (ascii) "+" else "\u2524"     // ┤
        val horizontal: String = if (ascii) "-" else "\u2500"   // ─
        val vertical: String = if (ascii) "|" else "\u2502"     // │
    }

    private fun glyphs(ascii: Boolean): Glyphs = Glyphs(ascii)

    /**
     * El borde superior, con [title] opcional.
     *
     * `┌ KeyMessage ─────────┐`. Sin titulo, el borde entero de guiones.
     */
    fun top(title: String = "", width: Int, ascii: Boolean = false): String {
        val g = glyphs(ascii)
        val ancho = anchoValido(width)
        val limpio = Sanitizer.text(title).trim()
        if (limpio.isEmpty()) {
            return g.topLeft + g.horizontal.repeat(ancho - 2) + g.topRight
        }
        val interior = ancho - 4
        val t = Sanitizer.truncate(limpio, interior, if (ascii) Sanitizer.ASCII_ELLIPSIS else Sanitizer.ELLIPSIS)
        val usado = Sanitizer.width(t)
        return g.topLeft + " " + t + " " + g.horizontal.repeat(interior - usado) + g.topRight
    }

    /** Separador interior `├───┤`, con [title] opcional. */
    fun separator(width: Int, title: String = "", ascii: Boolean = false): String {
        val g = glyphs(ascii)
        val ancho = anchoValido(width)
        val limpio = Sanitizer.text(title).trim()
        if (limpio.isEmpty()) {
            return g.teeLeft + g.horizontal.repeat(ancho - 2) + g.teeRight
        }
        val interior = ancho - 4
        val t = Sanitizer.truncate(limpio, interior, if (ascii) Sanitizer.ASCII_ELLIPSIS else Sanitizer.ELLIPSIS)
        val usado = Sanitizer.width(t)
        return g.teeLeft + " " + t + " " + g.horizontal.repeat(interior - usado) + g.teeRight
    }

    /** El borde inferior `└───┘`. */
    fun bottom(width: Int, ascii: Boolean = false): String {
        val g = glyphs(ascii)
        val ancho = anchoValido(width)
        return g.bottomLeft + g.horizontal.repeat(ancho - 2) + g.bottomRight
    }

    /**
     * Una linea de contenido: `│ contenido... │`.
     *
     * El contenido se trunca a `width - 4` (marco + un espacio a cada lado).
     */
    fun line(content: String, width: Int, ascii: Boolean = false): String {
        val g = glyphs(ascii)
        val ancho = anchoValido(width)
        val interior = ancho - 4
        val cuerpo = Sanitizer.pad(content, interior)
        return g.vertical + " " + cuerpo + " " + g.vertical
    }

    /** Una caja completa: superior, lineas y inferior. */
    fun frame(
        title: String,
        lines: List<String>,
        width: Int,
        ascii: Boolean = false,
    ): List<String> {
        val salida = ArrayList<String>(lines.size + 2)
        salida += top(title, width, ascii)
        lines.forEach { salida += line(it, width, ascii) }
        salida += bottom(width, ascii)
        return salida
    }

    /**
     * El aviso de terminal demasiado pequena, o `null` si cabe.
     *
     * No se devuelve una pantalla "adaptada": se dice el minimo y se ofrece el
     * modo lineas. Fingir que cabe es lo que deja al usuario sin ver la mitad de
     * los datos.
     */
    fun tooSmall(viewport: Size): String? {
        if (viewport.usable) return null
        return "terminal ${viewport.columns}x${viewport.rows}: minimo " +
            "${Size.MIN_COLUMNS}x${Size.MIN_ROWS}; usa --no-tui"
    }

    private fun anchoValido(width: Int): Int {
        require(width >= MIN_WIDTH) { "ancho $width < $MIN_WIDTH: no cabe un marco" }
        return width
    }
}
