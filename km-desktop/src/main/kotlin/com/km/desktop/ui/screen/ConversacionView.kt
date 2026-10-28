package com.km.desktop.ui.screen

import com.km.desktop.ui.FocusField
import com.km.desktop.ui.MessageDirection
import com.km.desktop.ui.MessageRow
import com.km.desktop.ui.MouseIntent
import com.km.desktop.ui.Presence
import com.km.desktop.ui.RenderClock
import com.km.desktop.ui.Sanitizer
import com.km.desktop.ui.ShellState
import com.km.desktop.ui.layout.ListId
import com.km.desktop.ui.layout.RowRegion
import com.km.desktop.ui.layout.Scroll
import com.km.desktop.ui.render.BodyRow
import com.km.desktop.ui.theme.Style
import com.km.model.MessageState

/**
 * CONVERSACION: hilo de ancho completo, burbujas por autor y compositor con borde.
 *
 * ## SE PARECE A LA APP ANDROID, NO A UN LOG
 *
 * En Android el mensaje propio va a la DERECHA con corchetes (`[ ... ]`) y el del
 * par a la IZQUIERDA con llaves (`{ ... }`), y debajo lleva hora y estado. Aqui
 * se hace lo mismo en una sola fila, porque una terminal no tiene burbujas de dos
 * lineas sin gastar la pantalla:
 *
 *     { hola, ¿que tal?                    22:10 IN }
 *                    [ nos vemos a las 8   22:13 OUT ]
 *
 * La alineacion NO es color: los corchetes, el gap y la sangria se ven igual en
 * MONO y en `--ascii`. La burbuja nunca pasa del 70% del ancho, de modo que el
 * autor se reconoce de un vistazo aunque el texto sea largo.
 *
 * ## ESTADOS HONESTOS
 *
 * La etiqueta textual (`OUT`, `ENTREGADO`, `EXPIRADO ...`) sigue siendo la unica
 * traduccion del estado y nunca se sustituye por color (R8/R15). `OUT` solo
 * significa bytes aceptados por el socket.
 *
 * ## COMPOSITOR CON BORDE, EN EL PIE
 *
 * Una regla de ancho completo y una linea `│ > texto ... │`: el mismo campo
 * enmarcado de la app, sin botones flotantes. Las teclas van debajo.
 */
internal object ConversacionView {

    /** La burbuja nunca ocupa mas de esta fraccion del ancho. */
    private const val FRACCION_BURBUJA: Int = 70

    fun body(
        state: ShellState,
        ascii: Boolean,
        clock: RenderClock,
        rows: Int,
        width: Int,
    ): List<BodyRow> {
        val conv = state.conversation ?: return listOf(BodyRow("sin conversacion abierta"))
        val cells = ArrayList<BodyRow>(rows + 4)
        val sesion = if (conv.hasSession) "S sesion" else "sin sesion: el envio se rechazara"
        val scroll = state.scrollOf(ListId.MESSAGES)
        val total = conv.messages.size
        val badge = Scroll.indicator(scroll.offset, rows, total)
        val nuevos = if (conv.unread > 0) "[${conv.unread} nuevos \u2193]" else null
        val pendientes = listOfNotNull(nuevos, badge).joinToString("  ")
        cells += BodyRow(
            conv.peerPresence.glyph + " " + presencia(conv.peerPresence) + " \u00b7 " + sesion +
                (if (pendientes.isEmpty()) "" else "  " + pendientes),
            style = Style.MUTED,
        )
        if (total == 0) {
            // El vacio se pinta DENTRO del area del hilo, centrado como en la app.
            val texto = "sin mensajes: la cola vive en memoria y el relay no guarda"
            repeat((rows - 1).coerceAtLeast(0)) { i ->
                cells += if (i == 0) BodyRow(centrar(texto, width), style = Style.MUTED) else BodyRow("")
            }
        } else {
            val ventana = Scroll.window(total, scroll.offset, rows)
            // Como en la app, el hilo crece HACIA ARRIBA: si sobran filas se
            // rellenan por delante y el mensaje nuevo queda pegado al compositor.
            repeat((rows - ventana.count()).coerceAtLeast(0)) { cells += BodyRow("") }
            for (i in ventana) {
                val foco = state.focus.field == FocusField.LIST && i == state.focus.index
                cells += burbuja(conv.messages[i], clock, foco, width)
                    .copy(regions = listOf(RowRegion(0, null, MouseIntent.SelectMessage(i))))
            }
        }
        cells += BodyRow("", separator = true, style = Style.MUTED)
        cells += compositor(conv.composer, state.focus.field == FocusField.COMPOSER, width, ascii)
            .copy(regions = listOf(RowRegion(0, null, MouseIntent.FocusComposer)))
        cells += BodyRow(
            when {
                conv.sending -> "enviando\u2026 si el nucleo acepta, la fila pasa a OUT (no es entrega)"
                state.selectedMessage()?.retryable == true ->
                    "[R] reintentar (mismo id)   [D] descartar (confirma)   [ENTER] enviar"
                else -> "[ENTER] enviar   [Tab] lista/compositor   [ESC] volver"
            },
            style = if (conv.sending) Style.WARNING else Style.MUTED,
        )
        return cells
    }

    /**
     * Una burbuja alineada por autor, con su hora y su etiqueta FUERA del marco.
     *
     * `{ texto }  22:08 [IN]` a la izquierda si es del par; `[ texto ]  22:10
     * [OUT]` a la derecha si es propio. La etiqueta conserva los corchetes de
     * siempre (`[OUT]`, `[ENTREGADO]`, `[EXPIRADO ...]`): es texto, no color, y
     * el estado nunca se sustituye por un tono.
     */
    private fun burbuja(
        fila: MessageRow,
        clock: RenderClock,
        foco: Boolean,
        width: Int,
    ): BodyRow {
        val propio = fila.direction == MessageDirection.OUT
        val abierto = if (propio) "[" else "{"
        val cerrado = if (propio) "]" else "}"
        val meta = clock.hora(fila.at) + " [" + Sanitizer.text(fila.label) + "]"
        val tope = (width * FRACCION_BURBUJA / 100).coerceIn(12, width)
        val disponible = (tope - 4 - 2 - Sanitizer.width(meta)).coerceAtLeast(1)
        val texto = Sanitizer.truncate(fila.text, disponible)
        val cuerpo = abierto + " " + texto + " " + cerrado + "  " + meta
        val sangria = if (propio) (width - Sanitizer.width(cuerpo)).coerceAtLeast(0) else 0
        val tono = when (fila.state) {
            MessageState.EXPIRED, MessageState.FAILED -> Style.DANGER
            MessageState.SENDING -> Style.MUTED
            else -> Style.NONE
        }
        return BodyRow(" ".repeat(sangria) + cuerpo, focus = foco, style = tono)
    }

    /** El compositor: regla ya pintada por el llamador; esta linea lleva el borde. */
    private fun compositor(texto: String, foco: Boolean, width: Int, ascii: Boolean): BodyRow {
        val borde = com.km.desktop.ui.theme.Glyphs(ascii).vertical
        val interior = (width - 4).coerceAtLeast(1)
        val contenido = Sanitizer.truncate(texto, interior)
        val cursor = if (foco) "\u2588" else ""
        val cuerpo = "> " + contenido + cursor
        val linea = borde + " " + Sanitizer.pad(cuerpo, interior) + " " + borde
        return BodyRow(linea, focus = foco)
    }

    private fun centrar(texto: String, width: Int): String {
        val limpio = Sanitizer.truncate(texto, width)
        val sangria = ((width - Sanitizer.width(limpio)) / 2).coerceAtLeast(0)
        return " ".repeat(sangria) + limpio
    }

    private fun presencia(presence: Presence): String = when (presence) {
        Presence.UNKNOWN -> "presencia desconocida"
        Presence.RELAY_PRESENT -> "relay-presente (NO es disponibilidad)"
        Presence.RELAY_ABSENT -> "ausente del relay"
    }
}
