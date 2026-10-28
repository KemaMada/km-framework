package com.km.desktop.ui

/**
 * Cuando se activa el tracking del raton (`--mouse`).
 *
 * ## POR QUE ES UN TIPO Y NO UN BOOLEAN
 *
 * Porque `auto` NO es "si" ni "no": es una decision que solo se puede tomar al
 * arrancar, cuando ya se sabe si la terminal es viable (tamano, `TERM`) y si el
 * usuario no pidio un modo lineal. Guardar solo el boolean obligaria a repetir
 * esa resolucion en cada sitio que quiera explicar el valor efectivo.
 *
 * El valor EFECTIVO que muestra `?` es el boolean ya resuelto ([auto] incluido);
 * este enum es solo la intencion de la linea de comandos.
 */
enum class MouseMode(val flag: String) {
    AUTO("auto"),
    ON("on"),
    OFF("off"),

    ;

    companion object {
        /** El valor de `--mouse`, o `null` si no es uno de los tres. */
        fun parse(raw: String): MouseMode? =
            entries.firstOrNull { it.flag.equals(raw.trim(), ignoreCase = true) }
    }
}
