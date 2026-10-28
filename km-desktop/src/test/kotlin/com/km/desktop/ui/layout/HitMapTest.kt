package com.km.desktop.ui.layout

import com.km.desktop.ui.Intent
import com.km.desktop.ui.MouseIntent
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/**
 * B2 — El hit-map gana por ORDEN DE PINTADO.
 *
 * ## EL CONTRATO
 *
 * `at()` devuelve la ULTIMA region que contiene la celda. Es lo que hace que el
 * dialogo gane a la lista tapada y el boton a su caja. El control negativo de
 * B2 invierte esta busqueda y este test se pone rojo.
 */
class HitMapTest {

    private val contacto = MouseIntent.SelectContact(0)
    private val dismiss = MouseIntent.Dismiss
    private val copiar = MouseIntent.Act(Intent.Copy)

    @Test
    fun `gana la ultima region pintada`() {
        val mapa = HitMap(
            listOf(
                HitRegion(Rect(0, 0, 80, 24), dismiss),
                HitRegion(Rect(2, 3, 20, 1), contacto),
                HitRegion(Rect(5, 3, 4, 1), copiar),
            ),
        )
        assertEquals(copiar, mapa.at(6, 3), "el boton gana a la fila y al fondo")
        assertEquals(contacto, mapa.at(2, 3), "fuera del boton sigue la fila")
        assertEquals(dismiss, mapa.at(70, 20), "el fondo cubre el resto")
    }

    @Test
    fun `fuera de toda region es null`() {
        val mapa = HitMap(listOf(HitRegion(Rect(2, 3, 4, 1), contacto)))
        assertNull(mapa.at(1, 3), "a la izquierda")
        assertNull(mapa.at(6, 3), "a la derecha (right es exclusivo)")
        assertNull(mapa.at(2, 2), "encima")
        assertNull(mapa.at(2, 4), "debajo")
        assertEquals(contacto, mapa.at(5, 3), "ultima columna incluida")
    }

    @Test
    fun `un mapa vacio no inventa acciones`() {
        assertNull(HitMap.EMPTY.at(0, 0))
        assertNull(HitMap().at(10, 10))
    }
}
