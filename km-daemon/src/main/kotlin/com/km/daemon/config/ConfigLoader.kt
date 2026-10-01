package com.km.daemon.config

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

/**
 * Carga la configuracion del daemon con una precedencia explicita y corta:
 *
 * ```
 * defaults  <  km-daemon.conf.json  <  variables de entorno KM_DAEMON_*
 * ```
 *
 * El fichero JSON es OPCIONAL: si no existe, se arranca con los valores por
 * defecto y se sigue. Las variables de entorno ganan sobre el fichero porque
 * son lo que un gestor de procesos (systemd, PM2, un contenedor) puede
 * inyectar sin montar ficheros.
 *
 * Politica de parseo, coherente con `AuthJsonCodec` (KM-WIRE-RELAY §7.1):
 * una clave desconocida se IGNORA; una clave conocida con el tipo equivocado es
 * un error de arranque, no un valor silenciosamente sustituido por el
 * defecto. Un daemon que arranca con la configuracion equivocada y no lo dice
 * es peor que uno que no arranca.
 */
object ConfigLoader {

    private val mapper = ObjectMapper()

    private const val PREFIX = "KM_DAEMON_"
    private const val ENV_LISTEN_HOST = "${PREFIX}LISTEN_HOST"
    private const val ENV_LISTEN_PORT = "${PREFIX}LISTEN_PORT"
    private const val ENV_IDENTITY_PATH = "${PREFIX}IDENTITY_PATH"
    private const val ENV_RELAY_ENABLED = "${PREFIX}RELAY_ENABLED"
    private const val ENV_SIGNALING_ENABLED = "${PREFIX}SIGNALING_ENABLED"
    private const val ENV_SESSION_EXPIRY_MS = "${PREFIX}SESSION_EXPIRY_MS"
    private const val ENV_MAX_CONNECTIONS = "${PREFIX}MAX_CONNECTIONS"

    /**
     * @param env lectura de variables de entorno; inyectable para poder probar
     *   sin tocar el proceso real.
     * @param configPath fichero de configuracion. Por defecto, el que indique
     *   `KM_DAEMON_CONFIG`, o `./km-daemon.conf.json`.
     * @return configuracion efectiva.
     * @throws IllegalArgumentException si algun valor es de un tipo o rango
     *   que no puede ser valido. Falla ruidosamente, a proposito.
     */
    fun load(
        env: (String) -> String? = System::getenv,
        configPath: Path? = null,
    ): DaemonConfig {
        val file = resolveConfigFile(env, configPath)
        val fromFile: JsonNode? = file
            ?.takeIf { Files.isRegularFile(it) }
            ?.let { readJson(it) }

        val defaults = DaemonConfig()

        return DaemonConfig(
            listenHost = text(env, fromFile, "listenHost", ENV_LISTEN_HOST)
                ?: defaults.listenHost,
            listenPort = integer(env, fromFile, "listenPort", ENV_LISTEN_PORT)
                ?: defaults.listenPort,
            identityPath = text(env, fromFile, "identityPath", ENV_IDENTITY_PATH)
                ?: defaults.identityPath,
            relayEnabled = flag(env, fromFile, "relayEnabled", ENV_RELAY_ENABLED)
                ?: defaults.relayEnabled,
            signalingEnabled = flag(env, fromFile, "signalingEnabled", ENV_SIGNALING_ENABLED)
                ?: defaults.signalingEnabled,
            sessionExpiryMs = long(env, fromFile, "sessionExpiryMs", ENV_SESSION_EXPIRY_MS)
                ?: defaults.sessionExpiryMs,
            maxConnections = integer(env, fromFile, "maxConnections", ENV_MAX_CONNECTIONS)
                ?: defaults.maxConnections,
        )
    }

    /**
     * Ruta del fichero de configuracion, o `null` si se pasa uno explicito
     * que no existe (para no caer por sorpresa al fichero del directorio de
     * trabajo).
     */
    private fun resolveConfigFile(env: (String) -> String?, configPath: Path?): Path? {
        if (configPath != null) return configPath
        val fromEnv = env(DaemonConfig.CONFIG_PATH_ENV)
        return if (fromEnv.isNullOrBlank()) {
            Paths.get(DaemonConfig.CONFIG_FILE_NAME)
        } else {
            Paths.get(fromEnv)
        }
    }

    private fun readJson(path: Path): JsonNode {
        val node = runCatching { mapper.readTree(Files.readAllBytes(path)) }
            .getOrElse { throw IllegalArgumentException("configuracion ilegible en $path: ${it.message}", it) }
        require(node != null && node.isObject) { "la configuracion de $path debe ser un objeto JSON" }
        return node
    }

    // --- campos ------------------------------------------------------------

    private fun text(env: (String) -> String?, file: JsonNode?, key: String, envName: String): String? =
        env(envName) ?: file?.get(key)?.let { require(it.isTextual) { "$key debe ser un string" }; it.asText() }

    private fun integer(env: (String) -> String?, file: JsonNode?, key: String, envName: String): Int? {
        env(envName)?.let { return parseInt(it, envName) }
        file?.get(key)?.let {
            require(it.isIntegralNumber) { "$key debe ser un entero" }
            return it.asInt()
        }
        return null
    }

    private fun long(env: (String) -> String?, file: JsonNode?, key: String, envName: String): Long? {
        env(envName)?.let { return parseLong(it, envName) }
        file?.get(key)?.let {
            require(it.isIntegralNumber) { "$key debe ser un entero" }
            return it.asLong()
        }
        return null
    }

    private fun flag(env: (String) -> String?, file: JsonNode?, key: String, envName: String): Boolean? {
        env(envName)?.let { raw -> return parseBoolean(raw, envName) }
        file?.get(key)?.let {
            require(it.isBoolean) { "$key debe ser true o false" }
            return it.asBoolean()
        }
        return null
    }

    private fun parseInt(raw: String, source: String): Int =
        raw.trim().toIntOrNull() ?: throw IllegalArgumentException("$source debe ser un entero, recibido '$raw'")

    private fun parseLong(raw: String, source: String): Long =
        raw.trim().toLongOrNull() ?: throw IllegalArgumentException("$source debe ser un entero, recibido '$raw'")

    /**
     * Solo `true`/`false` en cualquier caja. Un `1`, un `yes` o un `""` se
     * rechazan: una bandera malinterpretada abre o cierra el camino de datos
     * sin que nadie lo haya pedido.
     */
    private fun parseBoolean(raw: String, source: String): Boolean = when (raw.trim().lowercase()) {
        "true" -> true
        "false" -> false
        else -> throw IllegalArgumentException("$source debe ser 'true' o 'false', recibido '$raw'")
    }
}
