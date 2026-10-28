package com.km.desktop.ui.widget

import com.km.desktop.ui.ConversationView
import com.km.desktop.ui.LinkState
import com.km.desktop.ui.Presence
import com.km.desktop.ui.Sanitizer
import com.km.desktop.ui.ShellState
import com.km.desktop.ui.render.BodyRow
import com.km.desktop.ui.theme.Glyphs
import com.km.desktop.ui.theme.Style

/**
 * La BARRA DE ESTADO: identidad, relay, presencia del par, contadores y
 * aviso de volatilidad.
 *
 * ## NUNCA DICE ONLINE DEL PAR
 *
 * `P2P[o]` describe el enlace AL RELAY, no la disponibilidad del par. Por eso
 * la barra dice `par ?` / `par ·` / `par -` y la etiqueta del enlace es
 * "conectado", no "online". Prometer presencia o entrega es el fallo que R18
 * prohibe.
 *
 * ## SE ADAPTA, NO MIENTE
 *
 * A 60 columnas no cabe todo: se acorta la identidad y se omite el endpoint,
 * pero NUNCA se omite el aviso de volatilidad. El orden de sacrificio esta
 * escrito aqui para que la barra no cambie de contenido segun el humor.
 */
object StatusBar {

    fun row(state: ShellState, width: Int, ascii: Boolean, nowMs: Long): BodyRow {
        val g = Glyphs(ascii)
        val sep = if (ascii) " | " else " \u00b7 "
        val status = state.status
        val fp = status.localFingerprint
        val corta = fp?.let { if (Sanitizer.width(it) > 9) Sanitizer.truncate(it, 9) else it }
        val endpoint = status.endpoint?.let { Sanitizer.truncate(it, 22) }
        val link = status.glyph + " " + status.link.label
        val par = "par[" + presencia(state.conversation) + "]"
        val counters = "S" + status.sessions + " K" + status.ackKeys
        val volatil = if (ascii) "VOLATIL" else "VOL\u00c1TIL"
        val spinner = if (enVuelo(state)) g.spinner((nowMs / 120).toInt()) + " " else ""

        fun junta(identidad: String?, relay: String?): String = buildList {
            add(spinner + "yo " + (identidad ?: "sin identidad"))
            relay?.let { add("relay " + it) }
            add(link)
            add(par)
            add(counters)
            add(volatil)
        }.joinToString(sep)

        val completo = junta(fp, endpoint)
        val endpointCorto = status.endpoint?.let { Sanitizer.truncate(it, 18) }
        val texto = when {
            Sanitizer.width(completo) <= width -> completo
            Sanitizer.width(junta(corta, endpoint)) <= width -> junta(corta, endpoint)
            Sanitizer.width(junta(corta, endpointCorto)) <= width -> junta(corta, endpointCorto)
            Sanitizer.width(junta(corta, null)) <= width -> junta(corta, null)
            else -> Sanitizer.truncate(junta(corta, null), width)
        }
        return BodyRow(texto, style = styleOf(state))
    }

    /** El glifo de presencia del PAR; en INICIO no hay par, asi que `?`. */
    private fun presencia(conv: ConversationView?): String =
        (conv?.peerPresence ?: Presence.UNKNOWN).glyph

    /** Hay un efecto en vuelo: solo entonces gira el spinner. */
    private fun enVuelo(state: ShellState): Boolean =
        state.conversation?.sending == true ||
            state.status.link == LinkState.CONNECTING ||
            state.status.link == LinkState.RECONNECTING

    /** Un enlace caido tiñe la barra; el texto ya dice que pasa en MONO. */
    private fun styleOf(state: ShellState): Style = when (state.status.link) {
        LinkState.DISCONNECTED -> Style.DANGER
        LinkState.CONNECTING, LinkState.RECONNECTING -> Style.WARNING
        LinkState.ONLINE -> Style.MUTED
        LinkState.UNKNOWN -> Style.MUTED
    }
}
