package com.example.keymessage.ui.theme

import androidx.compose.ui.graphics.Color

val TerminalBlack = Color(0xFF000000)
val TerminalWhite = Color(0xFFE0E0E0)
val TerminalRed = Color(0xFFFF4444)
val TerminalCyan = Color(0xFF00DDDD)
val TerminalGreen = Color(0xFF44FF44)
/**
 * `#666666` — texto secundario GRANDE (>= 16sp).
 *
 * ## POR QUE SIGUE EXISTIENDO Y CUANDO NO USARLO
 *
 * A 14sp o menos, `#666` sobre negro incumple WCAG AA (R8.2). Este token queda
 * para texto >= 16sp, donde el tamano compensa; para el resto esta
 * [TerminalGray30]. Un guard de contraste mira los usos pequenos.
 */
val TerminalGray = Color(0xFF666666)

/**
 * `#8A8A8A` — texto secundario PEQUENO (<= 14sp).
 *
 * El sustituto de [TerminalGray] en texto pequeno: sube el contraste lo justo
 * para cruzar WCAG AA sobre negro sin cambiar el tono sobrio de la paleta.
 */
val TerminalGray30 = Color(0xFF8A8A8A)

val TerminalDarkGray = Color(0xFF1A1A1A)
val TerminalBorder = Color(0xFF333333)
