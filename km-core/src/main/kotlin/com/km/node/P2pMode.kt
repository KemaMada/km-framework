package com.km.node

/**
 * Modo del tier P2P de un cliente, derivado de una variable de entorno o de un
 * ajuste persistido.
 *
 * ## Por que un tri-estado y no un booleano
 *
 * Un booleano no distingue dos despliegues MUY distintos:
 *
 * - `AUTO`: intenta P2P solo si hay un servidor TURN utilizable; si no, se
 *   queda en relay con un motivo estable (`p2p_no_ice` sin configuracion,
 *   `p2p_no_turn` con STUN solo). Es el modo de produccion.
 * - `ON`: exige P2P (LAN, pruebas). Declara que expone IP local y falla si no
 *   hay ICE o si solo hay STUN (sin relay), en vez de degradar en silencio.
 *
 * ## Compatibilidad
 *
 * `true/1/yes` valen [AUTO] (no [ON]): un despliegue que ponia `true` antes de
 * que existiera el tri-estado espera el comportamiento tolerante, no el
 * estricto.
 *
 * Valores no reconocidos —incluido vacio— caen a [OFF]: un typo no enciende la
 * red P2P.
 *
 * ## Donde vive
 *
 * En `km-core` y no en un cliente: la politica del medio es la misma en movil y
 * escritorio, y duplicarla seria justo lo que el framework evita. El nombre de
 * la variable lo pone cada cliente ([fromEnv] recibe la clave).
 */
enum class P2pMode {

    /** Relay-only, byte a byte. El default. */
    OFF,

    /** P2P si hay TURN configurado; si no, relay con motivo `p2p_no_turn`. */
    AUTO,

    /** Exige P2P. Sin TURN es un error de configuracion, no una degradacion. */
    ON,
    ;

    /** Si este modo puede llegar a construir el backend P2P. */
    val attemptsP2p: Boolean get() = this != OFF

    /** Etiqueta estable para DIAGNOSTICO. */
    val wireName: String get() = name.lowercase()

    companion object {
        /** La variable historica del escritorio; el default de [fromEnv]. */
        const val ENV_VAR: String = "KM_DESKTOP_P2P"

        /**
         * Interpreta el valor crudo.
         *
         * `on` se comprueba ANTES de la tabla booleana a proposito: un parser
         * booleano trata `on` como `true`, y aqui `on` es el modo estricto.
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

        /**
         * Resuelve el modo desde un entorno (inyectable para tests), leyendo la
         * variable historica del escritorio.
         */
        fun fromEnv(
            env: (String) -> String? = { name -> System.getenv(name) },
        ): P2pMode = parse(env(ENV_VAR))

        /**
         * Resuelve el modo desde una variable con nombre explicito.
         *
         * Es la puerta del movil, que no usa la variable del escritorio; no se
         * mete como segundo parametro de [fromEnv] para que una trailing lambda
         * siga ligando al entorno y no al nombre.
         */
        fun fromEnvVar(
            key: String,
            env: (String) -> String? = { name -> System.getenv(name) },
        ): P2pMode = parse(env(key))
    }
}
