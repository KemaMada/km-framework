package com.km.desktop.shell

/**
 * Sondeo MINIMO de soporte de raton, sin pty y sin escribir al terminal.
 *
 * ## POR QUE NO SE PREGUNTA AL TERMINAL
 *
 * El protocolo SGR 1006 no tiene un "¿lo soportas?" fiable y barato: xterm
 * responde a `?1000h` moviendo el raton, no a un handshake. Preguntarlo
 * exigiria un timeout de entrada y podria robar bytes de la primera tecla.
 * Por eso la deteccion es conservadora y DECLARADA (README): se apaga en
 * `TERM=dumb`, en una consola Linux sin SGR y cuando no hay terminal.
 *
 * tmux/conhost no se pueden distinguir de forma fiable desde aqui: con esos
 * multiplexores el tracking funciona si el usuario activa su propio raton
 * (`set -g mouse on`), y si no, `--mouse=off` es la salida. Se declara en el
 * README en vez de adivinar.
 */
object MouseSupport {

    /**
     * @param term valor de `TERM` (puede ser `null` o vacio).
     * @param console hay terminal interactiva en stdin/stdout.
     * @param typeDumb JLine ya dijo que la terminal es `dumb`.
     */
    fun hasMouseSupport(term: String?, console: Boolean, typeDumb: Boolean): Boolean {
        if (!console || typeDumb) return false
        val t = term?.trim()?.lowercase().orEmpty()
        if (t.isEmpty() || t == "dumb" || t.startsWith("dumb")) return false
        // La consola virtual Linux (Ctrl-Alt-F1) no habla SGR 1006.
        if (t == "linux") return false
        return true
    }
}
