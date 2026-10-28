package com.km.desktop.ui.theme

/**
 * Los ROLES semanticos de color de la TUI.
 *
 * ## POR QUE UN ROL Y NO UN NUMERO ANSI
 *
 * Porque `ui/` no puede emitir secuencias: la capacidad del terminal
 * (truecolor, 256, 16 o monocromo) se resuelve en `shell/` y `shell/Sgr`
 * traduce rol a SGR. Un numero dentro del modelo seria un color que el
 * terminal puede no entender y, peor, texto que mide de mas.
 *
 * ## NUNCA ES LA UNICA SEÑAL
 *
 * Un error va con `!` en el texto; el foco con `>` o `▌`; la entrega con
 * `[ENTREGADO]`. En MONO estos roles no emiten nada y la pantalla sigue
 * diciendo lo mismo (R15/R21).
 */
enum class ColorRole {
    TEXT,
    MUTED,
    BRAND,
    ACCENT,
    SUCCESS,
    DANGER,
    WARNING,
    BORDER,
    BG_RAISED,
    BG_SELECT,
    QR_FG,
    QR_BG,
}

/** Los atributos que no son color: negrita, atenuado y reverse. */
enum class Attr {
    BOLD,
    DIM,
    REVERSE,
}
