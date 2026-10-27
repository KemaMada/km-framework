package com.km.desktop.ui

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotSame
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * 2.1 — La pila de navegacion es PURA y no apila lo que no debe.
 *
 * ## QUE SE AFIRMA
 *
 * - La raiz es INICIO y desde ahi no hay "atras".
 * - `push` no duplica la pantalla actual (un `Esc` no puede parecer muerto).
 * - `pop` en la raiz devuelve `null`: la senal de "pide confirmacion", no un
 *   destino inventado.
 * - `substitute` reemplaza la CIMA: importar con exito deja INICIO debajo, de
 *   modo que el `Esc` desde el chat vuelve en UN paso (spec R4).
 * - `replace` descarta el historial.
 * - Ninguna operacion muta la pila de entrada.
 */
class ScreenStackTest {

    @Test
    fun `la pila inicial es INICIO y no puede volver`() {
        val pila = ScreenStack.initial()

        assertEquals(Screen.INICIO, pila.current)
        assertEquals(listOf(Screen.INICIO), pila.screens)
        assertFalse(pila.canGoBack)
        assertNull(pila.pop(), "en la raiz no hay pantalla anterior")
    }

    @Test
    fun `push apila y permite volver`() {
        val pila = ScreenStack.initial().push(Screen.IMPORTAR)

        assertEquals(Screen.IMPORTAR, pila.current)
        assertEquals(listOf(Screen.INICIO, Screen.IMPORTAR), pila.screens)
        assertTrue(pila.canGoBack)
    }

    @Test
    fun `push de la pantalla actual no duplica`() {
        val pila = ScreenStack.initial().push(Screen.INICIO)

        assertEquals(listOf(Screen.INICIO), pila.screens)
        assertFalse(pila.canGoBack)
    }

    @Test
    fun `pop vuelve a la anterior y no muta la original`() {
        val conImport = ScreenStack.initial().push(Screen.IMPORTAR)
        val vuelta = conImport.pop()!!

        assertEquals(Screen.INICIO, vuelta.current)
        assertNotSame(conImport, vuelta)
        assertEquals(
            listOf(Screen.INICIO, Screen.IMPORTAR),
            conImport.screens,
            "la pila de entrada no se toca",
        )
    }

    @Test
    fun `substitute cambia la cima y deja INICIO debajo`() {
        // El caso de la spec R4: importar con exito abre la conversacion SIN
        // apilar el dialogo de importar.
        val trasImportar = ScreenStack.initial()
            .push(Screen.IMPORTAR)
            .substitute(Screen.CONVERSACION)

        assertEquals(listOf(Screen.INICIO, Screen.CONVERSACION), trasImportar.screens)
        assertEquals(Screen.INICIO, trasImportar.pop()!!.current, "Esc desde el chat vuelve a INICIO")
    }

    @Test
    fun `replace descarta el historial`() {
        val pila = ScreenStack.initial()
            .push(Screen.IMPORTAR)
            .push(Screen.CONVERSACION)
            .replace(Screen.INICIO)

        assertEquals(listOf(Screen.INICIO), pila.screens)
        assertFalse(pila.canGoBack)
    }

    @Test
    fun `of rechaza una pila vacia`() {
        assertThrows(IllegalArgumentException::class.java) { ScreenStack.of() }
    }

    @Test
    fun `dos pilas con las mismas pantallas son iguales`() {
        assertEquals(ScreenStack.of(Screen.INICIO, Screen.COMPARTIR), ScreenStack.initial().push(Screen.COMPARTIR))
        assertEquals(
            ScreenStack.of(Screen.INICIO, Screen.COMPARTIR).hashCode(),
            ScreenStack.initial().push(Screen.COMPARTIR).hashCode(),
        )
    }
}
