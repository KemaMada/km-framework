package com.km.node

import com.km.config.KmFlags
import com.km.model.IdentityId
import kotlin.math.roundToLong
import kotlin.random.Random

/**
 * A4.2 — Handle CANCELABLE de una suscripcion a eventos de transporte.
 *
 * Existe para que el cableado de produccion no sea de ida: el supervisor se
 * suscribe al [PeerTransportManager] al arrancar y se desuscribe al parar. Una
 * lista de handlers sin forma de retirarse convierte cada reconexion del host
 * en una fuga de observadores y, peor, en triggers duplicados.
 */
fun interface ChainSubscription {
    /** Retira el observador. Idempotente: cancelar dos veces no falla. */
    fun cancel()
}

/**
 * A4.1 — Estado observable del supervisor de cadena (R5, R12).
 *
 * Es la maquina de estados del failover P2P -> relay (-> Tor, A4.3). NO decide
 * QUE bytes salen: eso ya lo decidio la capa de sesion. Solo decide POR DONDE,
 * y lo hace de forma observable, serial y determinista.
 *
 * ```
 * DOWN
 *   |
 *   v
 * P2P_NEGOTIATING --READY--> P2P_ACTIVE
 *   |                           |
 *   | fallo                     | perdida (ICE/heartbeat/network)
 *   v                           v
 * DEGRADED <--------------------+
 *   |
 *   v
 * RELAY_NEGOTIATING --READY--> RELAY_ACTIVE
 *   |                           |
 *   | sin mas tiers             | sin mas tiers
 *   v                           v
 * EPHEMERAL_QUEUE <-------------+
 *
 * cualquier fallo con permitsFallback == false -> BLOCKED (sin failover)
 * ```
 *
 * `TOR_NEGOTIATING`/`TOR_ACTIVE` existen como RESERVA de A4.3: con el flag
 * `km.tor.mode` apagado (default) son inalcanzables — el constructor lo exige.
 */
enum class ChainState {
    DOWN,
    P2P_NEGOTIATING,
    P2P_ACTIVE,
    DEGRADED,
    RELAY_NEGOTIATING,
    RELAY_ACTIVE,
    TOR_NEGOTIATING,
    TOR_ACTIVE,
    BLOCKED,
    EPHEMERAL_QUEUE,
    ;

    /** Un tier esta siendo negociado (aun no hay `READY`). */
    val isNegotiating: Boolean
        get() = this == P2P_NEGOTIATING || this == RELAY_NEGOTIATING || this == TOR_NEGOTIATING

    /** Hay un medio con autoridad confirmada por el propio transporte. */
    val isActive: Boolean
        get() = this == P2P_ACTIVE || this == RELAY_ACTIVE || this == TOR_ACTIVE

    /** Estado de la reserva Tor (A4.3). */
    val isTor: Boolean
        get() = this == TOR_NEGOTIATING || this == TOR_ACTIVE
}

/**
 * A4.2 — Motivo de un re-intento de tier-1.
 *
 * Los tres son EXACTAMENTE los del diseño: cambio de red, caida ICE despues de
 * `READY`, y la sonda periodica. Un trigger no transporta politica: solo dice
 * por que se vuelve a intentar P2P.
 */
enum class ChainTrigger {
    NETWORK_CHANGE,
    ICE_FAILED,
    P2P_PROBE,
}

/**
 * Numeros y predicados del failover (R5).
 *
 * Viven fuera de [ChainSupervisor] y son PURAS para poder probarlas sin
 * montar una cadena: el predicado de fallo y el calendario de backoff son
 * politica, no estado.
 */
object ChainFailover {

    /** Timeout de un establecimiento completo ([TransportChain.establish]). */
    const val ESTABLISH_TIMEOUT_MS: Long = 10_000L

    /** Sin `READY` tras este plazo, el tier fallo (fallo ICE, sin candidato). */
    const val ICE_TIMEOUT_MS: Long = 8_000L

    /** Cadencia de heartbeat del enlace activo. */
    const val HEARTBEAT_INTERVAL_MS: Long = 15_000L

    /** Silencio maximo tolerado antes de dar el enlace por perdido. */
    const val HEARTBEAT_TIMEOUT_MS: Long = 5_000L

    /** Fallos de escritura tolerados para UN frame antes de dar el tier por muerto. */
    const val RETRY_BUDGET: Int = 3

    /** Sonda de P2P mientras se opera por un tier inferior (relay/Tor). */
    const val P2P_PROBE_INTERVAL_MS: Long = 60_000L

    /** Fraccion de jitter (+/-20 %). */
    const val JITTER_FRACTION: Double = 0.20

    /** Backoff exponencial con tope: 1s, 2s, 4s, 8s, 16s, 30s, 30s, ... */
    val BACKOFF_MS: List<Long> = listOf(1_000L, 2_000L, 4_000L, 8_000L, 16_000L, 30_000L)

    /**
     * Retardo del intento [attempt] (base 0) con la muestra de jitter [sample]
     * en `[0, 1)`. La base se topa en [BACKOFF_MS] y el jitter se aplica
     * SIEMPRE dentro de +/-[JITTER_FRACTION].
     */
    fun backoffMs(attempt: Int, sample: Double): Long {
        require(attempt >= 0) { "el intento de backoff no puede ser negativo: $attempt" }
        val base = BACKOFF_MS[minOf(attempt, BACKOFF_MS.lastIndex)]
        val s = sample.coerceIn(0.0, 1.0)
        val factor = 1.0 + JITTER_FRACTION * (s * 2.0 - 1.0)
        return (base * factor).roundToLong()
    }

    /**
     * Entrada del predicado de fallo de un tier.
     *
     * [result] es el resultado del establecimiento (o `null` si no llego a
     * producirse). [ready] es la confirmacion REAL del transporte, no una
     * promesa: un `Success` intermedio no cuenta.
     */
    data class TierOutcome(
        val ready: Boolean,
        val result: TransportResult<Unit>?,
        val elapsedMs: Long = 0L,
        val writeAttempts: Int = 0,
        val lastHeartbeatMs: Long? = null,
        val nowMs: Long = 0L,
    )

    /**
     * El tier fallo y AUTORIZA failover si CUALQUIERA de:
     *  - el resultado es `Failure` con `permitsFallback == true`;
     *  - no hubo `READY` dentro de [ICE_TIMEOUT_MS];
     *  - se supero el [RETRY_BUDGET] de escrituras para un frame;
     *  - hubo un heartbeat miss ([HEARTBEAT_TIMEOUT_MS] de silencio).
     *
     * Un fallo con `permitsFallback == false` NO es "tier fallido con
     * fallback": es [blocked], y tiene precedencia sobre todo lo demas.
     */
    fun tierFailed(outcome: TierOutcome): Boolean {
        if (blocked(outcome)) return false
        val failure = outcome.result as? TransportResult.Failure
        if (failure != null && failure.error.permitsFallback) return true
        if (!outcome.ready && outcome.elapsedMs >= ICE_TIMEOUT_MS) return true
        if (outcome.writeAttempts > RETRY_BUDGET) return true
        val last = outcome.lastHeartbeatMs
        if (last != null && outcome.nowMs - last > HEARTBEAT_TIMEOUT_MS) return true
        return false
    }

    /**
     * Fallo NO recuperable cambiando de medio (SECURITY / PROTOCOL / STATE).
     * Es la unica via a `BLOCKED`; el failover nunca la esconde.
     */
    fun blocked(outcome: TierOutcome): Boolean {
        val failure = outcome.result as? TransportResult.Failure ?: return false
        return !failure.error.permitsFallback
    }

    /** `true` si el enlace lleva demasiado silencio para seguir vivo. */
    fun heartbeatMissed(lastHeartbeatMs: Long, nowMs: Long): Boolean =
        nowMs - lastHeartbeatMs > HEARTBEAT_TIMEOUT_MS
}

/**
 * A4.1/A4.2 — Supervisor de failover que ENVUELVE un [TransportChain].
 *
 * ## Que hace y que NO hace
 *
 * El supervisor NO cifra, NO descifra, NO crea ni reconstruye una sesion, y NO
 * decide QUE bytes viajan. Cuando re-selecciona un medio, la MISMA instancia de
 * [TransportChain] cambia su puntero `selected`: la capa de entrega reenvia el
 * `OutboundRecord` durable VERBATIM. Es exactamente el invariante R12/CHAIN-08.
 *
 * ## Serial, nunca en carrera
 *
 * Los tiers se intentan UNO A UNO, en el orden del plan. No existe ninguna
 * ronda paralela entre P2P y relay: cuando el supervisor re-intenta tier-1,
 * llama a [TransportChain.establish] con una fabrica FRESCA, que crea un
 * establecimiento nuevo por medio. Un medio ya cerrado no se reutiliza.
 *
 * ## `READY` solo cuando el transporte lo confirma
 *
 * El supervisor solo transiciona a `<TIER>_ACTIVE` cuando `awaitReady`
 * devuelve `Success` Y el establecimiento declara `EstablishmentState.READY`.
 * Una implementacion que "diga que si" sin estarlo no activa nada.
 *
 * ## Flag
 *
 * Todo el comportamiento proactivo (maquina de estados, triggers, backoff)
 * esta detras de `km.chain.probe` (default OFF). Con el flag apagado el
 * supervisor es un DECORADOR TRANSPARENTE: delega en la cadena sin envolver
 * la fabrica, sin disparar re-intentos y sin tocar su estado observable.
 *
 * @param chain la cadena agnostica de cripto que decide el medio.
 * @param tiers tier de cada backend, alineado por indice con `chain.transports`.
 * @param enabled `km.chain.probe`.
 * @param torEnabled `km.tor.mode`; con el apagado no se admite un tier TOR
 *   (A4.3 lo implementa), y los estados `TOR_*` son inalcanzables.
 * @param now reloj inyectable (tests con reloj falso).
 * @param jitter muestra `[0, 1)` para el backoff; inyectable para tests.
 */
class ChainSupervisor(
    /** La cadena supervisada. La autoridad de medio vive en ella. */
    val chain: TransportChain,
    /** Tier de cada backend, en el MISMO orden que `chain.transports`. */
    val tiers: List<TransportTier>,
    /** `km.chain.probe`; apagado = decorador transparente. */
    val enabled: Boolean = KmFlags.chainProbe,
    /** `km.tor.mode`; apagado = `TOR_*` inalcanzable. */
    val torEnabled: Boolean = KmFlags.torMode,
    private val now: () -> Long = System::currentTimeMillis,
    private val jitter: () -> Double = { Random.nextDouble() },
) : TransportBackend {

    init {
        require(tiers.size == chain.transports.size) {
            "tiers (${tiers.size}) y backends (${chain.transports.size}) no coinciden: " +
                "la alineacion por indice es el contrato del supervisor"
        }
        require(torEnabled || tiers.none { it == TransportTier.TOR }) {
            "un tier TOR con km.tor.mode apagado es inalcanzable: A4.3 lo implementa"
        }
    }

    override val name: String get() = "supervisor[${chain.name}]"

    // ===================================================================
    // Delegacion transparente al backend de la cadena
    // ===================================================================

    override var eventCallbacks: TransportEventCallbacks
        get() = chain.eventCallbacks
        set(value) { chain.eventCallbacks = value }

    override fun initialize(): TransportResult<Unit> = chain.initialize()
    override fun onCreateBinding(localIdentity: IdentityId, remotePeerId: IdentityId): TransportResult<Unit> =
        chain.onCreateBinding(localIdentity, remotePeerId)
    override fun onLocalSdp(localIdentity: IdentityId, remotePeerId: IdentityId, sdp: String): TransportResult<Unit> =
        chain.onLocalSdp(localIdentity, remotePeerId, sdp)
    override fun onRemoteSdp(localIdentity: IdentityId, remotePeerId: IdentityId, sdp: String): TransportResult<Unit> =
        chain.onRemoteSdp(localIdentity, remotePeerId, sdp)
    override fun onIceCandidate(localIdentity: IdentityId, remotePeerId: IdentityId, candidate: String): TransportResult<Unit> =
        chain.onIceCandidate(localIdentity, remotePeerId, candidate)
    override fun onCloseBinding(localIdentity: IdentityId, remotePeerId: IdentityId): TransportResult<Unit> =
        chain.onCloseBinding(localIdentity, remotePeerId)
    override fun shutdown(): TransportResult<Unit> = chain.shutdown()

    /**
     * Envio: delegacion PURA en la cadena.
     *
     * El supervisor NO reordena ni reintenta aqui. La cadena ya aplica la
     * politica de fallback ([TransportError.permitsFallback]) sobre los MISMOS
     * bytes, y reenviar por su cuenta seria un segundo camino de entrega.
     */
    override fun sendData(remotePeerId: IdentityId, data: ByteArray): TransportResult<Unit> =
        chain.sendData(remotePeerId, data)

    // ===================================================================
    // Estado observable
    // ===================================================================

    @Volatile
    var state: ChainState = ChainState.DOWN
        private set

    private val stateObservers = mutableListOf<(ChainState) -> Unit>()
    private val historyList = mutableListOf<ChainState>(ChainState.DOWN)

    /** Secuencia de estados recorrida, en orden. */
    @Synchronized
    fun history(): List<ChainState> = historyList.toList()

    /** Observa cada transicion. */
    fun onStateChange(observer: (ChainState) -> Unit) {
        stateObservers.add(observer)
    }

    private fun transitionTo(next: ChainState) {
        val current = state
        if (current == next) return
        check(canTransition(current, next)) {
            "transicion ilegal segun la tabla del diseño: $current -> $next"
        }
        state = next
        synchronized(historyList) { historyList.add(next) }
        if (next.isActive) {
            // Un enlace ACTIVO arma el detector de perdida... salvo cuando lo
            // produce una ronda disparada por un trigger: ese enlace aun no
            // esta confirmado por el transporte, y re-armarlo aqui haria que un
            // evento duplicado migrara dos veces.
            if (!inTriggerRound) armHeartbeat()
            if (next == ChainState.P2P_ACTIVE) lastProbeMs = now()
            else if (lastProbeMs == Long.MIN_VALUE) lastProbeMs = now()
        }
        stateObservers.forEach { it(next) }
    }

    /** Arma el detector de latido con el reloj del supervisor. */
    private fun armHeartbeat() {
        lastHeartbeatMs = now()
        linkArmed = true
    }

    /** Reloj del supervisor, expuesto para que el cableado marque sus ticks. */
    fun nowMs(): Long = now()

    // ===================================================================
    // A4.1 — Establecimiento dirigido por la maquina de estados
    // ===================================================================

    private var lastLocal: IdentityId? = null

    @Volatile
    private var lastRemote: IdentityId? = null
    private var lastFactory: ((TransportBackend) -> TransportEstablishment)? = null

    @Volatile
    var lastProbeMs: Long = Long.MIN_VALUE
        private set

    @Volatile
    var retryCount: Int = 0
        private set

    /**
     * A4.2 — Ultimo latido observado del enlace activo, o [Long.MIN_VALUE] si
     * el enlace aun no fue armado.
     *
     * Es la pieza que convierte el predicado [ChainFailover.heartbeatMissed] en
     * un hecho observable: sin un latido registrado, el silencio no tiene
     * origen y no se puede medir.
     */
    @Volatile
    var lastHeartbeatMs: Long = Long.MIN_VALUE
        private set

    /**
     * `true` mientras el enlace activo esta ARMADO para detectar su perdida.
     *
     * Es el amortiguador de eventos duplicados: una misma caida puede llegar
     * dos veces (callback del transporte Y `failBinding` del manager), y solo
     * la primera debe migrar. Se desarma al procesar la caida y se vuelve a
     * armar cuando el transporte CONFIRMA un enlace vivo (`markConnected`) o
     * llega un latido.
     */
    @Volatile
    private var linkArmed: Boolean = false

    /**
     * `true` mientras una ronda disparada por un trigger esta en curso.
     *
     * La ronda fresca se intenta, pero su exito NO re-arma el detector: un
     * enlace recien re-establecido no ha sido confirmado por el transporte. Sin
     * esta marca, un evento `false` duplicado dispararia una segunda migracion.
     */
    @Volatile
    private var inTriggerRound: Boolean = false

    private val triggersRaised = mutableListOf<ChainTrigger>()

    /** Motivos de los re-intentos disparados, en orden. */
    @Synchronized
    fun triggersRaised(): List<ChainTrigger> = triggersRaised.toList()

    /**
     * Establece por la cadena, reflejando el avance en [state].
     *
     * Con el flag apagado delega INTACTO: pasa la fabrica del llamante tal cual
     * y no toca el estado. Con el flag encendido envuelve la fabrica para
     * observar el paso por cada tier, pero la decision de fallback sigue
     * siendo de [TransportChain] — el supervisor no la reimplementa.
     */
    @Synchronized
    fun establish(
        localIdentity: IdentityId,
        remotePeerId: IdentityId,
        timeoutMs: Long = ChainFailover.ESTABLISH_TIMEOUT_MS,
        factory: (TransportBackend) -> TransportEstablishment,
    ): EstablishmentResult {
        lastLocal = localIdentity
        lastRemote = remotePeerId
        lastFactory = factory

        if (!enabled) {
            // Decorador transparente: ni envuelve la fabrica ni toca el estado.
            return chain.establish(localIdentity, remotePeerId, timeoutMs, factory)
        }
        if (state == ChainState.BLOCKED) transitionTo(ChainState.DOWN)

        var firstTier = true
        val wrapped: (TransportBackend) -> TransportEstablishment = { backend ->
            val tier = tierForBackend(backend)
            beginTier(tier, firstTier)
            firstTier = false
            ObserveEstablishment(tier, factory(backend))
        }

        val result = chain.establish(localIdentity, remotePeerId, timeoutMs, wrapped)
        applyResult(result)
        return result
    }

    private fun beginTier(tier: TransportTier, first: Boolean) {
        if (!first && state != ChainState.DEGRADED) transitionTo(ChainState.DEGRADED)
        transitionTo(negotiatingState(tier))
    }

    private fun applyResult(result: EstablishmentResult) {
        when (result) {
            is EstablishmentResult.Ready -> {
                val tier = tiers.getOrNull(result.attempt.index)
                if (tier != null) transitionTo(activeState(tier))
            }
            is EstablishmentResult.Stopped -> transitionTo(ChainState.BLOCKED)
            is EstablishmentResult.Exhausted -> transitionTo(ChainState.EPHEMERAL_QUEUE)
        }
    }

    // ===================================================================
    // A4.2 — Re-intentos de tier-1
    // ===================================================================

    /**
     * Dispara un re-intento. Devuelve `true` si de verdad se creo un
     * establecimiento FRESCO.
     *
     * Con el flag apagado es un NO-OP (`false`): asi el flag-off es identico al
     * comportamiento de hoy.
     *
     * La ronda se marca como disparada ([inTriggerRound]) para que su exito no
     * re-arme el detector de caida: solo el transporte confirma un enlace vivo.
     */
    @Synchronized
    fun trigger(trigger: ChainTrigger, nowMs: Long = now()): Boolean {
        if (!enabled) return false
        if (state == ChainState.BLOCKED) return false
        if (trigger == ChainTrigger.P2P_PROBE && !probeDue(nowMs)) return false
        val local = lastLocal ?: return false
        val remote = lastRemote ?: return false
        val factory = lastFactory ?: return false

        triggersRaised.add(trigger)
        retryCount++
        lastProbeMs = nowMs
        // Ronda FRESCA: la fabrica del llamante se vuelve a invocar por medio.
        val prevTrigger = inTriggerRound
        inTriggerRound = true
        val result = try {
            establish(local, remote, ChainFailover.ESTABLISH_TIMEOUT_MS, factory)
        } finally {
            inTriggerRound = prevTrigger
        }
        return result is EstablishmentResult.Ready || result is EstablishmentResult.Exhausted
    }

    /** Cambio de red o de ruta: re-intenta tier-1. */
    fun onNetworkChange(): Boolean = trigger(ChainTrigger.NETWORK_CHANGE)

    /**
     * Estado de conexion del medio activo. Una caida despues de `READY`
     * re-intenta tier-1; una conexion re-arma el detector y la sonda.
     *
     * ## Idempotencia
     *
     * Una misma caida puede anunciarse dos veces (el callback del transporte Y
     * el `failBinding` del manager). Solo la PRIMERA migra: al procesarla el
     * detector queda desarmado, y no se vuelve a armar hasta que el transporte
     * CONFIRMA un enlace nuevo ([connected] `true` o un latido). Los eventos
     * repetidos son, por tanto, NO-OP.
     */
    @Synchronized
    fun onConnectionStateChange(connected: Boolean): Boolean {
        if (connected) {
            if (!enabled) return false
            // Confirmacion del transporte: el enlace vuelve a estar armado.
            armHeartbeat()
            lastProbeMs = now()
            return false
        }
        if (!enabled) return false
        if (!linkArmed) return false
        linkArmed = false
        return trigger(ChainTrigger.ICE_FAILED)
    }

    /**
     * A4.2 — Latido observado por el enlace activo.
     *
     * Marca el enlace como vivo y re-arma el detector. Con el flag apagado es
     * NO-OP.
     */
    fun onHeartbeat(nowMs: Long = now()): Boolean {
        if (!enabled) return false
        lastHeartbeatMs = nowMs
        linkArmed = true
        return true
    }

    /** `true` si el enlace activo lleva mas de [ChainFailover.HEARTBEAT_TIMEOUT_MS] sin latir. */
    fun heartbeatExpired(nowMs: Long = now()): Boolean {
        if (!enabled) return false
        if (!state.isActive) return false
        val last = lastHeartbeatMs
        if (last == Long.MIN_VALUE) return false
        return ChainFailover.heartbeatMissed(last, nowMs)
    }

    /**
     * A4.2 — Enforce del timeout de latido DURANTE la ronda.
     *
     * Convierte el predicado [ChainFailover.heartbeatMissed] en una accion: si
     * el enlace activo esta armado y lleva demasiado silencio, lo trata como
     * caida (trigger de tier-1) y dispara el fallback. Idempotente: la primera
     * deteccion desarma, y solo un latido/conexion nuevo vuelve a armar.
     *
     * @return `true` si la expiracion disparo la transicion/fallback.
     */
    @Synchronized
    fun checkHeartbeat(nowMs: Long = now()): Boolean {
        if (!enabled) return false
        if (!state.isActive) return false
        if (!linkArmed) return false
        val last = lastHeartbeatMs
        if (last == Long.MIN_VALUE) return false
        if (!ChainFailover.heartbeatMissed(last, nowMs)) return false
        linkArmed = false
        lastHeartbeatMs = Long.MIN_VALUE
        return trigger(ChainTrigger.ICE_FAILED, nowMs)
    }

    /** Sonda periodica (default: solo si toca). */
    fun onProbe(nowMs: Long = now()): Boolean = trigger(ChainTrigger.P2P_PROBE, nowMs)

    /** `true` si, operando por debajo de tier-1, toca sonear P2P. */
    fun probeDue(nowMs: Long = now()): Boolean {
        if (!enabled) return false
        val onLowerTier = state == ChainState.RELAY_ACTIVE ||
            state == ChainState.TOR_ACTIVE ||
            state == ChainState.EPHEMERAL_QUEUE
        if (!onLowerTier) return false
        return nowMs - lastProbeMs >= ChainFailover.P2P_PROBE_INTERVAL_MS
    }

    /**
     * Suscribe el supervisor a los cambios de conexion del manager (A4.2).
     *
     * El manager NO conoce al supervisor: solo anuncia `(remotePeerId,
     * connected)` a quien se registre. Con nadie registrado, el manager se
     * comporta igual que hoy.
     *
     * El supervisor solo reacciona a eventos del peer que supervisa
     * ([lastRemote]): un manager puede alojar bindings de varios peers, y una
     * caida ajena no puede migrar esta cadena.
     *
     * @return handle cancelable; cancelarlo deja la lista del manager como
     *   estaba (comportamiento identico al de antes de suscribirse).
     */
    @Synchronized
    fun attachTo(manager: PeerTransportManager): ChainSubscription {
        val subscription = manager.onConnectionStateChange { remote, connected ->
            val supervised = lastRemote
            if (supervised == null || supervised == remote) onConnectionStateChange(connected)
        }
        return ChainSubscription { subscription.cancel() }
    }

    // ===================================================================
    // A4.1 — Presupuesto de escritura (retryBudget)
    // ===================================================================

    @Volatile
    private var consecutiveWriteFailures: Int = 0

    /**
     * Registra el resultado de UNA escritura del frame actual.
     *
     * @return `true` si se supero [ChainFailover.RETRY_BUDGET] y el tier debe
     *   darse por muerto. Un `Success` reinicia el contador.
     */
    fun recordWriteResult(result: TransportResult<Unit>): Boolean {
        consecutiveWriteFailures =
            if (result is TransportResult.Failure && result.error.permitsFallback) consecutiveWriteFailures + 1 else 0
        return consecutiveWriteFailures > ChainFailover.RETRY_BUDGET
    }

    /** Escrituras fallidas consecutivas del frame actual. */
    val writeFailures: Int get() = consecutiveWriteFailures

    /** Reinicia el presupuesto (frame nuevo). */
    fun resetWriteBudget() {
        consecutiveWriteFailures = 0
    }

    /** Retardo de backoff del intento [attempt], con jitter inyectado. */
    fun backoffFor(attempt: Int): Long = ChainFailover.backoffMs(attempt, jitter())

    // ===================================================================
    // Helpers
    // ===================================================================

    private fun tierForBackend(backend: TransportBackend): TransportTier {
        val index = chain.transports.indexOfFirst { it === backend }
        val tier = tiers.getOrNull(index)
        return tier ?: error("backend '${backend.name}' no tiene tier asignado en el supervisor")
    }

    private fun negotiatingState(tier: TransportTier): ChainState = when (tier) {
        TransportTier.P2P -> ChainState.P2P_NEGOTIATING
        TransportTier.RELAY -> ChainState.RELAY_NEGOTIATING
        TransportTier.TOR -> ChainState.TOR_NEGOTIATING
    }

    private fun activeState(tier: TransportTier): ChainState = when (tier) {
        TransportTier.P2P -> ChainState.P2P_ACTIVE
        TransportTier.RELAY -> ChainState.RELAY_ACTIVE
        TransportTier.TOR -> ChainState.TOR_ACTIVE
    }

    /**
     * Envuelve un [TransportEstablishment] para observar `READY` SIN inventarlo.
     *
     * El unico punto donde se declara `<TIER>_ACTIVE` es [awaitReady], y solo
     * si el resultado es `Success` Y el medio declara `READY`. Todas las demas
     * fases se delegan sin tocar el estado: un fallo intermedio lo observara la
     * ronda al pasar al tier siguiente.
     */
    private inner class ObserveEstablishment(
        private val tier: TransportTier,
        private val delegate: TransportEstablishment,
    ) : TransportEstablishment {

        override val transportName: String get() = delegate.transportName
        override val state: EstablishmentState get() = delegate.state

        override fun initialize(): TransportResult<Unit> = delegate.initialize()
        override fun createBinding(localIdentity: IdentityId, remotePeerId: IdentityId): TransportResult<Unit> =
            delegate.createBinding(localIdentity, remotePeerId)
        override fun negotiate(): TransportResult<Unit> = delegate.negotiate()

        override fun awaitReady(timeoutMs: Long): TransportResult<Unit> {
            val result = delegate.awaitReady(timeoutMs)
            // Doble condicion, y es el corazon de "no activacion optimista":
            // ni un Success basta si el medio no dice READY.
            if (result is TransportResult.Success && delegate.state == EstablishmentState.READY) {
                transitionTo(activeState(tier))
            }
            return result
        }

        override fun abort() = delegate.abort()
    }

    companion object {

        /**
         * Tabla de transiciones de `estado -> estados siguientes permitidos`.
         *
         * Es la tabla del diseño, explicitada para que un cambio de maquina no
         * pase inadvertido. `DEGRADED` es el estado de paso entre perdida de un
         * medio y la negociacion del siguiente; `BLOCKED` solo sale por
         * reinicio explicito.
         */
        val TRANSITIONS: Map<ChainState, Set<ChainState>> = mapOf(
            ChainState.DOWN to setOf(ChainState.P2P_NEGOTIATING, ChainState.RELAY_NEGOTIATING),
            ChainState.P2P_NEGOTIATING to setOf(
                ChainState.P2P_ACTIVE, ChainState.DEGRADED, ChainState.RELAY_NEGOTIATING,
                ChainState.BLOCKED, ChainState.EPHEMERAL_QUEUE,
            ),
            ChainState.P2P_ACTIVE to setOf(
                ChainState.DEGRADED, ChainState.P2P_NEGOTIATING, ChainState.RELAY_NEGOTIATING,
                ChainState.BLOCKED, ChainState.EPHEMERAL_QUEUE,
            ),
            ChainState.DEGRADED to setOf(
                ChainState.RELAY_NEGOTIATING, ChainState.P2P_NEGOTIATING, ChainState.BLOCKED,
                ChainState.EPHEMERAL_QUEUE,
            ),
            ChainState.RELAY_NEGOTIATING to setOf(
                ChainState.RELAY_ACTIVE, ChainState.DEGRADED, ChainState.TOR_NEGOTIATING,
                ChainState.P2P_NEGOTIATING, ChainState.BLOCKED, ChainState.EPHEMERAL_QUEUE,
            ),
            ChainState.RELAY_ACTIVE to setOf(
                ChainState.DEGRADED, ChainState.RELAY_NEGOTIATING, ChainState.TOR_NEGOTIATING,
                ChainState.P2P_NEGOTIATING, ChainState.BLOCKED, ChainState.EPHEMERAL_QUEUE,
            ),
            ChainState.TOR_NEGOTIATING to setOf(
                ChainState.TOR_ACTIVE, ChainState.DEGRADED, ChainState.P2P_NEGOTIATING,
                ChainState.BLOCKED, ChainState.EPHEMERAL_QUEUE,
            ),
            ChainState.TOR_ACTIVE to setOf(
                ChainState.DEGRADED, ChainState.P2P_NEGOTIATING, ChainState.BLOCKED,
                ChainState.EPHEMERAL_QUEUE,
            ),
            ChainState.EPHEMERAL_QUEUE to setOf(
                ChainState.P2P_NEGOTIATING, ChainState.RELAY_NEGOTIATING, ChainState.DOWN,
            ),
            ChainState.BLOCKED to setOf(ChainState.DOWN),
        )

        /** `true` si `from -> to` pertenece a la tabla del diseño. */
        fun canTransition(from: ChainState, to: ChainState): Boolean =
            from == to || TRANSITIONS[from]?.contains(to) == true
    }
}
