package com.example.keymessage.ui.components

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.example.keymessage.ui.log.LogExport
import com.example.keymessage.ui.theme.TerminalBlack
import com.example.keymessage.ui.theme.TerminalCyan
import com.example.keymessage.ui.theme.TerminalDarkGray
import com.example.keymessage.ui.theme.TerminalGray30
import com.example.keymessage.ui.theme.TerminalRed
import com.example.keymessage.ui.theme.TerminalWhite
import com.example.keymessage.ui.theme.TuiTokens
import com.example.keymessage.util.KMLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.IOException

/**
 * F5 — El panel de logs: pinta `KMLog.lines()`, filtra por canal y permite
 * COPIAR / COPIAR FILTRADO / EXPORTAR / COMPARTIR.
 *
 * ## LA FUENTE
 *
 * SOLO [KMLog.lines] y [KMLog.CHANNELS]. No re-capturar de fuentes crudas: eso
 * abriria un camino de fuga nuevo (spec R6.3). El texto que se copia/exporta
 * sale de [LogExport.textoExport], que solo une lineas ya formateadas por
 * `KMLog` —que es no-fuga por API—.
 *
 * ## EL FEEDBACK ES VERAZ
 *
 * Exito SOLO cuando la escritura se confirmo. Cancelar el selector SAF NO es un
 * error: el fichero no se escribio y decirlo seria un fallo inventado. Un fallo
 * real se informa en rojo. Nunca un exito falso.
 *
 * @param lineas el buffer a pintar; el llamante lo refresca.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun LogPanel(
    lineas: List<String>,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var canal by remember { mutableStateOf(LogExport.TODOS) }
    var feedback by remember { mutableStateOf<String?>(null) }
    var feedbackTipo by remember { mutableStateOf(TuiFeedbackTipo.EXITO) }

    /** El texto que el usuario PIDIO exportar, fijado antes de abrir el SAF. */
    var aExportar by remember { mutableStateOf<String?>(null) }

    val filtradas = remember(lineas, canal) { LogExport.filtrarPorCanal(lineas, canal) }

    // SAF CreateDocument: el usuario elige DONDE. `text/plain` porque son lineas
    // de texto; el export no cambia el contenido por el tipo declarado.
    val crearLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/plain"),
    ) { uri ->
        // Cancelar NO es un error: no se escribe nada y no se pinta un fallo.
        if (uri == null) return@rememberLauncherForActivityResult
        val texto = aExportar ?: return@rememberLauncherForActivityResult
        scope.launch(Dispatchers.IO) {
            val resultado = runCatching {
                val destino = context.contentResolver.openOutputStream(uri, "wt")
                    ?: throw IOException("el selector no devolvio un flujo para $uri")
                destino.use { it.write(texto.toByteArray(Charsets.UTF_8)) }
            }
            resultado.fold(
                onSuccess = {
                    // Solo aqui se afirma exito: la escritura se confirmo.
                    feedback = "LOGS EXPORTADOS"
                    feedbackTipo = TuiFeedbackTipo.EXITO
                    KMLog.event("[CONFIG]", "logs exportados lineas=${texto.lines().size}")
                },
                onFailure = { fallo ->
                    feedback = "NO SE PUDO EXPORTAR ${fallo.message ?: "FILE_WRITE_FAILED"}"
                    feedbackTipo = TuiFeedbackTipo.FALLO
                    KMLog.event("[CONFIG]", "export fallido codigo=FILE_WRITE_FAILED", KMLog.Level.WARN)
                },
            )
        }
    }

    Column(modifier = modifier.fillMaxWidth()) {
        // --- Filtro por canal: la fila de botones de canal ---
        Text(
            text = "FILTRO:",
            color = TerminalGray30,
            fontFamily = FontFamily.Monospace,
            fontSize = TuiTokens.FontSize.sm,
        )
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        ) {
            val opciones = listOf(LogExport.TODOS) + KMLog.CHANNELS
            opciones.forEach { opcion ->
                val activo = opcion == canal
                Text(
                    text = if (activo) "[$opcion]" else " $opcion ",
                    color = if (activo) TerminalBlack else TerminalCyan,
                    fontFamily = FontFamily.Monospace,
                    fontSize = TuiTokens.FontSize.sm,
                    modifier = Modifier
                        .background(if (activo) TerminalCyan else TerminalDarkGray)
                        .clickable { canal = opcion }
                        .padding(horizontal = 6.dp, vertical = 4.dp),
                )
            }
        }

        TuiScrollbar(visible = 1, total = filtradas.size)

        // --- El cuerpo: las lineas en una LazyColumn ACOTADA, o el estado vacio ---
        //
        // F3: antes era un `verticalScroll` sin cota dentro de un Column externo:
        // con 200 lineas empujaba las acciones FUERA de la pantalla. Ahora el
        // cuerpo tiene `weight(1f)` (cede espacio) y scrollea por dentro, de modo
        // que las acciones de abajo quedan siempre alcanzables.
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f, fill = false)
                .background(TerminalBlack)
                .padding(vertical = 4.dp),
        ) {
            if (filtradas.isEmpty()) {
                Text(
                    text = if (lineas.isEmpty()) "SIN LOGS TODAVIA" else "SIN LINEAS EN $canal",
                    color = TerminalGray30,
                    fontFamily = FontFamily.Monospace,
                    fontSize = TuiTokens.FontSize.body,
                )
            } else {
                LazyColumn(modifier = Modifier.fillMaxWidth()) {
                    items(filtradas) { linea ->
                        Text(
                            text = linea,
                            color = if (linea.contains("FAILED") || linea.contains("RECHAZADO")) TerminalRed
                            else TerminalCyan,
                            fontSize = TuiTokens.FontSize.sm,
                            fontFamily = FontFamily.Monospace,
                        )
                    }
                }
            }
        }

        // --- Feedback veraz ---
        TuiStatusMessage(texto = feedback, tipo = feedbackTipo)

        // --- Las acciones ---
        ListOfActions(
            onCopiar = {
                copiarAlPortapapeles(context, "KeyMessage logs", LogExport.textoExport(lineas))
                feedback = "LOGS COPIADOS (${lineas.size})"
                feedbackTipo = TuiFeedbackTipo.EXITO
            },
            onCopiarFiltrado = {
                if (filtradas.isEmpty()) {
                    feedback = "NO HAY LINEAS QUE COPIAR"
                    feedbackTipo = TuiFeedbackTipo.FALLO
                } else {
                    copiarAlPortapapeles(context, "KeyMessage logs $canal", LogExport.textoExport(filtradas))
                    feedback = "COPIADAS ${filtradas.size} LINEAS ($canal)"
                    feedbackTipo = TuiFeedbackTipo.EXITO
                }
            },
            onExportar = {
                if (lineas.isEmpty()) {
                    feedback = "NO HAY LOGS QUE EXPORTAR"
                    feedbackTipo = TuiFeedbackTipo.FALLO
                } else {
                    aExportar = LogExport.textoExport(lineas)
                    crearLauncher.launch("keymessage-logs.txt")
                }
            },
            onCompartir = {
                if (lineas.isEmpty()) {
                    feedback = "NO HAY LOGS QUE COMPARTIR"
                    feedbackTipo = TuiFeedbackTipo.FALLO
                } else {
                    val intent = Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(Intent.EXTRA_TEXT, LogExport.textoExport(lineas))
                    }
                    context.startActivity(Intent.createChooser(intent, "Compartir logs"))
                }
            },
        )
    }
}

@Composable
private fun ListOfActions(
    onCopiar: () -> Unit,
    onCopiarFiltrado: () -> Unit,
    onExportar: () -> Unit,
    onCompartir: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth().padding(top = 4.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
            TuiAction(texto = "[ COPIAR ]", onClick = onCopiar, modifier = Modifier.weight(1f))
            TuiAction(texto = "[ COPIAR FILTRADO ]", onClick = onCopiarFiltrado, modifier = Modifier.weight(1f))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
            TuiAction(texto = "[ EXPORTAR ]", onClick = onExportar, modifier = Modifier.weight(1f))
            TuiAction(texto = "[ COMPARTIR ]", onClick = onCompartir, modifier = Modifier.weight(1f))
        }
    }
}

private fun copiarAlPortapapeles(context: Context, etiqueta: String, texto: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    clipboard.setPrimaryClip(ClipData.newPlainText(etiqueta, texto))
}

/** F5 — La etiqueta que describe el panel: la usa DIAGNOSTICO. */
@Composable
fun LogPanelTitulo() {
    Text(
        text = "LOGS  (buffer ${KMLog.lines().size}/${KMLog.MAX_BUFFERED_LINES})",
        color = TerminalWhite,
        fontFamily = FontFamily.Monospace,
        fontSize = TuiTokens.FontSize.md,
    )
}
