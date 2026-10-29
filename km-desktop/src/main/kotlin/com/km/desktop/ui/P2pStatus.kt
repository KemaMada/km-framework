package com.km.desktop.ui

import com.km.config.IceConfig
import com.km.node.P2pReason

/**
 * El medio de datos, en texto, SEPARADO del glifo del enlace.
 *
 * ## Por que una clase propia
 *
 * Porque la etiqueta tiene que ser la misma en la barra y en DIAGNOSTICO, y
 * porque la regla de "cuando se dice `(sin ICE)`" es una decision de producto,
 * no de pintura: solo cuando el modo `auto` se quedo en relay por no haber
 * configuracion ICE. Un fallo de traversal dice `via relay` y su motivo estable
 * aparece en DIAGNOSTICO, no se disfraza de "sin ICE".
 *
 * ## Lo que NUNCA dice
 *
 * No dice "online", no promete entrega y no dice que el ACK viaje por el medio
 * de datos: el ACK sigue siendo del relay. El glifo `P2P[o|~|x|?]` no se toca.
 */
object P2pStatus {

    /** Etiqueta de la barra, o `null` si no hay medio que declarar. */
    fun mediumLabel(medium: String?, reason: String?): String? {
        if (medium == null) return null
        return when {
            medium == P2P -> "via P2P"
            reason == P2pReason.NO_ICE -> "via relay (sin ICE)"
            // Hay ICE (STUN) pero ningun TURN utilizable: se declara la causa.
            reason == P2pReason.NO_TURN -> "via relay (sin TURN)"
            else -> "via relay"
        }
    }

    /**
     * La linea de DIAGNOSTICO, con URLs REDACTADAS.
     *
     * `IceConfig.redactedUrls()` ya quita userinfo y credenciales; esta funcion
     * no vuelve a formatear la configuracion por su cuenta, para que no exista
     * una segunda ruta que pueda imprimir el password.
     */
    fun diagnostic(
        mode: String?,
        reason: String?,
        medium: String?,
        urls: List<String>,
        hostOnly: Boolean,
    ): String {
        val modo = mode ?: "desconocido"
        val porque = reason ?: "sin intento"
        val medio = medium ?: "desconocido"
        val servidores = if (hostOnly) {
            "host-only (sin STUN/TURN)"
        } else {
            urls.joinToString(",")
        }
        return "p2p modo=$modo motivo=$porque medio=$medio ice=$servidores"
    }

    /** Igual que el overload de listas, tomando las URLs YA redactadas. */
    fun diagnostic(
        mode: String?,
        reason: String?,
        medium: String?,
        ice: IceConfig,
    ): String = diagnostic(mode, reason, medium, ice.redactedUrls(), ice.isHostOnly)

    /** Etiqueta estable del medio P2P, la misma que publica `km-core`. */
    const val P2P: String = "P2P"
}
