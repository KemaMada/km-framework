package com.km.desktop.ui.theme

/**
 * Cuanto color entiende el terminal.
 *
 * La capacidad se resuelve en `DesktopMain` (NO_COLOR, TERM, COLORTERM,
 * `--ascii`) y viaja al escritor. `ui/` solo la usa para decidir si pide
 * glifos ricos; el texto NUNCA depende de ella.
 *
 * [MONO] no emite ni un atributo: la jerarquia la hacen `▌`, `>`, `─`,
 * mayusculas de seccion y etiquetas. Es el modo que exige R21.
 */
enum class ColorCapability {
    TRUECOLOR,
    ANSI256,
    ANSI16,
    MONO,
    ;

    val sgr: Boolean get() = this != MONO

    companion object {

        /**
         * Capacidad a partir del entorno, sin tocar `System.getenv` por dentro.
         *
         * @param attributes `false` apaga todo (NO_COLOR, pipe, `--ascii`).
         */
        fun detect(attributes: Boolean, env: Map<String, String> = emptyMap()): ColorCapability {
            if (!attributes) return MONO
            val colorTerm = env["COLORTERM"]?.lowercase().orEmpty()
            if ("truecolor" in colorTerm || "24bit" in colorTerm) return TRUECOLOR
            if ("256" in env["TERM"].orEmpty()) return ANSI256
            return ANSI16
        }
    }
}
