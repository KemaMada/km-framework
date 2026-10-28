package com.km.desktop.shell

import com.km.desktop.ui.MouseIntent
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/**
 * B3 — Un clic es press+release en la MISMA region.
 *
 * ## ARRASTRE
 *
 * Change B solo activa `?1000`, pero aunque llegara un arrastre, press en una
 * fila y release en otra producen intenciones distintas: no hay accion. Este es
 * el test que el control negativo de B3 rompe si el release actua siempre.
 */
class ClickTrackerTest {

    private val fila2 = MouseIntent.SelectContact(1)
    private val fila3 = MouseIntent.SelectContact(2)

    @Test
    fun `press y release en la misma region es un clic`() {
        val tracker = ClickTracker()
        tracker.press(fila2)
        assertEquals(fila2, tracker.release(fila2))
    }

    @Test
    fun `press en una fila y release en otra no actua`() {
        val tracker = ClickTracker()
        tracker.press(fila2)
        assertNull(tracker.release(fila3))
    }

    @Test
    fun `release sin press no actua`() {
        assertNull(ClickTracker().release(fila2))
    }

    @Test
    fun `un gesto se consume una sola vez`() {
        val tracker = ClickTracker()
        tracker.press(fila2)
        assertEquals(fila2, tracker.release(fila2))
        assertNull(tracker.release(fila2))
    }

    @Test
    fun `reset olvida un press a medias`() {
        val tracker = ClickTracker()
        tracker.press(fila2)
        tracker.reset()
        assertNull(tracker.release(fila2))
    }
}
