package com.km.transport.p2p

import com.km.model.IdentityId
import com.km.node.EstablishmentResult
import com.km.node.P2pReason
import com.km.node.P2pRole
import com.km.node.TransportBackend
import com.km.node.TransportChain
import com.km.node.TransportEstablishment
import com.km.node.TransportError
import com.km.node.TransportResult
import java.util.concurrent.ConcurrentHashMap

/**
 * Conduce el enlace P2P de cada par: un intento, un presupuesto, un backoff.
 *
 * ## Que decide y que NO decide
 *
 * Decide CUANDO se intenta P2P y cual es el motivo observable. NO decide por
 * donde salen los datos: eso es de [TransportChain], y este coordinador solo
 * lee su resultado. Tampoco envia bytes: `relay.send` no aparece en este
 * fichero, y el test lo comprueba.
 *
 * ## Garantias
 *
 *  - **Single-flight por par**: dos `prepare` concurrentes producen UN intento.
 *    El segundo devuelve [LinkOutcome.Skipped] con `p2p_in_flight`; no espera,
 *    porque quien llama es un handler de presencia y bloquearlo retrasaria al
 *    relay.
 *  - **Presupuesto de 8 s TOTALES**: es el `timeoutMs` con el que la cadena
 *    fija UN deadline por ronda y lo reparte entre `negotiate` y `awaitReady`
 *    (ICE + DTLS + DataChannel). Al vencer, la cadena cae al relay SOLO si el
 *    fallo autoriza `permitsFallback`; el coordinador no reimplementa esa
 *    politica, la hereda. Que el reloj sea el mismo evita que `negotiate` y
 *    `awaitReady` sumen dos esperas.
 *  - **Backoff de 60 s por par**: un P2P que falla no se reintenta en cada
 *    `PEER_ONLINE` ni en cada frame. 60 s es el mismo orden que la sonda de
 *    `ChainFailover.P2P_PROBE_INTERVAL_MS`.
 *  - **No fallback en SECURITY/PROTOCOL/STATE**: [EstablishmentResult.Stopped]
 *    se propaga como `Degraded(stopped = true)` y los eslabones posteriores
 *    NI SE INTENTAN (lo garantiza la cadena).
 *
 * ## Concurrencia
 *
 * El coordinador es SINCRONO y sin hilos propios: corre en el hilo que dispara
 * la presencia o el envio. En el escritorio, los callbacks de libwebrtc salen
 * por el executor unico de `RealWebRtcTransport`, de modo que la API nativa
 * nunca se invoca desde el hilo de libwebrtc.
 */
class P2pLinkCoordinator(
    private val localIdentity: IdentityId,
    /** Cadena del par, o `null` si no hay ninguna montada. */
    private val chainFor: (IdentityId) -> TransportChain?,
    /**
     * Fabrica del establecimiento de CADA eslabon.
     *
     * Recibe el backend para que el llamante decida: `P2pProvider.link(...)`
     * para el primero y [RelayTransportEstablishment] para el ultimo. Si la
     * fabrica devolviera un establecimiento P2P para el relay, la cadena
     * pediria SDP al medio equivocado; por eso es una dependencia explicita y
     * no una heuristica interna.
     */
    private val establishmentFor: (IdentityId, P2pRole, TransportBackend) -> TransportEstablishment,
    private val now: () -> Long = System::currentTimeMillis,
    /** Presupuesto del medio P2P: ICE + DTLS + DataChannel. */
    private val timeoutMs: Long = DEFAULT_TIMEOUT_MS,
    /** No reintentar P2P para el mismo par antes de esto. */
    private val backoffMs: Long = DEFAULT_BACKOFF_MS,
    /**
     * Reloj de reintentos. Inyectable para que los tests no esperen de verdad.
     */
    private val retryScheduler: RetryScheduler = DaemonRetryScheduler,
    /** Intentos por armado antes de rendirse hasta el proximo trigger. */
    private val maxAttempts: Int = DEFAULT_MAX_ATTEMPTS,
    /**
     * Espera entre intentos. El ANSWERER espera POCO: su trabajo es estar
     * escuchando cuando llegue la oferta, no ganar una carrera. El OFFERER
     * espera mas para no inundar de SDP al par.
     */
    private val answererRetryMs: Long = DEFAULT_ANSWERER_RETRY_MS,
    private val offererRetryMs: Long = DEFAULT_OFFERER_RETRY_MS,
    private val jitterMs: Long = DEFAULT_JITTER_MS,
) {

    private val inFlight: MutableSet<IdentityId> = ConcurrentHashMap.newKeySet()
    private val attemptCounts = ConcurrentHashMap<IdentityId, Int>()
    private val lastAttemptAt = ConcurrentHashMap<IdentityId, Long>()
    private val lastReason = ConcurrentHashMap<IdentityId, String>()

    /** Pares con reintento automatico activo mientras esten online. */
    private val armed: MutableSet<IdentityId> = ConcurrentHashMap.newKeySet()
    private val cancellations = ConcurrentHashMap<IdentityId, () -> Unit>()
    private val attemptsThisArm = ConcurrentHashMap<IdentityId, Int>()

    /**
     * Observador de cada intento. Lo usa el bundle para publicar el medio real
     * (y el motivo) segun van ocurriendo los reintentos.
     */
    @Volatile
    var observer: ((IdentityId, LinkOutcome) -> Unit)? = null

    /**
     * Arma el reintento de [remotePeerId] mientras siga online.
     *
     * ## Por que asincrono
     *
     * `prepare` bloquea hasta 8 s. Llamarlo desde el hilo de presencia o desde
     * el de sesion retrasaba el relay y hacia que el ANSWERER se rindiera
     * ANTES de que el OFFERER empezara (los dos establecen sesion en momentos
     * distintos). Con reintentos armados, el receptor permanece escuchando
     * aunque el primero falle.
     */
    fun arm(remotePeerId: IdentityId) {
        if (!armed.add(remotePeerId)) return
        attemptsThisArm[remotePeerId] = 0
        schedule(remotePeerId, 0L)
    }

    /** Deja de reintentar con ese par (offline, cierre o exito). */
    fun disarm(remotePeerId: IdentityId) {
        armed.remove(remotePeerId)
        cancellations.remove(remotePeerId)?.invoke()
        attemptsThisArm.remove(remotePeerId)
    }

    private fun schedule(remotePeerId: IdentityId, delayMs: Long) {
        cancellations.remove(remotePeerId)?.invoke()
        val cancel = retryScheduler.schedule(delayMs) { attempt(remotePeerId, reveal = true) }
        cancellations[remotePeerId] = cancel
    }

    private fun attempt(remotePeerId: IdentityId, reveal: Boolean) {
        if (!armed.contains(remotePeerId)) return
        val n = (attemptsThisArm[remotePeerId] ?: 0) + 1
        attemptsThisArm[remotePeerId] = n
        val outcome = prepare(remotePeerId, force = true)
        if (reveal) observer?.invoke(remotePeerId, outcome)

        if (outcome is LinkOutcome.Ready) {
            disarm(remotePeerId)
            return
        }
        val retriable = outcome.reason == P2pReason.TIMEOUT ||
            outcome.reason == P2pReason.FAILED ||
            outcome.reason == P2pReason.IN_FLIGHT
        if (!retriable || n >= maxAttempts) {
            disarm(remotePeerId)
            return
        }
        val role = P2pRole.forPair(localIdentity, remotePeerId)
        val base = if (role == P2pRole.ANSWERER) answererRetryMs else offererRetryMs
        val jitter = if (jitterMs <= 0L) 0L else (now() % (jitterMs + 1))
        schedule(remotePeerId, base + jitter)
    }

    /**
     * Intenta P2P para [remotePeerId].
     *
     * Nunca lanza: un fallo del medio es un [LinkOutcome], no una excepcion.
     */
    fun prepare(remotePeerId: IdentityId, force: Boolean = false): LinkOutcome {
        if (!inFlight.add(remotePeerId)) {
            return LinkOutcome.Skipped(P2pReason.IN_FLIGHT)
        }
        try {
            val previous = lastAttemptAt[remotePeerId]
            if (!force && previous != null && now() - previous < backoffMs) {
                return LinkOutcome.Skipped(P2pReason.BACKOFF).also {
                    lastReason[remotePeerId] = P2pReason.BACKOFF
                }
            }
            val chain = chainFor(remotePeerId)
                ?: return LinkOutcome.Skipped(P2pReason.NO_CHAIN).also {
                    lastReason[remotePeerId] = P2pReason.NO_CHAIN
                }

            val role = P2pRole.forPair(localIdentity, remotePeerId)
            val startedAt = now()
            val result = try {
                chain.establish(localIdentity, remotePeerId, timeoutMs) { backend ->
                    establishmentFor(remotePeerId, role, backend)
                }
            } catch (e: Throwable) {
                // La cadena ya atrapa a los establecimientos que revientan; un
                // fallo AQUI solo puede venir de la fabrica. Se clasifica como
                // medio roto para que el relay siga siendo el suelo.
                EstablishmentResult.Exhausted(
                    emptyList(),
                    TransportResult.Failure(
                        TransportError.BACKEND_ERROR,
                        "la fabrica de establecimiento lanzo ${e::class.simpleName}: ${e.message}",
                    ),
                )
            }
            val elapsed = now() - startedAt
            val reason = reasonFor(result, elapsed)

            attemptCounts.merge(remotePeerId, 1, Int::plus)
            lastAttemptAt[remotePeerId] = startedAt
            lastReason[remotePeerId] = reason

            return when (result) {
                is EstablishmentResult.Ready -> {
                    if (result.attempt.transport == p2pLinkName()) {
                        LinkOutcome.Ready(result.attempt.transport, reason)
                    } else {
                        // La cadena alcanzo READY, pero por el RELAY: P2P no
                        // sirvio. Devolver `Ready("relay")` haria que el
                        // llamante creyera que hay enlace directo. El medio de
                        // datos es relay y el motivo dice POR QUE no hay P2P.
                        LinkOutcome.Degraded(
                            reason = reason,
                            stopped = false,
                            detail = (result.attempts.firstOrNull { it.transport != p2pLinkName() }
                                ?.result as? TransportResult.Failure)?.message
                                ?: "el medio P2P no alcanzo READY",
                        )
                    }
                }
                is EstablishmentResult.Stopped -> LinkOutcome.Degraded(
                    reason = reason,
                    stopped = true,
                    detail = (result.attempt.result as? TransportResult.Failure)?.message,
                )
                is EstablishmentResult.Exhausted -> LinkOutcome.Degraded(
                    reason = reason,
                    stopped = false,
                    detail = result.lastFailure.message,
                )
            }
        } finally {
            inFlight.remove(remotePeerId)
        }
    }

    /**
     * Motivo ESTABLE del intento.
     *
     * Se deriva del resultado y del tiempo consumido, no del texto de una
     * excepcion: un mensaje de libwebrtc cambia entre versiones y el codigo no.
     */
    internal fun reasonFor(result: EstablishmentResult, elapsedMs: Long): String = when (result) {
        is EstablishmentResult.Ready ->
            if (result.attempt.transport == p2pLinkName()) P2pReason.READY
            else reasonForFailure(p2pFailureOf(result.attempts), elapsedMs)
        is EstablishmentResult.Stopped -> P2pReason.FAILED
        is EstablishmentResult.Exhausted -> reasonForFailure(result.lastFailure, elapsedMs)
    }

    private fun p2pFailureOf(attempts: List<com.km.node.EstablishmentAttempt>): TransportResult.Failure? =
        (attempts.firstOrNull { it.transport == p2pLinkName() }?.result) as? TransportResult.Failure

    /**
     * Un fallo del medio es timeout si consumio el presupuesto o si el propio
     * medio declaro que no abrio el canal; si no, es `p2p_failed`.
     *
     * No se mira el TEXTO de un mensaje de libwebrtc: se mira el CODIGO
     * estable. El texto cambia entre versiones, el codigo no.
     */
    private fun reasonForFailure(failure: TransportResult.Failure?, elapsedMs: Long): String = when {
        elapsedMs >= timeoutMs -> P2pReason.TIMEOUT
        failure?.error == TransportError.BACKEND_SEND_FAILED -> P2pReason.TIMEOUT
        else -> P2pReason.FAILED
    }

    private fun p2pLinkName(): String = com.km.node.Medium.P2P.linkName

    /** El par desaparecio: suelta su medio y permite un intento limpio al volver. */
    fun close(remotePeerId: IdentityId) {
        disarm(remotePeerId)
        chainFor(remotePeerId)?.releaseSelected()
        lastAttemptAt.remove(remotePeerId)
        inFlight.remove(remotePeerId)
        lastReason[remotePeerId] = P2pReason.CLOSED
    }

    /** Cierre local: suelta TODOS los medios. */
    fun closeAll() {
        // No hay `chains()` en el contrato: el coordinador solo conoce los pares
        // que intento. Un par sin intento no tiene medio que soltar.
        armed.toList().forEach { disarm(it) }
        lastAttemptAt.keys.toList().forEach { close(it) }
    }

    /** Veces que se intento P2P con este par. */
    fun attempts(remotePeerId: IdentityId): Int = attemptCounts[remotePeerId] ?: 0

    /** Ultimo motivo estable conocido, o `null` si nunca se intento. */
    fun reason(remotePeerId: IdentityId): String? = lastReason[remotePeerId]

    /** Si hay un intento en vuelo. */
    fun isInFlight(remotePeerId: IdentityId): Boolean = remotePeerId in inFlight

    companion object {
        /** Presupuesto de ICE + DTLS + DataChannel. */
        const val DEFAULT_TIMEOUT_MS: Long = 8_000L

        /** Backoff por par entre intentos P2P. */
        const val DEFAULT_BACKOFF_MS: Long = 60_000L

        /** Intentos por armado; despues se espera a un trigger nuevo. */
        const val DEFAULT_MAX_ATTEMPTS: Int = 10

        /** El ANSWERER casi no espera: su trabajo es estar escuchando. */
        const val DEFAULT_ANSWERER_RETRY_MS: Long = 1_000L

        /** El OFFERER espera mas para no inundar de SDP al par. */
        const val DEFAULT_OFFERER_RETRY_MS: Long = 5_000L

        /** Jitter para que dos pares no se sincronicen permanentemente. */
        const val DEFAULT_JITTER_MS: Long = 1_000L
    }
}

/**
 * Reloj de reintentos.
 *
 * Se inyecta para que los tests sean deterministas: un test puede ejecutar la
 * tarea inmediatamente sin dormir. En produccion usa un hilo daemon propio, de
 * modo que un reintento pendiente no impide cerrar la app.
 */
fun interface RetryScheduler {
    /** Programa [action] y devuelve la funcion que la cancela. */
    fun schedule(delayMs: Long, action: () -> Unit): () -> Unit
}

/** Implementacion de produccion: un solo hilo daemon compartido. */
object DaemonRetryScheduler : RetryScheduler {

    private val pool = java.util.concurrent.Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "km-p2p-retry").apply { isDaemon = true }
    }

    override fun schedule(delayMs: Long, action: () -> Unit): () -> Unit {
        val future = pool.schedule({ action() }, delayMs, java.util.concurrent.TimeUnit.MILLISECONDS)
        return { future.cancel(false) }
    }
}

/**
 * Resultado de [P2pLinkCoordinator.prepare], con motivo ESTABLE.
 *
 * Tres finales y no dos: "no se intento" ([Skipped]) es distinto de "se
 * intento y no hay P2P" ([Degraded]). Confundirlos haria que DIAGNOSTICO
 * dijera `p2p_failed` cuando en realidad nadie lo intento.
 */
sealed class LinkOutcome {

    /** Motivo estable, siempre presente. */
    abstract val reason: String

    /** Un medio alcanzo `READY`. */
    data class Ready(
        /** Nombre del eslabon activo (`p2p`). */
        val transport: String,
        override val reason: String,
    ) : LinkOutcome()

    /**
     * Se intento y no hay P2P; el relay es el medio de datos.
     *
     * @param stopped `true` si el fallo NO autorizaba fallback
     *   (SECURITY/PROTOCOL/STATE). El medio no cambio por politica, no por
     *   falta de opciones.
     */
    data class Degraded(
        override val reason: String,
        val stopped: Boolean,
        val detail: String? = null,
    ) : LinkOutcome()

    /** No se intento, por modo, backoff o single-flight. */
    data class Skipped(override val reason: String) : LinkOutcome()
}
