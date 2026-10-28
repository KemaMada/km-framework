package com.km.desktop.ui

import com.km.model.IdentityId
import com.km.model.MessageId
import com.km.model.MessageState
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * M6.1/M6.2 — El modal atrapa el foco y NO destruye por defecto.
 *
 * Un Enter reflejo sobre una accion destructiva cae en `No`. Para confirmar hay
 * que mover la seleccion a `Si` (Tab o flechas) y volver a pulsar Enter. Esc
 * cierra sin efectos, siempre.
 */
class DialogReducerTest {

    private val ana = IdentityId("a".repeat(64))

    private fun chat() = ShellState(
        viewport = Size(80, 24),
        contacts = listOf(
            ContactRow(
                identityId = ana,
                fingerprint = ana.value.take(16) + "\u2026",
                displayName = "ana",
                hasSession = true,
                hasAckKey = true,
            ),
        ),
        contactsLoaded = true,
        tabs = TabStacks.of(Screen.INICIO, Screen.CONVERSACION),
        conversation = ConversationView(
            peer = ana,
            title = "ana",
            messages = listOf(
                MessageRow(
                    MessageId.random(),
                    MessageDirection.OUT,
                    1L,
                    "vale",
                    MessageState.EXPIRED,
                    "ACK_TIMEOUT",
                ),
            ),
            hasSession = true,
        ),
        focus = Focus(FocusField.LIST, 0),
    )

    private fun abrir(): ShellState {
        val r = ShellReducer.reduce(chat(), ShellAction.Key(KeyStroke.char('d')))
        assertTrue(r.effects.isEmpty(), "D solo pregunta")
        return r.state
    }

    @Test
    fun `Tab y flechas mueven el boton marcado y nada mas`() {
        val abierto = abrir()
        assertEquals(
            DialogChoice.SECONDARY,
            (abierto.overlay as Overlay.Confirm).selection,
        )

        val tab = ShellReducer.reduce(abierto, ShellAction.Key(KeyStroke(Key.Tab))).state
        assertEquals(DialogChoice.PRIMARY, (tab.overlay as Overlay.Confirm).selection)

        val shiftTab = ShellReducer.reduce(tab, ShellAction.Key(KeyStroke.shift(Key.Tab))).state
        assertEquals(DialogChoice.SECONDARY, (shiftTab.overlay as Overlay.Confirm).selection)

        // El foco de la pantalla de debajo NO se mueve: esta atrapado.
        assertEquals(FocusField.LIST, shiftTab.focus.field)
        assertEquals(0, shiftTab.focus.index)
        assertEquals(Screen.CONVERSACION, shiftTab.screen, "el modal no navega por debajo")
    }

    @Test
    fun `Enter en No cierra sin efectos y Enter en Si descarta`() {
        val abierto = abrir()
        val no = ShellReducer.reduce(abierto, ShellAction.Key(KeyStroke(Key.Enter)))
        assertTrue(no.effects.isEmpty(), "No no destruye")
        assertNull(no.state.overlay)
        assertEquals(1, no.state.conversation!!.messages.size)

        val si = ShellReducer.reduce(
            ShellReducer.reduce(abierto, ShellAction.Key(KeyStroke(Key.Tab))).state,
            ShellAction.Key(KeyStroke(Key.Enter)),
        )
        assertTrue(si.effects.single() is ShellEffect.Discard, "Si descarta")
    }

    @Test
    fun `Esc cierra sin borrar aunque el foco este en Si`() {
        val abierto = abrir()
        val enSi = ShellReducer.reduce(abierto, ShellAction.Key(KeyStroke(Key.Tab))).state
        val salida = ShellReducer.reduce(enSi, ShellAction.Key(KeyStroke(Key.Escape)))
        assertTrue(salida.effects.isEmpty())
        assertNull(salida.state.overlay)
        assertEquals(1, salida.state.conversation!!.messages.size)
    }

    @Test
    fun `salir arranca en No y un Enter reflejo no cierra la app`() {
        val inicio = chat().copy(
            tabs = TabStacks.of(Screen.INICIO),
            conversation = null,
            focus = Focus(FocusField.LIST, 0),
        )
        val porEsc = ShellReducer.reduce(inicio, ShellAction.Key(KeyStroke(Key.Escape))).state
        val overlay = porEsc.overlay as Overlay.Confirm
        assertEquals(ConfirmKind.EXIT, overlay.kind)
        assertEquals(DialogChoice.SECONDARY, overlay.selection, "salir arranca en No")

        val enter = ShellReducer.reduce(porEsc, ShellAction.Key(KeyStroke(Key.Enter)))
        assertTrue(enter.effects.isEmpty(), "Enter en No no sale")
        assertFalse(enter.state.quitting, "un Enter reflejo pierde la cola local")
        assertNull(enter.state.overlay)

        // `q` es la otra ruta a la MISMA confirmacion: tambien arranca en No.
        val porQ = ShellReducer.reduce(inicio, ShellAction.Key(KeyStroke.char('q'))).state
        assertEquals(
            DialogChoice.SECONDARY,
            (porQ.overlay as Overlay.Confirm).selection,
            "q tambien arranca en No",
        )

        // Para salir hay que mover la seleccion a Si.
        val si = ShellReducer.reduce(porEsc, ShellAction.Key(KeyStroke(Key.Tab))).state
        val sale = ShellReducer.reduce(si, ShellAction.Key(KeyStroke(Key.Enter)))
        assertTrue(sale.effects.single() is ShellEffect.Quit, "Si sale")
        assertTrue(sale.state.quitting)
    }

    @Test
    fun `la ayuda se desplaza sin tocar el foco de la pantalla`() {
        val conAyuda = chat().copy(helpVisible = true, viewport = Size(60, 20))
        val abajo = ShellReducer.reduce(conAyuda, ShellAction.Key(KeyStroke(Key.PageDown))).state
        assertTrue(abajo.helpOffset > 0, "la ventana se movio")
        assertEquals(FocusField.LIST, abajo.focus.field, "el foco de la lista no cambia")

        val fin = ShellReducer.reduce(abajo, ShellAction.Key(KeyStroke(Key.End))).state
        val max = com.km.desktop.ui.render.OverlayWindow.maxOffset(20, Keymap.help(Screen.CONVERSACION).size + 2)
        assertEquals(max, fin.helpOffset)
        assertTrue(fin.helpVisible, "desplazarse no cierra la ayuda")
    }
}
