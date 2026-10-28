package com.km.desktop.shell

import com.km.desktop.ui.LinePalette
import com.km.desktop.ui.Osc52
import com.km.desktop.ui.RenderedLine
import com.km.desktop.ui.Size
import com.km.desktop.ui.theme.Attr
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
 * ## DIFF POR FILAS (M7)
 *
 * [draw] compara el frame con el ULTIMO que escribio y solo posiciona y escribe
 * las filas que cambiaron (`ESC[<n>;1H`, texto, `ESC[K`). Un frame identico no
 * emite NI UN BYTE: ni siquiera un flush. Con 500 mensajes en pantalla eso es la
 * diferencia entre repintar quince filas y repintar el terminal entero en cada
 * tick.
 *
 * La comparacion es por [RenderedLine] COMPLETA —texto, spans con su estilo,
 * foco y paleta—, no solo por caracteres: una fila que solo cambia de color
 * tiene que repintarse. Ese es exactamente el error que deja basura visible, y
 * por eso `ScreenWriterDiffTest` lo afirma.
 *
 * ## CUANDO SE REPINTA TODO
 *
 * - primer frame;
 * - cambio de tamano (filas distintas o `Terminal.size` distinto);
 * - [enter]/[exit] (la pantalla alternativa se limpia y lo pintado ya no esta);
 * - [invalidate], que el bucle llama al cambiar de tema o de capacidad de color
 *   y al entrar o salir de `--ascii`.
 *
 * ## QUE NO HACE
 *
 * No formatea contenido, no sanea y no mide: recibe lineas ya medidas por
 * [com.km.desktop.ui.ShellRenderer]. Si midiera aqui, habria dos anchos. No
 * decide el layout: solo decide que filas de lo recibido hay que reescribir.
 */
class ScreenWriter(
    private val terminal: Terminal,
    /** `true` si se pueden emitir atributos SGR (color/reverse). */
    private val attributes: Boolean,
    /** Cuanto color entiende el terminal; MONO no emite ni un SGR. */
    private val capability: com.km.desktop.ui.theme.ColorCapability =
        com.km.desktop.ui.theme.ColorCapability.ANSI16,
) {

    /**
     * Si se pueden emitir atributos. El bucle de la TUI lo consulta para decidir
     * si el renderer puede ofrecer QR: sin color no se pintan bloques.
     */
    val attributesEnabled: Boolean get() = attributes && capability.sgr

    private val out = terminal.writer()
    private var entered = false

    /** El tracking esta ACTIVO en este momento (para no duplicar el ON). */
    private var mouseOn = false

    /** Ultimo frame pintado, para reescribir SOLO las filas que cambiaron. */
    private var ultimo: List<RenderedLine>? = null

    /** Tamano con el que se pinto [ultimo]: si cambia, el diff no sirve. */
    private var ultimoTamano: Size? = null

    companion object {
        /** Sube el cursor a casa sin limpiar: inicio de un repintado completo. */
        private const val HOME: String = "\u001b[H"

        /** Borra desde el cursor hasta el final de la pantalla. */
        private const val CLEAR_BELOW: String = "\u001b[J"

        /** Borra desde el cursor hasta el final de la fila. */
        private const val CLEAR_LINE: String = "\u001b[K"

        /**
         * Posicion absoluta del cursor al principio de [fila] (base 1).
         *
         * Se usa posicionamiento ABSOLUTO y no movimientos relativos: una fila
         * que se acorta o un frame con menos filas dejan de importar, porque no
         * hay ninguna suposicion sobre donde quedo el cursor.
         */
        fun cursor(fila: Int): String = "\u001b[" + (fila + 1) + ";1H"

        /**
         * Negro sobre blanco: el contraste FIJO del QR.
         *
         * Es el MISMO par que [Sgr.QR] (unica fuente): `30;47` basico y no 256
         * colores para que funcione igual en cualquier terminal que soporte
         * SGR, incluidos los que no implementan `38;5;n`.
         */
        const val QR_STYLE: String = Sgr.QR

        /** Fin de los atributos: vuelve al tema del terminal. */
        const val RESET: String = "\u001b[0m"

        /** Reverse video: el refuerzo del foco, nunca la unica señal. */
        const val REVERSE: String = "\u001b[7m"

        /**
         * Tracking del raton: `?1000h` (press/release) + `?1006h` (SGR).
         *
         * NO se activan `?1002`/`?1003`: change B no tiene arrastre ni hover, y
         * pedirlos haria que el terminal mandara movimiento que la app descarta.
         */
        const val MOUSE_ON: String = "\u001b[?1000h\u001b[?1006h"

        /**
         * Apagado del tracking. Va SIEMPRE antes de `?25h?1049l`: dejar el
         * raton capturado al volver al shell es un terminal inservible hasta
         * que el usuario reinicia la sesion.
         */
        const val MOUSE_OFF: String = "\u001b[?1000l\u001b[?1006l"
    }

    /** La terminal mide lo que mide AHORA. */
    fun size(): Size {
        val s = terminal.size
        return Size(s.columns.coerceAtLeast(0), s.rows.coerceAtLeast(0))
    }

    /**
     * Entra en pantalla alternativa, esconde el cursor y limpia.
     *
     * [mouse] activa el tracking DESPUES de la pantalla alternativa y del
     * borrado: el orden importa porque `?1006h` no debe irse con un `2J`.
     *
     * ## YA DENTRO (desbloqueo -> TUI)
     *
     * `UnlockGate` deja el escritor dentro de la pantalla alternativa y el
     * shell arranca despues con el MISMO escritor. Por eso, si ya se entro y
     * ahora se pide raton, se emite SOLO el ON: salir y volver a entrar
     * borraria la pantalla de desbloqueo sin necesidad y el tracking se
     * quedaria sin activar (el `return` temprano de antes).
     */
    fun enter(mouse: Boolean = false) {
        if (!entered) {
            entered = true
            // La pantalla se limpia: lo que hubiera pintado antes ya no esta.
            invalidate()
            out.print("\u001b[?1049h\u001b[?25l\u001b[2J\u001b[H")
        }
        if (mouse && !mouseOn) {
            out.print(MOUSE_ON)
            mouseOn = true
        }
        out.flush()
    }

    /**
     * Olvida el ultimo frame: el siguiente [draw] repinta TODO.
     *
     * Hace falta cuando cambia algo que el diff no puede ver en las lineas —la
     * capacidad de color, `--ascii`, el tamano— porque comparar solo el texto
     * reescribiria una fila con los atributos viejos. Con el ultimo frame
     * olvidado no hay comparacion posible y se pinta entero.
     */
    fun invalidate() {
        ultimo = null
        ultimoTamano = null
    }

    /**
     * Pinta [lines]: solo las filas que cambiaron, posicionando el cursor.
     *
     * ## IDENTICO ES CERO BYTES
     *
     * Si la fila i es igual a la del ultimo frame no se emite NADA para ella. Un
     * frame entero identico no escribe ni un byte ni hace flush: no hay
     * repintado que pueda parpadear.
     *
     * ## LA FILA SE COMPARA ENTERA
     *
     * [RenderedLine] es un `data class` con texto, spans (que llevan el
     * [com.km.desktop.ui.theme.Style]), foco y paleta. Comparar la linea entera
     * es lo que hace que un cambio SOLO de color o de foco se repinte; comparar
     * el texto dejaria en pantalla el color anterior.
     *
     * ## EL QR NO USA EL TEMA DEL TERMINAL
     *
     * Las celdas del QR llevan [com.km.desktop.ui.theme.Style.QR] y [Sgr.of] las
     * traduce al par FIJO `30;47` —negro sobre blanco— sin mirar el tema. Los
     * glifos `█ ▀ ▄` representan modulos OSCUROS: con el primer plano claro y el
     * fondo oscuro del tema habitual el QR salia INVERTIDO y ninguna camara lo
     * leia.
     *
     * ## EL `ESC[K` VA DESPUES DEL RESET (correccion post-archivo)
     *
     * Antes se emitia `ESC[K` con el fondo blanco del QR ACTIVO, de modo que el
     * borrado llegaba hasta el borde derecho de la terminal: una barra blanca de
     * ancho completo bajo el codigo, aunque el QR midiera la mitad. Ahora el
     * borrado de fin de linea va SIEMPRE con los atributos reseteados:
     * [Sgr.of] cierra cada tramo con [RESET] y el blanco cubre solo las celdas
     * que el compositor marco como QR.
     *
     * Solo se emite con [attributes] activo. Sin color no hay QR: el renderer ni
     * siquiera produce esas lineas (ver `ShellRenderer.render(color = ...)`).
     */
    fun draw(lines: List<RenderedLine>) {
        if (!entered) enter()
        val tamano = size()
        val sb = StringBuilder(lines.size * 80)
        if (needsFullRepaint(lines, tamano)) {
            fullRepaint(sb, lines)
            ultimo = lines
            ultimoTamano = tamano
        } else {
            val previo = ultimo ?: return
            var reescritas = 0
            lines.forEachIndexed { i, linea ->
                if (linea == previo[i]) return@forEachIndexed
                sb.append(cursor(i))
                appendRow(sb, linea)
                reescritas++
            }
            // Ni una fila cambio: cero bytes, ni un flush. El ultimo frame sigue
            // siendo valido y no hay nada que actualizar.
            if (reescritas == 0) return
            ultimo = lines
            ultimoTamano = tamano
        }
        out.print(sb)
        out.flush()
    }

    /**
     * `true` cuando el diff no es aplicable y hay que repintar el frame entero.
     *
     * Cubre el primer frame, un cambio de alto del frame y un cambio de tamano
     * del terminal: en los tres casos "la fila i de ahora" ya no esta en el
     * mismo sitio que "la fila i de antes".
     */
    private fun needsFullRepaint(lines: List<RenderedLine>, tamano: Size): Boolean {
        val previo = ultimo ?: return true
        return previo.size != lines.size || ultimoTamano != tamano
    }

    /** Frame completo: cursor a casa, filas, y borrar lo que sobra por abajo. */
    private fun fullRepaint(sb: StringBuilder, lines: List<RenderedLine>) {
        sb.append(HOME)
        lines.forEachIndexed { i, linea ->
            appendRow(sb, linea)
            if (i != lines.lastIndex) sb.append("\r\n")
        }
        sb.append(CLEAR_BELOW)
    }

    /**
     * Una fila completa: texto con su estilo, `ESC[K` y, si toca, el reset.
     *
     * ## EL BORRADO NUNCA HEREDA EL ESTILO
     *
     * El `ESC[K` va SIEMPRE despues de cerrar los atributos del ultimo tramo.
     * Emitirlo dentro del estilo del QR pintaba de blanco hasta el borde de la
     * terminal: el defecto que el usuario reprodujo como una banda blanca
     * horizontal a la derecha del codigo. El blanco del QR son CELDAS de la
     * rejilla, no un fondo de linea.
     */
    private fun appendRow(sb: StringBuilder, linea: RenderedLine) {
        if (!attributesEnabled) {
            sb.append(linea.text)
            sb.append(CLEAR_LINE)
            return
        }
        // Una linea QR sin tramos (tests de bytes, llamadas directas) conserva el
        // contraste fijo, pero el borrado sigue yendo DESPUES del reset.
        if (linea.spans.isEmpty() && linea.palette == LinePalette.QR) {
            sb.append(QR_STYLE).append(linea.text).append(RESET)
        } else {
            appendStyled(sb, linea)
        }
        sb.append(CLEAR_LINE)
    }

    /**
     * Escribe [linea] con sus tramos de estilo.
     *
     * El foco se suma a cada tramo como `REVERSE` ADEMAS del glifo que ya va en
     * el texto. Si la linea no trae tramos (p. ej. el desbloqueo) se conserva el
     * reverse de linea completa de siempre.
     */
    private fun appendStyled(sb: StringBuilder, linea: RenderedLine) {
        val spans = linea.spans
        if (spans.isEmpty()) {
            if (linea.focus) sb.append(REVERSE).append(linea.text).append(RESET) else sb.append(linea.text)
            return
        }
        spans.forEach { span ->
            val style = if (linea.focus) span.style.with(Attr.REVERSE) else span.style
            val sgr = Sgr.of(style, capability)
            if (sgr.isEmpty()) {
                sb.append(span.text)
            } else {
                sb.append(sgr).append(span.text).append(RESET)
            }
        }
    }

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
        // Al volver a entrar la pantalla alternativa empieza limpia: el diff
        // contra el frame de la sesion anterior no valdria.
        invalidate()
        // El raton se SUELTA ANTES de salir de la pantalla alternativa y de
        // devolver el cursor. Se emite siempre (aunque `enter` fuera sin raton):
        // apagar un modo que no estaba encendido es inofensivo, y olvidarlo
        // cuando si lo estaba deja el terminal capturado.
        out.print(MOUSE_OFF + RESET + "\u001b[?25h\u001b[?1049l")
        out.flush()
        mouseOn = false
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
