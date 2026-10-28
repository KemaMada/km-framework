package com.km.desktop.ui.render

import com.km.desktop.ui.layout.Scroll

/**
 * La VENTANA de un overlay: cuantas lineas caben y como se desplazan.
 *
 * ## EL DEFECTO QUE CIERRA
 *
 * La ayuda tiene mas lineas que una terminal de 60x20 u 80x24. El overlay
 * pintaba la caja entera, asi que a 80x24 devolvia 37 filas y el escritor tenia
 * que recortar por su cuenta (o, peor, dejar el marco roto). Es un defecto que
 * ya existia en la implementacion congelada y que el kernel no puede heredar:
 * la capa modal se VENTANEA, no se recorta a la mitad.
 *
 * ## EL INDICADOR ES TEXTO
 *
 * Cuando hay mas lineas que hueco se reserva la ultima fila para `[n/m]
 * PgUp/PgDn`. En MONO y con `--ascii` se lee igual.
 */
object OverlayWindow {

    /** Filas de contenido que caben dentro de una caja en [viewportRows]. */
    fun capacity(viewportRows: Int): Int = (viewportRows - 2).coerceAtLeast(1)

    /** Filas que se pintan; si hay desbordamiento, una se gasta en el aviso. */
    fun visibleRows(viewportRows: Int, total: Int): Int {
        if (total <= 0) return 0
        val cap = capacity(viewportRows)
        return if (total > cap) (cap - 1).coerceAtLeast(1) else total
    }

    /** La ventana de contenido, con el offset ya recortado. */
    fun window(viewportRows: Int, total: Int, offset: Int): IntRange =
        Scroll.window(total, offset, visibleRows(viewportRows, total))

    /** El mayor offset util para este overlay. */
    fun maxOffset(viewportRows: Int, total: Int): Int =
        Scroll.maxOffset(total, visibleRows(viewportRows, total))

    /** El aviso de posicion, o `null` si todo cabe. */
    fun indicator(viewportRows: Int, total: Int, offset: Int): String? {
        val visible = visibleRows(viewportRows, total)
        if (total <= capacity(viewportRows)) return null
        val ultimo = window(viewportRows, total, offset).last + 1
        return "[" + ultimo + "/" + total + "] PgUp/PgDn"
    }
}
