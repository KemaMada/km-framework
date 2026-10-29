package com.example.keymessage.ui.insets

import com.example.keymessage.ui.nav.Pantalla

/**
 * Quien reserva el INSET INFERIOR en una pantalla, y con que mecanismo.
 *
 * ## POR QUE UNA POLITICA Y NO UN `navigationBarsPadding()` EN LA RAIZ
 *
 * Porque la raiz la comparten TODAS las pantallas, incluida CONVERSACION, cuyo
 * compositor ya reserva `navigationBarsPadding().imePadding()`. Un bottom en la
 * raiz se SUMARIA a ese y dejaria un hueco duplicado bajo el teclado (D1/D5).
 * El defecto que esto arregla era el contrario: nadie mas reservaba la barra de
 * navegacion, y NUEVO / COMPARTIR QR / CONFIG quedaban debajo del sistema.
 *
 * ## UN SOLO DUENO POR PANTALLA
 *
 * Cada pantalla tiene exactamente UN consumidor: el footer fijo (HOME,
 * COMPARTIR), el contenedor raiz (DIAGNOSTICO), el contenedor que scrollea
 * (FRASE, RESTAURAR) o el compositor del IME (CONVERSACION, sin cambios).
 * IMPORTAR queda [MecanismoInset.NINGUNO] a proposito: R1 no lo enumera y este
 * cambio no amplia alcance (follow-up declarado).
 *
 * ## POR QUE ES UNA FUNCION PURA
 *
 * Porque este modulo no tiene `ComposeTestRule`: un `Modifier` dentro de un
 * composable no se puede afirmar en JVM. La POLITICA —que pantalla reserva
 * donde— es una funcion, y una funcion se prueba
 * ([com.example.keymessage.ui.InsetInferiorGuardTest]).
 */
enum class MecanismoInset {

    /** La barra de acciones fija del fondo reserva el inset (HOME, COMPARTIR). */
    FOOTER_FIJO,

    /** El contenedor raiz de la pantalla lo reserva (DIAGNOSTICO). */
    CONTENEDOR_FIJO,

    /** Un envoltorio NO scrolleable lo reserva; el scroll vive dentro (FRASE, RESTAURAR). */
    CONTENEDOR_SCROLL,

    /** Un overlay interno a pantalla completa lo reserva (detalle de contacto). */
    OVERLAY_INTERNO,

    /** El compositor del chat lo reserva junto al IME (CONVERSACION, sin cambios). */
    COMPOSITOR_IME,

    /** Fuera de alcance: nadie lo reserva y se declara como follow-up. */
    NINGUNO,
}

/**
 * El mecanismo de cada pantalla, EXHAUSTIVO sobre [Pantalla].
 *
 * Sin `else`: si manana se anade una ruta, esto deja de compilar y obliga a
 * decidir quien reserva el inset, en vez de heredar un defecto silencioso.
 */
fun mecanismoInset(pantalla: Pantalla): MecanismoInset = when (pantalla) {
    Pantalla.INICIO -> MecanismoInset.FOOTER_FIJO
    Pantalla.DIAGNOSTICO -> MecanismoInset.CONTENEDOR_FIJO
    Pantalla.COMPARTIR -> MecanismoInset.FOOTER_FIJO
    Pantalla.FRASE, Pantalla.RESTAURAR -> MecanismoInset.CONTENEDOR_SCROLL
    Pantalla.CONVERSACION -> MecanismoInset.COMPOSITOR_IME
    // R1 no incluye IMPORTAR: su dialogo tambien queda bajo la barra, pero
    // arreglarlo es otro cambio. Se declara, no se finge.
    Pantalla.IMPORTAR -> MecanismoInset.NINGUNO
}
