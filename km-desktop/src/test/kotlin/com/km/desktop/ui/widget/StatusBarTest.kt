package com.km.desktop.ui.widget

import com.km.desktop.ui.ConversationView
import com.km.desktop.ui.LinkState
import com.km.desktop.ui.ShellState
import com.km.desktop.ui.Size
import com.km.desktop.ui.StatusLine
import com.km.desktop.ui.theme.Style
import com.km.model.IdentityId
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * M5.5 — La barra de estado.
 *
 * ## QUE SE AFIRMA
 *
 * - A 120 columnas caben identidad, relay, enlace, presencia del par,
 *   contadores y aviso de volatilidad.
 * - A 60 columnas se sacrifica el endpoint, NUNCA el aviso de volatilidad ni la
 *   honestidad de la presencia (`par[?]`, jamas ONLINE del par).
 * - Un enlace caido tiñe la barra con DANGER; el texto ya lo dice en MONO.
 * - El spinner solo existe mientras hay un efecto en vuelo.
 */
class StatusBarTest {

    private val ana = IdentityId("a".repeat(64))

    private fun estado(link: LinkState = LinkState.ONLINE) = ShellState(
        viewport = Size(120, 40),
        status = StatusLine(
            link = link,
            endpoint = "wss://relay.example/km",
            localFingerprint = "f".repeat(16) + "\u2026",
            localIdentityId = "f".repeat(64),
            sessions = 3,
            ackKeys = 2,
        ),
        conversation = ConversationView(peer = ana, title = "ana"),
    )

    @Test
    fun `a 120 columnas cabe todo y nunca dice ONLINE del par`() {
        val texto = StatusBar.row(estado(), width = 88, ascii = false, nowMs = 0L).text
        assertTrue(texto.contains("yo ffffffff\u2026"), texto)
        assertTrue(texto.contains("relay wss://"), texto)
        assertTrue(texto.contains("P2P[o] conectado"), texto)
        assertTrue(texto.contains("par[?]"), "presencia desconocida: $texto")
        assertTrue(texto.contains("S3 K2"), texto)
        assertTrue(texto.contains("VOL\u00c1TIL"), texto)
        assertFalse(texto.contains("ONLINE"), texto)
    }

    @Test
    fun `a 60 columnas se omite el endpoint pero no la volatilidad`() {
        val texto = StatusBar.row(estado(), width = 58, ascii = true, nowMs = 0L).text
        assertTrue(texto.contains("VOLATIL"), texto)
        assertTrue(texto.contains("par[?]"), texto)
        assertTrue(texto.contains("P2P[o] conectado"), texto)
        assertFalse(texto.contains("relay "), "el endpoint es lo primero que se va: $texto")
        assertTrue(com.km.desktop.ui.Sanitizer.width(texto) <= 58, "cabe en 58: $texto")
    }

    @Test
    fun `un enlace caido usa DANGER y el texto lo dice`() {
        val fila = StatusBar.row(estado(LinkState.DISCONNECTED), width = 88, ascii = false, nowMs = 0L)
        assertEquals(Style.DANGER, fila.style)
        assertTrue(fila.text.contains("P2P[x] sin conexion"), fila.text)
    }

    @Test
    fun `el spinner solo gira con un efecto en vuelo`() {
        val quieto = StatusBar.row(estado(LinkState.ONLINE), width = 88, ascii = true, nowMs = 0L)
        assertTrue(quieto.text.startsWith("yo "), quieto.text)

        val enviando = estado().copy(
            conversation = estado().conversation!!.copy(sending = true),
        )
        val frame0 = StatusBar.row(enviando, width = 88, ascii = true, nowMs = 0L).text
        val frame120 = StatusBar.row(enviando, width = 88, ascii = true, nowMs = 120L).text
        assertEquals("|", frame0.take(1), frame0)
        assertEquals("/", frame120.take(1), frame120)
        assertTrue(frame0.contains("yo "), frame0)
    }
}
