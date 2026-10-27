package com.km.desktop.shell

import com.km.desktop.ui.Osc52
import com.km.desktop.ui.RenderedLine
import com.km.desktop.ui.Size
import org.jline.terminal.Terminal

/**
 * El UNICO escritor del terminal de la TUI.
 *
 * ## POR QUE UNA CLASE Y NO `println` EN CADA PANTALLA
 *
 * Porque una pantalla completa se repinta: dos escritores (el renderer y un
 * handler del motor) intercalan media caja con media linea de log y el resultado
 * es ilegible. Aqui no hay estado de pantalla: [draw] escribe el frame COMPLETO,
 * desde el cursor a casa y limpiando lo que sobra. Todos los demas publican
 * eventos y nadie mas escribe.
 *
 * ## RESTAURAR NO ES OPCIONAL
 *
 * [enter] usa la pantalla alternativa y esconde el cursor. Si el proceso muere
 * sin [exit], el usuario se queda sin prompt, sin cursor y sin ver su shell. Por
 * eso [exit] es idempotente y el bucle lo llama en `finally` Y desde un
 * shutdown hook (SIGINT/SIGTERM).
 *
 * ## QUE NO HACE
 *
 * No formatea contenido, no sanea y no mide: recibe lineas ya medidas por
 * [com.km.desktop.ui.ShellRenderer]. Si midiera aqui, habria dos anchos.
 */
class ScreenWriter(
    private val terminal: Terminal,
    /** `true` si se pueden emitir atributos SGR (color/reverse). */
    private val attributes: Boolean,
) {

    private val out = terminal.writer()
    private var entered = false

    /** Ultimo frame pintado, para no repintar si nada cambio. */
    private var ultimo: List<RenderedLine>? = null

    /** La terminal mide lo que mide AHORA. */
    fun size(): Size {
        val s = terminal.size
        return Size(s.columns.coerceAtLeast(0), s.rows.coerceAtLeast(0))
    }

    /** Entra en pantalla alternativa, esconde el cursor y limpia. */
    fun enter() {
        if (entered) return
        entered = true
        out.print("\u001b[?1049h\u001b[?25l\u001b[2J\u001b[H")
        out.flush()
    }

    /**
     * Pinta [lines] desde arriba, limpiando el resto.
     *
     * Repintar SIEMPRE (en vez de diferencias) es una decision: una pantalla
     * completa de 60-120 lineas es barata, y un diff mal hecho deja restos de la
     * pantalla anterior, que es el fallo que el usuario nota.
     */
    fun draw(lines: List<RenderedLine>) {
        if (!entered) enter()
        val sb = StringBuilder(lines.size * 80)
        sb.append("\u001b[H")
        lines.forEachIndexed { i, linea ->
            if (attributes && linea.focus) sb.append("\u001b[7m")
            sb.append(linea.text)
            if (attributes && linea.focus) sb.append("\u001b[0m")
            sb.append("\u001b[K")
            if (i != lines.lastIndex) sb.append("\r\n")
        }
        sb.append("\u001b[J")
        out.print(sb)
        out.flush()
        ultimo = lines
    }

    /** `true` si el frame es identico al ultimo: el bucle se ahorra el repintado. */
    fun sameFrame(lines: List<RenderedLine>): Boolean = lines == ultimo

    /**
     * Copia por OSC 52. Devuelve si se EMITIO la secuencia, no si el terminal
     * copio: eso no se puede saber y no se finge.
     */
    fun copy(text: String): Boolean {
        val secuencia = Osc52.sequence(text) ?: return false
        // Se sale de la pantalla alternativa solo para la secuencia: no, al
        // contrario. OSC 52 es una secuencia de control que el terminal procesa
        // sin cambiar la pantalla; se escribe tal cual.
        out.print(secuencia)
        out.flush()
        return true
    }

    /** Restaura cursor, atributos y pantalla normal. Idempotente. */
    fun exit() {
        if (!entered) return
        entered = false
        out.print("\u001b[0m\u001b[?25h\u001b[?1049l")
        out.flush()
    }

    /**
     * Escribe texto en la pantalla NORMAL (fuera de la alternativa).
     *
     * Solo para el mensaje de despedida o el aviso de que la sesion termino por
     * una restauracion. Nunca se usa mientras la TUI pinta.
     */
    fun plain(text: String) {
        out.println(text)
        out.flush()
    }

    /** La terminal subyacente, para apagar el modo crudo al salir. */
    fun close() {
        exit()
    }
}
