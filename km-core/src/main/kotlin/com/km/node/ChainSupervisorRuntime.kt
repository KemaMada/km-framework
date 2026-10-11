package com.km.node

import com.km.config.KmFlags
import com.km.model.IdentityId

/**
 * A4.2 — Fuente de eventos de cambio de red/rota.
 *
 * `km-core` es Kotlin/JVM puro y no puede enlazar con las APIs de conectividad
 * de la plataforma (Android `ConnectivityManager`, NetworkManager, etc.). Por
 * eso el SEAM es una interfaz: el host (km-desktop, km-daemon, Android) la
 * implementa y el runtime se suscribe.
 *
 * No es una comodidad de test: [ChainSupervisorRuntime.attach] la suscribe en
 * produccion, de modo que un cambio de red real re-intenta tier-1 sin que nadie
 * llame al supervisor a mano.
 */
fun interface NetworkChangeSource {
    /** Registra [handler]; devuelve el handle para desuscribirse. */
    fun onNetworkChange(handler: () -> Unit): ChainSubscription
}

/**
 * D5 — Seam de eventos de conexion del medio activo.
 *
 * [ChainSupervisor] solo necesita saber si un peer CONFIRMO (`true`) o PERDIO
 * (`false`) su enlace; no necesita conocer al [PeerTransportManager]. Esta
 * interfaz funcional desacopla la fuente para que un backend manager-free (el
 * de escritorio) alimente la maquina de estados sin pasar por el manager de
 * nodo.
 *
 * [PeerTransportManager.onConnectionStateChange] tiene esta firma EXACTA, de
 * modo que el manager conforma el seam sin cambios; el constructor secundario
 * de [ChainSupervisorRuntime] lo adapta y el cableado de nodo/test existente
 * queda intacto.
 */
fun interface ConnectionStateSource {
    /** Registra [handler]; devuelve el handle para desuscribirse. */
    fun onConnectionStateChange(handler: (IdentityId, Boolean) -> Unit): ChainSubscription
}

/**
 * A4.2 — CABLEADO DE PRODUCCION de los eventos de transporte y los latidos.
 *
 * Cierra la brecha del slice A4.1/A4.2: los predicados y la maquina de estados
 * existian como politica, pero el [PeerTransportManager] no tenia suscriptores
 * reales y el timeout de latido no se aplicaba durante la ronda. Esta clase es
 * la que conecta las FUENTES reales al [ChainSupervisor].
 *
 * ## Quien se suscribe a que
 *
 * ```
 * PeerTransportManager            NetworkChangeSource
 *   onConnectionStateChange          onNetworkChange
 *            \                       /
 *             \                     /
 *              v                   v
 *               ChainSupervisorRuntime
 *                        |
 *                        v
 *                  ChainSupervisor  (maquina de estados + triggers)
 * ```
 *
 * - **ICE DROPPED/FAILED**: `attach()` suscribe al manager. `markConnected`
 *   (`true`), `failBinding`/`closeBinding` (`false`) llegan al supervisor, que
 *   solo reacciona al peer supervisado.
 * - **Cambio de red**: si hay [NetworkChangeSource], `attach()` la suscribe.
 * - **Sonda de 60 s y latido**: el host llama a [tick] en su bucle; [tick]
 *   aplica la sonda periodica Y el enforce del timeout de latido. [heartbeat]
 *   alimenta latidos reales.
 *
 * ## Flag `km.chain.probe`
 *
 * Todo esta detras del flag (default OFF). Con el flag apagado [attach] NO
 * suscribe nada —la lista de observadores del manager queda vacia, identica a
 * la de antes— y [tick]/[heartbeat] son NO-OP. Es la transparencia del
 * decorador, llevada al cableado.
 *
 * @param supervisor cadena supervisada.
 * @param source fuente real de eventos de enlace (seam D5).
 * @param network fuente de cambios de red; `null` = solo ICE/heartbeat.
 * @param enabled `km.chain.probe`.
 */
class ChainSupervisorRuntime(
    val supervisor: ChainSupervisor,
    val source: ConnectionStateSource,
    private val network: NetworkChangeSource? = null,
    private val enabled: Boolean = KmFlags.chainProbe,
) {

    /**
     * Constructor retrocompatible: un [PeerTransportManager] ya tiene la firma
     * exacta del seam [ConnectionStateSource], asi que se adapta sin tocar el
     * manager ni los sitios de llamada de nodo/test.
     */
    constructor(
        supervisor: ChainSupervisor,
        manager: PeerTransportManager,
        network: NetworkChangeSource? = null,
        enabled: Boolean = KmFlags.chainProbe,
    ) : this(
        supervisor,
        ConnectionStateSource { handler -> manager.onConnectionStateChange(handler) },
        network,
        enabled,
    )

    private var connectionSubscription: ChainSubscription? = null
    private var networkSubscription: ChainSubscription? = null

    /** `true` si el runtime esta suscrito de verdad a la fuente. */
    val attached: Boolean get() = connectionSubscription != null

    /**
     * Suscribe el supervisor a las fuentes reales. Idempotente.
     *
     * Con el flag apagado es un NO-OP: no toca la lista de la fuente.
     */
    @Synchronized
    fun attach() {
        if (!enabled) return
        if (connectionSubscription != null) return
        connectionSubscription = supervisor.attachTo(source)
        networkSubscription = network?.onNetworkChange { supervisor.onNetworkChange() }
    }

    /**
     * Deshace [attach]. Idempotente.
     *
     * Deja la lista de observadores del manager exactamente como estaba antes:
     * es la mitad "unsubscribe" del cableado.
     */
    @Synchronized
    fun detach() {
        connectionSubscription?.cancel()
        connectionSubscription = null
        networkSubscription?.cancel()
        networkSubscription = null
    }

    /** Registra un latido real del enlace activo. NO-OP con el flag apagado. */
    fun heartbeat(nowMs: Long = supervisor.nowMs()): Boolean = supervisor.onHeartbeat(nowMs)

    /**
     * Tick del bucle del host: aplica la sonda periodica y el enforce del
     * timeout de latido. Devuelve `true` si alguna de las dos disparo una
     * transicion con fallback. NO-OP con el flag apagado.
     */
    fun tick(nowMs: Long = supervisor.nowMs()): Boolean {
        if (!enabled) return false
        val probe = supervisor.onProbe(nowMs)
        val heartbeat = supervisor.checkHeartbeat(nowMs)
        return probe || heartbeat
    }
}
