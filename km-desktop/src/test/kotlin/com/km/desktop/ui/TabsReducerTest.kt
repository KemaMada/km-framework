package com.km.desktop.ui

import com.km.model.IdentityId
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * M4 — Pestañas raiz desde el REDUCER: navegacion, `Esc` y borradores.
 *
 * ## LO QUE SE PINZA
 *
 * Que cambiar de pestaña no borra NADA de lo escrito. No basta con que
 * `ShellState` conserve los campos: si `TabTo` reconstruyera las pilas (o el
 * estado) "para dejarlo limpio", el borrador se perderia igual. Por eso cada
 * caso hace ida y vuelta y compara el TEXTO, no solo el campo.
 *
 * ## `Esc` NO CAMBIA
 *
 * De un chat se vuelve a INICIO; de FRASE, a INICIO; en INICIO, pide
 * confirmacion. La pestaña nueva no puede costar una tecla que el usuario ya
 * tenia aprendida.
 */
class TabsReducerTest {

    private val ana = IdentityId("a".repeat(64))
    private val luis = IdentityId("b".repeat(64))
    private val yo = IdentityId("f".repeat(64))

    private fun fila(id: IdentityId, nombre: String) = ContactRow(
        identityId = id,
        fingerprint = id.value.take(16) + "\u2026",
        displayName = nombre,
        hasSession = true,
        hasAckKey = true,
    )

    private fun base() = ShellState(
        tabs = TabStacks.of(Screen.INICIO),
        contacts = listOf(fila(ana, "ana"), fila(luis, "luis")),
        contactsLoaded = true,
        viewport = Size(100, 40),
        status = StatusLine(localIdentityId = yo.value),
    )

    private fun ShellState.press(stroke: KeyStroke): Reduced =
        ShellReducer.reduce(this, ShellAction.Key(stroke))

    private fun ShellState.press(c: Char): Reduced = press(KeyStroke.char(c))

    private fun ShellState.type(texto: String): ShellState =
        texto.fold(this) { s, c -> s.press(c).state }

    private fun ShellState.alt(n: Char): ShellState = press(KeyStroke.alt(n)).state

    private fun chat(): ShellState = base().press(KeyStroke(Key.Enter)).state

    // ------------------------------------------------------------------
    // Navegacion
    // ------------------------------------------------------------------

    @Test
    fun `Alt-3 va a DIAGNOSTICO y Alt-1 vuelve conservando el filtro`() {
        val conFiltro = base().press('/').state.type("lu")
        assertEquals("lu", conFiltro.filter)
        assertEquals(FocusField.FIELD, conFiltro.focus.field)

        val diag = conFiltro.alt('3')
        assertEquals(Screen.DIAGNOSTICO, diag.screen)
        assertEquals(RootTab.DIAGNOSTICO, diag.tabs.active)
        assertEquals("lu", diag.filter, "el filtro no se toca al cambiar de pestaña")

        val vuelta = diag.alt('1')
        assertEquals(Screen.INICIO, vuelta.screen)
        assertEquals("lu", vuelta.filter, "el texto sigue donde estaba")
        assertEquals(FocusField.FIELD, vuelta.focus.field, "y el foco tambien")
    }

    @Test
    fun `el compositor de un chat sobrevive a ir a DIAGNOSTICO`() {
        val escribiendo = chat().type("hola")
        assertEquals("hola", escribiendo.conversation!!.composer)

        val diag = escribiendo.alt('3')
        assertEquals(Screen.DIAGNOSTICO, diag.screen)

        val vuelta = diag.alt('1')
        assertEquals(Screen.CONVERSACION, vuelta.screen, "vuelve al hilo, no a INICIO")
        assertEquals("hola", vuelta.conversation!!.composer)
        assertEquals(FocusField.COMPOSER, vuelta.focus.field)
    }

    @Test
    fun `el borrador de IMPORTAR sobrevive a ir y volver`() {
        val importando = base().press('n').state.type("KM1")
        assertEquals("KM1", importando.importDraft!!.text)

        val diag = importando.alt('3')
        val vuelta = diag.alt('1')
        assertEquals(Screen.IMPORTAR, vuelta.screen)
        assertEquals("KM1", vuelta.importDraft!!.text)
        assertEquals(FocusField.FIELD, vuelta.focus.field)
    }

    @Test
    fun `Alt-2 abre FRASE desde un chat y Esc vuelve al MISMO chat`() {
        val escribiendo = chat().type("hola")
        val frase = escribiendo.alt('2')
        assertEquals(Screen.FRASE, frase.screen)
        assertEquals(RootTab.FRASE, frase.tabs.active)

        val vuelta = frase.press(KeyStroke(Key.Escape)).state
        assertEquals(Screen.CONVERSACION, vuelta.screen, "Esc vuelve al contexto del que se vino")
        assertEquals("hola", vuelta.conversation!!.composer)
        assertEquals(RootTab.INICIO, vuelta.tabs.active)
    }

    @Test
    fun `Tab llega a la barra y Enter activa la pestaña apuntada`() {
        val enTabs = base()
            .press(KeyStroke(Key.Tab)).state
            .press(KeyStroke(Key.Tab)).state
        assertEquals(FocusField.TABS, enTabs.focus.field)
        assertEquals(0, enTabs.focus.index, "el cursor cae en la pestaña activa")

        val apuntando = enTabs.press(KeyStroke(Key.Down)).state
        assertEquals(1, apuntando.focus.index)

        val activada = apuntando.press(KeyStroke(Key.Enter)).state
        assertEquals(Screen.FRASE, activada.screen)
        assertEquals(RootTab.FRASE, activada.tabs.active)
        assertEquals(FocusField.TABS, activada.focus.field)
        assertEquals(1, activada.focus.index)
    }

    // ------------------------------------------------------------------
    // Esc
    // ------------------------------------------------------------------

    @Test
    fun `Esc conserva el comportamiento en cada raiz`() {
        // Chat -> INICIO, limpiando la conversacion.
        val trasChat = chat().press(KeyStroke(Key.Escape)).state
        assertEquals(Screen.INICIO, trasChat.screen)
        assertNull(trasChat.conversation)

        // FRASE -> INICIO, borrando las palabras.
        val frase = base().press('f').state
        val trasFrase = frase.press(KeyStroke(Key.Escape)).state
        assertEquals(Screen.INICIO, trasFrase.screen)
        assertTrue(trasFrase.phrase.words.isEmpty())

        // DIAGNOSTICO -> INICIO.
        val trasDiag = base().press('d').state.press(KeyStroke(Key.Escape)).state
        assertEquals(Screen.INICIO, trasDiag.screen)

        // INICIO raiz -> confirmacion de salida, NO salida silenciosa.
        val enInicio = base().press(KeyStroke(Key.Escape)).state
        assertEquals(ConfirmKind.EXIT, (enInicio.overlay as Overlay.Confirm).kind)
        assertFalse(enInicio.quitting)
    }

    @Test
    fun `RESTAURAR se apila bajo FRASE y Esc solo desapila un nivel`() {
        val restaurar = base().press('f').state.press('r').state
        assertEquals(Screen.RESTAURAR, restaurar.screen)
        assertEquals(listOf(Screen.FRASE, Screen.RESTAURAR), restaurar.tabs.current.screens)

        val aFrase = restaurar.press(KeyStroke(Key.Escape)).state
        assertEquals(Screen.FRASE, aFrase.screen)
        assertEquals(RootTab.FRASE, aFrase.tabs.active)
    }

    @Test
    fun `importar con exito sustituye la cima y un Esc vuelve a INICIO`() {
        val importar = base().press('n').state
        val r = ShellReducer.reduce(
            importar,
            ShellAction.EffectDone(EffectResult.Imported(fila(luis, "luis"))),
        )
        assertEquals(Screen.CONVERSACION, r.state.screen)
        assertEquals(listOf(Screen.INICIO, Screen.CONVERSACION), r.state.screens.screens)

        val vuelta = r.state.press(KeyStroke(Key.Escape)).state
        assertEquals(Screen.INICIO, vuelta.screen)
        assertNull(vuelta.conversation)
    }

    // ------------------------------------------------------------------
    // Keymap y ayuda
    // ------------------------------------------------------------------

    @Test
    fun `Alt-1 a Alt-3 estan en la tabla y en la ayuda generada`() {
        assertEquals(Intent.TabTo(RootTab.INICIO), Keymap.resolve(Screen.INICIO, false, KeyStroke.alt('1')))
        assertEquals(Intent.TabTo(RootTab.FRASE), Keymap.resolve(Screen.CONVERSACION, true, KeyStroke.alt('2')))
        assertEquals(
            Intent.TabTo(RootTab.DIAGNOSTICO),
            Keymap.resolve(Screen.RESTAURAR, true, KeyStroke.alt('3')),
        )

        val ayuda = Keymap.help(Screen.INICIO)
        listOf("Alt-1", "Alt-2", "Alt-3", "RePág", "AvPág").forEach { etiqueta ->
            assertTrue(ayuda.any { it.startsWith(etiqueta) }, "la ayuda lista '$etiqueta': $ayuda")
        }
    }

    @Test
    fun `PgUp y PgDn se resuelven como intents de pagina en todas las pantallas`() {
        Screen.entries.forEach { screen ->
            assertEquals(Intent.PageUp, Keymap.resolve(screen, false, KeyStroke(Key.PageUp)))
            assertEquals(Intent.PageDown, Keymap.resolve(screen, false, KeyStroke(Key.PageDown)))
            // Y siguen siendo mandos mientras se escribe: no son imprimibles.
            assertEquals(Intent.PageDown, Keymap.resolve(screen, true, KeyStroke(Key.PageDown)))
        }
    }

    @Test
    fun `la ayuda de 120x40 sale de la tabla y muestra las pestañas`() {
        val estado = base().copy(helpVisible = true, viewport = Size(120, 40))
        val texto = ShellView.renderLines(estado, false, true, GoldenHarness.FIXED_CLOCK)
            .joinToString("\n") { it.text }
        assertTrue(texto.contains("AYUDA"), texto)
        assertTrue(texto.contains("Alt-2"), texto)
        Keymap.bindings(Screen.INICIO).forEach { binding ->
            assertTrue(texto.contains(binding.label), "falta '${binding.label}' en la ayuda")
        }
    }

    @Test
    fun `cambiar de pestaña cierra la ayuda pero no el aviso`() {
        val conAyuda = base().press('?').state
        val diag = conAyuda.alt('3')
        assertFalse(diag.helpVisible)

        val conAviso = base().copy(banner = Banner(LogLevel.WARN, "RELAY lento"))
        assertTrue(conAviso.alt('3').banner != null, "el aviso no es de la pestaña que se deja")
    }
}
