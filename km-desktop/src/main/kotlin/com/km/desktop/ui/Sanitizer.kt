package com.km.desktop.ui

/**
 * SANEO de todo texto no confiable antes de medirlo y pintarlo.
 *
 * ## EL ATAQUE QUE CIERRA
 *
 * El payload de un mensaje, el alias de un contacto y el texto de un error del
 * rele los escribe OTRO. Una secuencia `ESC[2J` borra la pantalla; `ESC]52;c;...`
 * escribe el portapapeles; `ESC[?25l` esconde el cursor. En una TUI de pantalla
 * completa eso no es "texto raro": es control del terminal del usuario por parte
 * de un tercero.
 *
 * ## POR QUE NOTACION CARET Y NO BORRADO SILENCIOSO
 *
 * Porque borrar sin decirlo convierte `ESC[2J` en `[2J`, que parece un dato y no
 * lo es. `^[[2J` se lee como "aqui habia una secuencia de control" y no se puede
 * confundir con contenido. El unico caso que se ELIMINA en silencio es el de los
 * caracteres invisibles de bidi/zero-width: no se pueden mostrar sin mentir
 * sobre su efecto, y su unico efecto posible es reordenar o esconder.
 *
 * ## ANCHO SIN JLINE
 *
 * El modelo `ui/` no importa JLine: se prueba sin terminal y no depende de que el
 * proveedor de terminal cargue. [width] implementa la misma aproximacion
 * `wcwidth` (0 para combinantes, 2 para CJK/emoji, 1 para el resto) con una
 * tabla de rangos explicita, que es lo que un terminal monoespaciado usa para
 * contar celdas.
 *
 * ## ORDEN
 *
 * Sanear y LUEGO medir/truncar. Medir el texto crudo contaria los bytes de las
 * secuencias de control como si fueran columnas y romperia la caja.
 */
object Sanitizer {

    /** El caracter de elipsis: una celda en un terminal monoespaciado. */
    const val ELLIPSIS: String = "\u2026"

    /** El equivalente ASCII, tres celdas. */
    const val ASCII_ELLIPSIS: String = "..."

    /**
     * Devuelve [raw] sin caracteres de control activos.
     *
     * - ESC y el resto de C0 se escriben en notacion caret (`^[`, `^G`).
     * - C1 y los controles Unicode se escriben como `\uXXXX`.
     * - CR/LF/TAB se convierten en UN espacio: son C0, pero en un cuerpo de
     *   mensaje su intencion es separar, no mover el cursor.
     * - Bidi y zero-width se eliminan: son invisibles y solo pueden reordenar.
     */
    fun text(raw: String): String {
        if (raw.isEmpty()) return raw
        val sb = StringBuilder(raw.length)
        var i = 0
        while (i < raw.length) {
            val cp = raw.codePointAt(i)
            when {
                cp == '\n'.code || cp == '\r'.code || cp == '\t'.code -> sb.append(' ')
                cp == 0x1B -> sb.append("^[")
                cp < 0x20 -> sb.append('^').append((cp + 64).toChar())
                cp == 0x7F -> sb.append("^?")
                cp in 0x80..0x9F -> sb.append(escape(cp))
                esSurrogateSuelto(raw, i) -> sb.append('?')
                esInvisible(cp) -> Unit
                Character.isISOControl(cp) -> sb.append(escape(cp))
                else -> sb.appendCodePoint(cp)
            }
            i += Character.charCount(cp)
        }
        return sb.toString()
    }

    /**
     * Divide un cuerpo en LINEAS y sanea cada una.
     *
     * Existe para que un mensaje con saltos de linea se pinte como varias filas
     * en vez de como una sola: [text] convierte LF en espacio porque una celda
     * no puede llevar un salto, pero un cuerpo si puede tenerlos. El renderer
     * corta primero y sanea cada trozo; asi el salto de linea no se pierde y
     * tampoco se cuela un control.
     */
    fun lines(raw: String): List<String> =
        raw.split("\r\n", "\r", "\n").map { text(it) }

    /** Ancho en celdas de un texto YA saneado (o crudo: los controles dan 0). */
    fun width(text: String): Int {
        var total = 0
        var i = 0
        while (i < text.length) {
            val cp = text.codePointAt(i)
            total += cpWidth(cp)
            i += Character.charCount(cp)
        }
        return total
    }

    /**
     * Recorta [raw] a [maxColumns] celdas, saneando primero.
     *
     * Si recorta, anade [ellipsis] (y su ancho cuenta dentro del limite). Nunca
     * devuelve un texto mas ancho que [maxColumns], que es el invariante que
     * mantiene recta una caja.
     */
    fun truncate(
        raw: String,
        maxColumns: Int,
        ellipsis: String = ELLIPSIS,
    ): String {
        if (maxColumns <= 0) return ""
        val clean = text(raw)
        if (width(clean) <= maxColumns) return clean
        val marca = if (width(ellipsis) > maxColumns) "" else ellipsis
        val limite = maxColumns - width(marca)
        val sb = StringBuilder(clean.length)
        var usado = 0
        var i = 0
        while (i < clean.length) {
            val cp = clean.codePointAt(i)
            val w = cpWidth(cp)
            if (usado + w > limite) break
            sb.appendCodePoint(cp)
            usado += w
            i += Character.charCount(cp)
        }
        return sb.toString() + marca
    }

    /** Rellena con espacios hasta [columns] celdas, recortando si sobra. */
    fun pad(raw: String, columns: Int): String {
        if (columns <= 0) return ""
        val clean = truncate(raw, columns)
        val falta = columns - width(clean)
        return if (falta <= 0) clean else clean + " ".repeat(falta)
    }

    /** Una celda de una lista: sin saltos de linea y sin controles. */
    fun cell(raw: String, columns: Int): String = pad(raw, columns)

    /** `true` si el texto contiene algo que hubiera sido un control. */
    fun hasControl(raw: String): Boolean = text(raw) != raw

    // ------------------------------------------------------------------
    // Interno
    // ------------------------------------------------------------------

    private fun escape(cp: Int): String = "\\u%04X".format(cp)

    private fun esSurrogateSuelto(raw: String, i: Int): Boolean {
        val c = raw[i]
        return Character.isSurrogate(c) && Character.charCount(raw.codePointAt(i)) == 1
    }

    /**
     * Invisibles de formato: bidi, zero-width, BOM, soft hyphen y word joiner.
     *
     * Se ELIMINAN (no se notan) porque mostrarlos seria inventar un glifo para
     * algo cuyo unico proposito es no verse.
     */
    private fun esInvisible(cp: Int): Boolean = when (cp) {
        0x00AD, 0x061C, 0x180E, 0x200B, 0x200C, 0x200D, 0x200E, 0x200F,
        0x202A, 0x202B, 0x202C, 0x202D, 0x202E,
        0x2060, 0x2061, 0x2062, 0x2063, 0x2064,
        0x2066, 0x2067, 0x2068, 0x2069,
        0xFEFF, 0xFFF9, 0xFFFA, 0xFFFB,
        -> true

        else -> Character.getType(cp) == Character.FORMAT.toInt()
    }

    /**
     * Ancho de un punto de codigo, en la aproximacion de `wcwidth`.
     *
     * La tabla cubre lo que un terminal monoespaciado ensancha de verdad: Hangul,
     * CJK, kana, formas de ancho completo y emoji. No pretende ser la UAX #11
     * completa; pretende que una caja no se rompa con lo que la gente escribe.
     */
    private fun cpWidth(cp: Int): Int = when {
        cp == 0 -> 0
        cp < 0x20 || cp in 0x7F..0x9F -> 0
        cp in 0xD800..0xDFFF -> 0
        esInvisible(cp) -> 0
        Character.getType(cp) == Character.NON_SPACING_MARK.toInt() -> 0
        Character.getType(cp) == Character.ENCLOSING_MARK.toInt() -> 0
        esAncho(cp) -> 2
        else -> 1
    }

    private fun esAncho(cp: Int): Boolean {
        for (rango in ANCHOS) {
            if (cp < rango.first) return false
            if (cp <= rango.last) return true
        }
        return false
    }

    /** Rangos East Asian Wide/Fullwidth y emoji, ordenados y sin solapes. */
    private val ANCHOS: List<IntRange> = listOf(
        0x1100..0x115F, // Hangul Jamo inicial
        0x2E80..0x303E, // Radicales CJK y puntuacion CJK
        0x3041..0x33FF, // Kana, Bopomofo, compatibilidad
        0x3400..0x4DBF, // CJK ext. A
        0x4E00..0x9FFF, // CJK unificado
        0xA000..0xA4CF, // Yi
        0xA960..0xA97F, // Hangul Jamo ext. A
        0xAC00..0xD7A3, // Silabas Hangul
        0xF900..0xFAFF, // CJK compatibilidad
        0xFE10..0xFE19, // Formas verticales
        0xFE30..0xFE6F, // Compatibilidad de formas
        0xFF00..0xFF60, // Formas de ancho completo
        0xFFE0..0xFFE6, // Simbolos de ancho completo
        0x1F300..0x1F5FF, // Emoji pictograficos
        0x1F600..0x1F64F, // Emoticonos
        0x1F680..0x1F6FF, // Transporte
        0x1F900..0x1F9FF, // Simbolos suplementarios
        0x1FA70..0x1FAFF, // Simbolos extendidos A
        0x20000..0x3FFFD, // CJK ext. B y siguientes
    )
}
