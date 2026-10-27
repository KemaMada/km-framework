package com.km.desktop.ui

/**
 * La pila de navegacion de la TUI, PURA e INMUTABLE.
 *
 * ## POR QUE UNA PILA Y NO `screen = ...`
 *
 * Porque `Esc` tiene que tener un nivel por pulsacion. Sin pila no hay "volver a
 * la pantalla anterior" y el usuario queda en un callejon: en la app Android esa
 * fue exactamente la regresion que cerro `TuiNavegacion`. Esta clase es la misma
 * idea, con los nombres del escritorio.
 *
 * ## `substitute` NO ES `replace`
 *
 * - [substitute] cambia la CIMA: es el cierre de un dialogo con navegacion. Si
 *   importar con exito APILARA la conversacion, el `Esc` desde el chat volveria a
 *   abrir el dialogo de importar —con su portador perdido—, que no es "la
 *   pantalla anterior" sino un bucle. La spec R4 lo exige: importar no apila.
 * - [replace] descarta TODA la pila y deja [Screen] como raiz. Se usa cuando el
 *   contexto anterior deja de existir (restaurar identidad, por ejemplo).
 *
 * ## POR QUE NO ES UN `data class`
 *
 * Porque `copy` es publico en un `data class` y podria construir una pila vacia
 * saltandose el `require`. La igualdad se implementa aqui y no se expone ningun
 * constructor publico con lista.
 */
class ScreenStack private constructor(private val entries: List<Screen>) {

    /** La pantalla visible. */
    val current: Screen get() = entries.last()

    /** La pila de abajo a arriba. Es una copia: mutarla no cambia el estado. */
    val screens: List<Screen> get() = entries

    /** Hay una pantalla a la que volver. */
    val canGoBack: Boolean get() = entries.size > 1

    /**
     * Apila [screen].
     *
     * Repetir la pantalla actual NO apila una copia: dos entradas iguales harian
     * que un `Esc` pareciera no hacer nada.
     */
    fun push(screen: Screen): ScreenStack =
        if (current == screen) this else ScreenStack(entries + screen)

    /**
     * Desapila la cima.
     *
     * @return la pila nueva, o `null` en la raiz. `null` es la senal de "no hay
     *   pantalla anterior": en la raiz el `Esc` pide confirmacion de salida, no
     *   inventa un destino.
     */
    fun pop(): ScreenStack? =
        if (entries.size <= 1) null else ScreenStack(entries.dropLast(1))

    /** Cambia la cima por [screen]. Es el cierre de un dialogo con navegacion. */
    fun substitute(screen: Screen): ScreenStack =
        ScreenStack(entries.dropLast(1) + screen)

    /** Deja [screen] como unica entrada, descartando el historial. */
    fun replace(screen: Screen): ScreenStack = ScreenStack(listOf(screen))

    override fun equals(other: Any?): Boolean =
        this === other || (other is ScreenStack && other.entries == entries)

    override fun hashCode(): Int = entries.hashCode()

    override fun toString(): String = entries.joinToString(" > ")

    companion object {
        /** La pila inicial: solo INICIO. */
        fun initial(screen: Screen = Screen.INICIO): ScreenStack = ScreenStack(listOf(screen))

        /**
         * Una pila con [screens] de abajo a arriba.
         *
         * Existe para tests y para reconstruir un estado persistido; no admite
         * una pila vacia porque "no hay pantalla" no es un estado de la TUI.
         */
        fun of(vararg screens: Screen): ScreenStack {
            require(screens.isNotEmpty()) { "una pila sin pantallas no es un estado valido" }
            return ScreenStack(screens.toList())
        }
    }
}
