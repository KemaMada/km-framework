package com.km.desktop.shell

import com.km.desktop.ui.FocusField
import com.km.desktop.ui.Intent
import com.km.desktop.ui.MouseIntent
import com.km.desktop.ui.RootTab
import com.km.desktop.ui.ShellAction
import com.km.desktop.ui.layout.HitMap
import com.km.desktop.ui.layout.HitRegion
import com.km.desktop.ui.layout.Rect
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/**
 * B3/B4 — La politica del raton, sin pty.
 *
 * ## RUEDA
 *
 * Sobre una region de lista emite `ScrollBy` (nunca un movimiento de foco
 * propio): la rama del reducer conserva `ensureVisible` y `followEnd`. Sobre
 * una pestaña, el pie, el fondo o con el foco en un campo de texto, no hace
 * nada. El control negativo de B4 rompe exactamente esta rama.
 *
 * ## ARRASTRE CON MOTION
 *
 * Un multiplexor que reenvie movimiento no puede producir un clic: `MouseCodec`
 * descarta los eventos con el bit 32 y el release de otra region no coincide
 * con la intencion recordada. Press+release en la MISMA region sigue siendo un
 * clic (el arrastre dentro de una misma region no es distinguible con `?1000`).
 */
class MouseRouterTest {

    private val fila = MouseIntent.SelectContact(3)
    private val mapa = HitMap(listOf(HitRegion(Rect(1, 5, 40, 1), fila)))

    private fun release(button: Int = 0) = MouseEvent.Release(button, 2, 5)

    @Test
    fun `press y release en la misma region emiten Pointer`() {
        val click = ClickTracker()
        assertNull(MouseRouter.route(MouseEvent.Press(0, 2, 5), click, mapa, FocusField.LIST, 3))
        assertEquals(
            ShellAction.Pointer(fila),
            MouseRouter.route(release(), click, mapa, FocusField.LIST, 3),
        )
    }

    @Test
    fun `arrastre entre filas no emite nada`() {
        val click = ClickTracker()
        MouseRouter.route(MouseEvent.Press(0, 2, 5), click, mapa, FocusField.LIST, 3)
        assertNull(MouseRouter.route(MouseEvent.Release(0, 2, 6), click, mapa, FocusField.LIST, 3))
    }

    @Test
    fun `un arrastre con motion no emite nada`() {
        val click = ClickTracker()
        // press en A (fila del mapa)
        assertNull(MouseRouter.route(MouseEvent.Press(0, 2, 5), click, mapa, FocusField.LIST, 3))
        // un multiplexor que reenvie motion: el codec lo descarta y NO llega al router
        assertNull(MouseCodec.decode("\u001b[<32;2;6M"), "el motion no se convierte en press")
        assertNull(MouseCodec.decode("\u001b[<32;2;5M"), "ni siquiera sobre la region pulsada")
        // release en B: la intencion no coincide y no hay accion
        assertNull(MouseRouter.route(MouseEvent.Release(0, 2, 6), click, mapa, FocusField.LIST, 3))
    }

    @Test
    fun `un release suelto no emite Pointer`() {
        val click = ClickTracker()
        assertNull(MouseRouter.route(release(), click, mapa, FocusField.LIST, 3))
    }

    @Test
    fun `el boton derecho no registra ni dispara`() {
        val click = ClickTracker()
        MouseRouter.route(MouseEvent.Press(2, 2, 5), click, mapa, FocusField.LIST, 3)
        assertNull(MouseRouter.route(MouseEvent.Release(2, 2, 5), click, mapa, FocusField.LIST, 3))
    }

    @Test
    fun `rueda sobre una lista emite ScrollBy con las filas configuradas`() {
        val click = ClickTracker()
        assertEquals(
            ShellAction.ScrollBy(3),
            MouseRouter.route(MouseEvent.Wheel(false, 2, 5), click, mapa, FocusField.LIST, 3),
        )
        assertEquals(
            ShellAction.ScrollBy(-7),
            MouseRouter.route(MouseEvent.Wheel(true, 2, 5), click, mapa, FocusField.LIST, 7),
        )
    }

    @Test
    fun `rueda se acota entre 1 y 10`() {
        val click = ClickTracker()
        assertEquals(
            ShellAction.ScrollBy(10),
            MouseRouter.route(MouseEvent.Wheel(false, 2, 5), click, mapa, FocusField.LIST, 99),
        )
        assertEquals(
            ShellAction.ScrollBy(1),
            MouseRouter.route(MouseEvent.Wheel(false, 2, 5), click, mapa, FocusField.LIST, 0),
        )
    }

    @Test
    fun `rueda fuera de una lista no desplaza`() {
        val click = ClickTracker()
        val tabs = HitMap(
            listOf(HitRegion(Rect(0, 1, 10, 1), MouseIntent.Act(Intent.TabTo(RootTab.FRASE)))),
        )
        assertNull(MouseRouter.route(MouseEvent.Wheel(false, 2, 1), click, tabs, FocusField.LIST, 3))
        assertNull(MouseRouter.route(MouseEvent.Wheel(false, 90, 90), click, mapa, FocusField.LIST, 3))
    }

    @Test
    fun `con foco en un campo de texto la rueda no mueve`() {
        val click = ClickTracker()
        listOf(FocusField.FIELD, FocusField.COMPOSER, FocusField.CONFIRM).forEach { campo ->
            assertNull(
                MouseRouter.route(MouseEvent.Wheel(false, 2, 5), click, mapa, campo, 3),
                "la rueda no debe mover con foco en $campo",
            )
        }
    }
}
