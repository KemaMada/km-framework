package com.km.desktop.shell

import com.km.desktop.ui.FocusField
import com.km.desktop.ui.MouseIntent
import com.km.desktop.ui.ShellAction
import com.km.desktop.ui.layout.HitMap

/**
 * Traduce un [MouseEvent] a la accion del bucle, o `null` si no toca hacer nada.
 *
 * ## POR QUE ES UNA FUNCION APARTE
 *
 * Porque asi la politica del raton (izquierdo, clic vs arrastre, rueda solo
 * sobre la lista y nunca con el foco en un campo de texto) se prueba en JVM sin
 * pty, y `JLineShell` solo aporta la lectura de bytes. Es la misma separacion
 * que `KeyCodec` respecto del lector de teclas.
 *
 * ## RUEDA
 *
 * Se emite [ShellAction.ScrollBy], NUNCA un movimiento de foco propio: la rama
 * del reducer que ya usa las flechas mantiene `ensureVisible` y `followEnd`
 * (R27). Con el foco en el filtro, el compositor o la confirmacion escrita, la
 * rueda no mueve: esas pantallas estan escribiendo, no hojeando (declarado).
 */
object MouseRouter {

    /**
     * @param focus campo con foco AHORA (el estado que se pinto en el hit-map).
     * @param wheelRows filas por muesca (`--wheel-rows`, 1..10).
     */
    fun route(
        event: MouseEvent,
        click: ClickTracker,
        hitMap: HitMap,
        focus: FocusField,
        wheelRows: Int,
    ): ShellAction? = when (event) {
        is MouseEvent.Press -> {
            click.press(if (event.button == 0) hitMap.at(event.column, event.row) else null)
            null
        }

        is MouseEvent.Release -> {
            val intencion = if (event.button == 0) hitMap.at(event.column, event.row) else null
            click.release(intencion)?.let { ShellAction.Pointer(it) }
        }

        is MouseEvent.Wheel -> {
            if (focus == FocusField.FIELD || focus == FocusField.COMPOSER || focus == FocusField.CONFIRM) {
                null
            } else {
                cuandoRueda(hitMap.at(event.column, event.row), event.up, wheelRows)
            }
        }

        is MouseEvent.Other -> {
            click.press(null)
            null
        }
    }

    /** Rueda solo sobre una region de LISTA; fuera de ella se ignora. */
    private fun cuandoRueda(intencion: MouseIntent?, up: Boolean, wheelRows: Int): ShellAction? {
        val sobreLista = intencion is MouseIntent.SelectContact ||
            intencion is MouseIntent.SelectMessage ||
            intencion is MouseIntent.SelectLog
        if (!sobreLista) return null
        val filas = wheelRows.coerceIn(1, 10)
        return ShellAction.ScrollBy(if (up) -filas else filas)
    }
}
