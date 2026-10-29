package com.example.keymessage.ui.screens

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * R17 — el scroll deja de arrastrar la vista y de esconder mensajes.
 *
 * ## EL DEFECTO
 *
 * `LaunchedEffect(messages.size) { animateScrollToItem(size - 1) }` era
 * incondicional: cada mensaje nuevo movia la vista aunque el usuario estuviera
 * leyendo arriba. Y al reves, un mensaje fuera del viewport se interpretaba como
 * mensaje PERDIDO: "se vieron 9 de 10". La ausencia visible no es evidencia de
 * perdida; el contador de filas es la evidencia.
 *
 * ## QUE SE PRUEBA
 *
 * La regla, sin Compose: seguir SOLO si ya se estaba al final y el conteo subio.
 * Si no, el aviso "N nuevos". Nada de autoscroll ciego.
 */
class ChatScrollPolicyTest {

    @Test
    fun `si el usuario estaba abajo y llega un mensaje, la vista sigue`() {
        assertTrue(ChatScrollPolicy.shouldFollow(wasAtBottom = true, previousCount = 9, newCount = 10))
    }

    @Test
    fun `leer arriba NO oculta el primer mensaje ni arrastra la vista`() {
        // 10 filas con una ventana de 3: el usuario miro el principio para
        // contar. Llega la fila 11 y NO se le mueve la vista.
        assertFalse(
            ChatScrollPolicy.shouldFollow(wasAtBottom = false, previousCount = 10, newCount = 11),
            "leer arriba no puede provocar un salto al final",
        )
        assertEquals(1, ChatScrollPolicy.newCount(10, 11), "y se avisa cuantos llegaron")
    }

    @Test
    fun `sin mensajes nuevos o con menos filas no hay scroll`() {
        assertFalse(ChatScrollPolicy.shouldFollow(wasAtBottom = true, previousCount = 10, newCount = 10))
        assertFalse(
            ChatScrollPolicy.shouldFollow(wasAtBottom = true, previousCount = 10, newCount = 9),
            "un descarte explicito baja el conteo y no es un mensaje nuevo",
        )
        assertEquals(0, ChatScrollPolicy.newCount(10, 9), "N nuevos nunca es negativo")
    }

    @Test
    fun `el conteo de nuevos se acumula mientras el usuario no baja`() {
        // Dos tandas mientras se lee arriba: 2 y luego 3 mas.
        val primera = ChatScrollPolicy.newCount(8, 10)
        val segunda = ChatScrollPolicy.newCount(10, 13)
        assertEquals(5, primera + segunda, "el aviso dice 5 nuevos, no solo los ultimos 3")
    }

    @Test
    fun `control negativo la regla vieja SI arrastraba la vista de un lector`() {
        // El comportamiento anterior, expresado como funcion, para dejar claro
        // que el arnes distingue una politica de la otra.
        fun viejaSiempreSigue(previousCount: Int, newCount: Int): Boolean = newCount > previousCount

        assertTrue(
            viejaSiempreSigue(10, 11),
            "la politica vieja seguia siempre... incluso leyendo arriba",
        )
        assertFalse(
            ChatScrollPolicy.shouldFollow(wasAtBottom = false, previousCount = 10, newCount = 11),
            "...y la nueva NO: ahi esta la diferencia que el test pina",
        )
    }

    // ===================================================================
    // F4 — abrir al fondo y re-anclar tras el IME
    // ===================================================================

    @Test
    fun `al ABRIR la conversacion el indice inicial es el ULTIMO`() {
        assertEquals(9, ChatScrollPolicy.indiceInicial(total = 10), "el hilo se lee de abajo arriba")
        assertEquals(0, ChatScrollPolicy.indiceInicial(total = 1), "con un mensaje, ese")
    }

    @Test
    fun `control negativo una lista vacia NO tiene indice inicial`() {
        assertEquals(
            -1,
            ChatScrollPolicy.indiceInicial(total = 0),
            "un LazyListState no se puede anclar a un indice que no existe",
        )
    }

    @Test
    fun `el IME re-ancla SOLO si el usuario ya estaba al final`() {
        assertTrue(
            ChatScrollPolicy.debeReanclarTrasIme(wasAtBottom = true, hayMensajes = true),
            "al final, el teclado no le tapa el ultimo mensaje",
        )
        assertFalse(
            ChatScrollPolicy.debeReanclarTrasIme(wasAtBottom = false, hayMensajes = true),
            "leyendo arriba, el teclado NO le mueve la vista",
        )
    }

    @Test
    fun `control negativo sin mensajes el IME no re-ancla nada`() {
        assertFalse(
            ChatScrollPolicy.debeReanclarTrasIme(wasAtBottom = true, hayMensajes = false),
            "no hay nada a lo que anclar en una conversacion vacia",
        )
    }
}
