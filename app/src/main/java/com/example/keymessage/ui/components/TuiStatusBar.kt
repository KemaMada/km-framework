package com.example.keymessage.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.keymessage.ui.theme.TerminalBlack
import com.example.keymessage.ui.theme.TerminalCyan
import com.example.keymessage.ui.theme.TerminalGreen
import com.example.keymessage.ui.theme.TerminalRed
import com.example.keymessage.ui.theme.TuiTokens
import com.km.protocol.ConnectionState

/**
 * F1 — La etiqueta de conexion, PURA, derivada del estado REAL.
 *
 * ## POR QUE ES UNA FUNCION Y NO UN `if` DENTRO DEL COMPOSABLE
 *
 * Porque en este modulo no hay `ComposeTestRule`: un `if` dentro de un
 * composable no se puede afirmar en JVM. La regla —"ONLINE solo con
 * `ConnectionState.ONLINE`"— es una funcion, y una funcion se prueba
 * ([TuiComponentsGeometryTest]).
 *
 * ## LA REGLA DURA (R1.3)
 *
 * `ONLINE` aparece UNICAMENTE cuando [estado] es `ConnectionState.ONLINE`. En
 * cualquier otro caso la barra dice `SIN CONEXION` con el codigo real, o el
 * nombre del estado si esta reconectando. Nunca se pinta "online" sin fuente.
 *
 * @param endpoint el endpoint preferido, para acompanar al `ONLINE`.
 * @param error el ultimo error de conexion, o `null`.
 */
fun etiquetaConexion(estado: ConnectionState, endpoint: String?, error: String?): String =
    when (estado) {
        ConnectionState.ONLINE -> "ONLINE  ${endpoint ?: "endpoint desconocido"}"
        ConnectionState.CONNECTING -> "CONECTANDO  ${error ?: estado.name}"
        ConnectionState.RECONNECTING -> "RECONECTANDO  ${error ?: estado.name}"
        ConnectionState.DISCONNECTED -> "SIN CONEXION  ${error ?: estado.name}"
        // Cualquier estado futuro que NO sea ONLINE cae aqui: la regla dura es
        // "ONLINE solo con ONLINE", y un `else` que degrada es mas seguro que
        // una exhaustividad que obligue a un `else` optimista.
        else -> "ESTADO ${estado.name}  ${error ?: "sin detalle"}"
    }

/**
 * F1 — La barra de estado TUI.
 *
 * Muestra la etiqueta de [etiquetaConexion] en verde (online) o rojo (todo lo
 * demas). El color NO inventa estado: deriva del mismo `estado` que la etiqueta.
 */
@Composable
fun TuiStatusBar(
    estado: ConnectionState,
    endpoint: String?,
    error: String?,
    modifier: Modifier = Modifier,
) {
    val texto = etiquetaConexion(estado, endpoint, error)
    val color = if (estado == ConnectionState.ONLINE) TerminalGreen else TerminalRed

    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(TerminalBlack)
            .semantics { contentDescription = texto }
            .padding(horizontal = 10.dp, vertical = 6.dp),
    ) {
        Text(
            text = texto,
            color = color,
            fontFamily = FontFamily.Monospace,
            fontSize = TuiTokens.FontSize.md,
            fontWeight = FontWeight.Bold,
        )
    }
}

/**
 * F1 — El estado de conexion cuando NO hay nucleo (`requireCore` fallo).
 *
 * Se separa de [TuiStatusBar] porque el caso "sin nucleo" no tiene un
 * `ConnectionState` todavia: la conexion ni ha empezado. La etiqueta es honesta
 * con eso: dice el estado textual y el codigo.
 */
fun etiquetaSinNucleo(estadoTexto: String, codigo: String): String =
    "SIN CONEXION  estado=$estadoTexto codigo=$codigo"

/** F1 — La barra de estado del caso "sin nucleo": rojo, con el codigo real. */
@Composable
fun TuiStatusBarSinNucleo(estadoTexto: String, codigo: String, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(TerminalBlack)
            .semantics { contentDescription = etiquetaSinNucleo(estadoTexto, codigo) }
            .padding(horizontal = 10.dp, vertical = 6.dp),
    ) {
        Text(
            text = etiquetaSinNucleo(estadoTexto, codigo),
            color = TerminalRed,
            fontFamily = FontFamily.Monospace,
            fontSize = TuiTokens.FontSize.md,
            fontWeight = FontWeight.Bold,
        )
    }
}
