package com.km.desktop.ui.screen

import com.km.desktop.ui.ContactRow
import com.km.desktop.ui.FocusField
import com.km.desktop.ui.MouseIntent
import com.km.desktop.ui.RenderClock
import com.km.desktop.ui.Sanitizer
import com.km.desktop.ui.ShellState
import com.km.desktop.ui.layout.ListId
import com.km.desktop.ui.layout.RowRegion
import com.km.desktop.ui.layout.Scroll
import com.km.desktop.ui.render.BodyRow
import com.km.desktop.ui.theme.Glyphs
import com.km.desktop.ui.theme.Style

/**
 * INICIO: la lista de chats, de ancho completo.
 *
 * ## POR QUE ASI Y NO UN PANEL CENTRADO
 *
 * El usuario lo dijo con sus palabras: en escritorio no tiene sentido centrar el
 * contenido, se lee como WhatsApp. Una lista de chats ocupa TODO el ancho: alias
 * a la izquierda, ultimo mensaje en el centro, hora y estado a la derecha. El
 * `maxWidth` de 88 columnas dejaba la pantalla flotando en el medio con dos
 * franjas vacias a los lados.
 *
 * ## UNA FILA = UN CONTACTO + SU SEPARADOR
 *
 * Cada contacto gasta dos filas: la ficha (`▌ · ana  hasta luego  22:13 SK`) y
 * una regla fina de ancho completo, igual que la lista de Android pone una regla
 * tras cada fila. `Layout.listRows` devuelve CONTACTOS visibles, no filas, para
 * que la ventana de scroll y `ensureVisible` cuenten lo mismo que se pinta.
 *
 * ## LO QUE NO SE PIERDE
 *
 * - El `?` de presencia desconocida sigue a la vista: nunca se dice ONLINE.
 * - `S` (sesion) y `K` (clave de firma) conservan su columna aunque falten.
 * - El aviso de volatilidad vive en la barra de estado (`VOLATIL`), que se pinta
 *   en todas las pantallas, y la leyenda de teclas en el pie y en la ayuda.
 * - El estado vacio/cargando/error se pinta DENTRO del area de la lista.
 */
internal object InicioView {

    /** Ancho minimo que se reserva al alias; el resto se lo queda el mensaje. */
    private const val ALIAS_MIN: Int = 8

    /** Tope del alias: mas ancho convierte la ficha en una linea de log. */
    private const val ALIAS_MAX: Int = 22

    fun body(
        state: ShellState,
        ascii: Boolean,
        clock: RenderClock,
        rows: Int,
        width: Int,
    ): List<BodyRow> {
        val filas = state.filteredContacts()
        val scroll = state.scrollOf(ListId.CONTACTS)
        val badge = Scroll.indicator(scroll.offset, rows, filas.size)
        val cells = ArrayList<BodyRow>(2 + rows * 2)
        cells += buscador(state, badge, ascii, width)
        cells += BodyRow("CONTACTOS " + filas.size, separator = true, style = Style.MUTED)
        when {
            state.contactsError != null ->
                cells += BodyRow(
                    "! no se pudo cargar la lista codigo=" + state.contactsError,
                    style = Style.DANGER,
                )

            !state.contactsLoaded ->
                cells += BodyRow("cargando contactos\u2026", style = Style.MUTED)

            filas.isEmpty() && state.filter.isNotBlank() ->
                cells += BodyRow("ningun contacto coincide con el filtro", style = Style.MUTED)

            filas.isEmpty() -> {
                cells += BodyRow("sin contactos todavia", style = Style.MUTED)
                cells += BodyRow("[N]uevo importa uno por texto, .km o QR", style = Style.MUTED)
                cells += BodyRow(
                    "la lista esta SOLO EN MEMORIA: se pierde al reiniciar",
                    style = Style.WARNING,
                )
            }

            else -> for (i in Scroll.window(filas.size, scroll.offset, rows)) {
                val foco = state.focus.field == FocusField.LIST && i == state.focus.index
                // La ficha Y su regla abren el chat: el bloque del contacto es
                // una sola zona clicable de ancho completo, como en la app.
                val region = listOf(RowRegion(0, null, MouseIntent.SelectContact(i)))
                cells += ficha(filas[i], clock, foco, ascii, width).copy(regions = region)
                cells += BodyRow("", separator = true, style = Style.MUTED, regions = region)
            }
        }
        return cells
    }

    /**
     * El buscador: una fila, siempre, para que la lista empiece siempre igual.
     *
     * `[/]` es la tecla real del keymap. Cuando no hay filtro ni foco no se
     * inventa un campo vacio: se anuncia el atajo.
     */
    private fun buscador(state: ShellState, badge: String?, ascii: Boolean, width: Int): BodyRow {
        val g = Glyphs(ascii)
        val activo = state.focus.field == FocusField.FIELD
        val texto = when {
            activo -> "> /" + state.filter + "\u2588"
            state.filter.isNotEmpty() -> "[/] " + state.filter + "   (Esc lo limpia)"
            else -> "[/] buscar"
        }
        val derecha = badge?.let { g.scrollBadge(it) } ?: ""
        val hueco = width - Sanitizer.width(texto) - Sanitizer.width(derecha)
        val linea = when {
            derecha.isEmpty() -> texto
            hueco >= 1 -> texto + " ".repeat(hueco) + derecha
            else -> texto
        }
        return BodyRow(
            linea,
            focus = activo,
            style = if (activo) Style.NONE else Style.MUTED,
            regions = listOf(RowRegion(0, null, MouseIntent.FocusFilter)),
        )
    }

    /**
     * Una ficha de ancho completo: `marca presencia alias  mensaje  hora S K`.
     *
     * El texto se rellena hasta [width] a proposito: el reverse del foco tiene
     * que cubrir la FILA entera, no solo el texto escrito. El alias se recorta
     * antes que el mensaje porque el mensaje es lo que cambia y lo que se lee.
     */
    private fun ficha(
        fila: ContactRow,
        clock: RenderClock,
        foco: Boolean,
        ascii: Boolean,
        width: Int,
    ): BodyRow {
        val g = Glyphs(ascii)
        val marca = if (foco) g.focus + " " else "  "
        val presencia = fila.presence.glyph + " "
        val hora = fila.lastAt?.let { clock.hora(it) } ?: "     "
        val estado = (if (fila.hasSession) "S" else "-") + (if (fila.hasAckKey) "K" else "-")
        val derecha = " " + hora + " " + estado
        val fijo = Sanitizer.width(marca) + Sanitizer.width(presencia) + Sanitizer.width(derecha)
        val hueco = (width - fijo - 1).coerceAtLeast(0)
        val aliasAncho = minOf(ALIAS_MAX, maxOf(ALIAS_MIN, hueco / 3)).coerceAtMost(hueco)
        val mensajeAncho = (hueco - aliasAncho).coerceAtLeast(0)
        val alias = Sanitizer.pad(Sanitizer.truncate(fila.label, aliasAncho), aliasAncho)
        val mensaje = Sanitizer.pad(
            Sanitizer.truncate(
                fila.lastMessage
                    ?: if (fila.hasSession) "(sin mensajes)" else "(sin mensajes \u00b7 sin sesion)",
                mensajeAncho,
            ),
            mensajeAncho,
        )
        val linea = marca + presencia + alias + " " + mensaje + derecha
        return BodyRow(Sanitizer.pad(linea, width), focus = foco)
    }
}
