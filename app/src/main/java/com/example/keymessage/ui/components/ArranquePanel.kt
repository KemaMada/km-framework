package com.example.keymessage.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.example.keymessage.boot.ArranqueTraza
import com.example.keymessage.boot.EstadoFase
import com.example.keymessage.boot.FaseArranque
import com.example.keymessage.boot.HitoFase
import com.example.keymessage.boot.filaFase
import com.example.keymessage.ui.theme.TerminalBlack
import com.example.keymessage.ui.theme.TerminalCyan
import com.example.keymessage.ui.theme.TerminalGray30
import com.example.keymessage.ui.theme.TerminalGreen
import com.example.keymessage.ui.theme.TerminalRed
import com.example.keymessage.ui.theme.TuiTokens

/**
 * F5 — La FRANJA de arranque de INICIO: una linea con las fases reales.
 *
 * No bloquea ni tapa: se pinta mientras queda alguna fase por completar y se
 * puede dejar visible siempre (es un resumen honesto). Sin porcentajes.
 */
@Composable
fun ArranqueFranja(hitos: List<HitoFase>, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(TerminalBlack)
            .padding(horizontal = 12.dp, vertical = 4.dp),
    ) {
        Text(
            text = "ARRANQUE  ${ArranqueTraza.franja(hitos)}",
            color = TerminalGray30,
            fontFamily = FontFamily.Monospace,
            fontSize = TuiTokens.FontSize.sm,
        )
    }
}

/**
 * F5 — La TABLA de fases para DIAGNOSTICO: una fila por fase, con su duracion
 * MEDIDA (o `-- ms`) y su nota.
 */
@Composable
fun ArranquePanel(hitos: List<HitoFase>, modifier: Modifier = Modifier) {
    Column(modifier = modifier.fillMaxWidth()) {
        hitos.forEach { hito ->
            Text(
                text = filaFase(hito),
                color = when (hito.estado) {
                    EstadoFase.HECHA -> TerminalGreen
                    EstadoFase.FALLIDA -> TerminalRed
                    EstadoFase.EN_CURSO -> TerminalCyan
                    EstadoFase.PENDIENTE -> TerminalGray30
                },
                fontFamily = FontFamily.Monospace,
                fontSize = TuiTokens.FontSize.sm,
            )
        }
        if (hitos.none { it.estado == EstadoFase.HECHA || it.estado == EstadoFase.FALLIDA }) {
            Text(
                text = "ARRANQUE  ${FaseArranque.CONFIG.etiqueta} pendiente",
                color = TerminalGray30,
                fontFamily = FontFamily.Monospace,
                fontSize = TuiTokens.FontSize.sm,
            )
        }
    }
}
