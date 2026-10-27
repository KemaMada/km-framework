package com.km.desktop.ui

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.Base64

/**
 * 3.5 — Copiar por OSC 52: se EMITE la secuencia, no se lanza un binario.
 *
 * ## LO QUE SE AFIRMA
 *
 * - La forma exacta `ESC ] 52 ; c ; <base64> BEL`.
 * - Que el contenido viaja en Base64 (no en claro) dentro de la secuencia.
 * - Que no es confirmable, y que lo dice.
 * - Que un portador por encima del tope NO emite una secuencia a medias.
 */
class Osc52Test {

    @Test
    fun `la secuencia es OSC 52 con base64 y BEL`() {
        val secuencia = Osc52.sequence("hola")
        assertEquals("\u001b]52;c;" + Base64.getEncoder().encodeToString("hola".toByteArray()) + "\u0007", secuencia)
        assertTrue(secuencia!!.startsWith("\u001b]52;c;"))
        assertTrue(secuencia.endsWith("\u0007"))
        assertFalse(secuencia.contains("hola"), "el contenido no viaja en claro")
    }

    @Test
    fun `no es confirmable y no se finge`() {
        assertFalse(Osc52.isConfirmable())
    }

    @Test
    fun `un portador por encima del tope no emite nada`() {
        val grande = "x".repeat(Osc52.MAX_BASE64)
        assertNull(Osc52.sequence(grande), "mejor null que una secuencia que el terminal truncaria")
    }

    @Test
    fun `un portador de 469 B si se emite`() {
        val portador = "a".repeat(469)
        assertTrue(Osc52.sequence(portador) != null)
    }
}
