package com.km.node

import com.km.model.IdentityId
import com.km.model.NodeAnnouncement
import com.km.model.NodeCapability
import com.km.model.NodeEndpoint
import com.km.model.RelayInfo
import com.km.model.RelayLimits
import java.util.EnumMap

/**
 * A1.2 — Semilla de bootstrap de un rele conocido fuera de banda.
 *
 * Una semilla es un endpoint que el operador declara en configuracion (una IP
 * de Tailscale, un `.onion`) ANTES de haber autenticado a nadie. Todavia no
 * hay un [IdentityId] verificado que la indexe, y por eso NO vive en el mapa
 * de peers: es un punto de arranque, no un peer.
 *
 * La semilla no afirma identidad. Solo dice "aqui puede haber un rele"; quien
 * la use tiene que seguir exigiendo un `NodeAnnouncement` firmado antes de
 * confiar en nada mas. Enlazar una semilla a una identidad sin firma seria
 * convertir una pista de configuracion en una afirmacion de autenticidad.
 */
data class RelaySeed(
    /** Direccion `host:puerto`, IP de Tailscale o `.onion`. */
    val address: String,
    /** Transporte del endpoint: `websocket` (clearnet/Tailscale) u `onion`. */
    val transport: String = TRANSPORT_WEBSOCKET,
    /**
     * Capacidades que se ESPERAN de este rele. Es una expectativa, no una
     * verificacion: se sustituye por el conjunto firmado en cuanto el peer
     * anuncia.
     */
    val expectedCapabilities: Set<NodeCapability> = DEFAULT_RELAY_CAPABILITIES,
) {
    init {
        require(address.isNotBlank()) { "una semilla necesita una direccion no vacia" }
    }

    companion object {
        const val TRANSPORT_WEBSOCKET: String = "websocket"
        const val TRANSPORT_ONION: String = "onion"

        /** Rol tipico de un nodo siempre-encendido; el mismo que corre un VPS. */
        val DEFAULT_RELAY_CAPABILITIES: Set<NodeCapability> = setOf(
            NodeCapability.RELAY,
            NodeCapability.SIGNALING,
            NodeCapability.STORE_AND_FORWARD,
        )

        /**
         * Clasifica una direccion de configuracion en un [RelaySeed].
         *
         * La unica distincion es `.onion`, porque es la unica que cambia el
         * TRANSPORTE y no el nombre del host. Una IP de Tailscale o un DNS
         * normal viajan como `websocket`; todo lo demas es un detalle del
         * valor, no del tipo, y adivinarlo mas seria inventar politica.
         */
        fun fromAddress(address: String): RelaySeed {
            val trimmed = address.trim()
            val onion = trimmed.contains(".onion", ignoreCase = true)
            return RelaySeed(
                address = trimmed,
                transport = if (onion) TRANSPORT_ONION else TRANSPORT_WEBSOCKET,
            )
        }
    }
}

/**
 * A1.1 — Directorio de roles y capacidades de peers (R1/S1).
 *
 * ## Que resuelve
 *
 * En esta arquitectura NINGUN nodo es privilegiado: un portatil, un escritorio
 * y un VPS corren las mismas clases y cualquiera puede ser cliente, senalizar y
 * relevar a la vez. Lo que diferencia a un nodo de otro no es un rol fijo, es
 * un CONJUNTO DE CAPACIDADES firmado en su `NodeAnnouncement`. Este directorio
 * guarda ese conjunto y lo hace consultable.
 *
 * ## Dos indices, una verdad
 *
 *  - `byIdentity`: `IdentityId -> RelayInfo`. Es el cache: lo que sabemos de
 *    cada peer, con su endpoint, limites y frescura.
 *  - `byCapability`: `NodeCapability -> {IdentityId}`. Es el indice invertido:
 *    "quien puede relevar" es una consulta, no un recorrido.
 *
 * Ambos se actualizan juntos en [put]; un borrado en el indice viejo
 * antecediendo al alta en el nuevo evita que un peer indexado por `RELAY`
 * siga apareciendo como tal despues de anunciar un conjunto sin `RELAY`.
 *
 * ## Conjunto vacio: estado VALIDO, no error
 *
 * Un nodo puede anunciar `emptySet()`. Significa "no ofrezco nada todavia", no
 * "el anuncio esta roto". Se guarda como un peer mas, sin ninguna entrada en el
 * indice invertido. [relays] devolveria vacio y eso NO es un fallo: es la
 * fotografia honesta de que hoy no hay rele.
 *
 * ## Sin criptografia
 *
 * Este directorio NO verifica firmas. Solo recibe `NodeAnnouncement` YA
 * verificado por quien lo trajo ([NodeRuntimeImpl.receiveAuthOk] /
 * [NodeRuntimeImpl.registerPeerRoles]). Verificar aqui seria tener dos sitios
 * donde se decide si un peer es quien dice ser, y con dos sitios la frontera
 * cede por el que nadie mira.
 *
 * @param now fuente de tiempo para `lastSeenMs`; inyectable para tests.
 */
class PeerRoleDirectory(
    private val now: () -> Long = System::currentTimeMillis,
) {

    private val byIdentity = LinkedHashMap<IdentityId, RelayInfo>()
    private val byCapability = EnumMap<NodeCapability, MutableSet<IdentityId>>(NodeCapability::class.java)
    private val seeds = LinkedHashMap<String, RelaySeed>()

    // ===================================================================
    // Peers con capacidades firmadas
    // ===================================================================

    /**
     * Registra (o reemplaza) el peer descrito por [announcement].
     *
     * Acepta anuncios con `endpoints` vacios y con `capabilities` vacias: son
     * estados validos. Si no hay endpoint, se guarda uno sintetico marcado como
     * `websocket` con direccion vacia, para no perder las capacidades por un
     * dato de transporte que todavia no llego.
     *
     * @param latencyMs latencia observada, o `-1` si se desconoce.
     * @return el [RelayInfo] almacenado.
     */
    @Synchronized
    fun record(announcement: NodeAnnouncement, latencyMs: Long = -1): RelayInfo {
        val endpoint = announcement.endpoints.firstOrNull()
            ?: NodeEndpoint(RelaySeed.TRANSPORT_WEBSOCKET, "")
        val info = RelayInfo(
            identity = announcement.identity,
            endpoint = endpoint,
            capabilities = announcement.capabilities,
            limits = announcement.limits ?: RelayLimits(),
            latencyMs = latencyMs,
            lastSeenMs = now(),
            isTrusted = false,
        )
        put(info)
        return info
    }

    /** Registra (o reemplaza) un [RelayInfo] ya construido. */
    @Synchronized
    fun update(info: RelayInfo) {
        put(info)
    }

    private fun put(info: RelayInfo) {
        val id = info.identity.nodeId
        // 1) Retirar del indice invertido las capacidades ANTERIORES. Sin este
        //    paso, un peer que deja de ser RELAY seguiria apareciendo en
        //    `peersWith(RELAY)`: el indice contaria una historia vieja.
        byIdentity[id]?.capabilities?.forEach { cap ->
            byCapability[cap]?.remove(id)
        }
        // 2) Alta en el cache.
        byIdentity[id] = info
        // 3) Alta en el indice invertido por cada capacidad nueva.
        info.capabilities.forEach { cap ->
            byCapability.getOrPut(cap) { LinkedHashSet() }.add(id)
        }
    }

    /** El [RelayInfo] de un peer, o `null` si no se conoce. */
    @Synchronized
    fun get(id: IdentityId): RelayInfo? = byIdentity[id]

    /** Todos los peers conocidos, en orden de insercion estable. */
    @Synchronized
    fun all(): List<RelayInfo> = byIdentity.values.toList()

    /** Identidades que anunciaron tener [capability]. */
    @Synchronized
    fun peersWith(capability: NodeCapability): Set<IdentityId> =
        byCapability[capability]?.toSet() ?: emptySet()

    /**
     * Peers que pueden relevar (`RELAY`), ordenados por criterio de preferencia.
     *
     * Orden: menor latencia primero (la desconocida, `-1`, va al final), y a
     * igualdad, la identidad como desempate para que el resultado sea estable
     * entre ejecuciones (INV-07: una lista que se compara no puede depender del
     * recorrido de un mapa).
     */
    @Synchronized
    fun relays(): List<RelayInfo> = peersWith(NodeCapability.RELAY)
        .mapNotNull { byIdentity[it] }
        .sortedWith(
            compareBy(
                { if (it.latencyMs < 0) Long.MAX_VALUE else it.latencyMs },
                { it.identity.nodeId.value },
            ),
        )

    /** Numero de peers conocidos. */
    @Synchronized
    fun size(): Int = byIdentity.size

    /** `true` si no hay ningun peer con capacidades conocidas. */
    @Synchronized
    fun isEmpty(): Boolean = byIdentity.isEmpty()

    /** `true` si hoy no hay ningun peer que pueda relevar. Estado soportado. */
    @Synchronized
    fun hasNoRelay(): Boolean = peersWith(NodeCapability.RELAY).isEmpty()

    /** Elimina a un peer del cache y del indice invertido. */
    @Synchronized
    fun remove(id: IdentityId) {
        byIdentity[id]?.capabilities?.forEach { cap -> byCapability[cap]?.remove(id) }
        byIdentity.remove(id)
    }

    // ===================================================================
    // Semillas de bootstrap (A1.2): endpoints sin identidad verificada
    // ===================================================================

    /** Registra una semilla. La clave es la direccion; repetirla la actualiza. */
    @Synchronized
    fun addSeed(seed: RelaySeed) {
        seeds[seed.address] = seed
    }

    /** Registra varias semillas. */
    @Synchronized
    fun addSeeds(newSeeds: List<RelaySeed>) {
        newSeeds.forEach { addSeed(it) }
    }

    /** Semillas conocidas, en orden de insercion. */
    @Synchronized
    fun seeds(): List<RelaySeed> = seeds.values.toList()

    /** Vacia peers y semillas. */
    @Synchronized
    fun clear() {
        byIdentity.clear()
        byCapability.clear()
        seeds.clear()
    }
}
