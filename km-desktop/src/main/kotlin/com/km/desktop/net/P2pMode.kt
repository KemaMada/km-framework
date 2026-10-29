package com.km.desktop.net

/**
 * Modo del tier P2P del escritorio, derivado de `KM_DESKTOP_P2P`.
 *
 * ## Por que un tri-estado y no el booleano
 *
 * `KmFlags.DESKTOP_P2P` es y sigue siendo un booleano: su definicion esta fijada
 * por `DesktopP2pFlagTest` y este cambio no la toca. Pero "encendido" no
 * distingue dos despliegues MUY distintos:
 *
 * - `AUTO`: intenta P2P solo si hay un servidor TURN utilizable; si no, se
 *   queda en relay con un motivo estable (`p2p_no_ice` sin configuracion,
 *   `p2p_no_turn` con STUN solo). Es el modo de produccion.
 * - `ON`: exige P2P (LAN, pruebas). Declara que expone IP local y falla si no
 *   hay ICE o si solo hay STUN (sin relay), en vez de degradar en silencio.
 *
 * Un booleano obligaria a deducir el modo mirando si hay ICE, y esa deduccion
 * apareceria en cada consumidor. Aqui se resuelve UNA vez, en un sitio.
 *
 * ## Compatibilidad
 *
 * El mismo `KM_DESKTOP_P2P` que antes se leia como booleano se sigue leyendo,
 * con la traduccion historica: `true/1/yes` valen [AUTO] (no [ON]), porque un
 * despliegue que ponia `true` antes de que existiera el tri-estado espera el
 * comportamiento tolerante, no el estricto.
 *
 * Valores no reconocidos —incluido vacio— caen a [OFF]: un typo en el entorno
 * no enciende la red P2P.
 */
enum class P2pMode {

    /** Relay-only, byte a byte. El default. */
    OFF,

    /** P2P si hay ICE configurado; si no, relay con motivo `p2p_no_ice`. */
    AUTO,

    /** Exige P2P. Sin ICE es un error de configuracion, no una degradacion. */
    ON,
    ;

    /** Si este modo puede llegar a construir el backend P2P. */
    val attemptsP2p: Boolean get() = this != OFF

    /** Etiqueta estable para DIAGNOSTICO. */
    val wireName: String get() = name.lowercase()

    companion object {
        /** La variable de entorno, la misma que declara `KmFlags.DESKTOP_P2P`. */
        const val ENV_VAR: String = "KM_DESKTOP_P2P"

        /**
         * Interpreta el valor crudo.
         *
         * `on` se comprueba ANTES de la tabla booleana a proposito:
         * `KmFlags.parseBoolean` trata `on` como `true`, y aqui `on` es el modo
         * estricto. Reusar ese parser convertiria `on` en [AUTO] por accidente.
         */
        fun parse(raw: String?): P2pMode {
            val normalized = raw?.trim()?.lowercase() ?: return OFF
            return when (normalized) {
                "on" -> ON
                "off", "false", "0", "no", "disabled", "" -> OFF
                "auto", "true", "1", "yes", "enabled" -> AUTO
                else -> OFF
            }
        }

        /** Resuelve el modo desde el entorno (inyectable para tests). */
        fun fromEnv(env: (String) -> String? = { name -> System.getenv(name) }): P2pMode =
            parse(env(ENV_VAR))
    }
}
