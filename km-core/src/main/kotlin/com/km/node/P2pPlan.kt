package com.km.node

import com.km.config.IceConfig

/**
 * Que hacer con el tier P2P en este arranque.
 *
 * ## Por que un plan y no un booleano
 *
 * [P2pMode] dice la INTENCION; el plan dice lo que se puede hacer con la
 * configuracion que hay. Separarlos deja el arranque con tres finales distintos
 * y honestos —apagado, omitido con motivo, o intento— sin que cada cliente
 * tenga que repetir la tabla.
 *
 * ## El invariante que protege
 *
 * [Off] y [Skip] NO construyen el backend: el cliente recibe `p2p = null`. En
 * `auto` con STUN solo tampoco ([Skip] con `p2p_no_turn`): un candidato
 * `host`/`srflx` expondria la IP sin garantizar traversal. Solo [Attempt]
 * construye el medio, y [Attempt] exige TURN.
 */
sealed class P2pPlan {

    /** Modo apagado: relay-only, byte a byte. */
    data class Off(val reason: String = P2pReason.OFF) : P2pPlan()

    /** No se intenta P2P, pero se declara por que. */
    data class Skip(val reason: String) : P2pPlan()

    /** Configuracion invalida para el modo elegido: el arranque falla. */
    data class Fail(val reason: String, val detail: String) : P2pPlan()

    /** Hay que construir el medio P2P con esta configuracion ICE. */
    data class Attempt(val ice: IceConfig) : P2pPlan()

    companion object {

        /**
         * Resuelve el plan a partir del modo y la configuracion ICE.
         *
         * @param envVarName nombre visible de la variable/ajuste, solo para el
         *   mensaje de error de [Fail]. No cambia la decision.
         */
        fun resolve(
            mode: P2pMode,
            ice: IceConfig,
            envVarName: String = P2pMode.ENV_VAR,
        ): P2pPlan = when (mode) {
            P2pMode.OFF -> Off()

            P2pMode.AUTO -> when {
                // Sin ICE no hay nada que intentar.
                !ice.isConfigured -> Skip(P2pReason.NO_ICE)
                // STUN solo NO basta: descubre la IP publica y no garantiza
                // traversal. Intentar P2P expondria candidatos host/srflx.
                !ice.hasRelayServer -> Skip(P2pReason.NO_TURN)
                else -> Attempt(ice)
            }

            P2pMode.ON -> when {
                !ice.isConfigured -> Fail(
                    P2pReason.ON_NO_ICE,
                    "$envVarName=on exige KM_ICE_STUN_URLS y/o KM_ICE_TURN_URLS; sin ICE no hay P2P",
                )

                !ice.hasRelayServer -> Fail(
                    P2pReason.ON_NO_TURN,
                    "$envVarName=on exige un servidor TURN (turn:/turns:) con credenciales; " +
                        "solo STUN expone la IP y no garantiza traversal",
                )

                else -> Attempt(ice)
            }
        }
    }
}
