package com.km.desktop.ui

/**
 * Las PESTAÑAS RAIZ de la TUI.
 *
 * ## POR QUE SON TRES Y NO UNA POR PANTALLA
 *
 * Porque una pantalla es un destino y una pestaña es un CONTEXTO: volver a
 * INICIO no debe costar cuatro `Esc`. Las pantallas apiladas siguen siendo las
 * de siempre; lo que cambia es que cada raiz tiene su propia pila.
 *
 * | Pestaña | Pantallas |
 * |---|---|
 * | INICIO | INICIO, IMPORTAR, COMPARTIR, CONVERSACION |
 * | FRASE | FRASE, RESTAURAR |
 * | DIAGNOSTICO | DIAGNOSTICO |
 *
 * ## `Esc` EN LA RAIZ
 *
 * Retrocede a la pestaña desde la que se entro (comportamiento de siempre: de
 * FRASE se vuelve a INICIO). En INICIO, que es la raiz del programa, sigue
 * pidiendo confirmacion de salida. La alternativa —`Esc` en FRASE pidiendo
 * salir— habria cambiado una tecla que el usuario ya tiene aprendida y que los
 * tests de navegacion pinzan.
 */
enum class RootTab(val title: String) {
    INICIO("INICIO"),
    FRASE("FRASE"),
    DIAGNOSTICO("DIAGNOSTICO"),

    ;

    /** La pantalla raiz de la pestaña. */
    val root: Screen
        get() = when (this) {
            INICIO -> Screen.INICIO
            FRASE -> Screen.FRASE
            DIAGNOSTICO -> Screen.DIAGNOSTICO
        }

    companion object {

        /** A que pestaña pertenece [screen]. */
        fun of(screen: Screen): RootTab = when (screen) {
            Screen.INICIO, Screen.IMPORTAR, Screen.COMPARTIR, Screen.CONVERSACION -> INICIO
            Screen.FRASE, Screen.RESTAURAR -> FRASE
            Screen.DIAGNOSTICO -> DIAGNOSTICO
        }

        /** El ordinal como indice de la barra. */
        fun at(index: Int): RootTab? = entries.getOrNull(index)
    }
}

/**
 * Una PILA por pestaña, con la activa dentro.
 *
 * ## ENVUELVE `ScreenStack`, NO LO SUSTITUYE
 *
 * `ScreenStack` conserva sus reglas (no apila repetido, `substitute` cambia la
 * cima, `pop` en la raiz devuelve `null`) y sus tests. Aqui solo se elige CUAL
 * de las pilas se navega. Reescribir la pila para que supiera de pestañas
 * habria metido dos conceptos en una clase que ya estaba probada.
 *
 * ## LO QUE NO HACE
 *
 * No guarda borradores ni foco: `filter`, el compositor, el borrador de
 * importar y la frase viven en `ShellState` y cambiar de pestaña NO los toca.
 * Esa es justamente la propiedad R16: irse a DIAGNOSTICO y volver no pierde lo
 * escrito.
 *
 * ## `previous`
 *
 * Recuerda de donde se vino para que `Esc` en la raiz de una pestaña vuelva al
 * contexto anterior. Es UN nivel: `Alt` no apila historial de pestañas, porque
 * un `Esc` que recorre diez saltos no es "volver", es una ruleta.
 */
class TabStacks private constructor(
    private val stacks: Map<RootTab, ScreenStack>,
    val active: RootTab,
    val previous: RootTab?,
) {

    /** La pila de la pestaña activa. */
    val current: ScreenStack get() = stacks[active] ?: ScreenStack.initial(active.root)

    /** La pantalla visible. */
    val screen: Screen get() = current.current

    /** Hay algo a lo que volver dentro de la pestaña, o a la pestaña anterior. */
    val canGoBack: Boolean
        get() = current.canGoBack || (active != RootTab.INICIO && previous != null)

    /** La pila de [tab]; una pestaña nunca visitada esta en su raiz. */
    fun stack(tab: RootTab): ScreenStack = stacks[tab] ?: ScreenStack.initial(tab.root)

    /** Las pilas, de abajo a arriba, por pestaña. Copia para tests y para pintar. */
    fun stacks(): Map<RootTab, ScreenStack> = stacks.toMap()

    /**
     * Apila [screen] en la pestaña que le corresponde.
     *
     * Si la pantalla pertenece a otra pestaña (abrir FRASE desde INICIO), se
     * activa esa pestaña y se recuerda la anterior. Es lo que hace que `f` siga
     * funcionando igual desde cualquier pantalla del tab INICIO.
     */
    fun push(screen: Screen): TabStacks {
        val tab = RootTab.of(screen)
        val destino = stack(tab).push(screen)
        val previo = if (tab == active) previous else active
        return TabStacks(stacks + (tab to destino), tab, previo)
    }

    /**
     * Retrocede un nivel: dentro de la pestaña, o a la pestaña anterior.
     *
     * @return las pilas nuevas, o `null` si no hay a donde volver. En INICIO la
     *   raiz NO vuelve a ninguna pestaña: alli `Esc` es "salir" y pide
     *   confirmacion.
     */
    fun pop(): TabStacks? {
        val pila = current
        pila.pop()?.let { return TabStacks(stacks + (active to it), active, previous) }
        if (active != RootTab.INICIO && previous != null) return TabStacks(stacks, previous, null)
        return null
    }

    /** Cambia la cima de la pila de la pestaña activa (fin de un dialogo). */
    fun substitute(screen: Screen): TabStacks {
        val tab = RootTab.of(screen)
        val destino = stack(tab).substitute(screen)
        return TabStacks(stacks + (tab to destino), active, previous)
    }

    /** Deja [screen] como unica entrada de su pestaña, descartando el historial. */
    fun replace(screen: Screen): TabStacks {
        val tab = RootTab.of(screen)
        val destino = ScreenStack.initial(screen)
        return TabStacks(stacks + (tab to destino), tab, if (tab == active) previous else active)
    }

    /**
     * Activa [tab] SIN tocar ninguna pila ni ningun borrador.
     *
     * Es el unico punto por el que se cambia de pestaña: si otra ruta tocara
     * `screens` directamente, la pila de la pestaña que se deja se quedaria
     * vieja y al volver se perderia el contexto.
     */
    fun activate(tab: RootTab): TabStacks =
        if (tab == active) this else TabStacks(stacks, tab, active)

    override fun equals(other: Any?): Boolean =
        this === other ||
            (other is TabStacks && other.stacks == stacks && other.active == active && other.previous == previous)

    override fun hashCode(): Int = 31 * (31 * stacks.hashCode() + active.hashCode()) + (previous?.hashCode() ?: 0)

    override fun toString(): String =
        RootTab.entries.joinToString(" | ") { tab ->
            val marca = if (tab == active) "*" else " "
            "$marca$tab:" + stack(tab).screens.joinToString(">")
        }

    companion object {

        /** INICIO activo y las tres pilas en su raiz. */
        fun initial(): TabStacks = TabStacks(
            mapOf(
                RootTab.INICIO to ScreenStack.initial(Screen.INICIO),
                RootTab.FRASE to ScreenStack.initial(Screen.FRASE),
                RootTab.DIAGNOSTICO to ScreenStack.initial(Screen.DIAGNOSTICO),
            ),
            RootTab.INICIO,
            null,
        )

        /**
         * Las pilas que resultan de navegar [screens] en orden.
         *
         * Existe para tests y fixtures: `of(INICIO, FRASE)` no crea una pila
         * INICIO>FRASE sino la pestaña FRASE con su raiz, que es lo que hace el
         * reducer al pulsar `f`. Un fixture que construyera otra cosa estaria
         * pintando un estado que el programa no produce.
         */
        fun of(vararg screens: Screen): TabStacks {
            require(screens.isNotEmpty()) { "una navegacion sin pantallas no es un estado valido" }
            return screens.fold(initial()) { tabs, screen -> tabs.push(screen) }
        }
    }
}
