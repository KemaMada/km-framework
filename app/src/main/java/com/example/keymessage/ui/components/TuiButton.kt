package com.example.keymessage.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.keymessage.ui.theme.TerminalBlack
import com.example.keymessage.ui.theme.TerminalBorder
import com.example.keymessage.ui.theme.TerminalCyan
import com.example.keymessage.ui.theme.TerminalDarkGray
import com.example.keymessage.ui.theme.TerminalWhite
import com.example.keymessage.ui.theme.TuiTokens

/**
 * F1 — El boton TUI: rectangulo, esquina 0, borde 1dp, area >= 48dp.
 *
 * ## POR QUE NO `Button`
 *
 * `Button` de Material3 es una PILDDORA (radio completo) por defecto: no hay
 * tokens de forma que lo dejen cuadrado sin deshacerlo entero. Este control se
 * dibuja: fondo de bloque, borde de 1dp y el texto centrado. Los estados son
 * fondo/borde que cambian, no una elevacion ni un ripple redondeado.
 *
 * ## ACCESIBILIDAD (R8.1)
 *
 * El area tactil es >= [TuiTokens.MinTouch] (48dp) por `defaultMinSize`: el
 * texto puede ser pequeno, el TARGET no. El rol es `Role.Button` para TalkBack,
 * y el texto se anuncia tal cual (es `Text` real).
 *
 * @param texto la etiqueta legible; NO se sustituye por un glifo.
 * @param activo color de fondo cuando el control esta habilitado.
 * @param deshabilitado cunado no se puede pulsar: sin ripple ni semantica de boton.
 */
@Composable
fun TuiButton(
    texto: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    activo: Color = TerminalCyan,
    contenido: Color = TerminalBlack,
    habilitado: Boolean = true,
) {
    val interaccion = remember { MutableInteractionSource() }
    val pulsado by interaccion.collectIsPressedAsState()

    val fondo = when {
        !habilitado -> TerminalDarkGray
        pulsado -> TerminalWhite
        else -> activo
    }
    val tinta = if (!habilitado) TerminalBorder else contenido

    Box(
        modifier = modifier
            .heightIn(min = TuiTokens.MinTouch)
            .background(fondo)
            .border(TuiTokens.Border, if (habilitado) TerminalBorder else TerminalDarkGray)
            .clickable(
                enabled = habilitado,
                role = Role.Button,
                interactionSource = interaccion,
                indication = null,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = texto,
            color = tinta,
            fontFamily = FontFamily.Monospace,
            fontSize = TuiTokens.FontSize.md,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 12.dp),
        )
    }
}

/**
 * F1 — La accion TEXTO: como un enlace de terminal, con area tactil completa.
 *
 * ## POR QUE ES OTRO CONTROL Y NO `TuiButton` CON `activo = color de fondo`
 *
 * Porque el rol visual es distinto: una accion es una palabra subrayable a la
 * que se le da un area generosa, no un bloque de color. Sustituye a `TextButton`
 * (que trae relleno y forma de Material). El area tactil sigue siendo >= 48dp
 * para no convertir la "linea de texto" en un target impracticable (R8.1).
 */
@Composable
fun TuiAction(
    texto: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    color: Color = TerminalCyan,
    habilitado: Boolean = true,
) {
    Box(
        modifier = modifier
            .defaultMinSize(minHeight = TuiTokens.MinTouch)
            .clickable(enabled = habilitado, role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.CenterStart,
    ) {
        Text(
            text = texto,
            color = if (habilitado) color else TerminalBorder,
            fontFamily = FontFamily.Monospace,
            fontSize = TuiTokens.FontSize.lg,
            modifier = Modifier.padding(horizontal = 4.dp, vertical = 12.dp),
        )
    }
}
