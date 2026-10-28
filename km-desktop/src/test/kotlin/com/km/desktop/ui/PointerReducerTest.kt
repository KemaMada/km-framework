package com.km.desktop.ui

import com.km.model.IdentityId
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * B3 — El reducer de un clic, sin terminal.
 *
 * ## LO QUE SE AFIRMA
 *
 * Un clic por TIPO de region hace UNA accion: fila abre chat, pestaña cambia,
 * pie ejecuta su `Intent`, boton de dialogo elige, compositor/filtro reciben el
 * foco. `Dismiss` sin capa modal no sale: un clic inerte no tiene efectos
 * destructivos que ninguna tecla tenga.
 */
class PointerReducerTest {

    private fun pointer(estado: ShellState, intent: MouseIntent): Reduced =
        ShellReducer.reduce(estado, ShellAction.Pointer(intent))

    private val pedro = IdentityId("b".repeat(64))

    @Test
    fun `clic en una ficha de INICIO abre su conversacion`() {
        val base = GoldenHarness.stateFor(Screen.INICIO, Size(80, 24))
        val reducido = pointer(base, MouseIntent.SelectContact(1))
        assertEquals(Screen.CONVERSACION, reducido.state.screen)
        assertEquals(pedro, reducido.state.conversation?.peer, "la segunda ficha es pedro")
        assertEquals(FocusField.COMPOSER, reducido.state.focus.field, "un chat se abre escribiendo")
        assertTrue(
            reducido.effects.contains(ShellEffect.LoadHistory(pedro)),
            "pide el historial del contacto pulsado: ${reducido.effects}",
        )
    }

    @Test
    fun `clic en una burbuja mueve el foco y no envia`() {
        val base = GoldenHarness.stateFor(Screen.CONVERSACION, Size(80, 24))
            .copy(focus = Focus(FocusField.COMPOSER, 0))
        val reducido = pointer(base, MouseIntent.SelectMessage(0))
        assertEquals(FocusField.LIST, reducido.state.focus.field)
        assertEquals(0, reducido.state.focus.index)
        assertTrue(reducido.effects.isEmpty(), "seleccionar no envia ni abre nada")
        assertEquals("hola", reducido.state.conversation?.composer, "el borrador no se toca")
    }

    @Test
    fun `clic en un log mueve el foco de la lista`() {
        val base = GoldenHarness.stateFor(Screen.DIAGNOSTICO, Size(120, 40))
        val reducido = pointer(base, MouseIntent.SelectLog(2))
        assertEquals(FocusField.LIST, reducido.state.focus.field)
        assertEquals(2, reducido.state.focus.index)
    }

    @Test
    fun `clic en una pestana cambia de raiz por TabTo`() {
        val base = GoldenHarness.stateFor(Screen.INICIO, Size(120, 40))
        val reducido = pointer(base, MouseIntent.Act(Intent.TabTo(RootTab.FRASE)))
        assertEquals(Screen.FRASE, reducido.state.screen)
        assertEquals(RootTab.FRASE, reducido.state.tabs.active)
    }

    @Test
    fun `clic en el pie de COMPARTIR ejecuta copiar`() {
        val base = GoldenHarness.stateFor(Screen.COMPARTIR, Size(120, 50))
        val portador = base.share?.carrier
        assertTrue(!portador.isNullOrEmpty(), "el fixture trae portador")
        val reducido = pointer(base, MouseIntent.Act(Intent.Copy))
        assertEquals(
            listOf(ShellEffect.CopyText(portador!!, "portador")),
            reducido.effects,
        )
    }

    @Test
    fun `clic en el compositor y en el filtro dan foco`() {
        val conv = GoldenHarness.stateFor(Screen.CONVERSACION, Size(80, 24))
            .copy(focus = Focus(FocusField.LIST, 0))
        assertEquals(FocusField.COMPOSER, pointer(conv, MouseIntent.FocusComposer).state.focus.field)

        val inicio = GoldenHarness.stateFor(Screen.INICIO, Size(80, 24))
            .copy(focus = Focus(FocusField.LIST, 0))
        assertEquals(FocusField.FIELD, pointer(inicio, MouseIntent.FocusFilter).state.focus.field)
    }

    @Test
    fun `clic en Si confirma y clic en No cancela`() {
        val base = GoldenHarness.stateFor(Screen.INICIO, Size(80, 24)).copy(
            overlay = Overlay.Confirm(
                ConfirmKind.EXIT,
                "salir de KeyMessage",
                "la cola es local",
                selection = DialogChoice.SECONDARY,
            ),
        )
        val si = pointer(base, MouseIntent.Dialog(DialogChoice.PRIMARY))
        assertTrue(si.state.quitting, "Si sale")
        assertEquals(listOf(ShellEffect.Quit), si.effects)
        assertNull(si.state.overlay)

        val no = pointer(base, MouseIntent.Dialog(DialogChoice.SECONDARY))
        assertFalse(no.state.quitting)
        assertTrue(no.effects.isEmpty())
        assertNull(no.state.overlay, "No cierra el modal sin actuar")
    }

    @Test
    fun `Dismiss cierra la capa superior pero no sale sin overlay`() {
        val conModal = GoldenHarness.stateFor(Screen.INICIO, Size(80, 24)).copy(
            overlay = Overlay.Confirm(ConfirmKind.EXIT, "salir", selection = DialogChoice.SECONDARY),
        )
        assertNull(pointer(conModal, MouseIntent.Dismiss).state.overlay)

        val ayuda = GoldenHarness.stateFor(Screen.INICIO, Size(80, 24)).copy(helpVisible = true)
        assertFalse(pointer(ayuda, MouseIntent.Dismiss).state.helpVisible, "cierra la ayuda")

        val sinCapa = GoldenHarness.stateFor(Screen.INICIO, Size(80, 24))
        val reducido = pointer(sinCapa, MouseIntent.Dismiss)
        assertEquals(sinCapa, reducido.state, "un clic inerte no cambia nada")
        assertFalse(reducido.state.quitting)
    }
}
