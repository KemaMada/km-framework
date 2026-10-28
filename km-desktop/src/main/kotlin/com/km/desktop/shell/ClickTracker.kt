package com.km.desktop.shell

import com.km.desktop.ui.MouseIntent

/**
 * Decide si un par press+release es un CLIC sobre la MISMA region.
 *
 * ## POR QUE EL ESTADO VIVE AQUI Y NO EN EL REDUCER
 *
 * Porque "ha sido un clic" es una propiedad de la ENTRADA (dos eventos con la
 * misma celda/region), no del modelo. Guardar el press en `ShellState` meteria
 * un dato de raton efimero en un estado que se serializa en tests y que el
 * reducer tendria que limpiar en cada tecla.
 *
 * ## ARRASTRE Y BOTON DERECHO NO ACTUAN
 *
 * Change B solo activa `?1000` (press/release), asi que un arrastre llega como
 * press en una region y release en otra: las intenciones no son iguales y el
 * clic se descarta. El motion (bit 32) ni siquiera llega: `MouseCodec` lo
 * descarta como evento. El boton no izquierdo ni se registra ([press] con
 * `null`), y un release sin press previo devuelve `null` porque no hay gesto.
 */
class ClickTracker {

    private var pressed: MouseIntent? = null

    /** Recuerda la region pulsada; `null` si el boton no es el izquierdo. */
    fun press(intent: MouseIntent?) {
        pressed = intent
    }

    /**
     * Cierra el gesto: devuelve la intencion solo si press y release son la
     * MISMA y no nula. Siempre olvida el press, para que un release suelto no
     * herede el gesto anterior.
     */
    fun release(intent: MouseIntent?): MouseIntent? {
        val pulsado = pressed
        pressed = null
        return if (pulsado != null && pulsado == intent) pulsado else null
    }

    /** Olvida un gesto a medias (resize, perdida de foco). */
    fun reset() {
        pressed = null
    }
}
