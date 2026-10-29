package com.example.keymessage.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.example.keymessage.ui.theme.TerminalBlack
import com.example.keymessage.ui.theme.TerminalCyan
import com.example.keymessage.ui.theme.TerminalDarkGray
import com.example.keymessage.ui.theme.TerminalGray30
import com.example.keymessage.ui.theme.TerminalRed
import com.example.keymessage.ui.theme.TerminalWhite
import com.example.keymessage.ui.theme.TuiTokens

/**
 * F1 — El indicador de scroll `[n/m]`, PUR0 y comprobable.
 *
 * ## POR QUE UN INDICADOR Y NO UNA BARRA DIBUJADA CON THUMB
 *
 * Un `[n/m]` es legible, accesible (un lector lo lee como "3 de 200") y no
 * depende del alto del contenedor para ser exacto. Una barra con thumb hay que
 * dibujarla, medirla y no aporta informacion que el numero no de. Se elige el
 * numero.
 *
 * ## LA FUNCION PURA
 *
 * [etiquetaScroll] es pura: el total y la posicion entran, la etiqueta sale. Un
 * `1/0` —lista vacia— se dice `[0/0]`, no `[1/0]`.
 */
fun etiquetaScroll(visible: Int, total: Int): String {
    if (total <= 0) return "[0/0]"
    val v = visible.coerceIn(1, total)
    return "[$v/$total]"
}

/** F1 — El indicador de scroll `[n/m]` como texto de la barra de estado. */
@Composable
fun TuiScrollbar(visible: Int, total: Int, modifier: Modifier = Modifier) {
    Text(
        text = etiquetaScroll(visible, total),
        color = TerminalGray30,
        fontFamily = FontFamily.Monospace,
        fontSize = TuiTokens.FontSize.sm,
        modifier = modifier.semantics { contentDescription = "fila $visible de $total" },
    )
}

/**
 * F1 — El dialogo TUI: un marco cuadrado, NO una `AlertDialog` redondeada.
 *
 * ## POR QUE NO `AlertDialog`/`Dialog` DE MATERIAL
 *
 * Porque traen esquina redondeada, elevacion y una superficie con forma de
 * Material. Aqui el "dialogo" es una seccion a pantalla completa con marco
 * cuadrado y una barra de acciones: el mismo contrato (contenido + acciones),
 * sin la forma.
 *
 * ## SEMANTICA
 *
 * El contenedor anuncia su titulo ("Seccion <titulo>") para que TalkBack no
 * entre en un bloque anonimo.
 */
@Composable
fun TuiDialog(
    titulo: String,
    modifier: Modifier = Modifier,
    acciones: @Composable ColumnScope.() -> Unit = {},
    content: @Composable ColumnScope.() -> Unit,
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .background(TerminalBlack)
            .semantics { contentDescription = "Seccion $titulo" },
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            TuiFrame(titulo = titulo, acento = TerminalCyan) {
                Column(modifier = Modifier.padding(TuiTokens.Unit * 3)) {
                    content()
                }
            }
            Column(modifier = Modifier.fillMaxWidth().padding(TuiTokens.Unit * 2)) {
                acciones()
            }
        }
    }
}

/**
 * F1 — El feedback de una accion: exito (cian) o fallo (rojo).
 *
 * ## POR QUE UN TIPO Y NO UN `String?`
 *
 * Porque "no hay feedback" y "feedback de exito" y "feedback de fallo" son tres
 * estados distintos, y un `String?` solo distingue dos. Un mensaje de exito
 * pintado en rojo, o un fallo pintado en cian, seria una mentira de color: el
 * tipo fuerza a elegir el significado, y el color se deriva de el.
 */
enum class TuiFeedbackTipo { EXITO, FALLO }

/** El color del feedback, derivado del tipo: el color no es una decision aparte. */
fun colorFeedback(tipo: TuiFeedbackTipo): Color = when (tipo) {
    TuiFeedbackTipo.EXITO -> TerminalCyan
    TuiFeedbackTipo.FALLO -> TerminalRed
}

/**
 * F1 — El mensaje de estado (el "snackbar" de la TUI): una linea, sin forma.
 *
 * `null` no pinta nada —no hay feedback—. Un mensaje se pinta con el color de su
 * TIPO: el exito nunca sale en rojo ni el fallo en cian.
 */
@Composable
fun TuiStatusMessage(
    texto: String?,
    tipo: TuiFeedbackTipo,
    modifier: Modifier = Modifier,
) {
    if (texto == null) return
    Box(
        modifier = modifier
            .fillMaxWidth()
            .background(TerminalDarkGray)
            .padding(horizontal = 10.dp, vertical = 6.dp),
    ) {
        Text(
            text = texto,
            color = colorFeedback(tipo),
            fontFamily = FontFamily.Monospace,
            fontSize = TuiTokens.FontSize.body,
        )
    }
}
