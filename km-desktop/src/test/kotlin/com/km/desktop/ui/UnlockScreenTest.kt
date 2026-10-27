package com.km.desktop.ui

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * 3.2 — La pantalla de desbloqueo: enmascarada, con confirmacion al crear y sin
 * porcentajes.
 *
 * ## LO QUE SE AFIRMA CON MAS CUIDADO
 *
 * Que la frase NO esta en las lineas pintadas. No basta con que el renderer
 * "no la ponga": se busca el texto literal en todo el frame, que es lo que un
 * terminal (o una captura) ensenaria.
 */
class UnlockScreenTest {

    private fun estado(creating: Boolean = false) = UnlockState(
        keystorePath = "/home/u/.config/keymessage/identity.kmkey",
        creating = creating,
        bootstraps = listOf("wss://a/km", "ws://127.0.0.1:8080/km"),
    )

    @Test
    fun `la frase se pinta enmascarada y nunca aparece en el frame`() {
        val conTexto = UnlockScreen.insert(estado(), "secreto")
        assertEquals("*******", conTexto.masked)

        val frame = UnlockScreen.render(conTexto, Size(100, 30)).joinToString("\n") { it.text }
        assertFalse(frame.contains("secreto"), "la frase no puede estar en pantalla: $frame")
        assertTrue(frame.contains("*******"), frame)
        assertTrue(frame.contains("identity.kmkey"), "se muestra el almacen: $frame")
        assertTrue(frame.contains("2 destino"), "se declaran los destinos: $frame")
        assertTrue(frame.contains("sin porcentajes"), frame)
    }

    @Test
    fun `al crear exige repetir la frase`() {
        var s = estado(creating = true)
        s = UnlockScreen.insert(s, "uno")
        s = UnlockScreen.cycleField(s)
        s = UnlockScreen.insert(s, "dos")

        val envio = UnlockScreen.submit(s)
        assertTrue(envio is UnlockSubmission.Invalid)
        assertEquals(UnlockScreen.Codes.MISMATCH, (envio as UnlockSubmission.Invalid).state.error)
    }

    @Test
    fun `al crear con la repeticion correcta entrega la frase`() {
        var s = estado(creating = true)
        s = UnlockScreen.insert(s, "uno")
        s = UnlockScreen.cycleField(s)
        s = UnlockScreen.insert(s, "uno")

        val envio = UnlockScreen.submit(s)
        assertEquals(UnlockSubmission.Ready("uno"), envio)
    }

    @Test
    fun `sin frase no se intenta abrir`() {
        val envio = UnlockScreen.submit(estado())
        assertTrue(envio is UnlockSubmission.Invalid)
        assertEquals(UnlockScreen.Codes.EMPTY, (envio as UnlockSubmission.Invalid).state.error)
    }

    @Test
    fun `Tab no cambia de campo si el almacen ya existe`() {
        val s = UnlockScreen.cycleField(estado(creating = false))
        assertEquals(UnlockField.PASSPHRASE, s.field)
    }

    @Test
    fun `Retroceso borra y limpia el error`() {
        var s = estado().copy(error = UnlockScreen.Codes.WRONG)
        s = UnlockScreen.insert(s, "ab")
        s = UnlockScreen.backspace(s)
        assertEquals("a", s.passphrase)
        assertEquals(null, s.error)
    }

    @Test
    fun `el frame mide el viewport y se ajusta si es pequeno`() {
        val frame = UnlockScreen.render(estado(), Size(70, 20))
        assertEquals(20, frame.size)
        frame.forEach { assertEquals(70, Sanitizer.width(it.text)) }
    }

    @Test
    fun `un error se pinta como codigo estable`() {
        val frame = UnlockScreen.render(
            estado().copy(error = UnlockScreen.Codes.WRONG),
            Size(100, 30),
        ).joinToString("\n") { it.text }
        assertTrue(frame.contains("PASSPHRASE_WRONG"), frame)
    }
}
