package com.km.desktop.ui

import com.km.desktop.client.ClientEvent
import com.km.model.IdentityId
import com.km.model.Message
import com.km.model.MessageId
import com.km.model.MessageState
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * 2.2/2.4 — El reducer: navegacion, contactos, conversacion, frase, overlay y
 * estado del enlace. CERO IO.
 *
 * ## POR QUE ESTOS TESTS NO TIENEN TERMINAL NI SERVICIOS
 *
 * Porque el reducer ES la logica. Si una transicion solo se puede comprobar
 * arrancando un pty, el diseno esta mal: aqui se construye el estado, se le da
 * una tecla o un resultado, y se afirma el estado siguiente y los efectos. Los
 * servicios de G1 aparecen solo como [EffectResult], nunca ejecutados.
 *
 * ## LO QUE SE AFIRMA CON MAS CUIDADO
 *
 * - Importar con exito SUSTITUYE (el `Esc` vuelve a INICIO en un paso).
 * - `SENT` se pinta `OUT`; `DELIVERED` solo cuando el estado lo dice.
 * - `D` no borra sin confirmacion; `R` conserva el `messageId`.
 * - La frase no se pide sin confirmar el reveal y se borra al salir.
 * - La presencia del par NUNCA se llama ONLINE.
 */
class ShellReducerTest {

    private val ana = IdentityId("a".repeat(64))
    private val luis = IdentityId("b".repeat(64))
    private val carla = IdentityId("c".repeat(64))
    private val yo = IdentityId("f".repeat(64))

    private fun fila(id: IdentityId, nombre: String) = ContactRow(
        identityId = id,
        fingerprint = id.value.take(16) + "...",
        displayName = nombre,
        hasAckKey = true,
    )

    private fun base() = ShellState(
        contacts = listOf(fila(ana, "ana"), fila(luis, "luis")),
        contactsLoaded = true,
        viewport = Size(100, 40),
        status = StatusLine(localIdentityId = yo.value),
    )

    private fun ShellState.press(c: Char): Reduced =
        ShellReducer.reduce(this, ShellAction.Key(KeyStroke.char(c)))

    private fun ShellState.press(stroke: KeyStroke): Reduced =
        ShellReducer.reduce(this, ShellAction.Key(stroke))

    private fun ShellState.done(result: EffectResult): Reduced =
        ShellReducer.reduce(this, ShellAction.EffectDone(result))

    private fun ShellState.event(event: ClientEvent): Reduced =
        ShellReducer.reduce(this, ShellAction.Event(event))

    private fun estadoInicialDeChat(): ShellState = base().press(KeyStroke(Key.Enter)).state

    // ------------------------------------------------------------------
    // Arranque y navegacion
    // ------------------------------------------------------------------

    @Test
    fun `arrancar pide la lista de contactos`() {
        assertEquals(listOf(ShellEffect.LoadContacts()), ShellReducer.initialEffects())
    }

    @Test
    fun `n abre IMPORTAR y Esc vuelve a INICIO en un paso`() {
        val importar = base().press('n').state

        assertEquals(Screen.IMPORTAR, importar.screen)
        assertEquals(FocusField.FIELD, importar.focus.field)
        assertTrue(importar.importDraft != null)

        val vuelta = importar.press(KeyStroke(Key.Escape)).state
        assertEquals(Screen.INICIO, vuelta.screen)
        assertNull(vuelta.importDraft, "el borrador se limpia al salir")
    }

    @Test
    fun `Esc en INICIO pide confirmacion y Enter sale`() {
        val pide = base().press(KeyStroke(Key.Escape)).state
        val overlay = pide.overlay as Overlay.Confirm

        assertEquals(ConfirmKind.EXIT, overlay.kind)
        assertEquals(Screen.INICIO, pide.screen)

        val dismiss = pide.press(KeyStroke(Key.Escape)).state
        assertNull(dismiss.overlay, "Esc cierra la confirmacion")

        val sale = pide.press(KeyStroke(Key.Enter))
        assertTrue(sale.state.quitting)
        assertEquals(listOf(ShellEffect.Quit), sale.effects)
    }

    @Test
    fun `Ctrl-C dos veces sale y una tecla normal desarma`() {
        val armado = base().press(KeyStroke.ctrl('c'))
        assertTrue(armado.state.quitArmed)
        assertTrue(armado.effects.isEmpty())

        val desarmado = armado.state.press('j').state
        assertFalse(desarmado.quitArmed)

        val sale = armado.state.press(KeyStroke.ctrl('c'))
        assertTrue(sale.state.quitting)
        assertEquals(listOf(ShellEffect.Quit), sale.effects)
    }

    @Test
    fun `la ayuda se abre y se cierra sin navegar`() {
        val abierta = base().press('?').state
        assertTrue(abierta.helpVisible)

        val cerrada = abierta.press(KeyStroke(Key.Escape)).state
        assertFalse(cerrada.helpVisible)
        assertEquals(Screen.INICIO, cerrada.screen)
    }

    @Test
    fun `un resize por debajo del minimo marca tooSmall`() {
        val s = ShellReducer.reduce(base(), ShellAction.Resize(Size(59, 19))).state

        assertTrue(s.tooSmall)
        assertEquals(Size(59, 19), s.viewport)
        assertTrue(Size(60, 20).usable)
        assertFalse(Size(60, 19).usable)
    }

    // ------------------------------------------------------------------
    // Contactos y filtro
    // ------------------------------------------------------------------

    @Test
    fun `ContactsLoaded llena la lista y Enter abre el primer contacto`() {
        val cargada = base().done(EffectResult.ContactsLoaded(listOf(fila(ana, "ana")))).state
        assertEquals(1, cargada.contacts.size)
        assertTrue(cargada.contactsLoaded)

        val r = cargada.press(KeyStroke(Key.Enter))
        assertEquals(Screen.CONVERSACION, r.state.screen)
        assertEquals(listOf<ShellEffect>(ShellEffect.LoadHistory(ana)), r.effects)
        assertEquals(FocusField.COMPOSER, r.state.focus.field)
        assertEquals("ana", r.state.conversation!!.title)
    }

    @Test
    fun `el filtro reduce la lista y Esc lo limpia antes de navegar`() {
        val editando = base().press('/').state
        assertEquals(FocusField.FIELD, editando.focus.field)

        val filtrado = editando.press('l').state
        assertEquals("l", filtrado.filter)
        assertEquals(1, filtrado.filteredContacts().size, "solo luis contiene una l")

        val limpio = filtrado.press(KeyStroke(Key.Escape)).state
        assertEquals("", limpio.filter)
        assertEquals(FocusField.LIST, limpio.focus.field)
        assertEquals(Screen.INICIO, limpio.screen, "Esc en el filtro NO navega")
    }

    @Test
    fun `un filtro sin coincidencias no abre nada`() {
        val vacio = base().press('/').state.press('z').state.press('z').state
        assertTrue(vacio.filteredContacts().isEmpty())

        val r = vacio.press(KeyStroke(Key.Enter))
        assertTrue(r.effects.isEmpty())
        assertEquals(Screen.INICIO, r.state.screen)
    }

    @Test
    fun `SessionOpened marca la sesion y la clave del contacto`() {
        val r = base().event(ClientEvent.SessionOpened(ana)).state
        assertTrue(r.contact(ana)!!.hasSession)
        assertTrue(r.contact(ana)!!.hasAckKey)
        assertFalse(r.contact(luis)!!.hasSession)
    }

    // ------------------------------------------------------------------
    // Importar
    // ------------------------------------------------------------------

    @Test
    fun `importar con exito SUSTITUYE y Esc vuelve a INICIO en un paso`() {
        val importar = base().press('n').state
        val r = importar.done(EffectResult.Imported(fila(carla, "carla")))

        assertEquals(Screen.CONVERSACION, r.state.screen)
        assertEquals(
            listOf(Screen.INICIO, Screen.CONVERSACION),
            r.state.screens.screens,
            "el dialogo de importar NO queda debajo",
        )
        assertEquals(listOf<ShellEffect>(ShellEffect.LoadHistory(carla)), r.effects)
        assertNull(r.state.importDraft, "el portador se limpia")
        assertEquals(3, r.state.contacts.size)

        val vuelta = r.state.press(KeyStroke(Key.Escape)).state
        assertEquals(Screen.INICIO, vuelta.screen)
        assertNull(vuelta.conversation)
    }

    @Test
    fun `un portador rechazado se queda en IMPORTAR con el codigo`() {
        val importar = base().press('n').state
        val r = importar.done(EffectResult.ImportRejected("IMPORT_FAILED"))

        assertEquals(Screen.IMPORTAR, r.state.screen)
        assertEquals("IMPORT_FAILED", r.state.importDraft!!.rejection)
        assertTrue(r.state.logs.any { it.text.contains("IMPORT_FAILED") })
        assertEquals(LogLevel.ERROR, r.state.banner!!.level)
    }

    @Test
    fun `Enter con el portador vacio no pide importar`() {
        val r = base().press('n').state.press(KeyStroke(Key.Enter))

        assertTrue(r.effects.isEmpty())
        assertEquals(LogLevel.WARN, r.state.banner!!.level)
    }

    @Test
    fun `el portador pegado se importa con la raiz esperada`() {
        val conRaiz = base().press('n').state.done(EffectResult.Pasted("portador")).state
        val r = conRaiz.press(KeyStroke(Key.Enter))

        assertEquals(listOf<ShellEffect>(ShellEffect.ImportText("portador", null)), r.effects)
    }

    @Test
    fun `un mensaje entrante actualiza la ultima fila y la conversacion`() {
        val mensaje = Message(
            messageId = MessageId.random(),
            from = ana,
            to = yo,
            payload = "hola".toByteArray(),
            timestamp = 5_000L,
            state = MessageState.DELIVERED,
        )

        val r = base().event(ClientEvent.MessageReceived(mensaje)).state
        assertEquals("hola", r.contact(ana)!!.lastMessage)
        assertEquals(5_000L, r.contact(ana)!!.lastAt)

        val conChat = estadoInicialDeChat().event(ClientEvent.MessageReceived(mensaje)).state
        assertEquals(MessageDirection.IN, conChat.conversation!!.messages.single().direction)
        assertEquals("IN", conChat.conversation!!.messages.single().label)
    }

    @Test
    fun `el estado guarda el payload crudo y la pintura lo neutraliza`() {
        val hostil = Message(
            messageId = MessageId.random(),
            from = ana,
            to = yo,
            payload = "hola\u001B[2J".toByteArray(),
            timestamp = 1L,
            state = MessageState.DELIVERED,
        )

        val r = base().event(ClientEvent.MessageReceived(hostil)).state
        val guardado = r.contact(ana)!!.lastMessage!!
        assertTrue(guardado.contains('\u001B'), "el estado conserva el dato, no lo mutila")
        assertFalse(Sanitizer.text(guardado).contains('\u001B'), "la pintura no deja un ESC activo")
    }

    // ------------------------------------------------------------------
    // Conversacion: envio, estados, reintento y descarte
    // ------------------------------------------------------------------

    @Test
    fun `escribir y Enter envia UNA vez y no limpia el texto`() {
        var s = estadoInicialDeChat()
        "hola".forEach { s = s.press(it).state }

        assertEquals("hola", s.conversation!!.composer)

        val r = s.press(KeyStroke(Key.Enter))
        assertEquals(listOf<ShellEffect>(ShellEffect.Send(ana, "hola")), r.effects)
        assertTrue(r.state.conversation!!.sending)
        assertEquals("hola", r.state.conversation!!.composer, "el texto no se pierde si el envio falla")

        val segundo = r.state.press(KeyStroke(Key.Enter))
        assertTrue(segundo.effects.isEmpty(), "un segundo Enter no duplica mientras hay envio en vuelo")
    }

    @Test
    fun `Sent anade la fila OUT y solo entonces limpia el compositor`() {
        val id = MessageId.random()
        var s = estadoInicialDeChat()
        "hola".forEach { s = s.press(it).state }
        s = s.press(KeyStroke(Key.Enter)).state

        val r = s.done(EffectResult.Sent(id, MessageState.SENT, "hola", 1_000L)).state
        val conv = r.conversation!!

        assertEquals("", conv.composer)
        assertFalse(conv.sending)
        assertEquals(1, conv.messages.size)
        assertEquals(MessageDirection.OUT, conv.messages.single().direction)
        assertEquals("OUT", conv.messages.single().label, "SENT es OUT: bytes aceptados, NO entrega")

        val entregado = r.event(ClientEvent.MessageStateChanged(id, MessageState.DELIVERED)).state
        assertEquals("ENTREGADO", entregado.conversation!!.messages.single().label)
    }

    @Test
    fun `un envio rechazado deja el texto y avisa con el codigo`() {
        var s = estadoInicialDeChat()
        "hola".forEach { s = s.press(it).state }
        s = s.press(KeyStroke(Key.Enter)).state

        val r = s.done(EffectResult.SendRejected("NO_SESSION")).state
        assertEquals("hola", r.conversation!!.composer)
        assertFalse(r.conversation!!.sending)
        assertEquals(LogLevel.ERROR, r.banner!!.level)
        assertTrue(r.banner!!.text.contains("NO_SESSION"))
    }

    @Test
    fun `R reintenta el tombstone con el MISMO id`() {
        val id = MessageId.random()
        val expirado = MessageRow(id, MessageDirection.OUT, 1_000L, "hola", MessageState.EXPIRED, "ACK_TIMEOUT")

        var s = estadoInicialDeChat()
        s = s.done(EffectResult.HistoryLoaded(ana, listOf(expirado))).state
        s = s.press(KeyStroke(Key.Tab)).state
        assertEquals(FocusField.LIST, s.focus.field)

        val r = s.press('r')
        assertEquals(listOf<ShellEffect>(ShellEffect.Resend(id)), r.effects)
        assertEquals("EXPIRADO ACK_TIMEOUT", expirado.label, "el motivo se pinta")
    }

    @Test
    fun `D pide confirmacion y Enter descarta`() {
        val id = MessageId.random()
        val expirado = MessageRow(id, MessageDirection.OUT, 1_000L, "hola", MessageState.EXPIRED, "TIMEOUT")

        var s = estadoInicialDeChat()
        s = s.done(EffectResult.HistoryLoaded(ana, listOf(expirado))).state
        s = s.press(KeyStroke(Key.Tab)).state

        val pide = s.press('d')
        val overlay = pide.state.overlay as Overlay.Confirm
        assertEquals(ConfirmKind.DISCARD_MESSAGE, overlay.kind)
        assertEquals(id, overlay.messageId)
        assertTrue(pide.effects.isEmpty(), "D no borra: solo pregunta")

        val confirma = pide.state.press(KeyStroke(Key.Enter))
        assertEquals(listOf<ShellEffect>(ShellEffect.Discard(id)), confirma.effects)

        val borrado = confirma.state.done(EffectResult.Discarded(id)).state
        assertTrue(borrado.conversation!!.messages.isEmpty())
    }

    @Test
    fun `D sobre un mensaje entregado no borra nada`() {
        val entregado = MessageRow(
            MessageId.random(), MessageDirection.OUT, 1L, "hola", MessageState.DELIVERED,
        )

        var s = estadoInicialDeChat()
        s = s.done(EffectResult.HistoryLoaded(ana, listOf(entregado))).state
        s = s.press(KeyStroke(Key.Tab)).state

        val r = s.press('d')
        assertNull(r.state.overlay)
        assertTrue(r.effects.isEmpty())
        assertEquals(LogLevel.WARN, r.state.banner!!.level)
    }

    @Test
    fun `en el compositor la tecla r es DATO y no reintenta`() {
        var s = estadoInicialDeChat()
        s = s.press('r').state

        assertEquals("r", s.conversation!!.composer)
        assertNull(s.overlay)
        assertEquals(Screen.CONVERSACION, s.screen)
    }

    // ------------------------------------------------------------------
    // Presencia y estado del enlace
    // ------------------------------------------------------------------

    @Test
    fun `el peer presente no se llama ONLINE`() {
        val s = base().event(ClientEvent.PeerPresence(ana, true)).state

        assertEquals(Presence.RELAY_PRESENT, s.contact(ana)!!.presence)
        assertEquals("\u00b7", s.contact(ana)!!.presence.glyph)
        assertEquals(Presence.UNKNOWN, s.contact(luis)!!.presence, "sin dato no se inventa")
        assertEquals("?", s.contact(luis)!!.presence.glyph)

        val ido = s.event(ClientEvent.PeerPresence(ana, false)).state
        assertEquals(Presence.RELAY_ABSENT, ido.contact(ana)!!.presence)
    }

    @Test
    fun `el enlace al relay se pinta P2P y el overlay avisa`() {
        val reconectando = base().event(ClientEvent.TransportState("RECONNECTING")).state
        assertEquals("P2P[~]", reconectando.status.glyph)
        assertTrue(reconectando.overlay is Overlay.Offline)

        val online = reconectando.event(ClientEvent.TransportState("ONLINE")).state
        assertEquals("P2P[o]", online.status.glyph)
        assertNull(online.overlay, "al volver ONLINE el overlay desaparece")

        val sin = online.event(ClientEvent.TransportState("DISCONNECTED")).state
        assertEquals("P2P[x]", sin.status.glyph)

        val raro = sin.event(ClientEvent.TransportState("LO_QUE_SEA")).state
        assertEquals("P2P[?]", raro.status.glyph)
    }

    @Test
    fun `el overlay sin conexion no esconde una confirmacion en curso`() {
        val confirmando = base().press(KeyStroke(Key.Escape)).state
        val r = confirmando.event(ClientEvent.TransportState("RECONNECTING")).state

        assertEquals(ConfirmKind.EXIT, (r.overlay as Overlay.Confirm).kind)
    }

    // ------------------------------------------------------------------
    // Frase y restauracion
    // ------------------------------------------------------------------

    @Test
    fun `la frase no se revela sin confirmar y se copia con aviso`() {
        var s = base().press('f').state
        assertEquals(Screen.FRASE, s.screen)
        assertFalse(s.phrase.revealed)

        val bloqueado = s.press('c')
        assertTrue(bloqueado.effects.isEmpty())
        assertEquals(LogLevel.WARN, bloqueado.state.banner!!.level)

        val pide = s.press('v')
        assertEquals(ConfirmKind.REVEAL_PHRASE, (pide.state.overlay as Overlay.Confirm).kind)

        val carga = pide.state.press(KeyStroke(Key.Enter))
        assertEquals(listOf<ShellEffect>(ShellEffect.LoadPhrase), carga.effects)

        val visible = carga.state.done(EffectResult.PhraseLoaded(listOf("abandon", "ability"))).state
        assertTrue(visible.phrase.revealed)
        assertEquals(2, visible.phrase.words.size)

        val copia = visible.press('c')
        val efecto = copia.effects.single() as ShellEffect.CopyText
        assertEquals("frase", efecto.what)
        assertTrue(efecto.warn, "copiar la frase avisa: OSC 52 no es confirmable")

        val oculta = visible.press('o').state
        assertFalse(oculta.phrase.revealed)
        assertTrue(oculta.phrase.words.isEmpty(), "ocultar borra el buffer")
    }

    @Test
    fun `salir de FRASE borra las palabras`() {
        var s = base().press('f').state
        s = s.done(EffectResult.PhraseLoaded(listOf("abandon", "ability"))).state

        val vuelta = s.press(KeyStroke(Key.Escape)).state
        assertTrue(vuelta.phrase.words.isEmpty())
        assertFalse(vuelta.phrase.revealed)
        assertEquals(Screen.INICIO, vuelta.screen)
    }

    @Test
    fun `restaurar exige frase valida y confirmacion escrita`() {
        val restaurar = base().press('f').state.press('r').state
        assertEquals(Screen.RESTAURAR, restaurar.screen)
        assertEquals(FocusField.FIELD, restaurar.focus.field)

        val sinValidar = restaurar.press(KeyStroke(Key.Enter))
        assertTrue(sinValidar.effects.isEmpty())
        assertEquals(LogLevel.WARN, sinValidar.state.banner!!.level)

        val teclea = restaurar.press('a')
        assertEquals(listOf<ShellEffect>(ShellEffect.CheckPhrase("a")), teclea.effects)

        val invalida = restaurar
            .done(EffectResult.PhraseChecked(23, true))
            .state
            .press(KeyStroke(Key.Enter))
        assertTrue(invalida.effects.isEmpty(), "23 palabras no es una frase")

        var valida = restaurar.done(EffectResult.PhraseChecked(24, true)).state
        val sinConfirmacion = valida.press(KeyStroke(Key.Enter))
        assertTrue(sinConfirmacion.effects.isEmpty())
        assertEquals(LogLevel.WARN, sinConfirmacion.state.banner!!.level)

        valida = valida.press(KeyStroke(Key.Tab)).state
        assertEquals(FocusField.CONFIRM, valida.focus.field)
        valida = valida.done(EffectResult.Pasted("SOBRESCRIBIR")).state
        assertEquals("SOBRESCRIBIR", valida.phrase.confirmation)

        val pide = valida.press(KeyStroke(Key.Enter))
        assertEquals(ConfirmKind.RESTORE_IDENTITY, (pide.state.overlay as Overlay.Confirm).kind)

        val confirma = pide.state.press(KeyStroke(Key.Enter))
        val efecto = confirma.effects.single() as ShellEffect.RestoreIdentity
        assertEquals("SOBRESCRIBIR", efecto.confirmation)
    }

    @Test
    fun `Restored vuelve a INICIO, limpia contactos y pide recargar`() {
        val r = base().done(EffectResult.Restored("d".repeat(64)))

        assertEquals(Screen.INICIO, r.state.screen)
        assertTrue(r.state.contacts.isEmpty())
        assertFalse(r.state.contactsLoaded)
        assertNull(r.state.conversation)
        assertEquals(listOf<ShellEffect>(ShellEffect.LoadContacts("restaurada")), r.effects)
        assertTrue(r.state.banner!!.text.contains("restaurada"))
        assertEquals("d".repeat(16) + "\u2026", r.state.status.localFingerprint)
    }

    // ------------------------------------------------------------------
    // Compartir, logs y export
    // ------------------------------------------------------------------

    @Test
    fun `compartir exporta una vez, copia y guarda el portador`() {
        val comparte = base().press('c')

        assertEquals(Screen.COMPARTIR, comparte.state.screen)
        assertEquals(listOf<ShellEffect>(ShellEffect.ExportOwn(0)), comparte.effects)

        val exportado = comparte.state
            .done(EffectResult.Exported("portador", 469, 0))
            .state
        assertEquals("portador", exportado.share!!.carrier)
        assertEquals(469, exportado.share!!.byteLength)

        val copia = exportado.press('c')
        val copiado = copia.effects.single() as ShellEffect.CopyText
        assertEquals("portador", copiado.text)
        assertFalse(copiado.warn)

        val guarda = exportado.press('w')
        assertEquals(listOf<ShellEffect>(ShellEffect.SaveCarrier("portador")), guarda.effects)

        val texto = exportado.press('t').state
        assertTrue(texto.share!!.showText)
    }

    @Test
    fun `el evento del log se guarda con canal y el filtro cicla`() {
        val s = base().event(ClientEvent.Warn("[RELAY] sin conexion")).state

        assertEquals(1, s.logs.size)
        assertEquals("[RELAY]", s.logs.single().channel)
        assertEquals(LogLevel.WARN, s.logs.single().level)
        assertEquals(LogLevel.WARN, s.banner!!.level)

        val diag = s.press('d').state
        assertEquals(Screen.DIAGNOSTICO, diag.screen)

        val filtrado = diag.press('f').state
        assertEquals("[BOOTSTRAP]", filtrado.logFilter)

        val copia = filtrado.press('c')
        assertEquals(listOf<ShellEffect>(ShellEffect.CopyLogs("[BOOTSTRAP]")), copia.effects)

        val exporta = filtrado.press('x')
        assertEquals(listOf<ShellEffect>(ShellEffect.ExportLogs("[BOOTSTRAP]")), exporta.effects)
    }

    @Test
    fun `el log de pantalla no crece sin limite`() {
        var s = base()
        repeat(ShellReducer.MAX_LOGS + 5) {
            s = s.event(ClientEvent.Info("linea")).state
        }

        assertEquals(ShellReducer.MAX_LOGS, s.logs.size)
    }
}
