package com.example.keymessage.net

import android.content.Context
import com.example.keymessage.BuildConfig
import com.example.keymessage.util.KMLog
import com.km.config.IceConfig
import com.km.model.IdentityId
import com.km.node.P2pMode
import com.km.node.P2pPlan
import com.km.node.PeerTransportManager
import com.km.transport.JvmRelayTransport
import com.km.transport.p2p.ClientTransportBundle
import com.km.transport.p2p.ClientTransportFactory
import com.km.transport.p2p.P2pComponents
import com.km.webrtc.android.AndroidP2pProvider
import com.km.webrtc.android.AndroidWebRtcBackend

/**
 * Ajustes del tier P2P en Android.
 *
 * El modo y los servidores ICE viven en preferencias, no en el APK: una
 * credencial TURN embebida se extrae del binario. El default del binario es
 * `off` ([BuildConfig.KM_P2P_DEFAULT]), de modo que una instalacion nueva
 * conserva el camino actual byte a byte.
 *
 * Las claves son las mismas que el escritorio lee del entorno:
 * `stun`, `turn`, `user`, `credential` y `secret` (TURN REST).
 */
object KmP2p {

    private const val PREFS: String = "km_p2p"

    fun mode(context: Context): P2pMode {
        val raw = prefs(context).getString("mode", BuildConfig.KM_P2P_DEFAULT)
        return P2pMode.parse(raw)
    }

    fun ice(context: Context): IceConfig {
        val p = prefs(context)
        return runCatching {
            IceConfig.of(
                stunUrls = splitUrls(p.getString("stun", null)),
                turnUrls = splitUrls(p.getString("turn", null)),
                turnUsername = p.getString("user", null),
                turnCredential = p.getString("credential", null),
                turnSecret = p.getString("secret", null),
            )
        }.getOrElse { fallo ->
            // Una configuracion invalida NO enciende P2P: se declara y se sigue
            // por relay. El mensaje no lleva la credencial.
            KMLog.event("[CONFIG]", "ICE invalido (${fallo.message}); se usa relay")
            IceConfig.hostOnly()
        }
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun splitUrls(raw: String?): List<String> =
        raw.orEmpty().split(',').map { it.trim() }.filter { it.isNotEmpty() }
}

/**
 * Compone el transporte del nucleo con el tier P2P de Android.
 *
 * La decision la toma `km-core` ([P2pPlan.resolve]): aqui no se repite la tabla
 * `auto`/`on`, solo se construye el backend cuando el plan es `Attempt`. En
 * `Off`/`Skip`/`Fail` se pasa `p2p = null` y la fabrica devuelve exactamente el
 * camino actual.
 */
object AndroidP2p {

    fun compose(
        relay: JvmRelayTransport,
        identity: IdentityId,
        context: Context,
        peerContextFor: (IdentityId) -> com.km.node.PeerContext?,
    ): ClientTransportBundle {
        val mode = KmP2p.mode(context)
        val ice = KmP2p.ice(context)
        val plan = P2pPlan.resolve(mode, ice, envVarName = "KM_P2P")

        val components = if (plan is P2pPlan.Attempt) {
            val backend = AndroidWebRtcBackend(context.applicationContext, plan.ice)
            val manager = PeerTransportManager(backend)
            val provider = AndroidP2pProvider(manager, identity, backend, peerContextFor)
            P2pComponents.of(provider, manager)
        } else {
            null
        }

        val reason = when (plan) {
            is P2pPlan.Off -> plan.reason
            is P2pPlan.Skip -> plan.reason
            is P2pPlan.Fail -> plan.reason
            is P2pPlan.Attempt -> null
        }
        if (plan is P2pPlan.Fail) {
            // En Android no hay `exit 2`: se declara el motivo y se sigue por
            // relay, que es el suelo.
            KMLog.event("[CONFIG]", "modo on sin configuracion valida: ${plan.reason}")
        }
        KMLog.event("[CONFIG]", "modo=${mode.wireName} motivo=${reason ?: "p2p_ready"} componentes=${components != null}")

        return ClientTransportFactory(
            relay = relay,
            identity = identity,
            p2p = components,
            ice = if (plan is P2pPlan.Attempt) plan.ice else IceConfig.hostOnly(),
            initialReason = reason,
        ).compose()
    }
}
