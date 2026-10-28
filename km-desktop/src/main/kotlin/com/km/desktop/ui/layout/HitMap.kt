package com.km.desktop.ui.layout

import com.km.desktop.ui.MouseIntent

/**
 * Una region dentro de una FILA semantica, en columnas RELATIVAS a su fila.
 *
 * ## POR QUE VIVE EN `BodyRow` Y NO EN EL ESTADO
 *
 * Porque una pantalla sabe que "esta fila es el contacto 3" sin saber en que
 * coordenada de pantalla cae: eso lo decide el layout por frame. La vista
 * declara [start] y [width] dentro de su propio ancho; el compositor, que es el
 * unico que conoce `x`/`y`, las convierte en [HitRegion]. Una sola verdad.
 *
 * [width] `null` significa "hasta el final del ancho pintado": es lo natural
 * para una fila de lista, que es clicable de borde a borde aunque su texto sea
 * corto. Un ancho explicito (pestaña, boton) solo se emite si CABE entero.
 */
data class RowRegion(
    val start: Int,
    val width: Int?,
    val intent: MouseIntent,
)

/** Una region ya colocada en celdas de pantalla. */
data class HitRegion(
    val rect: Rect,
    val intent: MouseIntent,
)

/**
 * El hit-map de un frame: regiones en ORDEN DE PINTADO.
 *
 * ## GANA LA ULTIMA
 *
 * El compositor anade primero el fondo y despues lo que va encima. Por eso
 * [at] devuelve la ULTIMA region que contiene la celda: el dialogo gana a la
 * lista que tapa, y un boton gana al `Dismiss` de su caja. Invertir ese orden
 * es el fallo que el control negativo de B2 pone rojo.
 *
 * ## FUERA = `null`
 *
 * No se inventa una accion por defecto: una celda inerte no hace nada, y el
 * llamador lo dice explicitamente.
 */
class HitMap(private val regions: List<HitRegion> = emptyList()) {

    /** La intencion de la celda, o `null` si no hay ninguna region que la cubra. */
    fun at(column: Int, row: Int): MouseIntent? =
        regions.lastOrNull { it.rect.contains(column, row) }?.intent

    /**
     * Las regiones en orden de pintado.
     *
     * Solo para tests y para el test de exhaustividad tecla<->region: la UI no
     * las recorre (pregunta por una celda).
     */
    fun regions(): List<HitRegion> = regions

    val size: Int get() = regions.size

    companion object {
        val EMPTY: HitMap = HitMap(emptyList())
    }
}
