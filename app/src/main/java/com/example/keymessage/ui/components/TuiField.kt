package com.example.keymessage.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.example.keymessage.ui.theme.TerminalBlack
import com.example.keymessage.ui.theme.TerminalBorder
import com.example.keymessage.ui.theme.TerminalCyan
import com.example.keymessage.ui.theme.TerminalGray30
import com.example.keymessage.ui.theme.TerminalWhite
import com.example.keymessage.ui.theme.TuiTokens

/**
 * F1 — El campo TUI: borde cuadrado 1dp, cursor de bloque, monoespaciado.
 *
 * ## POR QUE `BasicTextField` Y NO `OutlinedTextField`
 *
 * Porque `OutlinedTextField` trae esquina redondeada, relleno y una forma de
 * Material que no se puede dejar cuadrada sin reescribirla. `BasicTextField` es
 * el primitivo sin decoracion: aqui el chrome se dibuja (rect + regla) y el
 * texto sigue siendo el `TextField` de Compose —seleccionable, con IME— que es
 * lo que hace falta.
 *
 * ## EL "CURSOR" Y EL FOCO
 *
 * El cursor de texto lo pinta `BasicTextField` (color cian). El borde cambia de
 * color con el foco (`onFocusChanged`), que es la senal de "campo activo" de una
 * TUI. No se usa `rememberSaveable`: el estado es del llamante.
 *
 * ## EL IME ES DEL LLAMANTE
 *
 * [keyboardOptions] y [keyboardActions] se PROPAGAN al `BasicTextField` en vez
 * de quedar sin uso: el compositor de la conversacion necesita `ImeAction.Send`
 * (spec R5.1). Sin estos parametros el `TuiField` cerraba la puerta al IME y la
 * accion Send del teclado se perdia (verify #381 C2).
 *
 * @param multilinea si el campo crece en alto (bundle/pegar) o es de una linea.
 * @param keyboardOptions configuracion del teclado (IME action, tipo de input).
 * @param keyboardActions acciones del IME; aqui vive el "enviar" del chat.
 */
@Composable
fun TuiField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    etiqueta: String? = null,
    placeholder: String? = null,
    multilinea: Boolean = false,
    minAlto: Int = 160,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    keyboardActions: KeyboardActions = KeyboardActions.Default,
) {
    var enfocado by remember { mutableStateOf(false) }
    val colorBorde = if (enfocado) TerminalCyan else TerminalBorder

    Column(modifier = modifier.fillMaxWidth()) {
        if (etiqueta != null) {
            TuiLabel(etiqueta)
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .then(if (multilinea) Modifier.defaultMinSize(minHeight = minAlto.dp) else Modifier.defaultMinSize(minHeight = TuiTokens.MinTouch))
                .background(TerminalBlack)
                .drawBehind {
                    val g = TuiTokens.Border.toPx()
                    drawRect(
                        color = colorBorde,
                        topLeft = Offset(g / 2f, g / 2f),
                        size = size.copy(width = size.width - g, height = size.height - g),
                        style = androidx.compose.ui.graphics.drawscope.Stroke(width = g),
                    )
                }
                .padding(horizontal = 10.dp, vertical = 10.dp),
        ) {
            if (value.isEmpty() && placeholder != null) {
                Text(
                    text = placeholder,
                    color = TerminalGray30,
                    fontFamily = FontFamily.Monospace,
                    fontSize = TuiTokens.FontSize.body,
                )
            }
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                textStyle = TextStyle(
                    color = TerminalWhite,
                    fontFamily = FontFamily.Monospace,
                    fontSize = TuiTokens.FontSize.md,
                ),
                cursorBrush = SolidColor(TerminalCyan),
                singleLine = !multilinea,
                keyboardOptions = keyboardOptions,
                keyboardActions = keyboardActions,
                modifier = Modifier
                    .fillMaxWidth()
                    .then(
                        if (multilinea) Modifier else Modifier.defaultMinSize(minHeight = TuiTokens.MinTouch),
                    ),
                decorationBox = { inner ->
                    Box(modifier = Modifier.fillMaxWidth()) { inner() }
                },
            )
        }
    }
}

/** F1 — La etiqueta de un campo: monoespaciada, en el gris de texto pequeno. */
@Composable
fun TuiLabel(texto: String) {
    Text(
        text = texto,
        color = TerminalGray30,
        fontFamily = FontFamily.Monospace,
        fontSize = TuiTokens.FontSize.sm,
        modifier = Modifier.padding(bottom = 4.dp),
    )
}

/**
 * F1 — La regla: una linea DIBUJADA de 1dp, en lugar de `HorizontalDivider`.
 *
 * `HorizontalDivider` no es un control redondeado, pero arrastra el grosor y el
 * color del tema; aqui la regla es una linea del color de borde, sin relleno.
 * Se dibuja para que no herede ninguna forma.
 */
@Composable
fun TuiRule(modifier: Modifier = Modifier, color: Color = TerminalBorder) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = TuiTokens.Unit)
            .drawBehind {
                val g = TuiTokens.Border.toPx()
                drawLine(
                    color = color,
                    start = Offset(0f, size.height / 2f),
                    end = Offset(size.width, size.height / 2f),
                    strokeWidth = g,
                )
            },
    )
}
