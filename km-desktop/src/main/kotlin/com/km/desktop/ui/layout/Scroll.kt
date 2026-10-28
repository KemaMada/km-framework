package com.km.desktop.ui.layout

/**
 * Que LISTA se esta desplazando.
 *
 * Una sola clave por lista visible, no una por pantalla: INICIO y CONVERSACION
 * son pantallas, pero el offset pertenece al DATO (contactos, mensajes, logs) y
 * sobrevive a cambiar de pestaña sin arrastrar el de otra lista.
 */
enum class ListId {
    CONTACTS,
    MESSAGES,
    LOGS,
}

/**
 * El desplazamiento de una lista, en elementos.
 *
 * [offset] es el primer elemento visible, no una fraccion ni un pixel: la TUI
 * trabaja en filas de celdas y el estado guarda semantica, no pintura.
 *
 * [followEnd] solo tiene sentido en CONVERSACION: mientras esta activo un
 * mensaje nuevo mantiene el final a la vista. En cuanto el usuario sube, se
 * apaga y los que lleguen se anuncian (`[N nuevos]`) en vez de mover el hilo
 * bajo el cursor.
 */
data class ScrollState(
    val offset: Int = 0,
    val followEnd: Boolean = false,
) {
    init {
        require(offset >= 0) { "offset de scroll negativo: $offset" }
    }
}

/**
 * La VENTANA de una lista: funciones PURAS, en elementos, sin terminal.
 *
 * ## POR QUE VIVE EN EL KERNEL Y NO EN LA PANTALLA
 *
 * Porque el reducer necesita saber si la fila enfocada cabe para decidir el
 * offset, y la pantalla necesita el mismo calculo para no formatear lo que no se
 * ve. Si cada uno lo hiciera a su manera habria dos definiciones de "visible" y
 * una fila enfocada podria quedar fuera justo despues de redimensionar.
 *
 * El alto (`rows`) NO se guarda en el estado: sale de
 * [Layout.listRows], que es puro. Guardarlo seria una segunda verdad que se
 * queda vieja en el primer SIGWINCH.
 *
 * ## EL INVARIANTE
 *
 * Para todo `total`, `rows` e `index` validos,
 * `ensure(index, offset, rows, total)` devuelve un offset tal que
 * `index in window(total, offset, rows)`. Lo afirma `ScrollTest` como propiedad,
 * no como caso suelto: "la fila enfocada siempre se ve" no se prueba con un
 * ejemplo.
 */
object Scroll {

    /** El mayor offset util: con menos elementos que filas, el unico offset es 0. */
    fun maxOffset(total: Int, rows: Int): Int =
        if (total <= 0 || rows <= 0) 0 else (total - rows).coerceAtLeast(0)

    /**
     * El offset minimo que deja [index] dentro de la ventana.
     *
     * Sube solo lo justo para que el foco entre por abajo y baja solo lo justo
     * para que entre por arriba: un `ensure` que centrara el foco en cada
     * pulsacion haria que la lista saltara aunque el usuario se moviera una fila.
     */
    fun ensure(index: Int, offset: Int, rows: Int, total: Int): Int {
        if (total <= 0 || rows <= 0) return 0
        val limite = maxOffset(total, rows)
        val actual = offset.coerceIn(0, limite)
        val indice = index.coerceIn(0, total - 1)
        return when {
            indice < actual -> indice
            indice >= actual + rows -> (indice - rows + 1).coerceIn(0, limite)
            else -> actual
        }
    }

    /**
     * Los indices visibles con [offset] y [rows].
     *
     * Devuelve un rango VACIO (`0..-1`) si no hay lista o no hay filas. El
     * llamador lo recorre con `for (i in ventana)`: no hay que comprobar
     * indices a mano ni arriesgarse a leer fuera de la lista.
     */
    fun window(total: Int, offset: Int, rows: Int): IntRange {
        if (total <= 0 || rows <= 0) return IntRange.EMPTY
        val inicio = offset.coerceIn(0, maxOffset(total, rows))
        val fin = (inicio + rows).coerceAtMost(total)
        return inicio until fin
    }

    /**
     * El indicador textual `[n/m]`, o `null` si la lista cabe entera.
     *
     * `n` es la ULTIMA fila visible y `m` el total: `[13/50]` se lee "estas
     * viendo hasta la 13 de 50". Es texto y no una barra de color, de modo que
     * sobrevive a MONO y a `--ascii` (R14/R23).
     *
     * Se omite cuando no hay ventana que anunciar: un hilo de tres mensajes con
     * `[3/3]` es ruido, y ademas cambiaria los 21 goldens de paridad sin darle
     * al usuario ninguna informacion.
     */
    fun indicator(offset: Int, rows: Int, total: Int): String? {
        if (total <= rows || rows <= 0) return null
        val visible = window(total, offset, rows).last
        return "[" + (visible + 1) + "/" + total + "]"
    }
}
