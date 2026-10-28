package com.km.desktop.ui

import com.km.desktop.shell.ScreenWriter
import com.km.desktop.support.RecordingTerminal
import com.km.model.IdentityId
import com.km.model.MessageId
import com.km.model.MessageState
import com.km.desktop.ui.layout.Layout
import com.km.desktop.ui.layout.ListId
import com.km.desktop.ui.layout.Scroll
import com.km.desktop.ui.layout.ScrollState
import com.km.desktop.ui.screen.ConversacionView
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * COSTE DE UN FRAME con un hilo largo (M7).
 *
 * ## QUE SE MIDE, Y QUE NO
 *
 * No se mide tiempo de pared: un `assertTrue(ms < 50)` falla en una maquina
 * cargada y pasa en una rapida, y no dice nada del algoritmo. Se miden dos cosas
 * DETERMINISTAS:
 *
 * 1. **Filas formateadas**: la lista de mensajes se envuelve en un [List] que
 *    cuenta cada `get`. Si la vista formateara el hilo entero, leería 500; se
 *    exige que lea solo la ventana visible.
 * 2. **Bytes escritos**: con el diff, un frame identico son 0 bytes y un cambio
 *    visible es UNA fila; si el diff degradara el coste, se reescribiria el
 *    frame completo.
 *
 * ## EL CASO REAL
 *
 * 500 mensajes a 80x24. La ventana son 15 filas: el frame cuesta lo que 15
 * mensajes, no 500. Ese es el bug que `fit()` escondia recortando el centro y el
 * que `Scroll.window` cierra.
 */
class ConversationWindowCostTest {

    /** Envuelve la lista para saber QUE indices se leyeron, sin cambiar nada mas. */
    private class CountingMessages(private val base: List<MessageRow>) : AbstractList<MessageRow>() {
        val leidos = ArrayList<Int>()

        override val size: Int get() = base.size

        override fun get(index: Int): MessageRow {
            leidos += index
            return base[index]
        }
    }

    private val peer = IdentityId("ab".repeat(32))

    private fun mensajes(total: Int): List<MessageRow> = (0 until total).map { i ->
        MessageRow(
            messageId = MessageId(UUID(0L, i.toLong())),
            direction = if (i % 2 == 0) MessageDirection.IN else MessageDirection.OUT,
            at = 1_700_000_000_000L + i * 60_000L,
            text = "mensaje numero $i",
            state = MessageState.DELIVERED,
        )
    }

    private val viewport = Size(80, 24)
    private val rows = Layout.listRows(Screen.CONVERSACION, viewport)

    private fun estado(mensajes: List<MessageRow>, total: Int): ShellState = ShellState(
        tabs = TabStacks.of(Screen.INICIO, Screen.CONVERSACION),
        viewport = viewport,
        conversation = ConversationView(
            peer = peer,
            title = "par",
            messages = mensajes,
            peerPresence = Presence.RELAY_PRESENT,
            hasSession = true,
        ),
        scroll = mapOf(
            ListId.MESSAGES to ScrollState(
                offset = Scroll.maxOffset(total, rows),
                followEnd = true,
            ),
        ),
    )

    @Test
    fun `el frame formatea la ventana visible y no el hilo entero`() {
        val total = 500
        val contador = CountingMessages(mensajes(total))
        val state = estado(contador, total)

        val cuerpo = ConversacionView.body(state, ascii = false, clock = RenderClock.UTC_EPOCH, rows = rows, width = viewport.columns - 2 * Layout.padding(viewport.columns))

        // presencia + MENSAJES + ventana + separador + compositor + acciones
        assertEquals(
            rows + Layout.listChrome(Screen.CONVERSACION),
            cuerpo.size,
            "el alto del cuerpo depende de la ventana, no de los 500 mensajes",
        )

        val ventana = Scroll.window(total, Scroll.maxOffset(total, rows), rows)
        assertTrue(
            contador.leidos.size <= rows + 2,
            "se leyeron ${contador.leidos.size} de $total mensajes: la vista no puede formatear el hilo entero",
        )
        assertTrue(
            contador.leidos.all { it in ventana || it == state.focus.index },
            "solo se leen filas de la ventana (y el foco): ${contador.leidos}",
        )
    }

    /**
     * El coste no crece con el total: 60 y 500 mensajes formatean lo mismo.
     */
    @Test
    fun `el coste no depende del numero total de mensajes`() {
        val costes = listOf(60, 500).map { total ->
            val contador = CountingMessages(mensajes(total))
            val state = estado(contador, total)
            ConversacionView.body(state, ascii = false, clock = RenderClock.UTC_EPOCH, rows = rows, width = viewport.columns - 2 * Layout.padding(viewport.columns))
            contador.leidos.size
        }

        assertEquals(
            costes.first(),
            costes.last(),
            "60 y 500 mensajes no pueden costar distinto: $costes",
        )
        assertTrue(costes.last() < 60 / 2, "muy por debajo del total: ${costes.last()}")
    }

    @Test
    fun `un frame identico de 500 mensajes no escribe bytes y solo cambia la fila visible`() {
        val total = 500
        val base = mensajes(total)
        val fake = RecordingTerminal(viewport.columns, viewport.rows)
        val writer = ScreenWriter(fake.terminal, attributes = true)

        val primero = ShellRenderer.render(estado(base, total), ascii = false, color = true)
        writer.draw(primero)

        // 2. Frame identico: cero bytes.
        fake.limpiar()
        writer.draw(ShellRenderer.render(estado(base, total), ascii = false, color = true))
        assertEquals(0, fake.bytesEscritos(), "identico = cero bytes: '${fake.texto()}'")

        // 3. Cambia un mensaje FUERA de la ventana: no se ve, luego cero bytes.
        val fuera = base.toMutableList().also {
            it[0] = it[0].copy(text = "texto distinto fuera de la ventana visible")
        }
        fake.limpiar()
        writer.draw(ShellRenderer.render(estado(fuera, total), ascii = false, color = true))
        assertEquals(
            0,
            fake.bytesEscritos(),
            "un mensaje fuera de la ventana no puede cambiar el frame: '${fake.texto()}'",
        )

        // 4. Cambia el ULTIMO mensaje, que si esta en la ventana: una fila.
        val dentro = base.toMutableList().also {
            it[total - 1] = it[total - 1].copy(text = "texto distinto DENTRO de la ventana")
        }
        // Referencia de lo que cuesta el frame COMPLETO (misma base, invalidando).
        fake.limpiar()
        writer.invalidate()
        writer.draw(ShellRenderer.render(estado(base, total), ascii = false, color = true))
        val frameCompleto = fake.bytesEscritos()

        fake.limpiar()
        writer.draw(ShellRenderer.render(estado(dentro, total), ascii = false, color = true))
        val salida = fake.texto()

        assertEquals(
            1,
            Regex("\u001b\\[\\d+;1H").findAll(salida).count(),
            "un cambio visible es una fila, no el frame: $salida",
        )
        assertTrue(
            salida.contains("texto distinto DENTRO"),
            "se reescribe la fila cambiada: $salida",
        )
        assertTrue(
            fake.bytesEscritos() < frameCompleto / 3,
            "una fila no puede costar como el frame (${fake.bytesEscritos()} vs $frameCompleto bytes)",
        )
    }
}
