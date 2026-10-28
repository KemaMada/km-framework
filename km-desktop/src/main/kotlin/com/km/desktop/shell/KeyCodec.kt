package com.km.desktop.shell

import com.km.desktop.ui.Key
import com.km.desktop.ui.KeyModifier
import com.km.desktop.ui.KeyStroke

/**
 * Traduce lo que llega del terminal a [KeyStroke].
 *
 * ## POR QUE ES UNA CLASE APARTE Y PURA
 *
 * Porque la decodificacion de teclas es la parte del adaptador que SI se puede
 * probar sin un pty: se le dan enteros o una secuencia de escape y se afirma la
 * tecla. El bucle solo aporta el `read()`.
 *
 * ## LAS SECUENCIAS DE ESCAPE
 *
 * Un terminal no manda "flecha arriba": manda `ESC [ A`. El bucle lee el ESC,
 * espera unos milisegundos y entrega la secuencia completa; si no llega nada,
 * era la tecla `Esc` sola. Esa espera es la razon de [ESC_TIMEOUT_MS]: sin ella
 * `Esc` no se puede distinguir del principio de una flecha.
 *
 * ## LO QUE NO SE INVENTA
 *
 * Una secuencia desconocida devuelve `null` (se ignora). Convertirla en una
 * tecla cualquiera haria que un terminal raro moviera el foco solo.
 */
object KeyCodec {

    /** Cuanto se espera el resto de una secuencia tras un ESC. */
    const val ESC_TIMEOUT_MS: Long = 40L

    /** Un unico caracter leido de la terminal. */
    fun decode(code: Int): KeyStroke? = when (code) {
        -1 -> null
        3 -> KeyStroke.ctrl('c')
        12 -> KeyStroke.ctrl('l')
        22 -> KeyStroke.ctrl('v')
        9 -> KeyStroke(Key.Tab)
        10, 13 -> KeyStroke(Key.Enter)
        8, 127 -> KeyStroke(Key.Backspace)
        27 -> KeyStroke(Key.Escape)
        in 32..0xD7FF -> KeyStroke(Key.Char(code.toChar()))
        in 0xE000..0xFFFD -> KeyStroke(Key.Char(code.toChar()))
        else -> null
    }

    /**
     * Una secuencia completa que empieza por ESC.
     *
     * Se compara por sufijo despues de `ESC [` / `ESC O` para tolerar los
     * modificadores (`ESC [ 1 ; 5 A` = Ctrl-Arriba) sin una tabla enorme: un
     * modificador no cambia la tecla base, y la UI no distingue Ctrl-flecha.
     */
    fun decodeSequence(sequence: String): KeyStroke? {
        if (sequence.isEmpty()) return null
        // Defensa: el SGR del raton empieza por `ESC[<` y NUNCA es una tecla.
        // El bucle ya lo desvia a `MouseCodec`; esto cubre una llamada directa.
        if (sequence.startsWith("\u001b[<")) return null
        if (sequence == "\u001b") return KeyStroke(Key.Escape)
        if (sequence == "\u001b\r" || sequence == "\u001b\n") {
            return KeyStroke(Key.Enter, setOf(KeyModifier.ALT))
        }
        if (sequence.length == 2 && sequence[0] == '\u001b') {
            return KeyStroke(Key.Char(sequence[1]), setOf(KeyModifier.ALT))
        }
        val cuerpo = when {
            sequence.startsWith("\u001b[") -> sequence.substring(2)
            sequence.startsWith("\u001bO") -> sequence.substring(2)
            else -> return null
        }
        val final = cuerpo.lastOrNull() ?: return null
        // Con modificador (`1;5A`) la tecla es la ULTIMA letra.
        return when {
            final == 'A' -> KeyStroke(Key.Up)
            final == 'B' -> KeyStroke(Key.Down)
            final == 'C' -> KeyStroke(Key.Right)
            final == 'D' -> KeyStroke(Key.Left)
            final == 'H' -> KeyStroke(Key.Home)
            final == 'F' -> KeyStroke(Key.End)
            final == 'Z' -> KeyStroke.shift(Key.Tab)
            cuerpo == "1~" || cuerpo == "7~" -> KeyStroke(Key.Home)
            cuerpo == "4~" || cuerpo == "8~" -> KeyStroke(Key.End)
            cuerpo == "3~" -> KeyStroke(Key.Delete)
            cuerpo == "5~" -> KeyStroke(Key.PageUp)
            cuerpo == "6~" -> KeyStroke(Key.PageDown)
            else -> null
        }
    }
}
