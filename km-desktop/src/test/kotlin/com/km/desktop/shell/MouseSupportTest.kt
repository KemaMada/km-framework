package com.km.desktop.shell

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** B5 — El sondeo es conservador y DECLARADO: nada de fingir soporte. */
class MouseSupportTest {

    @Test
    fun `una terminal interactiva normal soporta raton`() {
        assertTrue(MouseSupport.hasMouseSupport("xterm-256color", console = true, typeDumb = false))
        assertTrue(MouseSupport.hasMouseSupport("screen-256color", console = true, typeDumb = false))
        assertTrue(MouseSupport.hasMouseSupport("tmux-256color", console = true, typeDumb = false))
    }

    @Test
    fun `sin consola dumb TERM vacio o consola Linux no se activa`() {
        assertFalse(MouseSupport.hasMouseSupport("xterm-256color", console = false, typeDumb = false))
        assertFalse(MouseSupport.hasMouseSupport("dumb", console = true, typeDumb = false))
        assertFalse(MouseSupport.hasMouseSupport("dumb-color", console = true, typeDumb = false))
        assertFalse(MouseSupport.hasMouseSupport("", console = true, typeDumb = false))
        assertFalse(MouseSupport.hasMouseSupport(null, console = true, typeDumb = false))
        assertFalse(MouseSupport.hasMouseSupport("linux", console = true, typeDumb = false))
        assertFalse(MouseSupport.hasMouseSupport("xterm", console = true, typeDumb = true))
    }
}
