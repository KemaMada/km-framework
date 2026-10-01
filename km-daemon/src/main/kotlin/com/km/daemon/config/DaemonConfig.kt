package com.km.daemon.config

/**
 * Configuracion del `km daemon`.
 *
 * MVP deliberadamente pequeno: los valores por defecto son los que el propio
 * `docs/protocol/KM-WIRE-RELAY.md` declara como normativos, de modo que un
 * daemon recien lanzado habla el dialecto sin tocar nada.
 *
 * Los limites del camino de DATAS (payload de 65 535 B, ventana de
 * timestamp de 300 000 ms, nonce de 16 B) NO estan aqui: son parametros de
 * `com.km.node.RelayServer` y ya tienen su valor en km-core. Este modulo no los
 * duplica ni los reimplementa.
 */
data class DaemonConfig(
    /** Interfaz de escucha. `"0.0.0.0"` es el valor de un VPS. */
    val listenHost: String = DEFAULT_LISTEN_HOST,

    /** Puerto TCP de escucha. */
    val listenPort: Int = DEFAULT_LISTEN_PORT,

    /**
     * Ruta del fichero de identidad del relé.
     *
     * Contiene el par Ed25519 con el que el daemon firma `AUTH_OK`. Si no
     * existe, se genera uno nuevo y se persiste. Borrarlo cambia la
     * identidad del relé y, con ella, la `responderIdentityId` contra la que
     * los clientes construyen el transcript de 152 B.
     */
    val identityPath: String = DEFAULT_IDENTITY_PATH,

    /** Camino de datos opacos (`MESSAGE`). Desactivado = el relé no enruta carga. */
    val relayEnabled: Boolean = true,

    /** Señalización WebRTC (SDP/ICE). Desactivado = solo datos y presencia. */
    val signalingEnabled: Boolean = true,

    /**
     * Vida maxima de una sesion autenticada, en milisegundos.
     *
     * Coincide con el `sessionExpiryMs` por defecto de `RelayServer`
     * (KM-WIRE-RELAY §15.1). Se pasa a su constructor, no se reimplementa.
     */
    val sessionExpiryMs: Long = DEFAULT_SESSION_EXPIRY_MS,

    /** Conexiones simultaneas. Coincide con `RelayLimits.maxConnections` (512). */
    val maxConnections: Int = DEFAULT_MAX_CONNECTIONS,
) {
    init {
        require(listenPort in 1..65535) { "listenPort fuera de rango: $listenPort" }
        require(maxConnections > 0) { "maxConnections debe ser positivo: $maxConnections" }
        require(sessionExpiryMs > 0) { "sessionExpiryMs debe ser positivo: $sessionExpiryMs" }
    }

    companion object {
        const val DEFAULT_LISTEN_HOST: String = "0.0.0.0"
        const val DEFAULT_LISTEN_PORT: Int = 8080
        const val DEFAULT_IDENTITY_PATH: String = "./km-daemon.identity"
        const val DEFAULT_SESSION_EXPIRY_MS: Long = 300_000L
        const val DEFAULT_MAX_CONNECTIONS: Int = 512

        /** Nombre del fichero de configuracion opcional, relativo al directorio de trabajo. */
        const val CONFIG_FILE_NAME: String = "km-daemon.conf.json"

        /** Variable de entorno que puede apuntar a otro `km-daemon.conf.json`. */
        const val CONFIG_PATH_ENV: String = "KM_DAEMON_CONFIG"
    }
}
