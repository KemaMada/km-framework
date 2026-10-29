package com.example.keymessage.ui.boot

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.example.keymessage.ui.components.TuiAction
import com.example.keymessage.ui.components.TuiRule
import com.example.keymessage.ui.theme.TerminalBlack
import com.example.keymessage.ui.theme.TerminalCyan
import com.example.keymessage.ui.theme.TerminalGray30
import com.example.keymessage.ui.theme.TuiTokens
import kotlinx.coroutines.delay

/**
 * R5 — El overlay de arranque se revela como MAXIMO 2 s.
 *
 * El numero es una cota, no una animacion: a los 2000 ms desde que aparece, se
 * cierra aunque el relay siga offline. No hay una fase que "espere" al relay
 * porque el overlay no conoce el relay: solo lee el estado que ya hay.
 */
const val OVERLAY_DURACION_MAXIMA_MS: Long = 2_000L

/**
 * R5 — El paso del revelado escalonado.
 *
 * Presentacional: las lineas YA existen (son fases reales); esto solo decide
 * cuando se muestran. Con ~10 lineas el revelado completo cae en ~600 ms, muy
 * por debajo de la cota de 2 s.
 */
const val OVERLAY_REVELADO_LINEA_MS: Long = 60L

/**
 * D5 — El gate del overlay, como funcion PURA.
 *
 * Se muestra solo si:
 *  - [inicioFrio]: `savedInstanceState == null` (un arranque del proceso, no
 *    una recreacion por rotacion);
 *  - [yaReclamado] es `false`: nadie lo reclamó antes en ESTE proceso (el
 *    reclamo es atomico en el contenedor, `compareAndSet`);
 *  - [fraseAvisada]: el gate de FRASE ya paso. En onboarding el overlay no
 *    aparece: la pantalla que toca es la frase, no una traza.
 */
fun debeMostrarOverlay(
    inicioFrio: Boolean,
    yaReclamado: Boolean,
    fraseAvisada: Boolean,
): Boolean = inicioFrio && !yaReclamado && fraseAvisada

/**
 * R5 / R6 — El overlay de arranque TUI.
 *
 * ## QUE PINTA
 *
 * Las lineas que produce `lineasBoot` (entorno real + fases reales + enlace
 * real), reveladas de una en una. Nada mas: no hay porcentaje, no hay API key,
 * no hay montajes de kernel, no hay barra de progreso.
 *
 * ## COMO SE CIERRA
 *
 *  - solo, a [OVERLAY_DURACION_MAXIMA_MS] (2 s) aunque el relay siga offline;
 *  - con tap en cualquier punto;
 *  - con OMITIR (que es un `TuiAction`, target de 48dp y rol de boton).
 *
 * ## ACCESIBILIDAD
 *
 * El bloque de texto lleva `contentDescription` con lo que se ve; las lineas
 * scrollean por dentro para que una font scale grande no las solape ni las
 * corte. OMITIR queda FUERA del scroll: siempre alcanzable.
 *
 * ## LO QUE NO HACE
 *
 * No toca la conexion ni el arranque: el `KeyMessageService` sigue conectando
 * por su cuenta y, al cerrarse, HOME pinta el estado REAL del enlace.
 */
@Composable
fun OverlayArranque(
    lineas: List<String>,
    onCerrar: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var reveladas by remember { mutableStateOf(0) }

    // Revelado escalonado. La clave es el NUMERO de lineas: si llega una fase
    // nueva (o cambia el estado de una), el efecto no reinicia el contador.
    LaunchedEffect(lineas.size) {
        while (reveladas < lineas.size) {
            delay(OVERLAY_REVELADO_LINEA_MS)
            reveladas += 1
        }
    }

    // La cota de 2 s: el overlay NO espera al relay ni a ninguna fase.
    LaunchedEffect(Unit) {
        delay(OVERLAY_DURACION_MAXIMA_MS)
        onCerrar()
    }

    val visibles = lineas.take(reveladas.coerceAtLeast(1).coerceAtMost(lineas.size))

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(TerminalBlack)
            // Tap en cualquier punto cierra: el overlay nunca secuestra la app.
            .clickable(onClick = onCerrar),
    ) {
        // El overlay NO es una pantalla: la raiz no reserva el bottom, asi que
        // aqui es su propio dueno del inset inferior. Sin esto, en un telefono
        // con barra de 3 botones (API28, inset de ~48dp) el OMITIR y la nota
        // "max 2 s" quedaban DEBAJO del sistema: el unico control para cerrar
        // el overlay no se podia tocar.
        Column(
            modifier = Modifier
                .fillMaxSize()
                .navigationBarsPadding()
                .padding(16.dp),
        ) {
            Text(
                text = "KEYMESSAGE  /  ARRANQUE",
                color = TerminalCyan,
                fontFamily = FontFamily.Monospace,
                fontSize = TuiTokens.FontSize.lg,
            )
            Spacer(modifier = Modifier.height(4.dp))
            TuiRule()
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .semantics(mergeDescendants = true) {
                        contentDescription = visibles.joinToString(". ")
                    },
            ) {
                visibles.forEach { linea ->
                    Text(
                        text = "> $linea",
                        color = TerminalGray30,
                        fontFamily = FontFamily.Monospace,
                        fontSize = TuiTokens.FontSize.sm,
                    )
                }
            }
            TuiRule()
            // OMITIR fuera del scroll: con font scale alta sigue visible.
            TuiAction(texto = "[ OMITIR ]", onClick = onCerrar)
            Text(
                text = "max 2 s  ·  tap para cerrar",
                color = TerminalGray30,
                fontFamily = FontFamily.Monospace,
                fontSize = TuiTokens.FontSize.xs,
            )
        }
    }
}
