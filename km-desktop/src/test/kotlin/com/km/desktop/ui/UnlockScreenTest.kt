package com.km.desktop.ui

import com.km.desktop.client.RecoveryService
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * 3.2 — La pantalla de desbloqueo: eleccion crear/recuperar, enmascarado,
 * confirmacion al crear y al sobrescribir, y cero secretos en el frame.
 *
 * ## LO QUE SE AFIRMA CON MAS CUIDADO
 *
 * Que ni la frase de 24 palabras ni la frase de paso estan en las lineas
 * pintadas. No basta con que el renderer "no la ponga": se busca el texto
 * literal en todo el frame, que es lo que un terminal (o una captura) ensenaria.
 */
class UnlockScreenTest {

    private fun estado(creating: Boolean = false) = UnlockState(
        keystorePath = "/home/u/.config/keymessage/identity.kmkey",
        creating = creating,
        bootstraps = listOf("wss://a/km", "ws://127.0.0.1:8080/km"),
    )

    private fun frame(state: UnlockState, size: Size = Size(100, 30)): String =
        UnlockScreen.render(state, size).joinToString("\n") { it.text }

    private val frase = (1..24).joinToString(" ") { "marker$it" }

    private val acepta: PhraseValidator = PhraseValidator { true }
    private val rechaza: PhraseValidator = PhraseValidator { false }

    @Test
    fun `la frase se pinta enmascarada y nunca aparece en el frame`() {
        val conTexto = UnlockScreen.insert(estado(), "secreto")
        assertEquals("*******", conTexto.masked)

        val pintado = frame(conTexto)
        assertFalse(pintado.contains("secreto"), "la frase no puede estar en pantalla: $pintado")
        assertTrue(pintado.contains("*******"), pintado)
        assertTrue(pintado.contains("identity.kmkey"), "se muestra el almacen: $pintado")
        assertTrue(pintado.contains("2 destino"), "se declaran los destinos: $pintado")
        assertTrue(pintado.contains("sin porcentajes"), pintado)
    }

    @Test
    fun `sin almacen la puerta no pide nada hasta elegir crear o recuperar`() {
        val inicial = estado(creating = true)

        assertEquals(UnlockMode.CHOICE, inicial.mode)
        assertEquals(UnlockField.CHOICE, inicial.field)
        val pintado = frame(inicial)
        assertTrue(pintado.contains("[C] crear"), pintado)
        assertTrue(pintado.contains("[R] recuperar"), pintado)
        assertTrue(pintado.contains("24 palabras"), pintado)
        assertFalse(pintado.contains("frase de paso: *"), "no se pide frase antes de elegir: $pintado")
    }

    @Test
    fun `se puede cambiar de camino antes de enviar sin arrastrar secretos`() {
        var s = UnlockScreen.choose(estado(creating = true), UnlockMode.CREATE)
        s = UnlockScreen.insert(s, "secreto")
        assertEquals(UnlockMode.CREATE, s.mode)
        assertEquals(UnlockField.PASSPHRASE, s.field)

        s = UnlockScreen.escape(s)
        assertEquals(UnlockMode.CHOICE, s.mode)
        assertEquals("", s.passphrase, "cambiar de camino borra la frase de paso")

        s = UnlockScreen.choose(s, UnlockMode.RECOVER)
        assertEquals(UnlockMode.RECOVER, s.mode)
        assertEquals(UnlockField.PHRASE, s.field)
        s = UnlockScreen.insert(s, "cabin")
        s = UnlockScreen.escape(s)
        assertEquals(UnlockMode.CHOICE, s.mode)
        assertEquals("", s.recovery, "cambiar de camino borra la frase de recuperacion")
        assertEquals(s, UnlockScreen.choose(s, UnlockMode.UNLOCK), "sin almacen no hay nada que desbloquear")
    }

    @Test
    fun `las teclas C y R eligen camino pero dentro de recuperar son texto`() {
        val base = estado(creating = true)
        assertEquals(UnlockMode.CREATE, UnlockScreen.shortcut(base, 'c')?.mode)
        assertEquals(UnlockMode.RECOVER, UnlockScreen.shortcut(base, 'R')?.mode)
        assertNull(UnlockScreen.shortcut(base, 'x'))

        var recuperando = UnlockScreen.choose(base, UnlockMode.RECOVER)
        recuperando = UnlockScreen.insert(recuperando, "cabin")
        assertNull(
            UnlockScreen.shortcut(recuperando, 'c'),
            "en la frase de 24 palabras la 'c' es una letra BIP39, no un atajo",
        )
    }

    @Test
    fun `recuperar cuenta n de 24 sin eco de las palabras`() {
        var s = UnlockScreen.choose(estado(creating = true), UnlockMode.RECOVER)
        s = UnlockScreen.insert(s, frase)
        assertEquals(24, s.recoveryWordCount)

        val pintado = frame(s)
        assertTrue(pintado.contains("24/24"), pintado)
        assertFalse(pintado.contains("marker1"), "la frase no se pinta: $pintado")
        assertFalse(pintado.contains(frase), pintado)
    }

    @Test
    fun `una frase invalida da PHRASE_INVALID y se queda en el campo`() {
        var s = UnlockScreen.choose(estado(creating = true), UnlockMode.RECOVER)
            .copy(recovery = "no es una frase", passphrase = "nueva", repeat = "nueva")

        val envio = UnlockScreen.submit(s, rechaza)
        assertTrue(envio is UnlockSubmission.Invalid)
        val invalid = envio as UnlockSubmission.Invalid
        assertEquals(UnlockScreen.Codes.PHRASE_INVALID, invalid.state.error)
        assertEquals(UnlockField.PHRASE, invalid.state.field)
        assertEquals(
            "nueva",
            invalid.state.passphrase,
            "no se pierde lo ya escrito: solo cambia el foco",
        )
    }

    @Test
    fun `frase valida sin frase de paso nueva pide PASSPHRASE_EMPTY`() {
        val s = UnlockScreen.choose(estado(creating = true), UnlockMode.RECOVER)
            .copy(recovery = frase)

        val envio = UnlockScreen.submit(s, acepta) as UnlockSubmission.Invalid
        assertEquals(UnlockScreen.Codes.EMPTY, envio.state.error)
        assertEquals(UnlockField.PASSPHRASE, envio.state.field)
    }

    @Test
    fun `la repeticion de la frase de paso nueva tiene que coincidir`() {
        val s = UnlockScreen.choose(estado(creating = true), UnlockMode.RECOVER)
            .copy(recovery = frase, passphrase = "uno", repeat = "dos")

        val envio = UnlockScreen.submit(s, acepta) as UnlockSubmission.Invalid
        assertEquals(UnlockScreen.Codes.MISMATCH, envio.state.error)
        assertEquals(UnlockField.REPEAT, envio.state.field)
    }

    @Test
    fun `con almacen existente recuperar exige la confirmacion escrita`() {
        var s = UnlockScreen.shortcut(estado(), 'R')
            ?: error("con el buffer vacio, R abre la restauracion")
        assertEquals(UnlockMode.RECOVER, s.mode)
        assertEquals(UnlockField.PHRASE, s.field)
        assertEquals(listOf(UnlockField.PHRASE, UnlockField.PASSPHRASE, UnlockField.REPEAT, UnlockField.CONFIRMATION), UnlockScreen.fieldsFor(s))

        s = s.copy(recovery = frase, passphrase = "nueva", repeat = "nueva")
        val sinConfirmar = UnlockScreen.submit(s, acepta) as UnlockSubmission.Invalid
        assertEquals(UnlockScreen.Codes.CONFIRMATION_REQUIRED, sinConfirmar.state.error)
        assertEquals(UnlockField.CONFIRMATION, sinConfirmar.state.field)

        val confirmado = UnlockScreen.submit(
            s.copy(confirmation = RecoveryService.CONFIRMATION),
            acepta,
        )
        assertTrue(confirmado is UnlockSubmission.Recover, "$confirmado")
    }

    @Test
    fun `sin almacen recuperar no pide confirmacion`() {
        val s = UnlockScreen.choose(estado(creating = true), UnlockMode.RECOVER)
            .copy(recovery = frase, passphrase = "nueva", repeat = "nueva")

        val envio = UnlockScreen.submit(s, acepta)
        assertTrue(envio is UnlockSubmission.Recover, "$envio")
        assertEquals("", (envio as UnlockSubmission.Recover).confirmation)
    }

    @Test
    fun `el frame de recuperar no muestra ni la frase ni la passphrase`() {
        var s = UnlockScreen.shortcut(estado(), 'R') ?: error("R debe abrir la restauracion")
        s = s.copy(
            recovery = "markeruno markerdos",
            passphrase = "PASS-SECRETA",
            repeat = "PASS-SECRETA",
            confirmation = RecoveryService.CONFIRMATION,
        )

        val pintado = frame(s)
        assertFalse(pintado.contains("markeruno"), pintado)
        assertFalse(pintado.contains("PASS-SECRETA"), pintado)
        assertTrue(pintado.contains("2/24"), "solo se pinta el contador: $pintado")
        assertTrue(
            pintado.contains("*".repeat("PASS-SECRETA".length)),
            "la passphrase se enmascara: $pintado",
        )
        assertTrue(pintado.contains(RecoveryService.CONFIRMATION), "la confirmacion no es secreta")
    }

    @Test
    fun `toString del estado no expone secretos`() {
        val s = estado().copy(
            mode = UnlockMode.RECOVER,
            field = UnlockField.PHRASE,
            recovery = "FRASE-SECRETA",
            passphrase = "PASS-SECRETA",
            repeat = "REPEAT-SECRETA",
            confirmation = "CONF",
        )

        val texto = s.toString()
        assertFalse(texto.contains("FRASE-SECRETA"), texto)
        assertFalse(texto.contains("PASS-SECRETA"), texto)
        assertFalse(texto.contains("REPEAT-SECRETA"), texto)
        assertTrue(texto.contains("recovery=1/24 palabras"), texto)
    }

    @Test
    fun `el atajo R no roba una letra de la frase de paso`() {
        val conTexto = estado().copy(passphrase = "R")
        assertNull(UnlockScreen.shortcut(conTexto, 'R'))
    }

    @Test
    fun `al crear exige repetir la frase`() {
        var s = UnlockScreen.choose(estado(creating = true), UnlockMode.CREATE)
        s = UnlockScreen.insert(s, "uno")
        s = UnlockScreen.cycleField(s)
        s = UnlockScreen.insert(s, "dos")

        val envio = UnlockScreen.submit(s)
        assertTrue(envio is UnlockSubmission.Invalid)
        assertEquals(UnlockScreen.Codes.MISMATCH, (envio as UnlockSubmission.Invalid).state.error)
    }

    @Test
    fun `al crear con la repeticion correcta entrega la frase`() {
        var s = UnlockScreen.choose(estado(creating = true), UnlockMode.CREATE)
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
        val pintado = UnlockScreen.render(estado(), Size(70, 20))
        assertEquals(20, pintado.size)
        pintado.forEach { assertEquals(70, Sanitizer.width(it.text)) }
    }

    @Test
    fun `un error se pinta como codigo estable`() {
        val pintado = frame(estado().copy(error = UnlockScreen.Codes.WRONG))
        assertTrue(pintado.contains("PASSPHRASE_WRONG"), pintado)
    }
}
