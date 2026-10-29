package com.example.keymessage.config

import com.km.transport.JvmRelayTransport
import com.example.keymessage.util.KMLog

/**
 * 3.1 — De donde sale el endpoint del rele, y en que ORDEN.
 *
 * ## LA REGLA
 *
 * Tres fuentes, y la precedencia es la unica que permite operarlo sin
 * recompilar:
 *
 *  1. **Override de runtime** — `Settings.Global` con la clave
 *     [SETTINGS_GLOBAL_KEY], lista separada por comas. Escribible por ADB.
 *  2. **`BuildConfig`** — lo que fijo la compilacion.
 *  3. **Default embebido** — [JvmRelayTransport.DEFAULT_ENDPOINT].
 *
 * El default NO se escribe a mano en la app: se lee del motor de p4, de modo
 * que un cambio de endpoint en el framework llega a la app sin que las dos
 * copias puedan separarse. Es la misma regla que "el motor de wire es UNO".
 *
 * ## MULTIPLES BOOTSTRAPS
 *
 * El resultado es una LISTA ORDENADA, no un `String`. Un solo endpoint
 * escrito en el codigo era exactamente el problema que esta clase elimina: con
 * uno, quien no puede alcanzarlo no tiene a donde ir.
 *
 * ## UNA ENTRADA EN BLANCO ES UN ERROR
 *
 * Una lista que se filtra en silencio acaba siendo la lista vacia, y una lista
 * vacia en el camino de arranque se parece muchisimo a "el rele no contesta".
 * Por eso una entrada vacia LANZA, y [RelayEndpointConfigException] lleva la
 * entrada que la produjo.
 */
class RelayEndpointConfigException(message: String) : IllegalArgumentException(message)

/** Lista ordenada de endpoints y de donde sale. */
data class ResolvedEndpoints(
    val endpoints: List<String>,
    val source: Source,
) {
    /** El primero: el que se intenta antes de los demas. */
    val preferred: String get() = endpoints.first()

    enum class Source { RUNTIME_OVERRIDE, BUILD_CONFIG, DEFAULT }
}

object RelayEndpointConfig {

    /** Clave de `Settings.Global` que se puede escribir por ADB. */
    const val SETTINGS_GLOBAL_KEY: String = "km_relay_ws_url"

    /**
     * Resuelve la lista de endpoints aplicando la precedencia.
     *
     * @param runtimeOverride contenido de [SETTINGS_GLOBAL_KEY], o `null`.
     * @param buildConfig `RELAY_WS_URL` de la compilacion, o `null`.
     * @param default el default del motor. Por defecto es el de p4.
     * @throws RelayEndpointConfigException si la fuente winner no contiene
     *   ninguna entrada utilizable. NUNCA devuelve una lista vacia: quien llama
     *   no tendria nada que registrar en `[BOOTSTRAP_ALL_FAILED]`.
     */
    fun resolve(
        runtimeOverride: String? = null,
        buildConfig: String? = null,
        default: String = JvmRelayTransport.DEFAULT_ENDPOINT,
    ): ResolvedEndpoints {
        val deRuntime = parse(runtimeOverride, ResolvedEndpoints.Source.RUNTIME_OVERRIDE, SETTINGS_GLOBAL_KEY)
        if (deRuntime != null) return deRuntime

        val deBuild = parse(buildConfig, ResolvedEndpoints.Source.BUILD_CONFIG, "RELAY_WS_URL")
        if (deBuild != null) return deBuild

        val delDefault = parse(default, ResolvedEndpoints.Source.DEFAULT, "DEFAULT_ENDPOINT")
            ?: throw RelayEndpointConfigException(
                "el default del motor no es un endpoint utilizable: vacio",
            )
        KMLog.event("[CONFIG]", "endpoints=${delDefault.endpoints} origen=DEFAULT")
        return delDefault
    }

    /**
     * Convierte una fuente en lista, o `null` si la fuente NO esta.
     *
     * Separar "no configurada" de "configurada pero vacia" es lo que permite que
     * la precedencia caiga al siguiente nivel sin que un override en blanco
     * parezca un default.
     */
    private fun parse(raw: String?, source: ResolvedEndpoints.Source, nombre: String): ResolvedEndpoints? {
        if (raw == null) return null
        if (raw.isBlank()) {
            throw RelayEndpointConfigException(
                "$nombre esta definido pero vacio: un override en blanco es un error de " +
                    "configuracion, no una peticion de valores por defecto",
            )
        }
        val entradas = raw.split(',').map { it.trim() }
        if (entradas.isEmpty() || entradas.all { it.isEmpty() }) {
            throw RelayEndpointConfigException("$nombre no contiene ninguna entrada utilizable")
        }
        entradas.forEachIndexed { i, e ->
            if (e.isEmpty()) {
                throw RelayEndpointConfigException(
                    "$nombre tiene una entrada vacia en la posicion $i de ${entradas.size}: " +
                        "separa los endpoints por comas y no dejes huecos",
                )
            }
        }
        KMLog.event("[CONFIG]", "endpoints=$entradas origen=${source.name}")
        return ResolvedEndpoints(entradas, source)
    }
}