package com.km.desktop.ui

import com.km.desktop.client.ClientEvent

/**
 * Una tecla, YA decodificada.
 *
 * ## POR QUE NO SE MIRA `char[]` DE JLINE EN EL MODELO
 *
 * Porque el modelo tiene que probarse sin terminal. El decodificador de la Fase 3
 * traduce los eventos de JLine a [KeyStroke]; a partir de ahi nada sabe si la
 * tecla vino de un pty, de un pipe o de un test. Un `KeyStroke` construido a mano
 * es la misma entrada que una pulsacion real.
 */
enum class KeyModifier { CTRL, ALT, SHIFT }

/** Las teclas con nombre. Las imprimibles van en [Key.Char]. */
sealed interface Key {
    data class Char(val value: kotlin.Char) : Key
    data object Enter : Key
    data object Escape : Key
    data object Tab : Key
    data object Backspace : Key
    data object Delete : Key
    data object Up : Key
    data object Down : Key
    data object Left : Key
    data object Right : Key
    data object Home : Key
    data object End : Key
    data object PageUp : Key
    data object PageDown : Key
}

/**
 * Una tecla con sus modificadores.
 *
 * [printable] es `false` para todo lo que no sea un caracter visible sin Ctrl ni
 * Alt. Es la unica definicion que usa el keymap para decidir si una tecla es
 * DATO (va al campo de texto) o MANDO.
 */
data class KeyStroke(
    val key: Key,
    val modifiers: Set<KeyModifier> = emptySet(),
) {
    /** Un caracter visible sin Ctrl/Alt. */
    val printable: Boolean
        get() {
            val c = (key as? Key.Char)?.value ?: return false
            return c >= ' ' && KeyModifier.CTRL !in modifiers && KeyModifier.ALT !in modifiers
        }

    /** El texto que inserta, o `null` si es un mando. */
    val text: String? get() = if (printable) (key as Key.Char).value.toString() else null

    fun with(modifier: KeyModifier): KeyStroke = copy(modifiers = modifiers + modifier)

    companion object {
        fun char(value: Char): KeyStroke = KeyStroke(Key.Char(value))

        fun ctrl(value: Char): KeyStroke =
            KeyStroke(Key.Char(value), setOf(KeyModifier.CTRL))

        fun alt(value: Char): KeyStroke =
            KeyStroke(Key.Char(value), setOf(KeyModifier.ALT))

        fun alt(key: Key): KeyStroke = KeyStroke(key, setOf(KeyModifier.ALT))

        fun shift(key: Key): KeyStroke = KeyStroke(key, setOf(KeyModifier.SHIFT))
    }
}

/**
 * Todo lo que puede mover el estado.
 *
 * ## POR QUE HAY CUATRO Y NO UN `String`
 *
 * Porque las cuatro fuentes son distintas y no se pueden confundir: una tecla,
 * un evento del motor, un cambio de tamano y el resultado de un efecto. Un
 * unico `String` obligaria al reducer a parsear, que es justo lo que el cliente
 * evita en el resto del codigo.
 */
sealed interface ShellAction {

    /** Una pulsacion, ya decodificada. */
    data class Key(val stroke: KeyStroke) : ShellAction

    /** Algo que publico un handler del core o del transporte. NUNCA imprime. */
    data class Event(val event: ClientEvent) : ShellAction

    /** SIGWINCH o medida inicial. */
    data class Resize(val viewport: Size) : ShellAction

    /** Un efecto termino: exito o codigo estable. */
    data class EffectDone(val result: EffectResult) : ShellAction
}
