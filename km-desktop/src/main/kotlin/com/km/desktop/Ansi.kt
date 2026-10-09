package com.km.desktop

/**
 * Color de la TUI con secuencias SGR (ECMA-48) y apagado automatico.
 *
 * POR QUE NO HAY `kansi`
 *
 * El diseno de `km-desktop` pedia `kansi` para esto, pero la libreria no esta
 * publicada en ningun repositorio accesible: ni
 * `com.github.ajalt.kansi:kansi-jvm` en Maven Central (404), ni el grupo
 * `com.github.ajalt` de Central (solo `clikt`, `colormath`, `mordant` y
 * `timberkt`), ni el repositorio GitHub `ajalt/kansi` (404), ni JitPack.
 * Declararla haria que `shadowJar` no compilase.
 *
 * Lo que hace falta para colorear cuatro lineas de estado son las secuencias
 * SGR basicas, asi que estan aqui. Si `kansi` aparece, este objeto se
 * substituye y no cambia ninguna otra clase.
 *
 * El apagado no es decorativo: `NO_COLOR`, el absence de `TERM` y la
 * redireccion de stdout fuera de una terminal hacen que el texto salga limpio,
 * que es lo que necesitan los scripts y los logs.
 */
object Ansi {

    /** Color activo: se resuelve una vez, al construir la TUI. */
    var enabled: Boolean = false
        private set

    /**
     * Decide si hay color y lo deja establecido en [enabled].
     *
     * @param stdoutTerminal `true` si stdout es una terminal interactiva.
     * @param env entorno del proceso, inyectado para poder probarlo.
     */
    fun detect(stdoutTerminal: Boolean, env: Map<String, String> = System.getenv()) {
        enabled = stdoutTerminal &&
            !env.containsKey("NO_COLOR") &&
            !env["TERM"].isNullOrBlank() &&
            env["TERM"] != "dumb"
    }

    private const val RESET = "\u001B[0m"

    private fun wrap(code: String, text: String): String =
        if (enabled) "\u001B[${code}m$text$RESET" else text

    /** Titulos y la primera palabra de un estado. */
    fun bold(text: String): String = wrap("1", text)

    fun dim(text: String): String = wrap("2", text)

    fun green(text: String): String = wrap("32", text)

    fun yellow(text: String): String = wrap("33", text)

    fun red(text: String): String = wrap("31", text)

    fun cyan(text: String): String = wrap("36", text)

    /** Prefijo `km> ` del prompt. */
    fun prompt(): String = if (enabled) "\u001B[36mkm>\u001B[0m " else "km> "
}