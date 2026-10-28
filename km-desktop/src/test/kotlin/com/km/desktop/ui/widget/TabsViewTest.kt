package com.km.desktop.ui.widget

import com.km.desktop.ui.Focus
import com.km.desktop.ui.FocusField
import com.km.desktop.ui.RootTab
import com.km.desktop.ui.Sanitizer
import com.km.desktop.ui.Screen
import com.km.desktop.ui.TabStacks
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * M4.4 — El widget de la barra de pestañas, sin terminal.
 *
 * ## POR QUE SE PRUEBA UN WIDGET QUE AUN NO SE PINTA
 *
 * Porque su contrato (que pestaña esta activa, como se marca sin color, cuanto
 * mide) es lo que M5 va a consumir en la cabecera. Dejarlo sin probar seria
 * estrenarlo justo cuando entra el rediseño visual, que es el momento con mas
 * piezas moviendose.
 *
 * El texto se afirma con `Sanitizer.width`: la barra la recortara `Grid.text`,
 * pero un widget que devuelve celdas de mas empuja la cabecera entera.
 */
class TabsViewTest {

    private val tabs = TabStacks.initial()

    @Test
    fun `la barra lista las tres raices y marca la activa`() {
        val etiquetas = TabsView.labels(tabs)
        assertEquals(RootTab.entries.size, etiquetas.size)
        assertEquals("\u258cINICIO", etiquetas[0])
        assertTrue(etiquetas[1].endsWith("FRASE") && !etiquetas[1].startsWith("\u258c"))
        assertTrue(etiquetas[2].endsWith("DIAGNOSTICO"))

        val activa = tabs.activate(RootTab.DIAGNOSTICO)
        assertEquals("  INICIO", TabsView.labels(activa)[0])
        assertEquals("\u258cDIAGNOSTICO", TabsView.labels(activa)[2])
    }

    @Test
    fun `ascii usa un marcador de una celda y no glifos Unicode`() {
        val linea = TabsView.line(tabs, ascii = true)
        assertTrue(linea.startsWith(">INICIO"), linea)
        assertFalse(linea.contains("\u258c"))
        assertEquals(TabsView.line(tabs, ascii = false).length, linea.length, "misma metrica")
    }

    @Test
    fun `el ancho es el de las etiquetas mas los huecos`() {
        val esperado = TabsView.labels(tabs).sumOf { Sanitizer.width(it) } +
            TabsView.GAP.length * (RootTab.entries.size - 1)
        assertEquals(esperado, Sanitizer.width(TabsView.line(tabs)))
        assertEquals(esperado, Sanitizer.width(TabsView.line(tabs, ascii = true)))
    }

    @Test
    fun `el foco de la barra se marca como fila enfocada`() {
        assertFalse(TabsView.row(tabs).focus)
        assertTrue(TabsView.row(tabs, focus = Focus(FocusField.TABS, 0)).focus)
        assertTrue(TabsView.row(tabs, focus = Focus(FocusField.LIST, 0)).focus.not())
    }

    @Test
    fun `el cursor de la barra solo existe con el foco en ella`() {
        assertNull(TabsView.cursor(Focus(FocusField.LIST, 2)))
        assertEquals(RootTab.FRASE, TabsView.cursor(Focus(FocusField.TABS, 1)))
        assertEquals(RootTab.INICIO, TabsView.cursor(Focus(FocusField.TABS, 0)))
    }

    @Test
    fun `la pestaña activa sigue al estado y no se escribe a mano`() {
        val conChat = tabs.push(Screen.CONVERSACION)
        assertEquals(RootTab.INICIO, com.km.desktop.ui.RootTab.of(conChat.screen))
        assertTrue(TabsView.line(conChat).startsWith("\u258cINICIO"))

        val frase = conChat.push(Screen.FRASE)
        assertTrue(TabsView.line(frase).contains("\u258cFRASE"), TabsView.line(frase))
        assertFalse(TabsView.line(frase).startsWith("\u258cINICIO"))
    }
}
