package com.km.desktop.support

import org.jline.terminal.Terminal
import java.io.PrintWriter
import java.io.StringWriter
import java.lang.reflect.Proxy

/**
 * Terminal de PRUEBA que solo acumula lo escrito y deja MOVER el tamano.
 *
 * ## POR QUE UN PROXY
 *
 * Implementar la interfaz entera de [Terminal] seria medio centenar de metodos
 * que no se invocan y que habria que mantener con cada version de JLine. Aqui
 * solo se responden `writer()` y `getSize()`; el resto devuelve el valor por
 * defecto de su tipo, que es lo que el codigo bajo prueba espera de un metodo
 * que no usa.
 *
 * ## POR QUE EL TAMANO ES MUTABLE
 *
 * El diff por filas tiene que invalidarse al redimensionar. Un `val` no
 * permitiria ejercitar ese camino sin un pty.
 */
class RecordingTerminal(columns: Int = 100, rows: Int = 50) {

    var columns: Int = columns
    var rows: Int = rows

    private val salida = StringWriter()

    val terminal: Terminal = Proxy.newProxyInstance(
        Terminal::class.java.classLoader,
        arrayOf(Terminal::class.java),
    ) { _, metodo, _ ->
        when (metodo.name) {
            "writer" -> PrintWriter(salida, true)
            // `this@RecordingTerminal` y no `columns` a secas: en el inicializador
            // de la propiedad, el nombre desnudo resuelve al PARAMETRO del
            // constructor (inmutable) y el test de resize no veria el cambio.
            "getSize" -> org.jline.terminal.Size(this@RecordingTerminal.columns, this@RecordingTerminal.rows)
            "toString" -> "RecordingTerminal"
            "hashCode" -> 1
            "equals" -> false
            else -> porDefecto(metodo.returnType)
        }
    } as Terminal

    /** Todo lo escrito hasta ahora. */
    fun texto(): String = salida.toString()

    /** Bytes (caracteres ASCII/UTF-16) escritos hasta ahora. */
    fun bytesEscritos(): Int = salida.toString().length

    /** Olvida lo escrito: el siguiente tramo se mide desde cero. */
    fun limpiar() {
        salida.buffer.setLength(0)
    }
}

/** Valor por defecto de un tipo, para metodos que el test no invoca. */
private fun porDefecto(tipo: Class<*>): Any? = when (tipo) {
    java.lang.Boolean.TYPE -> false
    java.lang.Byte.TYPE -> 0.toByte()
    java.lang.Short.TYPE -> 0.toShort()
    java.lang.Integer.TYPE -> 0
    java.lang.Long.TYPE -> 0L
    java.lang.Float.TYPE -> 0f
    java.lang.Double.TYPE -> 0.0
    java.lang.Character.TYPE -> ' '
    else -> null
}
