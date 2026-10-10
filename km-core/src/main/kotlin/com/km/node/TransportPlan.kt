package com.km.node

/**
 * A1.3 — Eslabones de transporte de la cadena.
 *
 * Los tiers se intentan en orden; `[p2p, relay, tor]` es el orden
 * no-regresivo del diseño. El objeto no toma decisiones de fallo: solo dice
 * "¿esta este eslabon presente?". La politica (cuando parar y con que
 * categoria) sigue siendo [TransportChain].
 */
enum class TransportTier {
    P2P,
    RELAY,
    TOR,
}

/**
 * A1.3 — Plan de transporte para una conexion.
 *
 * - Sin relayService ⇒ [relayAvailable] false ⇒ plan `[p2p]` (o `[p2p,tor]`).
 * - Con relayService pero sin reles conocidos hoy ⇒ el eslabon RELAY sigue
 *   presente. [TransportChain.establish] o la logica de supervisor puede
 *   intentar RELAY y obtener `Exhausted`; eso es DEGRADACION EN TIEMPO DE
 *   EJECUCION, no remover el eslabon.
 *
 * El plan NO entra en la base de que "no hay reles ⇒ quitar RELAY": eso
 * exigiria re-escribir la cadena ante cada aparicion/desaparicion. Por eso la
 * existencia del medio es el criterio minimo y suficiente.
 */
data class TransportPlan(
    val relayAvailable: Boolean,
    val torAvailable: Boolean,
) {

    val tiers: List<TransportTier> by lazy {
        buildList {
            add(TransportTier.P2P)
            if (relayAvailable) {
                add(TransportTier.RELAY)
            }
            if (torAvailable) {
                add(TransportTier.TOR)
            }
        }
    }

    val tierNames: String get() = tiers.joinToString(",") { it.name.lowercase() }

    companion object {
        /**
         * Degradacion cero-rele: el eslabon de relay NO existe en el plan.
         *
         * Regla: si `relayService == null`, la cadena pierde RELAY. La consulta
         * sobre "hay reles conocidos" (PeerRoleDirectory.hasNoRelay) no altera
         * el plan en este modelo: los reles pueden aparecer tras establecer la
         * cadena o pueden anunciarse in-band mas tarde. La degradacion es por
         * `relayService`, no por el estado del directorio.
         */
        fun of(relayAvailable: Boolean, torAvailable: Boolean = false): TransportPlan =
            TransportPlan(relayAvailable = relayAvailable, torAvailable = torAvailable)
    }
}
