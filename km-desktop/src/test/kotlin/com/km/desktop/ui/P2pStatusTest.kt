package com.km.desktop.ui

import com.km.config.IceConfig
import com.km.desktop.ui.theme.Style
import com.km.desktop.ui.widget.StatusBar
import com.km.model.IdentityId
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * S7 — El medio de datos se dice con OTRA etiqueta que el glifo del enlace y
 * sin credenciales.
 *
 * Lo que se impide:
 *  - reutilizar `P2P[o|~|x|?]` para el medio de datos;
 *  - decir `(sin ICE)` cuando el fallo fue de traversal;
 *  - que una credencial de TURN llegue a la barra o a DIAGNOSTICO.
 */
class P2pStatusTest {

    private val centinela = "centinela-7f3a-credential"

    private val ice = IceConfig.of(
        stunUrls = listOf("stun:stun.example.org:3478"),
        turnUrls = listOf("turn:turn.example.org:3478"),
        turnUsername = "usuario-turn",
        turnCredential = centinela,
    )

    // ------------------------------------------------------------------
    // Etiqueta
    // ------------------------------------------------------------------

    @Test
    @DisplayName("7.4-17 cada medio tiene su etiqueta y ninguna es el glifo")
    fun etiquetas() {
        assertEquals("via P2P", P2pStatus.mediumLabel("P2P", "p2p_ready"))
        assertEquals("via relay", P2pStatus.mediumLabel("RELAY", "p2p_timeout"))
        assertEquals("via relay (sin ICE)", P2pStatus.mediumLabel("RELAY", "p2p_no_ice"))
        assertEquals("via relay", P2pStatus.mediumLabel("RELAY", "p2p_off"))
        assertNull(P2pStatus.mediumLabel(null, null), "sin dato no se inventa un medio")
        assertFalse(P2pStatus.mediumLabel("P2P", "p2p_ready")!!.contains("P2P["), "el glifo no se reutiliza")
    }

    @Test
    @DisplayName("7.4-18 la barra anade el medio junto al glifo, que sigue intacto")
    fun barraConMedio() {
        val estado = ShellState(
            viewport = Size(120, 40),
            status = StatusLine(
                link = LinkState.ONLINE,
                endpoint = "wss://relay.example/km",
                localFingerprint = "f".repeat(16) + "\u2026",
                localIdentityId = "f".repeat(64),
                sessions = 1,
                ackKeys = 1,
                medium = "P2P",
                mediumReason = "p2p_ready",
            ),
            conversation = ConversationView(peer = IdentityId("a".repeat(64)), title = "ana"),
        )

        val texto = StatusBar.row(estado, width = 100, ascii = false, nowMs = 0L).text

        assertTrue(texto.contains("via P2P"), texto)
        assertTrue(texto.contains("P2P[o] conectado"), "el glifo sigue siendo el enlace: $texto")
        assertFalse(texto.contains("ONLINE"), texto)
    }

    @Test
    @DisplayName("7.4-19 auto sin ICE dice relay (sin ICE) y no promete P2P")
    fun sinIceEnLaBarra() {
        val estado = ShellState(
            viewport = Size(120, 40),
            status = StatusLine(
                link = LinkState.ONLINE,
                localFingerprint = "f".repeat(16) + "\u2026",
                localIdentityId = "f".repeat(64),
                medium = "RELAY",
                mediumReason = "p2p_no_ice",
            ),
            conversation = ConversationView(peer = IdentityId("a".repeat(64)), title = "ana"),
        )

        val texto = StatusBar.row(estado, width = 100, ascii = false, nowMs = 0L).text

        assertTrue(texto.contains("via relay (sin ICE)"), texto)
        assertFalse(texto.contains("via P2P"), texto)
        assertEquals(Style.MUTED, StatusBar.row(estado, width = 100, ascii = false, nowMs = 0L).style)
    }

    // ------------------------------------------------------------------
    // DIAGNOSTICO
    // ------------------------------------------------------------------

    @Test
    @DisplayName("7.4-20 la linea de diagnostico lleva modo, motivo, medio y URLs redactadas")
    fun diagnosticoCompleto() {
        val linea = P2pStatus.diagnostic("auto", "p2p_timeout", "RELAY", ice)

        assertTrue(linea.contains("modo=auto"), linea)
        assertTrue(linea.contains("motivo=p2p_timeout"), linea)
        assertTrue(linea.contains("medio=RELAY"), linea)
        assertTrue(linea.contains("stun:stun.example.org:3478"), linea)
        assertTrue(linea.contains("turn:turn.example.org:3478"), linea)
    }

    @Test
    @DisplayName("7.4-21 CENTINELA: la credencial y el usuario de TURN no aparecen")
    fun centinela() {
        val linea = P2pStatus.diagnostic("auto", "p2p_ready", "P2P", ice)

        assertFalse(linea.contains(centinela), "credencial en diagnostico: $linea")
        assertFalse(linea.contains("usuario-turn"), "usuario de TURN en diagnostico: $linea")
        assertFalse(ice.toString().contains(centinela), ice.toString())
        assertFalse(ice.redactedUrls().any { it.contains(centinela) })
    }

    @Test
    @DisplayName("7.4-22 host-only se declara en vez de dejar la lista vacia")
    fun hostOnlyDeclarado() {
        val linea = P2pStatus.diagnostic("off", "p2p_off", "RELAY", IceConfig.hostOnly())
        assertTrue(linea.contains("host-only"), linea)
    }
}
