package com.km.desktop.shell

/**
 * Como se presenta el cliente en el terminal.
 *
 * ## LOS TRES MODOS
 *
 * - [TUI]: pantallas con cajas, foco y repintado (defecto de G2).
 * - [ACCESSIBLE]: salida LINEAL y append-only, sin direccionamiento de cursor.
 *   Es el modo para lectores de pantalla y para terminales que no repintan bien.
 * - [LINE]: el REPL de G1, intacto. Sirve para scripts y como salida de
 *   emergencia si la terminal no da 60x20.
 *
 * `--ascii` no es un modo: es una variante de presentacion de [TUI]/[ACCESSIBLE]
 * que evita glifos Unicode. Se modela aparte porque puede combinarse con
 * cualquiera de los tres, mientras que los tres modos son excluyentes.
 */
enum class ShellMode {
    TUI,
    ACCESSIBLE,
    LINE,
}
