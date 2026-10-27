package com.km.desktop.ui

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * 2.3 — El keymap es una TABLA, y se prueba binding por binding.
 *
 * ## POR QUE RECORRER LA TABLA Y NO SOLO UNOS EJEMPLOS
 *
 * Porque el fallo tipico de un keymap no es "no funciona `Tab`": es que una
 * pantalla nueva se declaro sin sus teclas, o que dos bindings comparten stroke y
 * el segundo nunca se alcanza. Recorrer [Keymap.bindings] convierte la tabla en
 * la fuente de verdad y hace que un binding anadido sin resolver se vea rojo.
 *
 * ## LA TECLA IMPRIMIBLE ES DATO CUANDO SE ESCRIBE
 *
 * Es la prueba que impide el bug clasico: pulsar `r` en el compositor tiene que
 * escribir "r", no reintentar un tombstone. `editing` no se adivina: lo dice el
 * estado.
 */
class KeymapTest {

    @Test
    fun `INICIO resuelve todos sus bindings`() = assertPantalla(Screen.INICIO)

    @Test
    fun `IMPORTAR resuelve todos sus bindings`() = assertPantalla(Screen.IMPORTAR)

    @Test
    fun `COMPARTIR resuelve todos sus bindings`() = assertPantalla(Screen.COMPARTIR)

    @Test
    fun `CONVERSACION resuelve todos sus bindings`() = assertPantalla(Screen.CONVERSACION)

    @Test
    fun `FRASE resuelve todos sus bindings`() = assertPantalla(Screen.FRASE)

    @Test
    fun `RESTAURAR resuelve todos sus bindings`() = assertPantalla(Screen.RESTAURAR)

    @Test
    fun `DIAGNOSTICO resuelve todos sus bindings`() = assertPantalla(Screen.DIAGNOSTICO)

    private fun assertPantalla(screen: Screen) {
        val bindings = Keymap.bindings(screen)
        assertTrue(bindings.isNotEmpty(), "$screen no tiene keymap")

        bindings.forEach { binding ->
            assertEquals(
                binding.intent,
                Keymap.resolve(screen, editing = false, stroke = binding.stroke),
                "$screen: ${binding.label} deberia resolver ${binding.intent}",
            )
        }

        val strokes = bindings.map { it.stroke }
        assertEquals(strokes.size, strokes.toSet().size, "$screen tiene strokes duplicados")
    }

    @Test
    fun `Tab y Shift-Tab mueven el foco en todas las pantallas`() {
        Screen.entries.forEach { screen ->
            assertEquals(Intent.NextField, Keymap.resolve(screen, false, KeyStroke(Key.Tab)))
            assertEquals(Intent.PrevField, Keymap.resolve(screen, false, KeyStroke.shift(Key.Tab)))
        }
    }

    @Test
    fun `Enter y Esc son mandos en todas las pantallas`() {
        Screen.entries.forEach { screen ->
            assertEquals(Intent.Activate, Keymap.resolve(screen, false, KeyStroke(Key.Enter)))
            assertEquals(Intent.Back, Keymap.resolve(screen, false, KeyStroke(Key.Escape)))
            assertEquals(Intent.Activate, Keymap.resolve(screen, true, KeyStroke(Key.Enter)))
            assertEquals(Intent.Back, Keymap.resolve(screen, true, KeyStroke(Key.Escape)))
        }
    }

    @Test
    fun `escribir convierte las letras en DATO y no en mandos`() {
        // Sin la distincion, escribir "rd" en el compositor reintentaria y
        // descartaria en vez de escribir.
        assertEquals(Intent.Insert("r"), Keymap.resolve(Screen.CONVERSACION, true, KeyStroke.char('r')))
        assertEquals(Intent.Insert("d"), Keymap.resolve(Screen.CONVERSACION, true, KeyStroke.char('d')))
        assertEquals(Intent.Insert("n"), Keymap.resolve(Screen.INICIO, true, KeyStroke.char('n')))
        assertEquals(Intent.Insert("1"), Keymap.resolve(Screen.IMPORTAR, true, KeyStroke.char('1')))
    }

    @Test
    fun `los mandos con Ctrl siguen funcionando mientras se escribe`() {
        assertEquals(Intent.Paste, Keymap.resolve(Screen.CONVERSACION, true, KeyStroke.ctrl('v')))
        assertEquals(Intent.Cancel, Keymap.resolve(Screen.CONVERSACION, true, KeyStroke.ctrl('c')))
        assertEquals(Intent.Redraw, Keymap.resolve(Screen.CONVERSACION, true, KeyStroke.ctrl('l')))
        assertEquals(Intent.Insert("\n"), Keymap.resolve(Screen.CONVERSACION, true, KeyStroke.alt(Key.Enter)))
    }

    @Test
    fun `una tecla sin binding no inventa un mando`() {
        assertEquals(Intent.None, Keymap.resolve(Screen.INICIO, false, KeyStroke.char('z')))
        assertEquals(Intent.None, Keymap.resolve(Screen.COMPARTIR, false, KeyStroke(Key.Left)))
    }

    @Test
    fun `la ayuda sale de la tabla y no de una lista aparte`() {
        Screen.entries.forEach { screen ->
            val ayuda = Keymap.help(screen)
            assertEquals(Keymap.bindings(screen).size, ayuda.size)
            assertTrue(ayuda.all { it.isNotBlank() })
        }
        assertTrue(Keymap.help(Screen.INICIO).any { it.contains("filtrar") })
        assertFalse(Keymap.help(Screen.INICIO).isEmpty())
    }

    @Test
    fun `las teclas de la spec existen en su pantalla`() {
        assertEquals(Intent.Retry, Keymap.resolve(Screen.CONVERSACION, false, KeyStroke.char('r')))
        assertEquals(Intent.Discard, Keymap.resolve(Screen.CONVERSACION, false, KeyStroke.char('d')))
        assertEquals(Intent.Help, Keymap.resolve(Screen.INICIO, false, KeyStroke.char('?')))
        assertEquals(Intent.Copy, Keymap.resolve(Screen.COMPARTIR, false, KeyStroke.char('c')))
        assertEquals(Intent.SaveKm, Keymap.resolve(Screen.COMPARTIR, false, KeyStroke.char('w')))
        assertEquals(Intent.Reveal, Keymap.resolve(Screen.FRASE, false, KeyStroke.char('v')))
        assertEquals(Intent.CycleFilter, Keymap.resolve(Screen.DIAGNOSTICO, false, KeyStroke.char('f')))
    }
}
