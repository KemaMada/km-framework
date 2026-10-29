package com.example.keymessage.ui.theme

import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * F1 — Los tokens de la TUI: la rejilla, los bordes y las escalas.
 *
 * ## POR QUE UN OBJETO Y NO UN `MaterialTheme`
 *
 * Porque el look de esta app NO es Material: es una TUI. La rejilla es de 4dp y
 * las esquinas son 0 —no hay radio que personalizar—, asi que meterlo en un
 * `Typography`/`Shapes` de Material3 seria disfrazarlo. Un objeto de constantes
 * se lee de un vistazo y no arrastra el tema.
 *
 * ## LO QUE FIJA
 *
 *  - [Unit] una unidad base de 4dp: paddings y margenes son multiplos suyos.
 *  - [Border] 1dp, el grosor de los marcos dibujados.
 *  - [FontSize] las escalas 10/11/12/13/14/16/20/22sp que la app YA usaba.
 *  - [MinTouch] 48dp: el area tactil MINIMA de cualquier control (R8.1).
 *
 * Los COLORES viven en `Color.kt` (ya existian; F1 solo corrige el gris de
 * texto pequeno). No se duplican aqui.
 */
object TuiTokens {

    /** La unidad base de la rejilla. Todo padding/margen sale de aqui. */
    val Unit = 4.dp

    /** El grosor de los bordes y las reglas dibujadas. */
    val Border = 1.dp

    /** El area tactil minima de un control (R8.1). */
    val MinTouch = 48.dp

    /** Las escalas de tipografia en uso, como constantes con nombre. */
    object FontSize {
        val xs = 10.sp
        val s = 11.sp
        val sm = 12.sp
        val body = 13.sp
        val md = 14.sp
        val lg = 16.sp
        val xl = 20.sp
        val xxl = 22.sp
    }

    /**
     * El VOLUMEN de un texto, para elegir color honesto segun R8.2.
     *
     * El gris `#666` incumple contraste WCAG AA a 14sp o menos, asi que la UI
     * tiene que saber cuando un texto es "pequeno" para no pintarlo en `#666`.
     */
    val SMALL_TEXT_CEILING_SP: Int = 14
}
