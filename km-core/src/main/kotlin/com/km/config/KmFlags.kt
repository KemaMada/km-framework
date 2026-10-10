package com.km.config

/**
 * Definicion de un flag de configuracion del framework.
 *
 * Un flag tiene SIEMPRE:
 *  - [key]: la clave canonica (`km.*`) que identifica el comportamiento en
 *    documentacion, tests y overrides programaticos.
 *  - [envVar]: la variable de entorno `KM_*` que lo activa en despliegue. La
 *    convencion por defecto es `key.uppercase().replace('.', '_')`, pero se
 *    declara explicitamente porque el diseño fija algunos nombres concretos
 *    (p.ej. `KM_LEGACY_3001`, no `KM_SIGNALING_LEGACY3001`).
 *  - [default]: el valor seguro cuando no hay entorno ni override. Para esta
 *    arquitectura TODOS los flags de `transport-chain-architecture` nacen
 *    apagados: el comportamiento nuevo se enciende a proposito, nunca por
 *    omision.
 *  - [description]: que comportamiento gobierna, para que el registro sea
 *    autoexplicativo.
 *
 * Es un `data class` (no un enum) para que un consumidor pueda declarar su
 * propio [KmFlagDefinition] y reusar [KmFlags.isEnabled] sin tocar este
 * archivo.
 */
data class KmFlagDefinition(
    val key: String,
    val envVar: String,
    val default: Boolean,
    val description: String,
)

/**
 * Registro central de flags `KM_*` introducidos por
 * `transport-chain-architecture`.
 *
 * ## Contrato
 *
 *  - Cada flag tiene un valor por defecto DOCUMENTADO y seguro (todos `false`).
 *  - La resolucion sigue el orden: **override programatico > variable de
 *    entorno > default**. El override existe para tests y para incrustar el
 *    framework donde `System.getenv` no esta disponible.
 *  - Un valor de entorno que no se puede interpretar como booleano cae al
 *    default (nunca lanza): un typo en una variable de entorno no debe tumbar
 *    el arranque; se degrada al comportamiento seguro.
 *
 * ## Por que un registro central
 *
 * La separacion crypto/transporte (R12) y la reversibilidad por flag exigen
 * que cada slice se pueda apagar sin tocar codigo. Un unico punto de verdad
 * evita que dos modulos inventen nombres distintos para el mismo interruptor.
 *
 * Este archivo NO lee criptografia, red ni estado de sesion: es un resolvedor
 * de booleanos puro.
 */
object KmFlags {

    /**
     * A1 — modelo de capacidades de rol (PeerRoleDirectory, anuncio de
     * capacidades). Apagado: los nodos siguen sin intercambiar capacidades.
     */
    val ROLES_CAPABILITIES = KmFlagDefinition(
        key = "km.roles.capabilities",
        envVar = "KM_ROLES_CAPABILITIES",
        default = false,
        description = "Modelo de capacidades de rol (R1/S1): anuncio firmado de NodeCapability + PeerRoleDirectory.",
    )

    /**
     * A2.1/A2.2 — registro extensible de tipos de senalizacion.
     * Apagado: la senalizacion sigue el camino legado.
     */
    val SIGNALING_REGISTRY = KmFlagDefinition(
        key = "km.signaling.registry",
        envVar = "KM_SIGNALING_REGISTRY",
        default = false,
        description = "SignalTypeRegistry + Extension (R9/S12) y ventana de ofertas obsoletas (S13).",
    )

    /**
     * A2.3-A2.6 — contenedor `.km`, import de pubkey desnuda, descriptor
     * onion y QR render-only. Apagado.
     */
    val CONTACTFILE_ENABLED = KmFlagDefinition(
        key = "km.contactfile.enabled",
        envVar = "KM_CONTACTFILE_ENABLED",
        default = false,
        description = "Contenedor .km firmado, bare-pubkey import y descriptores .onion (R2-R4, R7).",
    )

    /**
     * A3 — cola efimera estricta del rele con TTL/cuotas y token efimero.
     * Apagado: el rele mantiene el comportamiento actual.
     */
    val RELAY_QUEUE_STRICT = KmFlagDefinition(
        key = "km.relay.queue.strict",
        envVar = "KM_RELAY_QUEUE_STRICT",
        default = false,
        description = "Cola efimera estricta del rele: TTL, cuotas por peer/global, token efimero (R11).",
    )

    /**
     * A4.1/A4.2 — ChainSupervisor con maquina de estados de failover y
     * re-intentos de tier-1. Apagado.
     */
    val CHAIN_PROBE = KmFlagDefinition(
        key = "km.chain.probe",
        envVar = "KM_CHAIN_PROBE",
        default = false,
        description = "ChainSupervisor observable + triggers de reintento de tier-1 (R5, R12).",
    )

    /**
     * A4.3/A4.4 — politica Tor (strictest-wins) y filtro de candidatos ICE.
     * Apagado.
     */
    val TOR_MODE = KmFlagDefinition(
        key = "km.tor.mode",
        envVar = "KM_TOR_MODE",
        default = false,
        description = "Negociacion de politica Tor y forzado SOCKS-only sin fugas de IP (R6, R8).",
    )

    /**
     * A5 — retiro del dialecto de senalizacion 3001/ChatManager.
     *
     * Por diseño el DEFAULT es OFF (se conserva el dialecto viejo). El
     * `envVar` es `KM_LEGACY_3001` — el nombre que fija el diseño — y no la
     * derivacion mecanica de la clave.
     */
    val SIGNALING_LEGACY3001 = KmFlagDefinition(
        key = "km.signaling.legacy3001",
        envVar = "KM_LEGACY_3001",
        default = false,
        description = "Dialecto de senalizacion WebSocket 3001 legado; OFF = camino por el registro del framework (R9).",
    )

    /**
     * Todos los flags declarados por `transport-chain-architecture`.
     *
     * X.1 (streams/files) NO aparece aqui a proposito: el diseño declara
     * `flag: none` para las interfaces de stream — se definen, no se encienden.
     * Inventar un flag para ellas violaria "cada flag introducido por el
     * diseño existe", que es una relacion bicondicional, no una invitacion.
     */
    val all: List<KmFlagDefinition> = listOf(
        ROLES_CAPABILITIES,
        SIGNALING_REGISTRY,
        CONTACTFILE_ENABLED,
        RELAY_QUEUE_STRICT,
        CHAIN_PROBE,
        TOR_MODE,
        SIGNALING_LEGACY3001,
    )

    /** Indexa el registro por clave canonica (`km.*`). */
    val byKey: Map<String, KmFlagDefinition> = all.associateBy { it.key }

    /** Indexa el registro por variable de entorno (`KM_*`). */
    val byEnvVar: Map<String, KmFlagDefinition> = all.associateBy { it.envVar }

    /**
     * Resuelve el valor efectivo de [definition].
     *
     * Prioridad: `overrides[key]` > `env(envVar)` > `definition.default`.
     *
     * @param env fuente de variables de entorno. Inyectable para tests; por
     *   defecto lee el proceso (`System.getenv`).
     * @param overrides mapa `key -> Boolean` que gana sobre el entorno.
     */
    fun isEnabled(
        definition: KmFlagDefinition,
        env: (String) -> String? = { name -> System.getenv(name) },
        overrides: Map<String, Boolean> = emptyMap(),
    ): Boolean {
        overrides[definition.key]?.let { return it }
        val raw = env(definition.envVar) ?: return definition.default
        return parseBoolean(raw, definition.default)
    }

    /**
     * Interpreta [raw] como booleano.
     *
     * Acepta `true/false`, `1/0`, `yes/no`, `on/off`, `enabled/disabled`
     * (case-insensitive, con espacios alrededor ignorados). Cualquier otro
     * valor — incluido vacio o `null` — devuelve [default]. No lanza nunca.
     */
    fun parseBoolean(raw: String?, default: Boolean): Boolean {
        val normalized = raw?.trim()?.lowercase() ?: return default
        return when (normalized) {
            "true", "1", "yes", "on", "enabled" -> true
            "false", "0", "no", "off", "disabled" -> false
            else -> default
        }
    }

    // --- Accesores de conveniencia: leen el entorno del proceso. ---

    /** `km.roles.capabilities` segun el entorno del proceso. */
    val rolesCapabilities: Boolean get() = isEnabled(ROLES_CAPABILITIES)

    /** `km.signaling.registry` segun el entorno del proceso. */
    val signalingRegistry: Boolean get() = isEnabled(SIGNALING_REGISTRY)

    /** `km.contactfile.enabled` segun el entorno del proceso. */
    val contactFileEnabled: Boolean get() = isEnabled(CONTACTFILE_ENABLED)

    /** `km.relay.queue.strict` segun el entorno del proceso. */
    val relayQueueStrict: Boolean get() = isEnabled(RELAY_QUEUE_STRICT)

    /** `km.chain.probe` segun el entorno del proceso. */
    val chainProbe: Boolean get() = isEnabled(CHAIN_PROBE)

    /** `km.tor.mode` segun el entorno del proceso. */
    val torMode: Boolean get() = isEnabled(TOR_MODE)

    /** `km.signaling.legacy3001` segun el entorno del proceso. */
    val signalingLegacy3001: Boolean get() = isEnabled(SIGNALING_LEGACY3001)
}
