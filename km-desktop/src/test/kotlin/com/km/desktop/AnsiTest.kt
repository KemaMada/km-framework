package com.km.desktop

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Pruebas del color.
 *
 * El apagado automatico no es cosmetico: una secuencia SGR en la salida de un
 * script deja los logs con basura, y una TUI que escribe en un fichero tiene que
 * salir limpia sin que el usuario tenga que acordarse de `--no-color`.
 */
class AnsiTest {

    @AfterEach
    fun reset() {
        Ansi.detect(stdoutTerminal = false, env = emptyMap())
    }

    @Test
    fun `sin terminal no hay secuencias de escape`() {
        Ansi.detect(stdoutTerminal = false, env = mapOf("TERM" to "xterm-256color"))
        assertFalse(Ansi.enabled)
        assertEquals("hola", Ansi.red("hola"))
        assertEquals("km> ", Ansi.prompt())
    }

    @Test
    fun `con terminal hay color`() {
        Ansi.detect(stdoutTerminal = true, env = mapOf("TERM" to "xterm-256color"))
        assertTrue(Ansi.enabled)
        assertTrue(Ansi.red("hola").contains("\u001B[31m"), Ansi.red("hola"))
        assertTrue(Ansi.bold("t").contains("\u001B[1m"))
    }

    @Test
    fun `NO_COLOR desactiva`() {
        Ansi.detect(stdoutTerminal = true, env = mapOf("TERM" to "xterm", "NO_COLOR" to "1"))
        assertFalse(Ansi.enabled)
    }

    @Test
    fun `TERM ausente o dumb desactiva`() {
        Ansi.detect(stdoutTerminal = true, env = emptyMap())
        assertFalse(Ansi.enabled)

        Ansi.detect(stdoutTerminal = true, env = mapOf("TERM" to "dumb"))
        assertFalse(Ansi.enabled)
    }

    @Test
    fun `el texto envuelto conserva el contenido`() {
        Ansi.detect(stdoutTerminal = true, env = mapOf("TERM" to "xterm"))
        val colored = Ansi.cyan("identidad")
        assertTrue(colored.endsWith("identidad\u001B[0m"), colored)
        assertEquals("identidad", colored.substringAfter('m', colored).substringBefore("\u001B"))
    }
}
