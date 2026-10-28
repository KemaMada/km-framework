package com.km.desktop.ui.layout

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * M3.2 — La VENTANA de una lista, como PROPIEDAD.
 *
 * ## POR QUE ES UNA PROPIEDAD Y NO TRES EJEMPLOS
 *
 * "La fila enfocada nunca queda fuera de vista" es lo unico que impide que el
 * scroll sea una regresion silenciosa: con un caso suelto, un `ensure` que
 * siempre devolviera 0 pasaria mientras ese caso fuera `index = 0`. Aqui se
 * recorren totales, altos, indices y offsets, y se afirma el invariante en
 * TODOS. El control negativo de abajo demuestra que la asercion puede fallar.
 *
 * ## LO QUE NO SE PRUEBA AQUI
 *
 * La pintura. Esto es aritmetica en filas: ni terminal, ni ANSI, ni estado.
 */
class ScrollTest {

    /** Un `ensure` roto (siempre 0) para el control negativo. */
    private fun ensureRoto(@Suppress("UNUSED_PARAMETER") index: Int): Int = 0

    @Test
    fun `para cualquier lista el indice enfocado cae dentro de la ventana`() {
        var comprobados = 0
        for (total in 0..80) {
            for (rows in 1..20) {
                val limite = Scroll.maxOffset(total, rows)
                for (index in 0 until maxOf(total, 1)) {
                    val offsets = linkedSetOf(
                        0,
                        limite,
                        limite / 2,
                        index,
                        (index - rows + 1).coerceIn(0, limite),
                        (index + rows).coerceIn(0, limite),
                    )
                    for (offset in offsets) {
                        val nuevo = Scroll.ensure(index, offset, rows, total)
                        assertTrue(
                            nuevo in 0..limite,
                            "total=$total rows=$rows index=$index offset=$offset -> $nuevo fuera de 0..$limite",
                        )
                        if (total > 0) {
                            assertTrue(
                                index in Scroll.window(total, nuevo, rows),
                                "total=$total rows=$rows index=$index offset=$offset -> " +
                                    "ventana ${Scroll.window(total, nuevo, rows)} no contiene el foco",
                            )
                        } else {
                            assertTrue(Scroll.window(total, nuevo, rows).isEmpty())
                        }
                        comprobados++
                    }
                }
            }
        }
        assertTrue(comprobados > 10_000, "la propiedad se ejercito $comprobados veces")
    }

    @Test
    fun `un ensure roto que siempre devuelve 0 SI rompe la propiedad`() {
        // Control negativo del test, no del codigo: sin esto, la propiedad de
        // arriba podria estar comprobando algo que nunca puede fallar.
        val total = 200
        val rows = 10
        val index = 150
        val roto = ensureRoto(index)
        assertFalse(
            index in Scroll.window(total, roto, rows),
            "con offset 0 la fila 150 no puede estar en una ventana de 10",
        )
        assertTrue(index in Scroll.window(total, Scroll.ensure(index, 0, rows, total), rows))
    }

    @Test
    fun `ensure no mueve la ventana si el foco ya se ve`() {
        // Mover una fila no puede recentrar la lista: saltaria en cada pulsacion.
        repeat(10) { offset ->
            assertEquals(offset, Scroll.ensure(offset + 3, offset, 5, 100))
        }
    }

    @Test
    fun `window clampa offsets imposibles y total cero`() {
        assertTrue(Scroll.window(0, 0, 10).isEmpty())
        assertTrue(Scroll.window(5, 0, 0).isEmpty())
        assertEquals(0 until 5, Scroll.window(5, -3, 10))
        assertEquals(90 until 100, Scroll.window(100, 999, 10))
        assertEquals(0 until 4, Scroll.window(4, 0, 10))
    }

    @Test
    fun `maxOffset nunca es negativo`() {
        assertEquals(0, Scroll.maxOffset(0, 10))
        assertEquals(0, Scroll.maxOffset(3, 10))
        assertEquals(90, Scroll.maxOffset(100, 10))
        assertEquals(0, Scroll.maxOffset(10, 0))
    }

    @Test
    fun `el indicador es texto y solo aparece cuando hay ventana`() {
        assertNull(Scroll.indicator(0, 10, 10), "una lista que cabe no anuncia nada")
        assertNull(Scroll.indicator(0, 10, 0))
        assertEquals("[10/50]", Scroll.indicator(0, 10, 50))
        assertEquals("[50/50]", Scroll.indicator(90, 10, 50))
        assertEquals("[13/50]", Scroll.indicator(0, 13, 50))
    }

    @Test
    fun `listRows es la unica altura y descuenta las filas fijas`() {
        val chico = com.km.desktop.ui.Size(60, 20)
        // 60x20: cabecera 2 + estado 1 + pie 1 => 16 filas de cuerpo.
        assertEquals(16, Layout.bodyRows(com.km.desktop.ui.Screen.INICIO, chico))
        // INICIO: buscador + regla fija, y cada contacto gasta ficha + separador.
        assertEquals(7, Layout.listRows(com.km.desktop.ui.Screen.INICIO, chico))
        // CONVERSACION: presencia + regla + compositor + acciones.
        assertEquals(12, Layout.listRows(com.km.desktop.ui.Screen.CONVERSACION, chico))
        assertEquals(7, Layout.listRows(com.km.desktop.ui.Screen.DIAGNOSTICO, chico))
        // Las pantallas sin lista no reservan filas de lista.
        assertEquals(16, Layout.listRows(com.km.desktop.ui.Screen.FRASE, chico))
        assertTrue(Layout.listRows(com.km.desktop.ui.Screen.CONVERSACION, chico) > 0)
        assertTrue(
            Layout.listRows(com.km.desktop.ui.Screen.INICIO, com.km.desktop.ui.Size(60, 6)) >= 1,
            "nunca cero: el foco tiene que caber",
        )
    }
}
