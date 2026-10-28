package com.km.desktop.shell

/**
 * Un evento de raton ya decodificado del protocolo SGR 1006.
 *
 * Las coordenadas son CELDAS base 0 (la terminal las manda base 1): asi el
 * hit-map y la rejilla hablan el mismo idioma y nadie resta uno en mitad del
 * reducer.
 */
sealed interface MouseEvent {
    val column: Int
    val row: Int

    /** Boton pulsado. [button] 0 = izquierdo; 1 medio; 2 derecho. */
    data class Press(
        val button: Int,
        override val column: Int,
        override val row: Int,
    ) : MouseEvent

    /** Boton soltado. Conserva [button] porque un release del derecho no actua. */
    data class Release(
        val button: Int,
        override val column: Int,
        override val row: Int,
    ) : MouseEvent

    /** Rueda: [up] `true` = muesca hacia arriba. */
    data class Wheel(
        val up: Boolean,
        override val column: Int,
        override val row: Int,
    ) : MouseEvent

    /** Cualquier otro boton (medio, derecho, botones 4+): se descarta. */
    data class Other(
        val button: Int,
        override val column: Int,
        override val row: Int,
    ) : MouseEvent
}

/**
 * Decodificador PURO de `ESC[<b;x;yM` (press) y `...m` (release).
 *
 * ## POR QUE NO SE DELEGA EN JLINE
 *
 * Porque `terminal.readMouseEvent` solo existe con el modo raton de JLine
 * activado y obliga a probar con un pty. Una funcion de `String` a
 * [MouseEvent] se prueba en JVM, que es donde se prueba todo el resto de la
 * entrada (`KeyCodec`).
 *
 * ## LO QUE SE DESCARTA SIN CAMBIAR ESTADO
 *
 * - secuencias truncadas (sin `M`/`m` final): el bucle las traga aparte y no
 *   llegan aqui;
 * - parametros vacios, no numericos o de mas de 4 digitos;
 * - coordenadas 0 (la terminal usa base 1);
 * - botones que no son izquierdo ni rueda vertical;
 * - **movimiento** (`b and 32`): hover y arrastre quedan FUERA de alcance
 *   (R28/R33). Change B solo pide `?1000`, pero un multiplexor puede reenviar
 *   motion igualmente; si se leyera como press, un arrastre acabaria en clic;
 * - **release sin boton** (botonera `3`): no corresponde a ningun press y no
 *   hay nada que soltar.
 *
 * Un release de un boton valido pero sin press previo SI se decodifica: quien
 * decide que no hay clic es `ClickTracker`, que olvida el gesto y devuelve
 * `null` (el codec es puro y no guarda estado).
 *
 * Un `null` significa "esto no es un evento utilizable", nunca "un evento con
 * valores inventados".
 */
object MouseCodec {

    /** Cabecera de una secuencia SGR de raton. */
    const val PREFIX: String = "\u001b[<"

    /** Digitios maximos por parametro: 4 admite terminales de hasta 9999 celdas. */
    const val MAX_DIGITS: Int = 4

    /** Bit de la botonera que marca MOVIMIENTO (hover/arrastre), no un boton. */
    const val MOTION_BIT: Int = 32

    /** Botonera `3` en las dos posiciones bajas = "sin boton". */
    private const val BOTON_NINGUNO: Int = 3

    fun decode(sequence: String): MouseEvent? {
        if (!sequence.startsWith(PREFIX)) return null
        if (sequence.length <= PREFIX.length + 1) return null
        val final = sequence.last()
        if (final != 'M' && final != 'm') return null
        val cuerpo = sequence.substring(PREFIX.length, sequence.length - 1)
        val partes = cuerpo.split(';')
        if (partes.size != 3) return null
        if (partes.any { it.isEmpty() || it.length > MAX_DIGITS || it.any { c -> c !in '0'..'9' } }) {
            return null
        }
        val b = partes[0].toIntOrNull() ?: return null
        val x = partes[1].toIntOrNull() ?: return null
        val y = partes[2].toIntOrNull() ?: return null
        if (x < 1 || y < 1) return null
        val column = x - 1
        val row = y - 1
        val boton = b and 0b11
        val rueda = b and 64 != 0
        val movimiento = b and MOTION_BIT != 0
        return when {
            // Hover/arrastre: no se piden 1002/1003, pero si un multiplexor
            // reenvia motion no puede convertirse en press (ni en rueda).
            movimiento -> null
            rueda && boton <= 1 -> MouseEvent.Wheel(up = boton == 0, column = column, row = row)
            rueda -> MouseEvent.Other(boton, column, row)
            // `3` = sin boton: un release asi no cierra ningun gesto.
            final == 'm' && boton == BOTON_NINGUNO -> null
            final == 'm' -> MouseEvent.Release(boton, column, row)
            boton == 0 -> MouseEvent.Press(boton, column, row)
            else -> MouseEvent.Other(boton, column, row)
        }
    }
}
