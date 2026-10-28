package com.km.desktop.ui

import com.km.desktop.client.ClientEvent
import com.km.model.IdentityId
import com.km.model.MessageId
import com.km.model.MessageState
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * 3.1/3.3/3.5/3.6/3.7/3.9 — La PINTURA de las pantallas, sin terminal.
 *
 * ## QUE SE AFIRMA
 *
 * - Toda linea mide EXACTAMENTE el ancho del viewport, en todas las pantallas.
 *   Una caja mellada es un bug visible en la primera pulsacion.
 * - El foco se ve por `>` en el TEXTO, no solo por color.
 * - La lista vacia AVISA de que es volatil (R5).
 * - Los estados por mensaje llevan etiqueta textual honesta (R8).
 * - La presencia del par nunca se pinta "ONLINE" (R9).
 * - Un payload con `ESC[2J` se pinta inerte (R3).
 * - `--ascii` no usa glifos de caja Unicode.
 */
class ShellRendererTest {

    private val ana = IdentityId("a".repeat(64))
    private val yo = IdentityId("f".repeat(64))

    private fun fila() = ContactRow(
        identityId = ana,
        fingerprint = ana.value.take(16) + "...",
        displayName = "ana",
        hasSession = true,
        hasAckKey = true,
        presence = Presence.RELAY_PRESENT,
        lastMessage = "hasta luego",
        lastAt = 1_700_000_000_000,
    )

    private fun base(screen: Screen = Screen.INICIO) = ShellState(
        tabs = TabStacks.of(Screen.INICIO, screen),
        contacts = listOf(fila()),
        contactsLoaded = true,
        viewport = Size(100, 30),
        status = StatusLine(
            link = LinkState.ONLINE,
            endpoint = "wss://relay.example/km",
            localIdentityId = yo.value,
            localFingerprint = yo.value.take(16) + "\u2026",
            sessions = 1,
            ackKeys = 1,
        ),
    )

    private fun lineas(state: ShellState, ascii: Boolean = false): List<RenderedLine> =
        ShellRenderer.render(state, ascii)

    private fun assertAncho(state: ShellState, ascii: Boolean = false) {
        lineas(state, ascii).forEachIndexed { i, linea ->
            assertEquals(
                state.viewport.columns,
                Sanitizer.width(linea.text),
                "linea $i no mide ${state.viewport.columns}: '${linea.text}'",
            )
        }
    }

    @Test
    fun `todas las pantallas miden el ancho exacto del viewport`() {
        val estados = listOf(
            base(Screen.INICIO),
            base(Screen.IMPORTAR).copy(importDraft = ImportDraft()),
            base(Screen.COMPARTIR).copy(
                share = ShareView(carrier = "abc", byteLength = 3, oneTimePreKeys = 0),
            ),
            base(Screen.CONVERSACION).copy(
                conversation = ConversationView(ana, "ana"),
            ),
            base(Screen.FRASE),
            base(Screen.RESTAURAR),
            base(Screen.DIAGNOSTICO),
        )
        estados.forEach { estado ->
            assertAncho(estado)
            assertEquals(estado.viewport.rows, lineas(estado).size, "filas de ${estado.screen}")
        }
    }

    @Test
    fun `el foco se ve por el glifo mayor que`() {
        val lineas = lineas(base().copy(focus = Focus(FocusField.LIST, 0)))
        val conFoco = lineas.filter { it.focus }
        assertEquals(1, conFoco.size, "una sola fila tiene el foco")
        assertTrue(
            conFoco.single().text.contains("\u258c") || conFoco.single().text.contains(">"),
            "el foco lleva su glifo en el texto: '${conFoco.single().text}'",
        )
        assertTrue(conFoco.single().text.contains("ana"))
    }

    @Test
    fun `una lista vacia avisa de que es VOLATIL`() {
        val lineas = lineas(base().copy(contacts = emptyList()))
        val texto = lineas.joinToString("\n") { it.text }
        assertTrue(texto.contains("sin contactos"), texto)
        assertTrue(texto.contains("SOLO EN MEMORIA"), texto)
        assertTrue(texto.contains("se pierde al reiniciar") || texto.contains("se pierden al reiniciar"), texto)
    }

    @Test
    fun `la lista distingue cargando de error y de vacia`() {
        val cargando = lineas(base().copy(contactsLoaded = false)).joinToString("\n") { it.text }
        assertTrue(cargando.contains("cargando contactos"), cargando)

        val error = lineas(base().copy(contactsError = "CONTACTS_FAILED")).joinToString("\n") { it.text }
        assertTrue(error.contains("CONTACTS_FAILED"), error)
    }

    @Test
    fun `S y K son dos marcadores distintos`() {
        val sinNada = lineas(
            base().copy(contacts = listOf(fila().copy(hasSession = false, hasAckKey = false))),
        ).first { it.text.contains("ana") }.text
        assertTrue(sinNada.trimEnd().endsWith("--"), "sin sesion ni clave: '$sinNada'")

        val conSesion = lineas(base()).first { it.text.contains("ana") }.text
        assertTrue(conSesion.contains("S"), conSesion)
        assertTrue(conSesion.contains("K"), conSesion)
    }

    @Test
    fun `la cabecera muestra el enlace P2P y NUNCA ONLINE del par`() {
        val texto = lineas(base()).joinToString("\n") { it.text }
        assertTrue(texto.contains("P2P[o]"), texto)
        assertFalse(texto.contains("ONLINE"), "el enlace se llama por su glifo, no ONLINE: $texto")
    }

    @Test
    fun `un payload con ESC no mueve el cursor`() {
        val conversacion = ConversationView(
            peer = ana,
            title = "ana",
            messages = listOf(
                MessageRow(
                    messageId = MessageId.random(),
                    direction = MessageDirection.IN,
                    at = 1_700_000_000_000,
                    // Ataque real: borrar pantalla y escribir el portapapeles.
                    text = "hola\u001b[2J\u001b]52;c;c2VjcmV0\u0007mundo",
                    state = MessageState.DELIVERED,
                ),
            ),
        )
        val estado = base(Screen.CONVERSACION).copy(conversation = conversacion)
        val texto = lineas(estado).joinToString("\n") { it.text }

        assertFalse(texto.contains("\u001b"), "ningun ESC crudo llega al terminal: $texto")
        assertFalse(texto.contains("\u0007"), "ningun BEL crudo llega al terminal")
        assertTrue(texto.contains("^[[2J"), "la secuencia se muestra inerte: $texto")
        assertTrue(texto.contains("^[]52"), "OSC 52 se muestra inerte: $texto")
    }

    @Test
    fun `los estados por mensaje llevan etiqueta textual`() {
        val rows = listOf(
            MessageRow(MessageId.random(), MessageDirection.OUT, 1, "a", MessageState.SENT),
            MessageRow(MessageId.random(), MessageDirection.OUT, 2, "b", MessageState.DELIVERED),
            MessageRow(
                MessageId.random(), MessageDirection.OUT, 3, "c",
                MessageState.EXPIRED, failureCode = "ACK_TIMEOUT",
            ),
        )
        val estado = base(Screen.CONVERSACION).copy(
            conversation = ConversationView(ana, "ana", messages = rows),
        )
        val texto = lineas(estado).joinToString("\n") { it.text }

        assertTrue(texto.contains("[OUT]"), texto)
        assertTrue(texto.contains("[ENTREGADO]"), texto)
        assertTrue(texto.contains("[EXPIRADO ACK_TIMEOUT]"), texto)
    }

    @Test
    fun `un tombstone seleccionado ofrece R y D`() {
        val row = MessageRow(
            MessageId.random(), MessageDirection.OUT, 1, "x",
            MessageState.EXPIRED, failureCode = "ACK_TIMEOUT",
        )
        val estado = base(Screen.CONVERSACION).copy(
            conversation = ConversationView(ana, "ana", messages = listOf(row)),
            focus = Focus(FocusField.LIST, 0),
        )
        val texto = lineas(estado).joinToString("\n") { it.text }
        assertTrue(texto.contains("[R] reintentar"), texto)
        assertTrue(texto.contains("[D] descartar"), texto)
    }

    @Test
    fun `el overlay sin conexion dice que el relay NO guarda`() {
        val estado = base().copy(
            overlay = Overlay.Offline(
                link = LinkState.RECONNECTING,
                attempt = 3,
                nextRetryInMs = 4_000,
                lastError = "RELAY_NOT_AUTHORIZED",
            ),
        )
        val texto = lineas(estado).joinToString("\n") { it.text }
        assertTrue(texto.contains("SIN CONEXION"), texto)
        assertTrue(texto.contains("NO guarda"), texto)
        assertTrue(texto.contains("la cola es local"), texto)
        assertTrue(texto.contains("RELAY_NOT_AUTHORIZED"), texto)
        assertFalse(texto.contains("ONLINE"), "el overlay no promete entrega ni disponibilidad")
    }

    @Test
    fun `--ascii usa la misma metrica sin glifos Unicode`() {
        val estado = base()
        val texto = lineas(estado, ascii = true).joinToString("\n")
        assertTrue(texto.contains("|"), "separador ASCII: $texto")
        assertFalse(texto.contains("\u250c"), "sin esquina Unicode")
        assertFalse(texto.contains("\u2502"), "sin vertical Unicode")
        assertFalse(texto.contains("\u2500"), "sin regla Unicode")
        assertFalse(texto.contains("\u258c"), "sin marcador Unicode")
        assertAncho(estado, ascii = true)
    }

    @Test
    fun `una terminal pequena muestra el minimo y no una pantalla adaptada`() {
        val estado = base().copy(viewport = Size(40, 10))
        val texto = lineas(estado).joinToString("\n") { it.text }
        assertTrue(texto.contains("minimo 60x20"), texto)
        assertTrue(texto.contains("--no-tui"), texto)
    }

    @Test
    fun `la ayuda sale del keymap de la pantalla`() {
        val estado = base().copy(helpVisible = true)
        val texto = lineas(estado).joinToString("\n") { it.text }
        assertTrue(texto.contains("AYUDA"), texto)
        Keymap.bindings(Screen.INICIO).take(3).forEach { binding ->
            assertTrue(texto.contains(binding.label), "la ayuda lista '${binding.label}': $texto")
        }
    }

    @Test
    fun `COMPARTIR declara el minimo cuando el QR no cabe`() {
        val carrier = buildString { while (length < 469) append("aB3-_") }.take(469)
        val estado = base(Screen.COMPARTIR).copy(
            viewport = Size(80, 24),
            share = ShareView(carrier = carrier, byteLength = 469, oneTimePreKeys = 0),
        )
        val texto = lineas(estado).joinToString("\n") { it.text }
        assertTrue(texto.contains("no cabe"), texto)
        assertTrue(texto.contains("85x43"), texto)
        assertTrue(texto.contains(".km"), "ofrece la salida por fichero: $texto")
        assertAncho(estado)
    }

    @Test
    fun `DIAGNOSTICO filtra por canal y no promete presencia del par`() {
        val estado = base(Screen.DIAGNOSTICO).copy(
            logs = listOf(
                LogRow(LogLevel.INFO, "[RELAY]", "[RELAY] 2026-10-27 10:00:00 conexion ONLINE"),
                LogRow(LogLevel.INFO, "[SEND]", "[SEND] 2026-10-27 10:00:01 msg=abc"),
            ),
            logFilter = "[SEND]",
        )
        val texto = lineas(estado).joinToString("\n") { it.text }
        assertTrue(texto.contains("msg=abc"), texto)
        assertFalse(texto.contains("conexion ONLINE"), "el filtro deja fuera RELAY")
        assertTrue(texto.contains("NO es presencia del par"), texto)
    }

    @Test
    fun `IMPORTAR ofrece los tres portadores`() {
        val texto = lineas(base(Screen.IMPORTAR).copy(importDraft = ImportDraft()))
            .joinToString("\n") { it.text }
        assertTrue(texto.contains("1 texto"), texto)
        assertTrue(texto.contains("2 fichero .km"), texto)
        assertTrue(texto.contains("3 ver mi QR"), texto)

        val rechazo = lineas(
            base(Screen.IMPORTAR).copy(
                importDraft = ImportDraft(rejection = "KCE_INVALID"),
            ),
        ).joinToString("\n") { it.text }
        assertTrue(rechazo.contains("RECHAZADO KCE_INVALID"), rechazo)
    }

    @Test
    fun `la frase oculta no pinta palabras y el reveal las pinta`() {
        val oculta = lineas(base(Screen.FRASE)).joinToString("\n") { it.text }
        assertTrue(oculta.contains("OCULTA"), oculta)

        val visible = lineas(
            base(Screen.FRASE).copy(
                phrase = PhraseView(words = List(24) { "palabra$it" }, revealed = true),
            ),
        ).joinToString("\n") { it.text }
        assertTrue(visible.contains("palabra0"), visible)
        assertTrue(visible.contains("NO va a logs"), visible)
    }

    @Test
    fun `un evento del motor se pinta como aviso sin romper la caja`() {
        val reduced = ShellReducer.reduce(
            base(),
            ShellAction.Event(ClientEvent.Error("RELAY AUTH_FAIL")),
        )
        val texto = lineas(reduced.state).joinToString("\n") { it.text }
        assertTrue(texto.contains("RELAY AUTH_FAIL"), texto)
        assertAncho(reduced.state)
    }

    // ------------------------------------------------------------------
    // CRITICAL-1 de #446: el QR al minimo se pintaba recortado sin aviso
    // ------------------------------------------------------------------

    /** El portador real de Android: 469 B que fuerzan 85x43 con medias celdas. */
    private fun portadorReal(): String =
        buildString { while (length < 469) append("aB3-_") }.take(469)

    private fun compartir(viewport: Size, showText: Boolean = false) = base(Screen.COMPARTIR).copy(
        viewport = viewport,
        share = ShareView(
            carrier = portadorReal(),
            byteLength = 469,
            oneTimePreKeys = 0,
            showText = showText,
        ),
    )

    private fun filasQr(): List<String> =
        (QrRenderer.layout(portadorReal(), Size(QrRenderer.MIN_COLUMNS, QrRenderer.MIN_ROWS)) as QrLayout.Fits)
            .lines

    @Test
    fun `COMPARTIR a 85x43 pinta el QR COMPLETO sin marco`() {
        val estado = compartir(Size(85, 43))
        val lineas = lineas(estado)
        val esperado = filasQr()

        assertEquals(43, lineas.size, "43 filas exactas: el QR ocupa la pantalla")
        assertEquals(esperado, lineas.map { it.text }, "cada fila es una fila intacta del QR")
        assertAncho(estado)
        assertFalse(
            lineas.any { it.text.contains("\u250c") || it.text.contains("\u2502") || it.text.contains("\u2514") },
            "sin marco de caja al minimo",
        )
        val texto = lineas.joinToString("\n") { it.text }
        assertFalse(texto.contains("lineas)"), "no se sustituyen filas por el aviso de recorte: $texto")
        assertFalse(texto.contains("no cabe"), "el QR SI cabe a 85x43")
    }

    @Test
    fun `COMPARTIR por encima del minimo conserva el QR entero y recupera el marco`() {
        val estado = compartir(Size(89, 47))
        val lineas = lineas(estado)
        val texto = lineas.joinToString("\n") { it.text }

        assertTrue(texto.contains("portador 469 B"), texto)
        assertFalse(texto.contains("lineas)"), "sin recorte de filas: $texto")
        filasQr().forEach { fila ->
            assertTrue(lineas.any { it.text.contains(fila) }, "falta una fila del QR: '$fila'")
        }
        // El criterio preservado: a 89x47 (85+4 x 43+4) el marco VUELVE. La
        // asercion se habia borrado y el QR se quedaba sin marco en silencio.
        assertTrue(texto.contains("\u250c"), "el marco vuelve a 89x47: $texto")
        assertTrue(texto.contains("\u2514"), "el marco cierra por abajo: $texto")
        assertTrue(texto.contains("\u2502"), "el marco tiene lados: $texto")
        assertAncho(estado)
    }

    @Test
    fun `COMPARTIR por debajo del minimo declara 85x43 y no pinta bloques`() {
        val estado = compartir(Size(84, 42))
        val texto = lineas(estado).joinToString("\n") { it.text }

        assertTrue(texto.contains("no cabe"), texto)
        assertTrue(texto.contains("85x43"), texto)
        assertTrue(texto.contains(".km"), texto)
        assertFalse(
            listOf("\u2588", "\u2580", "\u2584").any { texto.contains(it) },
            "no se pinta un QR a medias: $texto",
        )
        assertAncho(estado)
    }

    @Test
    fun `COMPARTIR con texto activo al minimo pinta el PORTADOR envuelto, no el QR`() {
        val estado = compartir(Size(85, 43), showText = true)
        val texto = lineas(estado).joinToString("\n") { it.text }

        assertTrue(texto.contains("aB3-_"), "se ve el portador: $texto")
        assertFalse(
            listOf("\u2588", "\u2580", "\u2584").any { texto.contains(it) },
            "no se pinta un QR a medias: $texto",
        )
        assertAncho(estado)
        assertEquals(43, lineas(estado).size)
    }

    @Test
    fun `IMPORTAR viendo mi QR a 85x43 tampoco recorta el QR`() {
        val estado = base(Screen.IMPORTAR).copy(
            viewport = Size(85, 43),
            importDraft = ImportDraft(source = ImportSource.OWN_QR),
            share = ShareView(carrier = portadorReal(), byteLength = 469, oneTimePreKeys = 0),
        )
        val lineas = lineas(estado)

        assertEquals(43, lineas.size)
        assertEquals(filasQr(), lineas.map { it.text })
        assertAncho(estado)
    }
}
