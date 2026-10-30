package com.km.desktop.net

import com.km.config.IceConfig
import com.km.desktop.ui.LinkState
import com.km.desktop.ui.P2pStatus
import com.km.desktop.ui.ShellState
import com.km.desktop.ui.Size
import com.km.desktop.ui.StatusLine
import com.km.desktop.ui.widget.StatusBar
import com.km.model.IdentityId
import com.km.node.ChainedTransport
import com.km.node.P2pMode
import com.km.node.P2pPlan
import com.km.transport.JvmRelayTransport
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * S10 — El BASELINE `off`: el camino actual, sin construccion de nada P2P.
 *
 * Este test existe para que "off sigue igual" no sea una afirmacion del
 * `apply-progress` sino una guardia: si alguien construyera el medio P2P en
 * `off` y despues lo ignorara, el contador de construcciones lo veria aunque
 * los tests de comportamiento pasaran.
 */
class OffBaselineTest {

    private val local = IdentityId("11".repeat(32))

    private fun relay() = JvmRelayTransport(
        identity = local,
        ed25519PublicKey = ByteArray(32),
        ed25519PrivateKey = ByteArray(32),
    )

    @Test
    @DisplayName("10.3 sin entorno el modo es off y el ICE es host-only")
    fun sinEntornoEsOff() {
        assertEquals(P2pMode.OFF, P2pMode.fromEnv { null })
        assertTrue(IceConfig.fromEnv { null }.isHostOnly)
        assertEquals(P2pReasonOff, P2pWiring.plan(P2pMode.OFF, IceConfig.fromEnv { null }).let {
            (it as P2pPlan.Off).reason
        })
    }

    @Test
    @DisplayName("10.4 off no construye RealWebRtcTransport ni cadena; el transporte es el motor")
    fun offByteIdentico() {
        val motor = relay()
        val bundle = P2pWiring.compose(
            relay = motor,
            identity = local,
            plan = P2pPlan.Off(),
            providerFactory = { _, _ -> error("off no puede construir el medio P2P") },
        )

        assertFalse(bundle.p2pEnabled)
        assertEquals(0, bundle.mountedChains(), "off no monta cadenas")
        assertSame(motor, bundle.transport, "el nucleo recibe el motor, como antes")
        assertSame(motor, bundle.manager.backend(), "y el manager sigue sobre el motor")
        assertFalse(bundle.transport is ChainedTransport)
    }

    @Test
    @DisplayName("10.5 la barra en off dice via relay y jamas via P2P")
    fun barraOffHonesta() {
        val snapshot = P2pStatus.mediumLabel("RELAY", "p2p_off")
        assertEquals("via relay", snapshot)
        assertFalse(snapshot!!.contains("P2P"))

        val estado = ShellState(
            viewport = Size(120, 40),
            status = StatusLine(
                link = LinkState.ONLINE,
                localFingerprint = "f".repeat(16) + "\u2026",
                localIdentityId = "f".repeat(64),
                medium = "RELAY",
                mediumReason = "p2p_off",
            ),
        )
        val texto = StatusBar.row(estado, width = 100, ascii = false, nowMs = 0L).text
        assertTrue(texto.contains("via relay"), texto)
        assertFalse(texto.contains("via P2P"), texto)
    }

    private companion object {
        /** `P2pReason.OFF` sin acoplar el test al nombre del objeto de core. */
        const val P2pReasonOff = "p2p_off"
    }
}
