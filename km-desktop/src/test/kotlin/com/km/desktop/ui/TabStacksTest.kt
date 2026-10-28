package com.km.desktop.ui

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * M4.1 — Las pilas por pestaña, sin reducer y sin pintura.
 *
 * ## LO QUE SE AFIRMA
 *
 * Que cada pestaña tiene SU pila, que `substitute` conserva la semantica de
 * `ScreenStack` (cambia la cima, no apila) y que `pop` en la raiz de una
 * pestaña vuelve a la anterior, mientras que en INICIO devuelve `null` para que
 * `Esc` siga pidiendo confirmacion de salida.
 */
class TabStacksTest {

    @Test
    fun `inicial tiene las tres raices y activa INICIO`() {
        val tabs = TabStacks.initial()
        assertEquals(RootTab.INICIO, tabs.active)
        assertEquals(Screen.INICIO, tabs.screen)
        RootTab.entries.forEach { tab ->
            assertEquals(listOf(tab.root), tabs.stack(tab).screens, "raiz de $tab")
        }
        assertNull(tabs.previous, "nadie ha cambiado de pestaña todavia")
        assertFalse(tabs.canGoBack, "INICIO raiz no vuelve a ninguna parte")
    }

    @Test
    fun `apilar CONVERSACION no cambia de pestaña`() {
        val tabs = TabStacks.initial().push(Screen.CONVERSACION)
        assertEquals(RootTab.INICIO, tabs.active)
        assertEquals(listOf(Screen.INICIO, Screen.CONVERSACION), tabs.current.screens)
    }

    @Test
    fun `abrir FRASE activa su pestaña y recuerda de donde vino`() {
        val tabs = TabStacks.initial().push(Screen.FRASE)
        assertEquals(RootTab.FRASE, tabs.active)
        assertEquals(Screen.FRASE, tabs.screen)
        assertEquals(RootTab.INICIO, tabs.previous)
        assertEquals(listOf(Screen.FRASE), tabs.current.screens, "FRASE es raiz, no se apila sobre INICIO")
    }

    @Test
    fun `RESTAURAR se apila dentro de FRASE`() {
        val tabs = TabStacks.initial().push(Screen.FRASE).push(Screen.RESTAURAR)
        assertEquals(RootTab.FRASE, tabs.active)
        assertEquals(listOf(Screen.FRASE, Screen.RESTAURAR), tabs.current.screens)
        assertEquals(RootTab.INICIO, tabs.previous)
    }

    @Test
    fun `pop retrocede en la pila y luego a la pestaña anterior`() {
        val restaura = TabStacks.initial().push(Screen.FRASE).push(Screen.RESTAURAR)

        val aFrase = restaura.pop()!!
        assertEquals(Screen.FRASE, aFrase.screen)
        assertEquals(RootTab.FRASE, aFrase.active)

        val aInicio = aFrase.pop()!!
        assertEquals(Screen.INICIO, aInicio.screen)
        assertEquals(RootTab.INICIO, aInicio.active)

        assertNull(aInicio.pop(), "en INICIO raiz no hay a donde volver: toca confirmar la salida")
    }

    @Test
    fun `pop en INICIO raiz es null aunque se haya venido de otra pestaña`() {
        val tabs = TabStacks.initial().push(Screen.FRASE).pop()!!
        assertEquals(RootTab.INICIO, tabs.active)
        assertNull(tabs.pop())
    }

    @Test
    fun `cambiar de pestaña NO toca la pila que se deja`() {
        val conChat = TabStacks.initial().push(Screen.CONVERSACION)
        val enDiag = conChat.activate(RootTab.DIAGNOSTICO)
        assertEquals(Screen.DIAGNOSTICO, enDiag.screen)

        val vuelta = enDiag.activate(RootTab.INICIO)
        assertEquals(
            listOf(Screen.INICIO, Screen.CONVERSACION),
            vuelta.current.screens,
            "el hilo sigue abierto donde estaba",
        )
    }

    @Test
    fun `substitute cambia la cima y replace deja la raiz`() {
        val importar = TabStacks.initial().push(Screen.IMPORTAR)
        val chat = importar.substitute(Screen.CONVERSACION)
        assertEquals(listOf(Screen.INICIO, Screen.CONVERSACION), chat.current.screens)
        assertEquals(RootTab.INICIO, chat.active)

        val raiz = chat.replace(Screen.INICIO)
        assertEquals(listOf(Screen.INICIO), raiz.current.screens)
    }

    @Test
    fun `of navega como el reducer y no inventa pilas imposibles`() {
        val tabs = TabStacks.of(Screen.INICIO, Screen.FRASE)
        assertEquals(RootTab.FRASE, tabs.active)
        assertEquals(listOf(Screen.FRASE), tabs.current.screens)

        val chat = TabStacks.of(Screen.INICIO, Screen.CONVERSACION)
        assertEquals(listOf(Screen.INICIO, Screen.CONVERSACION), chat.current.screens)
        assertEquals(RootTab.INICIO, chat.active)
    }

    @Test
    fun `RootTab of mapea cada pantalla a su raiz`() {
        assertEquals(RootTab.INICIO, RootTab.of(Screen.IMPORTAR))
        assertEquals(RootTab.INICIO, RootTab.of(Screen.COMPARTIR))
        assertEquals(RootTab.INICIO, RootTab.of(Screen.CONVERSACION))
        assertEquals(RootTab.FRASE, RootTab.of(Screen.RESTAURAR))
        assertEquals(RootTab.DIAGNOSTICO, RootTab.of(Screen.DIAGNOSTICO))
        assertEquals(RootTab.INICIO, RootTab.of(Screen.INICIO))
    }

    @Test
    fun `dos TabStacks con las mismas pilas son iguales`() {
        val a = TabStacks.initial().push(Screen.CONVERSACION)
        val b = TabStacks.initial().push(Screen.CONVERSACION)
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
        assertTrue(a.toString().contains("CONVERSACION"))
    }
}
