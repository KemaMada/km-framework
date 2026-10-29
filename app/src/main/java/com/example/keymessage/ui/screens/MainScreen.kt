package com.example.keymessage.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.keymessage.ui.components.TuiAction
import com.example.keymessage.ui.components.TuiButton
import com.example.keymessage.ui.components.TuiColumnHeader
import com.example.keymessage.ui.components.TuiFrame
import com.example.keymessage.ui.components.TuiListRow
import com.example.keymessage.ui.components.TuiP2PBadge
import com.example.keymessage.ui.components.TuiRule
import com.example.keymessage.ui.components.TuiScrollbar
import com.example.keymessage.ui.components.etiquetaConexion
import com.example.keymessage.ui.contact.ResumenContacto
import com.example.keymessage.ui.theme.TerminalBlack
import com.example.keymessage.ui.theme.TerminalCyan
import com.example.keymessage.ui.theme.TerminalDarkGray
import com.example.keymessage.ui.theme.TerminalGray30
import com.example.keymessage.ui.theme.TerminalGreen
import com.example.keymessage.ui.theme.TerminalRed
import com.example.keymessage.ui.theme.TerminalWhite
import com.example.keymessage.ui.theme.TuiTokens
import com.km.protocol.ConnectionState

/**
 * F2 — INICIO / CONTACTOS en el shell TUI.
 *
 * ## CABECERA CON `P2P`, SIN ENDPOINT NI HUELLA DE PEER
 *
 * La cabecera pinta [TuiP2PBadge], que es el estado del ENLACE AL RELAY (spec
 * R1), y ya NO el `ONLINE <endpoint>` que el usuario veia junto a la URL. El
 * endpoint vive en DIAGNOSTICO; aqui solo importa si hay enlace.
 *
 * ## FILA DE DOS LINEAS (spec R2)
 *
 * Cada fila muestra el alias/huella UNA vez (linea 1) y el snippet + hora del
 * ultimo mensaje (linea 2). NO hay columna de ID: la identidad completa se ve en
 * el detalle ([DetalleContacto]) y es copiable. Sin mensaje, la linea 2 dice
 * "SIN MENSAJES", nunca una hora inventada.
 *
 * ## PRESENCIA HONESTA (R3.2, R10.1)
 *
 * No hay fuente de presencia por peer: la fila NO pinta `[?]` de presencia ni
 * ningun glifo que insinue "online". El `P2P` de la cabecera es del transporte.
 */
@Composable
fun MainScreen(
    contacts: List<ResumenContacto>,
    myIdentityId: String,
    connectionState: ConnectionState,
    connectionError: String?,
    banner: String? = null,
    onImportClick: () -> Unit = {},
    onNewContactClick: () -> Unit = onImportClick,
    onShareQrClick: () -> Unit = {},
    onSettingsClick: () -> Unit = {},
    onDiagnosticsClick: () -> Unit = {},
    onContactClick: (ResumenContacto) -> Unit = {},
) {
    val context = LocalContext.current

    /** El contacto cuyo detalle esta abierto, o `null`. */
    var detalleDe by remember { mutableStateOf<ResumenContacto?>(null) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(TerminalBlack),
    ) {
        // --- cabecera: identidad (tap = copiar) + enlace al relay ---
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(TerminalDarkGray)
                .padding(horizontal = 12.dp, vertical = 12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "KeyMessage",
                    color = TerminalRed,
                    fontSize = TuiTokens.FontSize.xl,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f),
                )
                TuiP2PBadge(estado = connectionState)
            }
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = myIdentityId,
                color = TerminalCyan,
                fontSize = TuiTokens.FontSize.body,
                fontFamily = FontFamily.Monospace,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.clickable(onClick = { copyToClipboard(context, myIdentityId) }),
            )
            Text(
                text = "(tap para copiar tu identityId)",
                color = TerminalGray30,
                fontSize = TuiTokens.FontSize.sm,
                fontFamily = FontFamily.Monospace,
            )
        }

        TuiFrame(titulo = null, modifier = Modifier.weight(1f)) {
            ColumnHeader()
            TuiRule()
            // R12: el contacto auto-registrado es VOLATIL. Se dice aqui, siempre.
            VolatileNotice()
            banner?.let { codigo ->
                Text(
                    text = "INTERCAMBIO FALLIDO  $codigo",
                    color = TerminalRed,
                    fontSize = TuiTokens.FontSize.body,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
                )
            }
            Box(modifier = Modifier.weight(1f, fill = true).fillMaxWidth()) {
                if (contacts.isEmpty()) {
                    EmptyState()
                } else {
                    ContactList(
                        contacts = contacts,
                        onContactClick = onContactClick,
                        onDetalle = { detalleDe = it },
                    )
                }
            }
        }

        // --- barra de estado y de acciones ---
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(TerminalDarkGray)
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "Contactos: ${contacts.size}",
                color = TerminalCyan,
                fontSize = TuiTokens.FontSize.md,
                fontFamily = FontFamily.Monospace,
            )
            Spacer(modifier = Modifier.weight(1f))
            // R5 — al cerrar el overlay, INICIO dice el estado REAL del enlace
            // (ONLINE / RECONECTANDO / SIN CONEXION con su codigo). Sale de
            // `etiquetaConexion`, la misma funcion de DIAGNOSTICO: no hay una
            // segunda forma de nombrar el estado que pueda discrepar.
            Text(
                text = if (connectionState == ConnectionState.ONLINE) {
                    "ONLINE"
                } else {
                    etiquetaConexion(connectionState, endpoint = null, error = connectionError)
                },
                color = if (connectionState == ConnectionState.ONLINE) TerminalGreen else TerminalRed,
                fontSize = TuiTokens.FontSize.sm,
                fontFamily = FontFamily.Monospace,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            TuiScrollbar(visible = 1, total = contacts.size)
        }
        BottomBar(
            onNewContactClick = onNewContactClick,
            onShareQrClick = onShareQrClick,
            onSettingsClick = onSettingsClick,
        )
    }

    detalleDe?.let { contacto ->
        DetalleContacto(
            contacto = contacto,
            onCerrar = { detalleDe = null },
        )
    }
}

/**
 * La nota FIJA de que los contactos viven solo en memoria.
 *
 * Porque no hay forma de saber, mirando la lista, si un contacto concreto es de
 * memoria o persistido: HOY todos lo son. La persistencia es otro cambio
 * (`persist-contact-book`); mientras no exista, esto es lo que la app afirma.
 */
@Composable
private fun VolatileNotice() {
    Text(
        text = "CONTACTOS SOLO EN MEMORIA  ·  se pierden al reiniciar la app",
        color = TerminalGray30,
        fontSize = TuiTokens.FontSize.sm,
        fontFamily = FontFamily.Monospace,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
    )
}

@Composable
private fun ColumnHeader() {
    TuiColumnHeader(izquierda = "CONTACTOS", derecha = "ULTIMO MENSAJE")
}

@Composable
private fun ContactList(
    contacts: List<ResumenContacto>,
    onContactClick: (ResumenContacto) -> Unit,
    onDetalle: (ResumenContacto) -> Unit,
) {
    LazyColumn(modifier = Modifier.fillMaxWidth()) {
        items(contacts) { contact ->
            ContactRow(
                contact = contact,
                onClick = { onContactClick(contact) },
                onDetalle = { onDetalle(contact) },
            )
            TuiRule()
        }
    }
}

/**
 * Una fila de contacto, de DOS lineas.
 *
 * Linea 1: el alias/huella UNA vez. Linea 2: snippet + hora del ultimo mensaje,
 * o "SIN MENSAJES". El area tactil de la fila es >= 48dp ([TuiListRow]); el
 * boton `[i]` de detalle tambien.
 *
 * PRESENCIA: la fila NO pinta `[X]` ni `[?]` de presencia. La app no tiene esa
 * fuente; el `P2P` de la cabecera es del transporte, no del contacto.
 */
@Composable
private fun ContactRow(
    contact: ResumenContacto,
    onClick: () -> Unit,
    onDetalle: () -> Unit,
) {
    val segundaLinea = if (contact.snippet == null) {
        "SIN MENSAJES"
    } else {
        val hora = contact.hora ?: ""
        if (hora.isEmpty()) contact.snippet else "${contact.snippet}  $hora"
    }
    val sinMensajes = contact.snippet == null

    TuiListRow(
        descripcion = "Contacto ${contact.alias}. $segundaLinea",
        onClick = onClick,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = contact.alias,
                    color = TerminalWhite,
                    fontSize = TuiTokens.FontSize.lg,
                    fontFamily = FontFamily.Monospace,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = segundaLinea,
                    color = if (sinMensajes) TerminalGray30 else TerminalCyan,
                    fontSize = TuiTokens.FontSize.body,
                    fontFamily = FontFamily.Monospace,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            // Detalle: la identidad completa SOLO aqui, y copiable. >= 48dp.
            Text(
                text = "[i]",
                color = TerminalCyan,
                fontSize = TuiTokens.FontSize.md,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                modifier = Modifier
                    .width(48.dp)
                    .defaultMinSize(minHeight = TuiTokens.MinTouch)
                    .clickable(role = Role.Button, onClick = onDetalle)
                    .padding(horizontal = 6.dp, vertical = 12.dp),
            )
        }
    }
}

/**
 * F2 — El detalle de un contacto: la identidad completa, con COPIAR.
 *
 * ## QUE SE MUESTRA Y QUE NO
 *
 * El `id` completo (la raiz) y su alias. Se ofrece COPIAR del id completo, que
 * es lo unico que permite comparar dos identidades de verdad. NO se declara
 * online del peer: no hay fuente de presencia.
 */
@Composable
private fun DetalleContacto(
    contacto: ResumenContacto,
    onCerrar: () -> Unit,
) {
    val context = LocalContext.current
    // R1 — el detalle es un overlay INTERNO a pantalla completa: el inset se
    // reserva aqui, una vez, y VOLVER queda dentro del area visible.
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(TerminalBlack)
            .navigationBarsPadding()
            .padding(16.dp),
    ) {
        TuiFrame(titulo = "DETALLE DE CONTACTO") {
            Column(modifier = Modifier.padding(TuiTokens.Unit * 3)) {
                Text(
                    text = "ALIAS  ${contacto.alias}",
                    color = TerminalWhite,
                    fontSize = TuiTokens.FontSize.md,
                    fontFamily = FontFamily.Monospace,
                )
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = "IDENTIDAD (raiz, 64 hex)",
                    color = TerminalGray30,
                    fontSize = TuiTokens.FontSize.sm,
                    fontFamily = FontFamily.Monospace,
                )
                Text(
                    text = contacto.id,
                    color = TerminalCyan,
                    fontSize = TuiTokens.FontSize.body,
                    fontFamily = FontFamily.Monospace,
                )
                Spacer(modifier = Modifier.height(8.dp))
                TuiAction(
                    texto = "[ COPIAR IDENTIDAD ]",
                    onClick = { copyToClipboard(context, contacto.id) },
                )
                Spacer(modifier = Modifier.height(8.dp))
                TuiButton(
                    texto = "VOLVER",
                    onClick = onCerrar,
                    activo = TerminalDarkGray,
                    contenido = TerminalWhite,
                )
            }
        }
    }
}

@Composable
private fun EmptyState() {
    Box(modifier = Modifier.fillMaxWidth().fillMaxHeight(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(text = "Sin contactos", color = TerminalGray30, fontSize = TuiTokens.FontSize.xl, fontFamily = FontFamily.Monospace)
            Spacer(modifier = Modifier.height(8.dp))
            Text(text = "Agrega con NUEVO CONTACTO.", color = TerminalGray30, fontSize = TuiTokens.FontSize.md, fontFamily = FontFamily.Monospace)
        }
    }
}

@Composable
private fun BottomBar(
    onNewContactClick: () -> Unit,
    onShareQrClick: () -> Unit,
    onSettingsClick: () -> Unit,
) {
    // R1 / D1 — el UNICO dueno del inset inferior en INICIO es este footer.
    // El fondo se pinta ANTES del padding para que el area de la barra de
    // navegacion quede cubierta y los tres botones (>= 48dp por `TuiButton`)
    // queden por encima del sistema, no debajo.
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(TerminalDarkGray)
            .navigationBarsPadding()
            .padding(8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        TuiButton(
            texto = "NUEVO",
            onClick = onNewContactClick,
            modifier = Modifier.weight(1f),
        )
        TuiButton(
            texto = "COMPARTIR QR",
            onClick = onShareQrClick,
            modifier = Modifier.weight(1f),
        )
        TuiButton(
            texto = "CONFIG",
            onClick = onSettingsClick,
            activo = TerminalDarkGray,
            contenido = TerminalWhite,
            modifier = Modifier.weight(1f),
        )
    }
}

private fun copyToClipboard(context: Context, text: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    clipboard.setPrimaryClip(ClipData.newPlainText("identityId", text))
    Toast.makeText(context, "identityId copied", Toast.LENGTH_SHORT).show()
}
