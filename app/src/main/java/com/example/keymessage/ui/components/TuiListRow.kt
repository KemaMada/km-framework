package com.example.keymessage.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.keymessage.ui.theme.TerminalBlack
import com.example.keymessage.ui.theme.TerminalCyan
import com.example.keymessage.ui.theme.TerminalWhite
import com.example.keymessage.ui.theme.TuiTokens

/**
 * F1 — La fila de lista TUI: prefijo `>`, foco con borde cian, area >= 48dp.
 *
 * ## EL `>` DEL FOCO Y EL GLIFO DECORATIVO
 *
 * La fila seleccionada lleva `>` delante y un borde cian: es la senal de foco de
 * una TUI. El [glifo] —la presencia `[?]`, un icono de estado— es DECORATIVO: se
 * envuelve en `semantics { contentDescription = "" }` para que TalkBack no lea
 * los corchetes como contenido.
 *
 * ## QUE ANUNCIA TALKBACK (R1.2)
 *
 * El significado completo en [descripcion]: "Contacto <huella>, estado
 * desconocido". El contenedor LLEVA la semantica; los glifos no.
 *
 * @param descripcion el texto que OYE un lector de pantalla. Obligatorio: una
 *   fila sin descripcion seria una fila muda.
 * @param glifo el indicador decorativo (`[?]`, `x`, ...), o `null`.
 */
@Composable
fun TuiListRow(
    descripcion: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    seleccionado: Boolean = false,
    glifo: String? = null,
    glifoColor: Color = TerminalCyan,
    contenido: @Composable () -> Unit,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(if (seleccionado) TerminalCyan.copy(alpha = 0.12f) else TerminalBlack)
            .then(if (seleccionado) Modifier.border(TuiTokens.Border, TerminalCyan) else Modifier)
            .defaultMinSize(minHeight = TuiTokens.MinTouch)
            .semantics(mergeDescendants = true) {
                role = Role.Button
                contentDescription = descripcion
            }
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = if (seleccionado) ">" else " ",
            color = TerminalCyan,
            fontFamily = FontFamily.Monospace,
            fontSize = TuiTokens.FontSize.md,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.width(12.dp),
        )
        if (glifo != null) {
            Text(
                text = glifo,
                color = glifoColor,
                fontFamily = FontFamily.Monospace,
                fontSize = TuiTokens.FontSize.lg,
                modifier = Modifier
                    .width(32.dp)
                    // Decorativo: TalkBack no lo lee.
                    .semantics { contentDescription = "" },
            )
        }
        Box(modifier = Modifier.weight(1f)) {
            contenido()
        }
    }
}

/**
 * F1 — La cabecera de columnas de una lista (`CONTACTOS` / `ID`).
 *
 * No es un control: es texto rotulado con el color de acento. Se usa para que
 * las lista TUI tengan la cuadricula de una tabla de terminal.
 */
@Composable
fun TuiColumnHeader(izquierda: String, derecha: String? = null) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(TerminalBlack)
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = izquierda,
            color = TerminalCyan,
            fontFamily = FontFamily.Monospace,
            fontSize = TuiTokens.FontSize.md,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.weight(1f),
        )
        if (derecha != null) {
            Text(
                text = derecha,
                color = TerminalCyan,
                fontFamily = FontFamily.Monospace,
                fontSize = TuiTokens.FontSize.md,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
