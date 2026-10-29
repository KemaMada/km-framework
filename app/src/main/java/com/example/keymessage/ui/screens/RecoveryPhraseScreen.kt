package com.example.keymessage.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.example.keymessage.identity.IdentityMaterial
import com.example.keymessage.identity.fraseDeRecuperacion
import com.example.keymessage.recovery.AvisoSobrescritura
import com.example.keymessage.recovery.ResultadoRestauracion
import com.example.keymessage.recovery.Validacion
import com.example.keymessage.ui.components.TuiAction
import com.example.keymessage.ui.components.TuiButton
import com.example.keymessage.ui.components.TuiField
import com.example.keymessage.ui.components.TuiFrame
import com.example.keymessage.ui.theme.TerminalBlack
import com.example.keymessage.ui.theme.TerminalCyan
import com.example.keymessage.ui.theme.TerminalDarkGray
import com.example.keymessage.ui.theme.TerminalGray30
import com.example.keymessage.ui.theme.TerminalRed
import com.example.keymessage.ui.theme.TerminalWhite
import com.example.keymessage.ui.theme.TuiTokens
import com.km.mnemonic.MnemonicPhrase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 6.3 — Las dos pantallas de la frase de recuperacion.
 *
 * ## LO QUE ESTA EN `remember` Y POR QUE
 *
 * La frase vive en un `remember` DERIVADO: se calcula cuando el usuario acepta el
 * aviso y se deja de calcular en cuanto deja de estar aceptando. No hay
 * ViewModel, ni `rememberSaveable`, ni `savedStateHandle`: las tres escriben la
 * frase en disco cuando Android mata el proceso, que es justo lo que no puede
 * pasar. Al salir de la composicion el `remember` se tira; al dejar de aceptar el
 * aviso, tambien.
 *
 * ## EL ALCANCE QUE DICE LA PANTALLA
 *
 * "Restaura tu identidad". Ni una palabra mas. Los contactos se re-importan por
 * QR y los mensajes pasados no se recuperan, y ambas cosas se escriben EN
 * PANTALLA: un alcance que solo esta en la especificacion no lo puede leer
 * ninguno de los dos lados.
 */
private const val ALCANCE = "ESTA FRASE RESTAURA TU IDENTIDAD: LA CLAVE PUBLICA Y TU ID.\n" +
    "TUS CONTACTOS SE VUELVEN A IMPORTAR POR QR.\n" +
    "LOS MENSAJES PASADOS NO SON RECUPERABLES."

/**
 * 6.3 — Mostrar la frase de 24 palabras.
 *
 * @param material la identidad de la que se deriva la frase. No se guarda nada.
 * @param onConfirmarAviso se llama cuando el usuario ACEPTA el aviso de
 *   exposicion: es el punto en el que la frase empieza a existir en pantalla.
 * @param onIrARestaurar "TENGO FRASE": el camino de quien ya la tiene.
 * @param onVolver vuelve a donde Tucko.
 */
@Composable
fun FraseScreen(
    material: IdentityMaterial,
    onConfirmarAviso: () -> Unit,
    onIrARestaurar: () -> Unit,
    onVolver: () -> Unit,
) {
    var acepto by remember { mutableStateOf(false) }
    // Derivada: mientras `acepto` sea false NO hay frase en memoria. Cuando el
    // usuario deja de mirar (Volver a ocultar) el estado vuelve a false y la
    // frase se deja de tener.
    val frase = remember(acepto, material) { if (acepto) fraseDeRecuperacion(material) else null }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(TerminalBlack)
            // R1 / D2 — FRASE es CONTENEDOR_SCROLL: el inset se reserva ANTES
            // del scroll, asi que el VIEWPORT termina por encima de la barra de
            // navegacion y el `padding(16)` queda dentro. Es el envoltorio del
            // diseno sin anadir un nodo de layout: el orden de los modificadores
            // ya pone el inset fuera del scroll, no dentro del contenido que
            // scrollea (que dejaria 48dp muertos al final de la lista).
            .navigationBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
    ) {
        TuiFrame(titulo = "FRASE DE RECUPERACION") {
            Column(modifier = Modifier.padding(TuiTokens.Unit * 3)) {
                Text(ALCANCE, color = TerminalGray30, fontFamily = FontFamily.Monospace, fontSize = TuiTokens.FontSize.sm)
                Spacer(modifier = Modifier.height(16.dp))

                if (frase == null) {
                    Text(
                        "AVISO: AL VER ESTAS 24 PALABRAS VAS A PODER PERDER EL " +
                            "DISPOSITIVO.\nQUIEN LAS TENGA ACTUA COMO TU Y TU CLAVE " +
                            "PRIVADA SALE DE ESTE MOVIL PARA SIEMPRE.\n\n" +
                            "COPIA LAS 24 EN ORDEN. NO HAY FORMA DE RECUPERARLAS SI " +
                            "LAS PIERDES.",
                        color = TerminalRed,
                        fontFamily = FontFamily.Monospace,
                        fontSize = TuiTokens.FontSize.sm,
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    TuiButton(
                        texto = "ENTIENDO, VER MI FRASE",
                        onClick = {
                            acepto = true
                            onConfirmarAviso()
                        },
                        modifier = Modifier.fillMaxWidth(),
                    )
                } else {
                    RejillaFrase(frase)
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        "ESTA FRASE NO SE GUARDA EN NINGUN SITIO. ESCRIBE LAS 24 " +
                            "EN UN LUGAR FUERA DE ESTE MOVIL.",
                        color = TerminalRed,
                        fontFamily = FontFamily.Monospace,
                        fontSize = TuiTokens.FontSize.sm,
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    TuiButton(
                        texto = "VOLVER A OCULTAR",
                        onClick = { acepto = false },
                        activo = TerminalDarkGray,
                        contenido = TerminalWhite,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }

                Spacer(modifier = Modifier.height(16.dp))
                TuiAction(texto = "[ TENGO FRASE > RESTAURAR ]", onClick = onIrARestaurar)
                TuiAction(texto = "[<- VOLVER]", onClick = onVolver)
            }
        }
    }
}

/** La rejilla `01..24` en monospace: el numero es el que se copia. */
@Composable
private fun RejillaFrase(frase: MnemonicPhrase) {
    Column(modifier = Modifier.fillMaxWidth()) {
        for (i in 0 until frase.wordCount) {
            Row(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = "%02d".format(i + 1),
                    color = TerminalGray30,
                    fontFamily = FontFamily.Monospace,
                    fontSize = TuiTokens.FontSize.md,
                    modifier = Modifier.width(36.dp),
                )
                Text(
                    text = frase.wordAt(i),
                    color = TerminalWhite,
                    fontFamily = FontFamily.Monospace,
                    fontSize = TuiTokens.FontSize.lg,
                )
            }
        }
    }
}

/**
 * 6.3 — Pegar las 24 palabras y restaurar.
 *
 * ## POR QUE PEGADO COMPLETO Y NO PALABRA A PALABRA
 *
 * Un campo multilinea con las 24 pegadas del portapapeles es el unico modo que
 * funciona de verdad: el usuario que acaba de reinstalar tiene la frase en otro
 * sitio y teclear 24 palabras en un movil es lavia mas comun de errores de
 * tecleo. La normalizacion (espacios, mayusculas, saltos de linea) la hace
 * `Mnemonic.parse`, no esta pantalla.
 *
 * ## LOS TRES PASOS, EN ESTE ORDEN
 *
 * Validar, avisar de la sobrescritura, escribir. El boton de sobrescribir esta
 * APARTE del de validar a proposito: el que se pulsa sin querer es el de
 * validar, y ese no escribe nada.
 */
@Composable
fun RestaurarScreen(
    hayIdentidadLocal: Boolean,
    idActual: com.km.model.IdentityId?,
    validar: (String) -> Validacion,
    restaurar: (Validacion, com.km.model.IdentityId?, Boolean) -> ResultadoRestauracion,
    onBack: () -> Unit,
    onRestaurado: () -> Unit,
) {
    var texto by remember { mutableStateOf("") }
    var validacion by remember { mutableStateOf<Validacion?>(null) }
    var aviso by remember { mutableStateOf<AvisoSobrescritura?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var ocupado by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    // Todo lo que escribe o deriva claves va a IO: `restaurar` cierra el socket,
    // escribe con `commit()` y vuelve a levantar el motor.
    fun validarAhora() {
        if (ocupado) return
        ocupado = true
        scope.launch {
            val resultado = withContext(Dispatchers.IO) { validar(texto) }
            validacion = resultado
            aviso = null
            error = when (resultado) {
                is Validacion.Aceptada -> null
                is Validacion.Rechazada -> resultado.detalle
            }
            ocupado = false
        }
    }

    fun aplicar(confirmado: Boolean) {
        val aValidar = validacion ?: return
        if (ocupado) return
        ocupado = true
        scope.launch {
            val resultado = withContext(Dispatchers.IO) { restaurar(aValidar, idActual, confirmado) }
            when (resultado) {
                is ResultadoRestauracion.Restaurada -> {
                    // El texto pegado se tira ANTES de volver: es la segunda
                    // copia de la frase que hay en la app y no tiene por que
                    // sobrevivir a la pantalla.
                    texto = ""
                    validacion = null
                    aviso = null
                    onRestaurado()
                }
                is ResultadoRestauracion.NecesitaConfirmacion -> aviso = resultado.aviso
                is ResultadoRestauracion.Rechazada -> {
                    error = resultado.validacion.detalle
                    validacion = null
                }
            }
            ocupado = false
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(TerminalBlack)
            // R1 / D2 — RESTAURAR, igual que FRASE: CONTENEDOR_SCROLL. El inset
            // se reserva ANTES del scroll para que el viewport acabe por encima
            // de la barra de navegacion y la accion VOLVER del final sea
            // alcanzable sin pelear con el sistema.
            .navigationBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
    ) {
        TuiFrame(titulo = "RESTAURAR CON FRASE") {
            Column(modifier = Modifier.padding(TuiTokens.Unit * 3)) {
                Text(ALCANCE, color = TerminalGray30, fontFamily = FontFamily.Monospace, fontSize = TuiTokens.FontSize.sm)
                Spacer(modifier = Modifier.height(16.dp))

                TuiField(
                    value = texto,
                    onValueChange = {
                        texto = it
                        // Cualquier edicion invalida lo validado antes: sin esto, validar
                        // -> editar -> escribir escribiria una frase que el usuario ya
                        // no ve.
                        validacion = null
                        aviso = null
                        error = null
                    },
                    etiqueta = "PEGA LAS 24 PALABRAS",
                    placeholder = "abandon ability ...",
                    multilinea = true,
                )

                if (!hayIdentidadLocal) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        "NO HAY IDENTIDAD EN ESTE DISPOSITIVO: NO SE PIERDE NADA.",
                        color = TerminalGray30,
                        fontFamily = FontFamily.Monospace,
                        fontSize = TuiTokens.FontSize.sm,
                    )
                }

                Spacer(modifier = Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TuiButton(
                        texto = "VALIDAR",
                        onClick = { validarAhora() },
                        modifier = Modifier.weight(1f),
                    )
                    if (aviso != null) {
                        TuiButton(
                            texto = "SOBRESCRIBIR",
                            onClick = { aplicar(true) },
                            activo = TerminalRed,
                            contenido = TerminalWhite,
                            modifier = Modifier.weight(1f),
                        )
                    } else {
                        TuiButton(
                            texto = "RESTAURAR",
                            onClick = { aplicar(false) },
                            activo = TerminalDarkGray,
                            contenido = TerminalWhite,
                            habilitado = validacion is Validacion.Aceptada && !ocupado,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }

                aviso?.let {
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(it.texto, color = TerminalRed, fontFamily = FontFamily.Monospace, fontSize = TuiTokens.FontSize.body)
                    Spacer(modifier = Modifier.height(8.dp))
                    TuiAction(texto = "[ CANCELAR, NO CAMBIAR NADA ]", onClick = { aviso = null })
                }

                when (val v = validacion) {
                    is Validacion.Aceptada -> {
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            "FRASE VALIDA.\nIDENTIDAD QUE RESTAURA: ${v.identityId.value}",
                            color = TerminalCyan,
                            fontFamily = FontFamily.Monospace,
                            fontSize = TuiTokens.FontSize.sm,
                        )
                    }
                    else -> Unit
                }

                error?.let {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(it, color = TerminalRed, fontFamily = FontFamily.Monospace, fontSize = TuiTokens.FontSize.body)
                }

                Spacer(modifier = Modifier.height(12.dp))
                TuiAction(texto = "[<- VOLVER]", onClick = onBack)
            }
        }
    }
}