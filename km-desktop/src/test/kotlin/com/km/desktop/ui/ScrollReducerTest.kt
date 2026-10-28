package com.km.desktop.ui

import com.km.desktop.client.ClientEvent
import com.km.desktop.ui.layout.Layout
import com.km.desktop.ui.layout.ListId
import com.km.desktop.ui.layout.Scroll
import com.km.desktop.ui.layout.ScrollState
import com.km.model.IdentityId
import com.km.model.Message
import com.km.model.MessageId
import com.km.model.MessageState
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * M3 — Scroll de punta a punta: reducer -> estado -> VENTANA pintada.
 *
 * ## LO QUE SE AFIRMA, Y CONTRA QUE
 *
 * No se comprueba "el offset cambio": se pinta el frame y se exige que la fila
 * enfocada este EN EL TEXTO. El fallo real que esto caza es una lista que
 * calcula un offset con un alto distinto del que pinta el compositor: el test
 * de una sola capa lo dejaria pasar.
 *
 * ## 500 MENSAJES
 *
 * Es el caso que motivo el rediseño: con el recorte central de `fit()` el final
 * del hilo aparecia como `… (N lineas)`. Aqui se exige el ultimo mensaje a la
 * vista, el indicador `[500/500]` y que NO se formatee lo que no se ve.
 */
class ScrollReducerTest {

    private val ana = IdentityId("a".repeat(64))
    private val yo = IdentityId("f".repeat(64))

    private val tamanos = listOf(Size(60, 20), Size(80, 24), Size(100, 30), Size(120, 40))

    private fun texto(i: Int): String = "msg-" + i.toString().padStart(4, '0')

    private fun fila(i: Int) = MessageRow(
        messageId = MessageId.random(),
        direction = MessageDirection.IN,
        at = 1_700_000_000_000L + i,
        text = texto(i),
        state = MessageState.DELIVERED,
    )

    private fun hilo(total: Int, size: Size, offset: Int = 0, followEnd: Boolean = false, foco: Int = 0): ShellState {
        // Un estado `followEnd` con offset 0 no es un estado que el reducer
        // pueda producir: el fixture lo construye ENGANCHADO al final, con el
        // mismo `maxOffset` que usaria `ensure`.
        val rows = Layout.listRows(Screen.CONVERSACION, size)
        val inicio = if (followEnd) Scroll.maxOffset(total, rows) else offset
        return ShellState(
            tabs = TabStacks.of(Screen.INICIO, Screen.CONVERSACION),
            viewport = size,
            focus = Focus(FocusField.LIST, foco.coerceAtLeast(0)),
            status = StatusLine(localIdentityId = yo.value),
            conversation = ConversationView(
                peer = ana,
                title = "ana",
                messages = (0 until total).map(::fila),
                hasSession = true,
            ),
            scroll = mapOf(ListId.MESSAGES to ScrollState(inicio, followEnd)),
        )
    }

    private fun ShellState.press(stroke: KeyStroke): Reduced =
        ShellReducer.reduce(this, ShellAction.Key(stroke))

    private fun ShellState.press(c: Char): Reduced = press(KeyStroke.char(c))

    private fun ShellState.event(event: ClientEvent): Reduced =
        ShellReducer.reduce(this, ShellAction.Event(event))

    private fun lineas(state: ShellState, ascii: Boolean = false, color: Boolean = true) =
        ShellView.renderLines(state, ascii, color, GoldenHarness.FIXED_CLOCK)

    /**
     * La fila enfocada esta EN EL FRAME.
     *
     * Se busca la unica linea con `focus` y se exige que contenga el texto del
     * mensaje apuntado: si la ventana dejo fuera la seleccion, no hay linea con
     * foco (o hay otra), y el test falla con el indice en el mensaje.
     */
    private fun assertFocoVisible(state: ShellState) {
        val total = state.conversation!!.messages.size
        if (total == 0) return
        val conFoco = lineas(state).filter { it.focus }
        assertEquals(1, conFoco.size, "una sola fila con foco en ${state.viewport}")
        assertTrue(
            conFoco.single().text.contains(texto(state.focus.index)),
            "el foco ${state.focus.index} no se pinta: '${conFoco.single().text}'",
        )
    }

    // ------------------------------------------------------------------
    // Propiedad: mover y redimensionar nunca deja el foco fuera
    // ------------------------------------------------------------------

    @Test
    fun `mover por toda la lista y encoger el terminal mantiene el foco visible`() {
        for (size in tamanos) {
            for (total in listOf(1, 2, 5, 13, 14, 40, 150, 500)) {
                var s = hilo(total, size)
                val rows = Layout.listRows(Screen.CONVERSACION, size)
                // Recorrido completo con las teclas que mueven: flechas,
                // paginas y los extremos.
                val teclas = buildList {
                    repeat(total.coerceAtMost(40)) { add(KeyStroke(Key.Down)) }
                    add(KeyStroke(Key.PageDown))
                    add(KeyStroke(Key.PageUp))
                    add(KeyStroke(Key.End))
                    add(KeyStroke(Key.Home))
                    add(KeyStroke(Key.PageDown))
                }
                teclas.forEach { tecla ->
                    s = s.press(tecla).state
                    val ventana = Scroll.window(total, s.scrollOf(ListId.MESSAGES).offset, rows)
                    assertTrue(
                        s.focus.index in ventana,
                        "$size total=$total foco=${s.focus.index} ventana=$ventana (${tecla.key})",
                    )
                    assertFocoVisible(s)
                }
                // Y un cambio de tamano DESPUES de moverse: el alto cambia y el
                // offset tiene que recalcularse, no quedarse con el de antes.
                val encogido = ShellReducer.reduce(s, ShellAction.Resize(Size(60, 20))).state
                assertFocoVisible(encogido)
                val chico = Layout.listRows(Screen.CONVERSACION, Size(60, 20))
                assertTrue(
                    encogido.focus.index in Scroll.window(
                        total,
                        encogido.scrollOf(ListId.MESSAGES).offset,
                        chico,
                    ),
                    "tras encoger, el foco ${encogido.focus.index} quedo fuera",
                )
            }
        }
    }

    @Test
    fun `150 de 200 sigue visible tras pasar de 120x40 a 60x20`() {
        var s = hilo(200, Size(120, 40))
        repeat(150) { s = s.press(KeyStroke(Key.Down)).state }
        assertEquals(150, s.focus.index)

        val encogido = ShellReducer.reduce(s, ShellAction.Resize(Size(60, 20))).state
        val rows = Layout.listRows(Screen.CONVERSACION, Size(60, 20))
        val offset = encogido.scrollOf(ListId.MESSAGES).offset
        assertTrue(150 in Scroll.window(200, offset, rows), "offset=$offset rows=$rows")
        assertFocoVisible(encogido)
    }

    // ------------------------------------------------------------------
    // followEnd y pendientes
    // ------------------------------------------------------------------

    @Test
    fun `500 mensajes enganchados al final muestran el ultimo y el indicador`() {
        val estado = hilo(500, Size(80, 24), offset = 0, followEnd = true, foco = 499)
        val texto80 = lineas(estado).joinToString("\n") { it.text }
        assertTrue(texto80.contains(texto(499)), "el ultimo mensaje se ve")
        assertFalse(texto80.contains(texto(0)), "no se pinta el principio del hilo")
        assertTrue(texto80.contains("[500/500]"), "indicador de ventana: $texto80")
        assertTrue(texto80.contains("presencia"), "la cabecera del hilo dice la presencia: $texto80")
        assertTrue(texto80.contains("[IN]") || texto80.contains("[OUT]"), "etiquetas honestas: $texto80")
    }

    @Test
    fun `abrir un hilo y cargar 500 mensajes deja el final a la vista`() {
        val base = ShellState(
            tabs = TabStacks.of(Screen.INICIO),
            contacts = listOf(
                ContactRow(ana, ana.value.take(16) + "\u2026", "ana", hasSession = true, hasAckKey = true),
            ),
            contactsLoaded = true,
            viewport = Size(80, 24),
            status = StatusLine(localIdentityId = yo.value),
        )
        val abierto = base.press(KeyStroke(Key.Enter)).state
        assertEquals(Screen.CONVERSACION, abierto.screen)
        assertTrue(abierto.scrollOf(ListId.MESSAGES).followEnd)

        val cargado = abierto
            .let { ShellReducer.reduce(it, ShellAction.EffectDone(EffectResult.HistoryLoaded(ana, (0 until 500).map(::fila)))).state }
        assertTrue(cargado.scrollOf(ListId.MESSAGES).followEnd)
        assertFocoVisible(cargado.copy(focus = Focus(FocusField.LIST, 499)))
        assertTrue(lineas(cargado).joinToString("\n") { it.text }.contains(texto(499)))
    }

    @Test
    fun `subir desengancha y un mensaje nuevo NO mueve la vista`() {
        var s = hilo(100, Size(80, 24), offset = 0, followEnd = true, foco = 99)
        val antes = lineas(s).joinToString("\n") { it.text }
        assertTrue(antes.contains(texto(99)))

        s = s.press(KeyStroke(Key.Up)).state
        assertFalse(s.scrollOf(ListId.MESSAGES).followEnd, "subir desengancha")
        assertEquals(98, s.focus.index)
        val offsetAntes = s.scrollOf(ListId.MESSAGES).offset

        val nuevo = Message(
            messageId = MessageId.random(),
            from = ana,
            to = yo,
            payload = "llego tarde".toByteArray(),
            timestamp = 1_700_000_100_000L,
            state = MessageState.DELIVERED,
        )
        s = s.event(ClientEvent.MessageReceived(nuevo)).state

        assertEquals(offsetAntes, s.scrollOf(ListId.MESSAGES).offset, "la vista no salta")
        assertEquals(1, s.conversation!!.unread)
        val texto = lineas(s).joinToString("\n") { it.text }
        assertTrue(texto.contains("[1 nuevos \u2193]"), "se anuncia el pendiente: $texto")
        assertFalse(texto.contains("llego tarde"), "el mensaje nuevo queda fuera de la ventana")
    }

    @Test
    fun `End vuelve al final y limpia los pendientes`() {
        var s = hilo(100, Size(80, 24), followEnd = true, foco = 99)
        s = s.press(KeyStroke(Key.Up)).state
        val nuevo = Message(
            messageId = MessageId.random(),
            from = ana,
            to = yo,
            payload = "ultimo".toByteArray(),
            timestamp = 1_700_000_100_000L,
            state = MessageState.DELIVERED,
        )
        s = s.event(ClientEvent.MessageReceived(nuevo)).state

        s = s.press(KeyStroke(Key.End)).state
        assertTrue(s.scrollOf(ListId.MESSAGES).followEnd, "End reengancha")
        assertEquals(0, s.conversation!!.unread, "sin pendientes")
        assertEquals(100, s.focus.index, "el cursor va al ultimo: 100 mensajes previos + el nuevo")
        val texto = lineas(s).joinToString("\n") { it.text }
        assertTrue(texto.contains("ultimo"), texto)
        assertFalse(texto.contains("nuevos"), texto)
    }

    @Test
    fun `un mensaje propio no cuenta como pendiente`() {
        val conv = ConversationView(peer = ana, title = "ana", composer = "ya voy", hasSession = true)
        var s = hilo(30, Size(80, 24), followEnd = false, foco = 5).copy(conversation = conv)
        s = s.press(KeyStroke(Key.Enter)).state
        assertEquals(Screen.CONVERSACION, s.screen)
        s = ShellReducer.reduce(
            s,
            ShellAction.EffectDone(
                EffectResult.Sent(MessageId.random(), MessageState.SENT, "ya voy", 1_700_000_100_000L),
            ),
        ).state
        assertEquals(1, s.conversation!!.messages.size)
        assertEquals(0, s.conversation!!.unread, "lo que envio yo no es un pendiente")
        assertFalse(s.scrollOf(ListId.MESSAGES).followEnd)
    }

    // ------------------------------------------------------------------
    // Teclas de pagina y rueda
    // ------------------------------------------------------------------

    @Test
    fun `PgDn avanza una pagina y PgUp vuelve`() {
        val size = Size(60, 20)
        val rows = Layout.listRows(Screen.CONVERSACION, size)
        var s = hilo(100, size)
        s = s.press(KeyStroke(Key.PageDown)).state
        assertEquals(rows, s.focus.index)
        assertFocoVisible(s)
        s = s.press(KeyStroke(Key.PageUp)).state
        assertEquals(0, s.focus.index)
        assertFocoVisible(s)
    }

    @Test
    fun `ScrollBy mueve la seleccion sin romper el invariante`() {
        var s = hilo(100, Size(80, 24))
        s = ShellReducer.reduce(s, ShellAction.ScrollBy(5)).state
        assertEquals(5, s.focus.index)
        assertFocoVisible(s)
        s = ShellReducer.reduce(s, ShellAction.ScrollBy(-2)).state
        assertEquals(3, s.focus.index)
        assertFocoVisible(s)
    }

    /**
     * B4 — La rueda reusa `ScrollBy`, no una rama propia.
     *
     * R27: 20 contactos, foco 1, rueda abajo -> foco 4 VISIBLE y el campo no
     * cambia. Si la rueda moviera el offset sin mover el foco, la fila
     * seleccionada podria salir de la ventana.
     */
    @Test
    fun `rueda en INICIO mueve tres filas y mantiene el foco visible sin cambiar de campo`() {
        val size = Size(60, 20)
        val contactos = (0 until 20).map { i ->
            ContactRow(
                identityId = IdentityId(i.toString().padStart(2, '0').repeat(32)),
                fingerprint = "huella$i",
                displayName = "contacto-$i",
                hasAckKey = true,
            )
        }
        val base = ShellState(
            tabs = TabStacks.of(Screen.INICIO),
            viewport = size,
            focus = Focus(FocusField.LIST, 1),
            contacts = contactos,
            contactsLoaded = true,
            status = StatusLine(localIdentityId = yo.value),
        )
        val abajo = ShellReducer.reduce(base, ShellAction.ScrollBy(3)).state
        assertEquals(4, abajo.focus.index, "foco 1 + 3")
        assertEquals(FocusField.LIST, abajo.focus.field, "la rueda no cambia de campo")
        assertTrue(lineas(abajo).any { it.text.contains("contacto-4") }, "el foco 4 se pinta")

        val arriba = ShellReducer.reduce(abajo, ShellAction.ScrollBy(-3)).state
        assertEquals(1, arriba.focus.index)
        assertEquals(FocusField.LIST, arriba.focus.field)
    }

    @Test
    fun `rueda con el foco en el filtro no mueve la lista`() {
        val base = GoldenHarness.stateFor(Screen.INICIO, Size(80, 24))
            .copy(focus = Focus(FocusField.FIELD, 0))
        val igual = ShellReducer.reduce(base, ShellAction.ScrollBy(3)).state
        assertEquals(base.focus, igual.focus)
        assertEquals(0, igual.scrollOf(ListId.CONTACTS).offset)
    }

    @Test
    fun `la rueda respeta followEnd y End lo reactiva`() {
        var s = hilo(100, Size(80, 24), followEnd = true, foco = 99)
        s = ShellReducer.reduce(s, ShellAction.ScrollBy(-3)).state
        assertEquals(96, s.focus.index)
        assertFalse(s.scrollOf(ListId.MESSAGES).followEnd, "subir desengancha")

        s = ShellReducer.reduce(s, ShellAction.ScrollBy(3)).state
        assertEquals(99, s.focus.index)
        assertTrue(s.scrollOf(ListId.MESSAGES).followEnd, "volver al final reengancha")
        assertFocoVisible(s)
    }

    @Test
    fun `INICIO anuncia la ventana de contactos y mantiene el foco dentro`() {
        val size = Size(60, 20)
        val filas = Layout.listRows(Screen.INICIO, size)
        val contactos = (0 until 50).map { i ->
            ContactRow(
                identityId = IdentityId(i.toString().padStart(2, '0').repeat(32)),
                fingerprint = "huella$i",
                displayName = "contacto-$i",
                hasAckKey = true,
            )
        }
        val offset = Scroll.ensure(40, 0, filas, contactos.size)
        val estado = ShellState(
            tabs = TabStacks.of(Screen.INICIO),
            viewport = size,
            focus = Focus(FocusField.LIST, 40),
            contacts = contactos,
            contactsLoaded = true,
            status = StatusLine(localIdentityId = yo.value),
            scroll = mapOf(ListId.CONTACTS to ScrollState(offset, followEnd = false)),
        )

        val lineas = lineas(estado)
        val texto = lineas.joinToString("\n") { it.text }
        assertTrue(texto.contains("[41/50]"), "indicador de la lista: $texto")
        assertTrue(texto.contains("CONTACTOS 50"), "la cabecera de la lista cuenta: $texto")
        val conFoco = lineas.filter { it.focus }
        assertEquals(1, conFoco.size)
        assertTrue(conFoco.single().text.contains("contacto-40"), conFoco.single().text)
        assertFalse(texto.contains("contacto-0 "), "el principio de la lista no se pinta")
    }

    @Test
    fun `el indicador es texto tambien en ASCII y sin color`() {
        val estado = hilo(100, Size(80, 24), offset = 0, followEnd = true, foco = 99)
        val ascii = lineas(estado, ascii = true).joinToString("\n") { it.text }
        val mono = lineas(estado, ascii = false, color = false).joinToString("\n") { it.text }
        assertTrue(ascii.contains("[100/100]"), ascii)
        assertTrue(mono.contains("[100/100]"), mono)
        assertFalse(ascii.contains("\u258c"), "ASCII no usa glifos Unicode de la barra")
    }

    // ------------------------------------------------------------------
    // Coste: formatear SOLO la ventana
    // ------------------------------------------------------------------

    /**
     * Una lista que CUENTA y ACOTA sus accesos.
     *
     * Si la vista recorriera los 5.000 mensajes para luego recortar, el `get`
     * de una fila fuera de la ventana lanzaria y el test diria exactamente cual.
     * Es la unica forma determinista de probar "no es O(n)" sin medir
     * milisegundos que dependen de la maquina.
     */
    private class SoloVentana(
        override val size: Int,
        private val permitido: IntRange,
        private val fabrica: (Int) -> MessageRow,
    ) : AbstractList<MessageRow>() {
        var accesos: Int = 0
            private set

        override fun get(index: Int): MessageRow {
            accesos++
            check(index in permitido) { "la vista formateo la fila $index fuera de $permitido" }
            return fabrica(index)
        }
    }

    @Test
    fun `con 5000 mensajes solo se formatea la ventana visible`() {
        val size = Size(80, 24)
        val rows = Layout.listRows(Screen.CONVERSACION, size)
        val offset = 4_000
        val esperado = offset until (offset + rows)
        val lista = SoloVentana(5_000, esperado, ::fila)
        val estado = ShellState(
            tabs = TabStacks.of(Screen.INICIO, Screen.CONVERSACION),
            viewport = size,
            // Foco en el compositor: no se pide la fila seleccionada de la lista,
            // asi que el UNICO acceso es el de la ventana.
            focus = Focus(FocusField.COMPOSER, 0),
            status = StatusLine(localIdentityId = yo.value),
            conversation = ConversationView(peer = ana, title = "ana", messages = lista, hasSession = true),
            scroll = mapOf(ListId.MESSAGES to ScrollState(offset, followEnd = false)),
        )

        val lineas = lineas(estado)
        assertEquals(rows, lista.accesos, "se formatean las filas de la ventana, ni una mas")
        assertTrue(lineas.joinToString("\n") { it.text }.contains(texto(offset)))
        assertTrue(lineas.joinToString("\n") { it.text }.contains(texto(esperado.last)))
    }
}
