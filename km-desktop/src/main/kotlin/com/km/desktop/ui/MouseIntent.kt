package com.km.desktop.ui

/**
 * Lo que un CLIC o la RUEDA quieren decir, ya resuelto contra el hit-map.
 *
 * ## POR QUE EL REDUCER NO VE COORDENADAS
 *
 * El renderer es el unico que sabe donde cayo cada fila, pestaña o boton: el
 * hit-map traduce `(columna, fila)` a una de estas intenciones y el reducer
 * trabaja con semantica. Asi el estado no guarda geometria (R13) y el mismo
 * reducer se prueba sin terminal.
 *
 * ## NADA SOLO-RATON
 *
 * Toda intencion `Act` lleva un [Intent] DEL KEYMAP: el clic es un atajo, no una
 * capacidad nueva. Las selecciones (`Select*`) y los focos tienen su tecla
 * equivalente (flechas+Enter, Tab). `Dialog` equivale a Tab+Enter/Esc y
 * `Dismiss` a Esc.
 */
sealed interface MouseIntent {

    /** Fila de INICIO: abre la conversacion de ese contacto. */
    data class SelectContact(val index: Int) : MouseIntent

    /** Fila de CONVERSACION: mueve el foco a ese mensaje (no envia). */
    data class SelectMessage(val index: Int) : MouseIntent

    /** Fila de DIAGNOSTICO: mueve el foco a ese log. */
    data class SelectLog(val index: Int) : MouseIntent

    /** Compositor de la conversacion: le da el foco, sin caret (declarado). */
    data object FocusComposer : MouseIntent

    /** Campo de filtro de INICIO: le da el foco. */
    data object FocusFilter : MouseIntent

    /** Una accion que YA existe en el keymap. */
    data class Act(val intent: Intent) : MouseIntent

    /** Un boton del dialogo modal: marca y activa esa opcion. */
    data class Dialog(val choice: DialogChoice) : MouseIntent

    /** Clic fuera de todo, o fuera de la caja modal: cierra la capa superior. */
    data object Dismiss : MouseIntent
}
