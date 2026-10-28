package com.km.desktop.shell

import com.km.desktop.support.RecordingTerminal
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * B5 — Los bytes del tracking y su ORDEN de apagado.
 *
 * ## LO QUE SE AFIRMA
 *
 * - `enter(mouse = true)` activa `?1000h` y `?1006h` DESPUES de entrar en la
 *   pantalla alternativa;
 * - `enter(mouse = false)` no los emite;
 * - `exit()` emite `?1000l?1006l` ANTES de `?25h?1049l`, que es lo que deja el
 *   terminal usable si el proceso muere o recibe SIGINT;
 * - `exit()` es idempotente: la segunda llamada no escribe nada.
 *
 * El control negativo de B5 quita el OFF y este test de orden se pone rojo.
 */
class ScreenWriterMouseTest {

    private fun escritor(fake: RecordingTerminal) = ScreenWriter(fake.terminal, attributes = true)

    private fun orden(salida: String, aguja: String): Int {
        val i = salida.indexOf(aguja)
        assertTrue(i >= 0, "no se emitio '$aguja': $salida")
        return i
    }

    @Test
    fun `enter con raton activa 1000 y 1006 despues de la pantalla alternativa`() {
        val fake = RecordingTerminal()
        escritor(fake).enter(mouse = true)
        val salida = fake.texto()
        assertTrue(salida.contains(ScreenWriter.MOUSE_ON), salida)
        assertTrue(orden(salida, "\u001b[?1049h") < orden(salida, ScreenWriter.MOUSE_ON), salida)
        assertTrue(orden(salida, "\u001b[2J") < orden(salida, ScreenWriter.MOUSE_ON), salida)
    }

    @Test
    fun `enter sin raton no emite tracking`() {
        val fake = RecordingTerminal()
        escritor(fake).enter(mouse = false)
        assertFalse(fake.texto().contains("\u001b[?1000h"), fake.texto())
        assertFalse(fake.texto().contains("\u001b[?1006h"), fake.texto())
    }

    /**
     * EL ORDEN QUE IMPORTA: OFF 1000/1006 antes de cursor y alt-screen-off.
     */
    @Test
    fun `exit apaga el tracking ANTES de restaurar cursor y pantalla`() {
        val fake = RecordingTerminal()
        val writer = escritor(fake)
        writer.enter(mouse = true)
        fake.limpiar()
        writer.exit()
        val salida = fake.texto()

        val off = orden(salida, ScreenWriter.MOUSE_OFF)
        val cursor = orden(salida, "\u001b[?25h")
        val alt = orden(salida, "\u001b[?1049l")
        assertTrue(off < cursor, "OFF antes de mostrar el cursor: $salida")
        assertTrue(off < alt, "OFF antes de salir de la pantalla alternativa: $salida")
        assertTrue(cursor < alt, "el orden de restauracion no cambia: $salida")
        assertTrue(salida.contains("\u001b[?1000l"), "1000 apagado")
        assertTrue(salida.contains("\u001b[?1006l"), "1006 apagado")
    }

    @Test
    fun `exit es idempotente y no repite bytes`() {
        val fake = RecordingTerminal()
        val writer = escritor(fake)
        writer.enter(mouse = true)
        writer.exit()
        fake.limpiar()
        writer.exit()
        assertEquals(0, fake.bytesEscritos(), "la segunda salida no escribe: '${fake.texto()}'")
    }

    @Test
    fun `cerrar sin haber entrado no emite nada`() {
        val fake = RecordingTerminal()
        escritor(fake).close()
        assertEquals(0, fake.bytesEscritos(), fake.texto())
    }

    @Test
    fun `volver a entrar con raton lo reactiva`() {
        val fake = RecordingTerminal()
        val writer = escritor(fake)
        writer.enter(mouse = true)
        writer.exit()
        fake.limpiar()
        writer.enter(mouse = true)
        assertTrue(fake.texto().contains(ScreenWriter.MOUSE_ON), fake.texto())
    }

    /**
     * DESBLOQUEO -> TUI: el escritor ya esta dentro (UnlockGate llamo
     * `enter()` sin raton) y el shell pide el tracking despues. Tiene que
     * activarse sin salir y volver a entrar, que borraria la pantalla.
     */
    @Test
    fun `pedir raton estando ya dentro solo emite el ON`() {
        val fake = RecordingTerminal()
        val writer = escritor(fake)
        writer.enter(mouse = false)
        fake.limpiar()
        writer.enter(mouse = true)
        val salida = fake.texto()
        assertTrue(salida.contains(ScreenWriter.MOUSE_ON), salida)
        assertFalse(salida.contains("\u001b[?1049h"), "no se re-entra: $salida")
        assertFalse(salida.contains("\u001b[2J"), "no se borra la pantalla: $salida")
        // Un segundo enter con raton no duplica el ON.
        fake.limpiar()
        writer.enter(mouse = true)
        assertEquals(0, fake.bytesEscritos(), fake.texto())
    }
}
