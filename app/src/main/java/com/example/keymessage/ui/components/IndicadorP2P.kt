package com.example.keymessage.ui.components

import androidx.compose.foundation.layout.Row
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
import com.example.keymessage.ui.theme.TerminalCyan
import com.example.keymessage.ui.theme.TerminalGray30
import com.example.keymessage.ui.theme.TerminalGreen
import com.example.keymessage.ui.theme.TerminalRed
import com.example.keymessage.ui.theme.TuiTokens
import com.km.protocol.ConnectionState

/**
 * El indicador compacto del ENLACE AL RELAY: `P2P[simbolo]`.
 *
 * ## QUE MIDE ESTE INDICADOR, Y QUE NO (la regla dura)
 *
 * Mide el estado GLOBAL del transporte —el enlace al relay—, NO la presencia de
 * ningun contacto ni la entrega de ningun mensaje. La app no tiene hoy una
 * fuente de presencia por peer, asi que afirmarla seria inventar un estado.
 * Por eso:
 *
 *  - [glifo] `o` verde SOLO con [ConnectionState.ONLINE] (auth completada);
 *  - `~` cian con CONNECTING / AUTHENTICATING / RECONNECTING (transicion);
 *  - `x` rojo con DISCONNECTED;
 *  - `?` gris para cualquier estado que no se reconozca.
 *
 * ## POR QUE ES UNA FUNCION PURA
 *
 * Porque este modulo no tiene `ComposeTestRule`: un `when` dentro de un
 * composable no se puede afirmar en JVM. La regla —"verde solo con ONLINE"—
 * es una funcion, y una funcion se prueba
 * ([com.example.keymessage.ui.IndicadorP2PTest]).
 *
 * @param estado el estado REAL del transporte. La etiqueta lo pinta tal cual.
 */
data class IndicadorP2P(
    /** El glifo interior de `P2P[...]`. */
    val glifo: String,
    /** El color del glifo. Verde = relay enlazado; cian = en transicion. */
    val color: Color,
    /** El texto que OYE un lector de pantalla. Habla del RELAY, nunca del peer. */
    val descripcion: String,
) {
    /** La etiqueta compacta que se pinta en las cabeceras. */
    val etiqueta: String get() = "P2P[$glifo]"

    companion object {

        /** El glifo de "relay enlazado": verde. */
        const val GLIFO_ONLINE: String = "o"

        /** El glifo de "en transicion": cian. */
        const val GLIFO_TRANSICION: String = "~"

        /** El glifo de "sin enlace": rojo. */
        const val GLIFO_OFFLINE: String = "x"

        /** El glifo de "desconocido": gris. */
        const val GLIFO_DESCONOCIDO: String = "?"

        /**
         * La leyenda que se muestra en DIAGNOSTICO.
         *
         * Es la unica frase que fija que `P2P` habla del RELAY y no del peer:
         * sin ella, `P2P[o]` se podria leer como "el contacto esta online".
         */
        const val LEYENDA: String =
            "P2P = enlace al relay (transporte). NO indica si el contacto esta conectado."
    }
}

/**
 * Deriva el indicador de [estado].
 *
 * La descripcion NUNCA menciona al contacto ni la entrega: habla del enlace al
 * relay, que es lo unico que el estado global puede afirmar (spec R1).
 */
fun indicadorP2P(estado: ConnectionState): IndicadorP2P = when (estado) {
    ConnectionState.ONLINE -> IndicadorP2P(
        glifo = IndicadorP2P.GLIFO_ONLINE,
        color = TerminalGreen,
        descripcion = "Enlace al relay: conectado",
    )
    ConnectionState.CONNECTING,
    ConnectionState.AUTHENTICATING,
    ConnectionState.RECONNECTING,
    -> IndicadorP2P(
        glifo = IndicadorP2P.GLIFO_TRANSICION,
        color = TerminalCyan,
        descripcion = "Enlace al relay: en transicion (${estado.name})",
    )
    ConnectionState.DISCONNECTED -> IndicadorP2P(
        glifo = IndicadorP2P.GLIFO_OFFLINE,
        color = TerminalRed,
        descripcion = "Enlace al relay: sin conexion",
    )
    // Cualquier estado futuro que no se reconozca cae aqui: un glifo gris
    // honesto es mas seguro que una exhaustividad que obligue a un verde
    // optimista.
    else -> IndicadorP2P(
        glifo = IndicadorP2P.GLIFO_DESCONOCIDO,
        color = TerminalGray30,
        descripcion = "Enlace al relay: estado desconocido (${estado.name})",
    )
}

/**
 * El badge compacto `P2P[simbolo]` para las cabeceras de INICIO y CHAT.
 *
 * ## ACCESIBILIDAD
 *
 * El contenedor lleva la semantica con [IndicadorP2P.descripcion], que dice
 * "enlace al relay" y NUNCA que un contacto este conectado. El glifo se pinta
 * como `Text` decorativo dentro del `contentDescription` del contenedor.
 */
@Composable
fun TuiP2PBadge(estado: ConnectionState, modifier: Modifier = Modifier) {
    val indicador = indicadorP2P(estado)
    Row(
        modifier = modifier.semantics(mergeDescendants = true) {
            contentDescription = "${indicador.etiqueta}. ${indicador.descripcion}"
        },
    ) {
        Text(
            text = indicador.etiqueta,
            color = indicador.color,
            fontFamily = FontFamily.Monospace,
            fontSize = TuiTokens.FontSize.md,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(horizontal = 2.dp),
        )
    }
}
