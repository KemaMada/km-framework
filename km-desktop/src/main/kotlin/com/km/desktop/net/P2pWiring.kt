package com.km.desktop.net

import com.km.config.IceConfig
import com.km.model.IdentityId
import com.km.node.P2pReason
import com.km.node.PeerContext
import com.km.node.PeerTransportManager
import com.km.transport.JvmRelayTransport
import com.km.transport.p2p.ClientTransportBundle
import com.km.transport.p2p.ClientTransportFactory
import com.km.transport.p2p.P2pComponents
import com.km.webrtc.RealWebRtcTransport
import com.km.webrtc.WebRtcP2pProvider

/**
 * Que hacer con el tier P2P en este arranque.
 *
 * ## Por que un plan y no un booleano
 *
 * `P2pMode` dice la INTENCION; el plan dice lo que se puede hacer con la
 * configuracion que hay. Separarlos deja el arranque con tres finales distintos
 * y honestos —apagado, omitido con motivo, o intento— sin que `DesktopMain`
 * tenga que repetir la tabla.
 */
sealed class P2pPlan {

    /** `off`: relay-only, byte a byte. */
    data class Off(val reason: String = P2pReason.OFF) : P2pPlan()

    /** No se intenta P2P, pero se declara por que. */
    data class Skip(val reason: String) : P2pPlan()

    /** Configuracion invalida para el modo elegido: el arranque falla. */
    data class Fail(val reason: String, val detail: String) : P2pPlan()

    /** Hay que construir el medio P2P con esta configuracion ICE. */
    data class Attempt(val ice: IceConfig) : P2pPlan()
}

/**
 * Resuelve el modo y compone la pieza unica del cliente.
 *
 * ## El invariante que protege
 *
 * `off` NO construye `RealWebRtcTransport`: esta clase no llama a
 * [realComponents] y la fabrica de `km-transport` recibe `p2p = null`. En `auto`
 * sin ICE tampoco, y en `auto` con STUN solo tampoco (`p2p_no_turn`): un
 * candidato `host`/`srflx` expondria la IP del escritorio sin garantizar
 * traversal. Solo `Attempt` construye el medio, y `Attempt` exige TURN.
 *
 * ## Por que la fabrica de componentes es inyectable
 *
 * Para que un test pueda contar construcciones sin cargar el nativo de
 * libwebrtc. En produccion es [realComponents], que es la unica funcion que
 * nombra `km-webrtc`.
 */
object P2pWiring {

    /** Decide el plan. No toca el entorno: recibe modo y configuracion ya resueltos. */
    fun plan(mode: P2pMode, ice: IceConfig): P2pPlan = when (mode) {
        P2pMode.OFF -> P2pPlan.Off()
        P2pMode.AUTO -> when {
            // Sin ICE no hay nada que intentar.
            !ice.isConfigured -> P2pPlan.Skip(P2pReason.NO_ICE)
            // STUN solo NO basta: descubre la IP publica y no garantiza
            // traversal. Intentar P2P expondria candidatos host/srflx al par.
            !ice.hasRelayServer -> P2pPlan.Skip(P2pReason.NO_TURN)
            else -> P2pPlan.Attempt(ice)
        }
        P2pMode.ON -> when {
            !ice.isConfigured -> P2pPlan.Fail(
                P2pReason.ON_NO_ICE,
                "KM_DESKTOP_P2P=on exige KM_ICE_STUN_URLS y/o KM_ICE_TURN_URLS; sin ICE no hay P2P",
            )
            !ice.hasRelayServer -> P2pPlan.Fail(
                P2pReason.ON_NO_TURN,
                "KM_DESKTOP_P2P=on exige un servidor TURN (turn:/turns:) con credenciales; " +
                    "solo STUN expone la IP del escritorio y no garantiza traversal",
            )
            else -> P2pPlan.Attempt(ice)
        }
    }

    /**
     * Resuelve modo y plan DIRECTAMENTE del entorno.
     *
     * ## Por que existe esta funcion
     *
     * Con `off`, `KM_ICE_*` no se usa para nada: una URL invalida ahi no debe
     * impedir el rollback. Si el llamante hiciera `IceConfig.fromEnv()` antes
     * de mirar el modo, un typo en una variable IGNORADA tumbaria el arranque
     * (exit 2) y el rollback dejaria de ser incondicional. Aqui el modo se lee
     * PRIMERO y `off` no parsea ICE.
     *
     * @throws IllegalArgumentException si el modo no es `off` y el ICE es invalido.
     */
    fun planFromEnv(
        env: (String) -> String? = { name -> System.getenv(name) },
    ): P2pPlan {
        val mode = P2pMode.fromEnv(env)
        if (mode == P2pMode.OFF) return P2pPlan.Off()
        return plan(mode, IceConfig.fromEnv(env))
    }

    /**
     * Compone el transporte del nucleo segun [plan].
     *
     * @param providerFactory recibe la configuracion ICE y el resolutor de
     *   `PeerContext`. El resolutor se cablea contra el manager del bundle y se
     *   invoca MUCHO despues de componer, de modo que puede leer bindings que
     *   aun no existen en este instante.
     */
    fun compose(
        relay: JvmRelayTransport,
        identity: IdentityId,
        plan: P2pPlan,
        providerFactory: (IceConfig, (IdentityId) -> PeerContext?) -> P2pComponents = { ice, contextFor ->
            realComponents(ice, identity, contextFor)
        },
        now: () -> Long = System::currentTimeMillis,
    ): ClientTransportBundle {
        val ice = when (plan) {
            is P2pPlan.Attempt -> plan.ice
            else -> IceConfig.hostOnly()
        }
        val initialReason = when (plan) {
            is P2pPlan.Off -> plan.reason
            is P2pPlan.Skip -> plan.reason
            is P2pPlan.Fail -> plan.reason
            is P2pPlan.Attempt -> null
        }

        lateinit var bundle: ClientTransportBundle
        val components = if (plan is P2pPlan.Attempt) {
            providerFactory(ice) { peer -> bundle.manager.getBinding(identity, peer)?.peerContext }
        } else {
            null
        }
        bundle = ClientTransportFactory(
            relay = relay,
            identity = identity,
            p2p = components,
            ice = ice,
            initialReason = initialReason,
            now = now,
        ).compose()
        return bundle
    }

    /**
     * La unica funcion del escritorio que nombra el medio real.
     *
     * El `RealWebRtcTransport` es UNO por proceso: ya multiplexa por
     * `(localIdentity, remotePeerId)`.
     */
    private fun realComponents(
        ice: IceConfig,
        identity: IdentityId,
        peerContextFor: (IdentityId) -> PeerContext?,
    ): P2pComponents {
        val transport = RealWebRtcTransport(ice)
        val manager = PeerTransportManager(transport)
        val provider = WebRtcP2pProvider(
            manager = manager,
            localIdentity = identity,
            transport = transport,
            peerContextFor = peerContextFor,
        )
        return P2pComponents.of(provider, manager)
    }
}
