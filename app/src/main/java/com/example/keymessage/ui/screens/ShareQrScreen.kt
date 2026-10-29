package com.example.keymessage.ui.screens

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.example.keymessage.AppContainer
import com.example.keymessage.contact.ContactFileCodec
import com.example.keymessage.contact.SelfBundleExporter
import com.example.keymessage.qr.QrBitmap
import com.example.keymessage.ui.components.TuiAction
import com.example.keymessage.ui.components.TuiButton
import com.example.keymessage.ui.components.TuiFrame
import com.example.keymessage.ui.theme.*
import com.example.keymessage.util.KMLog
import java.io.IOException

/**
 * La linea de tamano del QR, la que el usuario lee para juzgar su densidad.
 *
 * ## POR QUE ESTA FUERA DEL COMPOSABLE
 *
 * Porque un `Text("bytes=...")` dentro de la pantalla no se puede afirmar en un
 * test de JVM: no hay `ComposeTestRule` en este modulo y no se va a meter uno por
 * una etiqueta. La etiqueta es, en cambio, justo el sitio donde estaba el bug:
 * mostraba [SelfBundleExporter.Exported.byteLength] cuando ese campo era UTF-8,
 * de modo que decia 720 para un payload de 469. Sacarla a una funcion la deja
 * probada contra el MISMO numero que el log del emisor.
 *
 * El numero viene de [SelfBundleExporter.Exported.byteLength], que ya es el
 * tamano del portador ISO-8859-1 ([com.example.keymessage.contact.BundleCompression.carrierBytes]):
 * aqui no se vuelve a medir, porque dos mediciones son dos formas de discrepar.
 */
fun etiquetaBundle(exportado: SelfBundleExporter.Exported): String =
    "bytes=${exportado.byteLength} opk=${exportado.oneTimePreKeyCount}"

/**
 * El nombre que SAF propone para el `.km`: `contact-<huella16>.km`.
 *
 * ## POR QUE ESTA FUERA DEL COMPOSABLE
 *
 * Por la misma razon que [etiquetaBundle], y con el mismo motivo: dentro de un
 * `Button(onClick = { launcher.launch(...) })` no hay forma de afirmar nada en un
 * test de JVM sin `ComposeTestRule`. La regla —prefijo de 16 hex de la identidad,
 * sin nada mas— es una FUNCION, y una funcion se prueba.
 *
 * ## POR QUE NO `KMLog.truncar`
 *
 * Porque [KMLog.truncar] esta hecho para una LINEA DE TRAZA, y anade `"..."` al
 * final cuando la cadena es mas larga que el prefijo pedido: el nombre acababa
 * siendo `contact-a1b2c3d4e5f60708....km`, con puntos suspensivos DENTRO del
 * nombre de fichero. Es un nombre valido pero feo, y separa la huella de la
 * extension. Aqui el truncado es un `take` a secas: 16 hex de una `identityId` son
 * 8 bytes, y el nombre se lee de un vistazo.
 *
 * Un `identityId` corto no revienta: `take(16)` de algo mas corto devuelve lo
 * entero, que es justo lo que se quiere.
 */
fun nombreFicheroContacto(identityIdHex: String): String =
    "contact-${identityIdHex.take(HUELLA_CARACTERES)}.km"

/** Caracteres de `identityId` que entran en el nombre: 16 hex son 8 bytes. */
private const val HUELLA_CARACTERES: Int = 16

@Composable
fun ShareQrScreen(container: AppContainer, onBack: () -> Unit) {
    val context = LocalContext.current
    var exported by remember { mutableStateOf<SelfBundleExporter.Exported?>(null) }
    var error by remember { mutableStateOf<String?>(null) }

    /**
     * El `exported` que el usuario pidio GUARDAR.
     *
     * No se reutiliza el `exported` que hay en pantalla dentro del `onResult`:
     * ese estado puede volver a cambiar mientras el selector SAF esta abierto, y
     * lo que el usuario eligio guardar es lo que estaba PINTADO cuando pulso el
     * boton. Se fija aqui, antes de lanzar, y el resultado escribe ese.
     */
    var aGuardar by remember { mutableStateOf<SelfBundleExporter.Exported?>(null) }

    /**
     * SAF `CreateDocument`: el usuario elige DONDE, la app no decide.
     *
     * ## POR QUE `application/octet-stream`
     *
     * Porque el contenido son los bytes ISO-8859-1 del portador, que no son
     * ningun tipo MIME conocido: declararlo como `text/plain` invita al selector
     * de documentos de texto y a los editores que re-codifican a UTF-8, que es
     * exactamente el fallo que `ContactFileCodec` no puede arreglar (leer un `.km`
     * re-codificado da otro documento, y se rechaza con `ENVELOPE_MALFORMED`).
     */
    val crearLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/octet-stream"),
    ) { uri ->
        // Cancelar el selector NO es un error: no se escribe ningun byte y no se
        // pinta nada. Solo hay un `error` cuando el usuario confirmo un destino y
        // el fichero no se pudo escribir.
        if (uri == null) return@rememberLauncherForActivityResult
        val exportado = aGuardar
        if (exportado == null) {
            error = "no hay ningun bundle que guardar"
            return@rememberLauncherForActivityResult
        }
        runCatching {
            val destino = context.contentResolver.openOutputStream(uri, "wt")
                ?: throw IOException("el selector no devolvio un flujo de escritura para $uri")
            // `"wt"` trunca: sin el, un fichero mas largo que el bundle anterior
            // conserva la cola y produce un `.km` corrupto que se rechaza al
            // importar, sin motivo aparente. El cierre es de aqui y SOLO de aqui.
            destino.use { ContactFileCodec.write(it, exportado.text) }
        }.fold(
            onSuccess = {
                error = null
                KMLog.event(
                    "[FILE]",
                    "guardado ${nombreFicheroContacto(container.identity.identityId.value)} " +
                        "bytes=${exportado.byteLength}",
                )
            },
            onFailure = { fallo ->
                error = "no se pudo guardar el fichero .km: ${fallo.message}"
                KMLog.event("[FILE]", "rechazado codigo=FILE_WRITE_FAILED msg=${fallo.message}", KMLog.Level.WARN)
            },
        )
    }

    LaunchedEffect(Unit) {
        val res = SelfBundleExporter.exportResult(container.identity, container.localKeys)
        exported = res.getOrNull()
        error = res.exceptionOrNull()?.message
    }

    val bitmap = remember(exported) { exported?.let { QrBitmap.renderOrNull(it.text) } }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(TerminalBlack)
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // R1 / D1 — COMPARTIR es FOOTER_FIJO: el cuerpo CEDE espacio y
        // scrollea por dentro; VOLVER queda fijo y es el UNICO dueno del inset
        // inferior. Con el QR de 280dp y el portador debajo, sin esta cota el
        // VOLVER se salia de la pantalla en un telefono pequeno.
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            TuiFrame(titulo = "MI QR") {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(TuiTokens.Unit * 3),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    bitmap?.let {
                        Image(bitmap = it.asImageBitmap(), contentDescription = "QR", modifier = Modifier.size(280.dp))
                    } ?: Text("Generando...", color = TerminalGray30, fontFamily = FontFamily.Monospace, fontSize = TuiTokens.FontSize.md)
                    error?.let { Text(it, color = TerminalRed, fontFamily = FontFamily.Monospace, fontSize = TuiTokens.FontSize.sm) }
                    exported?.let { e ->
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(etiquetaBundle(e), color = TerminalGray30, fontFamily = FontFamily.Monospace, fontSize = TuiTokens.FontSize.sm)
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(e.text, color = TerminalCyan, fontFamily = FontFamily.Monospace, fontSize = TuiTokens.FontSize.xs, maxLines = 3)
                        Spacer(modifier = Modifier.height(12.dp))
                        TuiButton(
                            texto = "COMPARTIR",
                            onClick = {
                                val intent = Intent(Intent.ACTION_SEND).apply {
                                    type = "text/plain"
                                    putExtra(Intent.EXTRA_TEXT, e.text)
                                }
                                context.startActivity(Intent.createChooser(intent, "Compartir QR"))
                            },
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        // El TERCER portador del MISMO `exported` que esta en el QR de
                        // arriba: no se vuelve a exportar ni a firmar nada aqui.
                        TuiButton(
                            texto = "GUARDAR .KM",
                            onClick = {
                                aGuardar = e
                                crearLauncher.launch(nombreFicheroContacto(container.identity.identityId.value))
                            },
                            activo = TerminalGray30,
                            contenido = TerminalBlack,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            }
        }
        Spacer(modifier = Modifier.height(8.dp))
        Box(modifier = Modifier.fillMaxWidth().navigationBarsPadding()) {
            TuiAction(texto = "[<- VOLVER]", onClick = onBack)
        }
    }
}