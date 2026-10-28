package com.km.desktop.ui.render

import com.km.desktop.ui.Sanitizer
import com.km.desktop.ui.theme.Style

/**
 * La REJILLA de celdas de un frame: `width` columnas x `height` filas.
 *
 * ## POR QUE UNA REJILLA Y NO UNA LISTA DE STRINGS
 *
 * Porque una celda tiene estilo, continuacion de glifo ancho y posicion. Una
 * lista de strings no puede decir "esta fila lleva el foco" sin inventar una
 * segunda estructura, ni puede garantizar que una linea mida exactamente N
 * celdas cuando aparecen emoji o CJK. Aqui el ancho es una propiedad del tipo.
 *
 * ## [text] ES LA UNICA PUERTA DE TEXTO
 *
 * Todo lo que entra en la rejilla pasa por [Grid.text], que sanea con
 * [Sanitizer] ANTES de medir. Una pantalla no puede saltarse el saneo ni medir
 * el texto crudo porque no tiene otra forma de escribir una celda. Es el mismo
 * invariante de R3/R24 que ya tenia `Box`, ahora en la unica capa que compone.
 *
 * ## CONTINUACION DE ANCHO 2
 *
 * Un glifo ancho ocupa dos celdas: la primera lleva el glifo y la segunda una
 * continuacion vacia marcada. Escribir la continuacion duplicaria el glifo;
 * sumarla como texto no cuadraria el ancho. Por eso [rowText] concatena
 * `cell.text` (la continuacion aporta "") y el resultado mide `width` celdas.
 */
class Grid(
    val width: Int,
    val height: Int,
) {
    init {
        require(width >= 0) { "ancho negativo: $width" }
        require(height >= 0) { "alto negativo: $height" }
    }

    private val cells: Array<Cell> = Array(width * height) { Cell.BLANK }

    private fun index(column: Int, row: Int): Int {
        require(row in 0 until height) { "fila $row fuera de la rejilla (alto $height)" }
        require(column in 0 until width) { "columna $column fuera de la rejilla (ancho $width)" }
        return row * width + column
    }

    operator fun get(column: Int, row: Int): Cell = cells[index(column, row)]

    fun set(column: Int, row: Int, cell: Cell) {
        cells[index(column, row)] = cell
    }

    /** Las celdas de [row], de izquierda a derecha. */
    fun row(row: Int): List<Cell> {
        require(row in 0 until height) { "fila $row fuera de la rejilla (alto $height)" }
        val inicio = row * width
        return cells.copyOfRange(inicio, inicio + width).toList()
    }

    /** El texto de [row]: mide [width] celdas. */
    fun rowText(row: Int): String {
        val sb = StringBuilder(width)
        row(row).forEach { sb.append(it.text) }
        return sb.toString()
    }

    /** Una copia independiente: el compositor no muta el frame de nadie. */
    fun copy(): Grid {
        val copia = Grid(width, height)
        cells.copyInto(copia.cells)
        return copia
    }

    /**
     * Escribe [raw] en ([column], [row]) como un SEGMENTO completo.
     *
     * El segmento se rellena con espacios hasta [maxWidth] (recortado al borde
     * de la rejilla): no deja restos del frame anterior, que es lo que hace
     * falta al repintar una fila entera. Devuelve las celdas escritas.
     *
     * Antes de escribir se limpia cualquier glifo ancho que quede a medias: si
     * la escritura cae sobre su segunda celda, se borra la primera; si cae sobre
     * la primera, se borra su continuacion. Asi no hay medio emoji huerfano.
     */
    fun put(
        column: Int,
        row: Int,
        raw: String,
        style: Style = Style.NONE,
        maxWidth: Int = width - column,
    ): Int {
        if (row !in 0 until height || column !in 0 until width) return 0
        val limite = minOf(maxWidth, width - column)
        if (limite <= 0) return 0
        clearStraddling(column, row)
        val nuevas = text(raw, limite, style)
        nuevas.forEachIndexed { i, cell -> cells[index(column + i, row)] = cell }
        return nuevas.size
    }

    /** Escribe una fila entera desde la columna 0. */
    fun putRow(row: Int, raw: String, style: Style = Style.NONE): Int =
        put(0, row, raw, style, width)

    /**
     * Escribe [raw] SIN rellenar el resto de la fila.
     *
     * ## POR QUE HACE FALTA
     *
     * [put] rellena hasta [maxWidth] con el MISMO estilo. Eso es lo correcto para
     * una fila de texto normal —deja la linea completa con su rol— pero es
     * exactamente el bug del QR: rellenar de espacios con `bg = QR_BG` pintaba
     * una banda blanca hasta el borde derecho del terminal, aunque el codigo
     * midiera la mitad.
     *
     * Aqui solo se escriben las celdas del texto. El resto de la fila conserva
     * lo que hubiera (una rejilla nueva: blanco por defecto), de modo que el
     * blanco del QR cubre EXCLUSIVAMENTE el codigo y su margen de silencio.
     *
     * Devuelve las celdas escritas (el texto, sin relleno).
     */
    fun putExact(
        column: Int,
        row: Int,
        raw: String,
        style: Style = Style.NONE,
        maxWidth: Int = width - column,
    ): Int {
        if (row !in 0 until height || column !in 0 until width) return 0
        val limite = minOf(maxWidth, width - column)
        if (limite <= 0) return 0
        clearStraddling(column, row)
        val nuevas = text(raw, limite, style)
        nuevas.forEachIndexed { i, cell -> cells[index(column + i, row)] = cell }
        return nuevas.size
    }

    private fun clearStraddling(column: Int, row: Int) {
        if (column > 0) {
            val anterior = cells[index(column - 1, row)]
            if (anterior.isWide) cells[index(column - 1, row)] = Cell.BLANK
        }
        val actual = cells[index(column, row)]
        if (actual.continuation) cells[index(column, row)] = Cell.BLANK
        if (actual.isWide && column + 1 < width) cells[index(column + 1, row)] = Cell.BLANK
    }

    companion object {

        /**
         * Convierte [raw] en EXACTAMENTE [columns] celdas.
         *
         * Sanea (unica puerta), mide por celdas y rellena. Si un glifo ancho no
         * cabe entero, se corta ANTES de el y se rellena con espacios: nunca se
         * parte un glifo ni se escribe su segunda celda sola.
         */
        fun text(raw: String, columns: Int, style: Style = Style.NONE): List<Cell> {
            if (columns <= 0) return emptyList()
            val limpio = Sanitizer.text(raw)
            val salida = ArrayList<Cell>(columns)
            var i = 0
            while (i < limpio.length && salida.size < columns) {
                val cp = limpio.codePointAt(i)
                val glifo = String(Character.toChars(cp))
                when (Sanitizer.width(glifo)) {
                    // Combinante o invisible: se pega al glifo anterior, no ocupa celda.
                    0 -> {
                        val ultimo = salida.lastOrNull()
                        if (ultimo != null && !ultimo.continuation) {
                            salida[salida.size - 1] = ultimo.copy(text = ultimo.text + glifo)
                        }
                    }

                    1 -> salida.add(Cell(glifo, style))

                    else -> if (salida.size + 2 <= columns) {
                        salida.add(Cell(glifo, style))
                        salida.add(Cell.continuation(style))
                    } else {
                        break
                    }
                }
                i += Character.charCount(cp)
            }
            while (salida.size < columns) salida.add(Cell(text = " ", style = style))
            return salida
        }

        /** Una rejilla a partir de lineas de texto, sin estilos. */
        fun of(lines: List<String>, width: Int, style: Style = Style.NONE): Grid {
            val grid = Grid(width, lines.size)
            lines.forEachIndexed { fila, linea -> grid.putRow(fila, linea, style) }
            return grid
        }

        /** Rejilla vacia (todo espacios). */
        fun blank(width: Int, height: Int): Grid = Grid(width, height)
    }
}
