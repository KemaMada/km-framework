package com.km.desktop.net

import com.km.config.IceConfig
import com.km.model.IdentityId
import com.km.node.ChainedTransport
import com.km.node.EstablishmentState
import com.km.node.Medium
import com.km.node.P2pProvider
import com.km.node.P2pReason
import com.km.node.P2pRole
import com.km.node.PeerSignalPort
import com.km.node.PeerTransportManager
import com.km.node.TransportBackend
import com.km.node.TransportError
import com.km.node.TransportEstablishment
import com.km.node.TransportEventCallbacks
import com.km.node.TransportResult
import com.km.transport.JvmRelayTransport
import com.km.transport.p2p.P2pComponents
import com.km.transport.p2p.PeerRouteBackend
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertIs

/**
 * S7 — El arranque del escritorio: el modo se lee de verdad y `off`/`sin ICE`
 * NO construyen el medio.
 *
 * El instrumento es un contador de construcciones inyectado en
 * [P2pWiring.compose]: si `off` construyera el backend y despues lo ignorara, el
 * test lo veria. Es el mismo invariante que el baseline del slice S10 mide con
 * el contador de `RealWebRtcTransport`.
 */
class DesktopMediumWiringTest {

    private val local = IdentityId("11".repeat(32))

    private fun relay() = JvmRelayTransport(
        identity = local,
        ed25519PublicKey = ByteArray(32),
        ed25519PrivateKey = ByteArray(32),
    )

    private class FakeBackend(override val name: String) : TransportBackend {
        override var eventCallbacks: TransportEventCallbacks = TransportEventCallbacks()
        override fun initialize() = TransportResult.Success(Unit)
        override fun onCreateBinding(localIdentity: IdentityId, remotePeerId: IdentityId) = TransportResult.Success(Unit)
        override fun onLocalSdp(localIdentity: IdentityId, remotePeerId: IdentityId, sdp: String) = TransportResult.Success(Unit)
        override fun onRemoteSdp(localIdentity: IdentityId, remotePeerId: IdentityId, sdp: String) = TransportResult.Success(Unit)
        override fun onIceCandidate(localIdentity: IdentityId, remotePeerId: IdentityId, candidate: String) = TransportResult.Success(Unit)
        override fun sendData(remotePeerId: IdentityId, data: ByteArray) = TransportResult.Success(Unit)
        override fun onCloseBinding(localIdentity: IdentityId, remotePeerId: IdentityId) = TransportResult.Success(Unit)
        override fun shutdown() = TransportResult.Success(Unit)
    }

    private class FakeProvider(private val backend: FakeBackend) : P2pProvider {
        override fun backend(): TransportBackend = backend
        override fun link(remotePeerId: IdentityId, role: P2pRole, signals: PeerSignalPort): TransportEstablishment =
            object : TransportEstablishment {
                override val transportName: String = Medium.P2P.linkName
                override var state: EstablishmentState = EstablishmentState.CREATED
                    private set
                override fun initialize() = TransportResult.Success(Unit)
                override fun createBinding(localIdentity: IdentityId, remotePeerId: IdentityId) = TransportResult.Success(Unit)
                override fun negotiate(): TransportResult<Unit> {
                    state = EstablishmentState.NEGOTIATING
                    return TransportResult.Success(Unit)
                }
                override fun awaitReady(timeoutMs: Long): TransportResult<Unit> {
                    state = EstablishmentState.FAILED
                    return TransportResult.Failure(TransportError.BACKEND_SEND_FAILED, "sin traversal")
                }
                override fun abort() {
                    state = EstablishmentState.CLOSED
                }
            }
    }

    /** Fabricas que cuentan construcciones y nunca cargan JNI. */
    private class CountingFactory {
        val calls = AtomicInteger(0)
        fun build(): (IceConfig, (IdentityId) -> com.km.node.PeerContext?) -> P2pComponents =
            { _, _ ->
                calls.incrementAndGet()
                val backend = FakeBackend("p2p")
                val manager = PeerTransportManager(backend)
                P2pComponents.of(FakeProvider(backend), manager)
            }
    }

    // `auto` exige TURN: un STUN solo no autoriza el intento P2P (fuga de IP).
    private val ice = IceConfig.of(
        stunUrls = listOf("stun:stun.example.org:3478"),
        turnUrls = listOf("turn:turn.example.org:3478"),
        turnUsername = "kmdesktop",
        turnCredential = "no-es-un-secreto-real",
    )

    private val soloStun = IceConfig.of(stunUrls = listOf("stun:stun.example.org:3478"))

    // ------------------------------------------------------------------
    // Plan
    // ------------------------------------------------------------------

    @Test
    @DisplayName("7.4-09 off no intenta P2P por mucho ICE que haya")
    fun offNoIntenta() {
        assertEquals(P2pPlan.Off(), P2pWiring.plan(P2pMode.OFF, ice))
    }

    @Test
    @DisplayName("7.4-10 auto sin ICE se OMITE con p2p_no_ice, no falla")
    fun autoSinIce() {
        val plan = P2pWiring.plan(P2pMode.AUTO, IceConfig.hostOnly())
        assertIs<P2pPlan.Skip>(plan)
        assertEquals(P2pReason.NO_ICE, (plan as P2pPlan.Skip).reason)
    }

    @Test
    @DisplayName("7.4-10b auto con SOLO STUN no intenta P2P: motivo p2p_no_turn")
    fun autoSoloStunNoIntenta() {
        val plan = P2pWiring.plan(P2pMode.AUTO, soloStun)
        assertIs<P2pPlan.Skip>(plan)
        assertEquals(P2pReason.NO_TURN, (plan as P2pPlan.Skip).reason)
    }

    @Test
    @DisplayName("7.4-10c auto con TURN SI intenta: el relay habilita el medio directo")
    fun autoConTurnIntenta() {
        assertIs<P2pPlan.Attempt>(P2pWiring.plan(P2pMode.AUTO, ice))
    }

    @Test
    @DisplayName("7.4-11 on sin ICE FALLA con p2p_on_no_ice")
    fun onSinIceFalla() {
        val plan = P2pWiring.plan(P2pMode.ON, IceConfig.hostOnly())
        assertIs<P2pPlan.Fail>(plan)
        assertEquals(P2pReason.ON_NO_ICE, (plan as P2pPlan.Fail).reason)
    }

    @Test
    @DisplayName("7.4-11b on con SOLO STUN FALLA con p2p_on_no_turn y mensaje claro")
    fun onSoloStunFalla() {
        val plan = P2pWiring.plan(P2pMode.ON, soloStun)
        assertIs<P2pPlan.Fail>(plan)
        assertEquals(P2pReason.ON_NO_TURN, (plan as P2pPlan.Fail).reason)
        assertTrue((plan.detail).contains("TURN"), "el mensaje debe decir que falta TURN: ${plan.detail}")
    }

    @Test
    @DisplayName("7.4-12 el modo y el ICE de PRODUCCION se resuelven con la tabla del flag")
    fun planDesdeEntorno() {
        val modo = P2pMode.fromEnv { name -> if (name == P2pMode.ENV_VAR) "auto" else null }
        val sinIce = IceConfig.fromEnv { null }
        assertIs<P2pPlan.Skip>(P2pWiring.plan(modo, sinIce))

        val conIce = IceConfig.fromEnv { name ->
            when (name) {
                IceConfig.ENV_STUN_URLS -> "stun:stun.example.org:3478"
                IceConfig.ENV_TURN_URLS -> "turn:turn.example.org:3478"
                IceConfig.ENV_TURN_USERNAME -> "kmdesktop"
                IceConfig.ENV_TURN_CREDENTIAL -> "no-es-un-secreto-real"
                else -> null
            }
        }
        assertIs<P2pPlan.Attempt>(P2pWiring.plan(modo, conIce))
    }

    @Test
    @DisplayName("7.4-12b con off un KM_ICE_* invalido NO tumba el arranque: el rollback es incondicional")
    fun offIgnoraIceInvalido() {
        val env: (String) -> String? = { name ->
            when (name) {
                P2pMode.ENV_VAR -> "off"
                IceConfig.ENV_STUN_URLS -> "http://mal.example"
                else -> null
            }
        }
        // No lanza: `off` no parsea ICE porque no lo usa.
        assertEquals(P2pPlan.Off(), P2pWiring.planFromEnv(env))
    }

    @Test
    @DisplayName("7.4-12c con auto un KM_ICE_* invalido SI es error de configuracion")
    fun autoConIceInvalidoFalla() {
        val env: (String) -> String? = { name ->
            when (name) {
                P2pMode.ENV_VAR -> "auto"
                IceConfig.ENV_STUN_URLS -> "http://mal.example"
                else -> null
            }
        }
        assertThrows(IllegalArgumentException::class.java) { P2pWiring.planFromEnv(env) }
    }

    // ------------------------------------------------------------------
    // Composicion
    // ------------------------------------------------------------------

    @Test
    @DisplayName("7.4-13 con off el contador de construccion del medio es CERO")
    fun offNoConstruye() {
        val motor = relay()
        val fabrica = CountingFactory()
        val bundle = P2pWiring.compose(motor, local, P2pPlan.Off(), fabrica.build())

        assertEquals(0, fabrica.calls.get(), "off no puede construir el backend P2P")
        assertFalse(bundle.p2pEnabled)
        assertSame(motor, bundle.transport)
        assertFalse(bundle.transport is ChainedTransport)
        assertSame(motor, bundle.manager.backend())
        assertEquals(P2pReason.OFF, bundle.modeReason)
    }

    @Test
    @DisplayName("7.4-14 con auto sin ICE el contador es CERO y el motivo es p2p_no_ice")
    fun autoSinIceNoConstruye() {
        val fabrica = CountingFactory()
        val bundle = P2pWiring.compose(
            relay(), local,
            P2pWiring.plan(P2pMode.AUTO, IceConfig.hostOnly()),
            fabrica.build(),
        )

        assertEquals(0, fabrica.calls.get(), "sin ICE no hay nada que construir")
        assertFalse(bundle.p2pEnabled)
        assertEquals(P2pReason.NO_ICE, bundle.modeReason)
        assertEquals(IceConfig.hostOnly(), bundle.ice, "sin ICE la configuracion es host-only, no inventada")
    }

    @Test
    @DisplayName("7.4-15 con auto y ICE se construye UNA vez y el manager va sobre el router")
    fun autoConIceCompone() {
        val motor = relay()
        val fabrica = CountingFactory()
        val bundle = P2pWiring.compose(
            motor, local,
            P2pWiring.plan(P2pMode.AUTO, ice),
            fabrica.build(),
        )

        assertEquals(1, fabrica.calls.get())
        assertTrue(bundle.p2pEnabled)
        assertTrue(bundle.transport is ChainedTransport)
        assertIs<PeerRouteBackend>(bundle.manager.backend())
        assertSame(motor, (bundle.manager.backend() as PeerRouteBackend).relay)
        assertSame(motor, bundle.stack.transport)
        assertSame(bundle.manager, bundle.stack.manager)
        assertEquals(ice, bundle.ice)
        assertEquals(null, bundle.modeReason, "con configuracion no hay motivo hasta el primer intento")
    }

    @Test
    @DisplayName("7.4-16 el medio del arranque es relay y no se promete P2P")
    fun medioInicialHonesto() {
        val peer = IdentityId("22".repeat(32))
        val bundle = P2pWiring.compose(relay(), local, P2pPlan.Off(), CountingFactory().build())
        assertEquals(Medium.RELAY, bundle.mediumFor(peer), "aun sin enlace, los datos van por el relay")
        assertEquals(P2pReason.OFF, bundle.reasonFor(peer), "y el motivo no se inventa")
    }
}
