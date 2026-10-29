package com.example.keymessage.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.example.keymessage.model.ChatMessage
import com.example.keymessage.model.MessageStatus
import com.example.keymessage.ui.components.TuiButton
import com.example.keymessage.ui.components.TuiField
import com.example.keymessage.ui.components.TuiP2PBadge
import com.example.keymessage.ui.components.TuiRule
import com.example.keymessage.ui.theme.TerminalBlack
import com.example.keymessage.ui.theme.TerminalCyan
import com.example.keymessage.ui.theme.TerminalDarkGray
import com.example.keymessage.ui.theme.TerminalGray30
import com.example.keymessage.ui.theme.TerminalGreen
import com.example.keymessage.ui.theme.TerminalRed
import com.example.keymessage.ui.theme.TerminalWhite
import com.example.keymessage.ui.theme.TuiTokens
import com.km.protocol.ConnectionState
import kotlinx.coroutines.launch

/**
 * F4 — CONVERSACION en el shell TUI.
 *
 * ## QUE CAMBIA RESPECTO AL LOOK MATERIAL
 *
 * `OutlinedTextField` y `Button` salen; entran el compositor [TuiField] con IME
 * Send y un [TuiButton] ENVIAR. Los separadores son [TuiRule].
 *
 * ## ESTADOS REALES (R5.2)
 *
 * El icono/etiqueta de cada mensaje sale de `MessageStatus`, que mapea el estado
 * REAL del nucleo. NO se pinta DELIVERED sin ACK: `statusIcon` distingue QUEUED,
 * SENDING, SENT, DELIVERED, EXPIRED y FAILED, y no colapsa encolado con enviado.
 *
 * @param banner el rechazo de envio (falta de sesion), como CODIGO estable.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ChatScreen(
    contactName: String,
    contactId: String,
    allMessages: Map<String, List<ChatMessage>>,
    connectionState: ConnectionState,
    banner: String? = null,
    onSend: (String) -> Unit,
    onRetry: (String) -> Unit = {},
    onDiscard: (String) -> Unit = {},
    onBack: () -> Unit,
) {
    val messages = allMessages[contactId] ?: emptyList()
    var inputText by remember { mutableStateOf("") }
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()

    /**
     * F4 — el IME, con FALLBACK.
     *
     * `WindowInsets.isImeVisible` no existe en todas las versiones del BOM de
     * Compose; el ancho/alto del insets del IME (`WindowInsets.ime`) SI. Se
     * combina: hay IME si `isImeVisible` (cuando existe) o si el bottom del
     * insets del IME es mayor que 0. Asi el re-anclaje no depende de una API
     * que podria no estar.
     */
    val densidad = LocalDensity.current
    val imeBottomPx = WindowInsets.ime.getBottom(densidad)
    val imeVisible = androidx.compose.foundation.layout.WindowInsets.isImeVisible || imeBottomPx > 0

    /**
     * R17 — el scroll deja de ser ciego.
     *
     * `wasAtBottom` se actualiza con el LAYOUT real, no con una suposicion: el
     * ultimo item visible tiene que ser el ultimo de la lista. Con eso,
     * [ChatScrollPolicy] decide si se puede arrastrar la vista. Si el usuario
     * esta leyendo arriba, no se le mueve: se avisa con "N nuevos".
     */
    var prevCount by remember(contactId) { mutableStateOf(messages.size) }
    var wasAtBottom by remember(contactId) { mutableStateOf(true) }
    var nuevos by remember(contactId) { mutableStateOf(0) }

    /** Mensaje terminal cuya confirmacion de descarte se esta pidiendo. */
    var descartarPendiente by remember(contactId) { mutableStateOf<String?>(null) }

    LaunchedEffect(listState, messages.size) {
        snapshotFlow {
            val ultimoVisible = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1
            ultimoVisible >= messages.lastIndex && messages.isNotEmpty()
        }.collect { wasAtBottom = it }
    }

    /**
     * F4 — ABRIR al fondo.
     *
     * Al entrar en la conversacion la lista se coloca en el ULTIMO item, no en
     * el primero: el hilo de una conversacion se lee de abajo arriba. Se hace una
     * vez por contacto (`contactId` como clave) y sin animacion, porque es el
     * estado INICIAL de la vista, no un salto.
     */
    LaunchedEffect(contactId, messages.isNotEmpty()) {
        val destino = ChatScrollPolicy.indiceInicial(messages.size)
        if (destino >= 0) listState.scrollToItem(destino)
    }

    /**
     * F4 — RE-ANCLAJE por IME.
     *
     * Cuando el teclado sube o baja, la vista solo se re-ancla al fondo si el
     * usuario YA estaba al final. Si estaba leyendo historial, el IME no le
     * mueve la vista (el mismo contrato que para los mensajes nuevos).
     */
    LaunchedEffect(imeVisible) {
        if (ChatScrollPolicy.debeReanclarTrasIme(wasAtBottom, messages.isNotEmpty())) {
            listState.animateScrollToItem(messages.lastIndex)
        }
    }

    /**
     * El unico camino de envio: boton ENVIAR e IME Send llaman AQUI.
     *
     * Es una funcion (no dos lambdas parecidas) para que "enviar" signifique lo
     * mismo por los dos caminos y no haya doble submit: [textoTrasEnviar] vacia
     * el campo en la MISMA llamada que entrega el texto.
     */
    val enviar: () -> Unit = { inputText = textoTrasEnviar(inputText, onSend) }

    LaunchedEffect(messages.size) {
        if (ChatScrollPolicy.shouldFollow(wasAtBottom, prevCount, messages.size)) {
            if (messages.isNotEmpty()) listState.animateScrollToItem(messages.lastIndex)
            nuevos = 0
        } else {
            nuevos += ChatScrollPolicy.newCount(prevCount, messages.size)
        }
        prevCount = messages.size
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(TerminalBlack),
    ) {
        // --- cabecera: back + huella + estado desconocido [?] ---
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(TerminalDarkGray)
                .padding(horizontal = 8.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "[<-]",
                color = TerminalCyan,
                fontSize = TuiTokens.FontSize.md,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier
                    .clickable(onClick = onBack)
                    .padding(horizontal = 6.dp, vertical = 6.dp),
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = contactName,
                color = TerminalRed,
                fontSize = TuiTokens.FontSize.md,
                fontFamily = FontFamily.Monospace,
                fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
                modifier = Modifier.weight(1f),
            )
            // El indicador del ENLACE AL RELAY: NO es presencia del contacto.
            // `P2P[o]` dice que hay relay, no que el peer este conectado.
            TuiP2PBadge(estado = connectionState)
        }

        // R17 — CONTADOR DE FILAS, no de burbujas visibles. El autoscroll no
        // puede ocultar la evidencia: aunque la ventana muestre tres, aqui se
        // lee cuantas filas hay y cuantas siguen sin confirmacion.
        val sinConfirmar = messages.count { it.isMine && !it.status.confirmed }
        Text(
            text = "${messages.size} mensajes · $sinConfirmar sin confirmar",
            color = TerminalGray30,
            fontSize = TuiTokens.FontSize.sm,
            fontFamily = FontFamily.Monospace,
            modifier = Modifier
                .fillMaxWidth()
                .background(TerminalBlack)
                .padding(horizontal = 8.dp, vertical = 4.dp),
        )

        banner?.let { texto ->
            Text(
                text = texto,
                color = TerminalRed,
                fontSize = TuiTokens.FontSize.sm,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier.fillMaxWidth().background(TerminalDarkGray).padding(8.dp),
            )
        }

        TuiRule()

        if (messages.isEmpty()) {
            Box(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    "Sin mensajes todavia.\nEscribe el primero.",
                    color = TerminalGray30,
                    fontSize = TuiTokens.FontSize.body,
                    fontFamily = FontFamily.Monospace,
                    textAlign = TextAlign.Center,
                )
            }
        } else {
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                // F4 — el hilo crece HACIA ARRIBA: los mensajes nuevos quedan
                // pegados al compositor, no flotando en el centro.
                verticalArrangement = Arrangement.Bottom,
                contentPadding = PaddingValues(bottom = 6.dp),
            ) {
                items(messages) { msg ->
                    MessageBubble(
                        msg = msg,
                        onRetry = { onRetry(msg.id) },
                        onPedirDescarte = { descartarPendiente = msg.id },
                        onConfirmarDescarte = {
                            descartarPendiente = null
                            onDiscard(msg.id)
                        },
                        onCancelarDescarte = { descartarPendiente = null },
                        pendingDiscard = descartarPendiente == msg.id,
                    )
                }
            }
        }

        // R17 — aviso de lo que llego mientras el usuario leia arriba. Tocar
        // lleva al final; no se arrastra la vista sin permiso.
        if (nuevos > 0) {
            Text(
                text = "[ $nuevos nuevos ] toca para ver",
                color = TerminalCyan,
                fontSize = TuiTokens.FontSize.sm,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier
                    .fillMaxWidth()
                    .background(TerminalDarkGray)
                    .clickable {
                        scope.launch {
                            if (messages.isNotEmpty()) listState.animateScrollToItem(messages.lastIndex)
                            nuevos = 0
                        }
                    }
                    .padding(horizontal = 8.dp, vertical = 6.dp),
            )
        }

        TuiRule()

        // --- compositor: campo multilinea + ENVIAR (boton e IME) ---
        // D5: el COMPOSITOR es el unico que consume el IME. `imePadding` sube el
        // campo y el boton por encima del teclado; `navigationBarsPadding` cubre
        // el caso sin teclado. Los dos juntos no duplican: `imePadding` toma el
        // maximo de (ime, barras) cuando el teclado esta visible.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(TerminalDarkGray)
                .navigationBarsPadding()
                .imePadding()
                .padding(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TuiField(
                value = inputText,
                onValueChange = { inputText = it },
                placeholder = "Escribe un mensaje...",
                multilinea = true,
                // 72dp = ~3 lineas: multilinea de verdad sin comerse el hilo en
                // pantallas pequenas (el default de 160dp es para el bundle).
                minAlto = 72,
                // IME Send (R5.1): el teclado muestra "Enviar" y esa tecla llama
                // al MISMO camino que el boton. Sin esto el TuiField no exponia
                // keyboardActions y la accion se perdia (verify #381 C2).
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = { enviar() }),
                modifier = Modifier.weight(1f),
            )
            Spacer(modifier = Modifier.width(8.dp))
            TuiButton(
                texto = "ENVIAR",
                onClick = enviar,
                // Estado vacio honesto: sin texto no hay nada que enviar y el
                // control lo DICE en vez de aceptar un toque que no hace nada.
                habilitado = inputText.isNotBlank(),
            )
        }
    }
}

@Composable
private fun MessageBubble(
    msg: ChatMessage,
    pendingDiscard: Boolean,
    onRetry: () -> Unit,
    onPedirDescarte: () -> Unit,
    onConfirmarDescarte: () -> Unit,
    onCancelarDescarte: () -> Unit,
) {
    val align = if (msg.isMine) Alignment.End else Alignment.Start
    val fgColor = if (msg.isMine) TerminalCyan else TerminalWhite
    val bracketOpen = if (msg.isMine) "[" else "{"
    val bracketClose = if (msg.isMine) "]" else "}"

    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = if (msg.isMine) Alignment.End else Alignment.Start,
    ) {
        Text(
            text = "$bracketOpen ${msg.text} $bracketClose",
            color = fgColor,
            fontSize = TuiTokens.FontSize.body,
            fontFamily = FontFamily.Monospace,
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = formatTime(msg.timestamp),
                color = TerminalGray30,
                fontSize = TuiTokens.FontSize.xs,
                fontFamily = FontFamily.Monospace,
            )
            if (msg.isMine) {
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    text = statusIcon(msg.status),
                    color = statusColor(msg.status),
                    fontSize = TuiTokens.FontSize.s,
                    fontFamily = FontFamily.Monospace,
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    text = msg.status.display(msg.statusDetail),
                    color = statusColor(msg.status),
                    fontSize = TuiTokens.FontSize.s,
                    fontFamily = FontFamily.Monospace,
                )
            }
        }

        // R16: el motivo NO se queda en un enum. Si el mensaje necesita una
        // accion, se dice que paso y se ofrecen las dos unicas salidas
        // explicitas: reenviar (mismo messageId) o descartar (con confirmacion).
        if (msg.isMine && msg.status.needsAction) {
            Text(
                text = "motivo: ${msg.statusDetail ?: "sin motivo declarado"}",
                color = TerminalRed,
                fontSize = TuiTokens.FontSize.xs,
                fontFamily = FontFamily.Monospace,
            )
            if (pendingDiscard) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "descartar definitivamente?",
                        color = TerminalRed,
                        fontSize = TuiTokens.FontSize.s,
                        fontFamily = FontFamily.Monospace,
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    TuiButton(texto = "SI", onClick = onConfirmarDescarte, activo = TerminalRed)
                    Spacer(modifier = Modifier.width(6.dp))
                    TuiButton(texto = "NO", onClick = onCancelarDescarte)
                }
            } else {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TuiButton(texto = "REINTENTAR", onClick = onRetry)
                    Spacer(modifier = Modifier.width(6.dp))
                    TuiButton(
                        texto = "DESCARTAR",
                        onClick = onPedirDescarte,
                        activo = TerminalRed,
                    )
                }
            }
        }
    }
    Spacer(modifier = Modifier.height(2.dp))
}

/**
 * El icono del estado.
 *
 * `QUEUED` y `CREATED` NO comparten icono con `SENT` a proposito: encolado no es
 * entregado, y un icono igual para los tres seria una afirmacion que la pantalla
 * no puede sostener. El tick (`⊣`) es el unico que implica ENTREGA, y solo lo
 * pinta `DELIVERED` (ACK firmado verificado).
 */
private fun statusIcon(status: MessageStatus): String = when (status) {
    MessageStatus.CREATED -> "o"
    MessageStatus.QUEUED -> "~"
    MessageStatus.SENDING -> "\u22A1"
    MessageStatus.SENT -> "\u22A2"
    MessageStatus.DELIVERED -> "\u22A3"
    MessageStatus.EXPIRED -> "x"
    MessageStatus.FAILED -> "\u22A5"
}

/**
 * Envia [borrador] si tiene contenido y devuelve lo que debe quedar en el campo.
 *
 * ## POR QUE ES UNA FUNCION PURA
 *
 * Porque el envio del compositor tiene que ser el MISMO por boton y por IME
 * (spec R5.1) y no hay `ComposeTestRule` para probar el composable. Con esta
 * funcion se prueba en JVM que: (a) un borrador vacio no envia nada; (b) se
 * envia el texto SIN espacios de borde; (c) el envio ocurre UNA sola vez; y
 * (d) el campo queda vacio solo si de verdad se envio.
 *
 * @return el texto que debe quedar en el campo tras la llamada.
 */
fun textoTrasEnviar(borrador: String, onSend: (String) -> Unit): String {
    val texto = borrador.trim()
    if (texto.isEmpty()) return borrador
    onSend(texto)
    return ""
}

private fun statusColor(status: MessageStatus): androidx.compose.ui.graphics.Color = when (status) {
    MessageStatus.CREATED -> TerminalGray30
    MessageStatus.QUEUED -> TerminalGray30
    MessageStatus.SENDING -> TerminalGray30
    // R12: OUT no es entrega. Se pinta en gris apagado, no en el cian de algo
    // confirmado, para no dar por bueno lo que solo escribio el socket local.
    MessageStatus.SENT -> TerminalGray30
    MessageStatus.DELIVERED -> TerminalGreen
    MessageStatus.EXPIRED -> TerminalRed
    MessageStatus.FAILED -> TerminalRed
}

private fun formatTime(ts: Long): String {
    val cal = java.util.Calendar.getInstance().apply { timeInMillis = ts }
    val h = cal.get(java.util.Calendar.HOUR_OF_DAY)
    val m = cal.get(java.util.Calendar.MINUTE)
    return "${h.toString().padStart(2, '0')}:${m.toString().padStart(2, '0')}"
}
