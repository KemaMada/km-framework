package com.example.keymessage.ui

import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.Manifest
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.keymessage.AppContainer
import com.example.keymessage.KeyMessageApp
import com.example.keymessage.boot.lineasBoot
import com.example.keymessage.contact.ContactFileCodec
import com.example.keymessage.contact.VerifiedContact
import com.example.keymessage.contact.rejectionCodeOf
import com.example.keymessage.data.ConversationRepository
import com.example.keymessage.model.Contact
import com.example.keymessage.ui.contact.ResumenContacto
import com.example.keymessage.ui.contact.mapearResumenes
import com.example.keymessage.ui.screens.ChatScreen
import com.example.keymessage.ui.screens.FraseScreen
import com.example.keymessage.ui.screens.MainScreen
import com.example.keymessage.ui.screens.RestaurarScreen
import com.example.keymessage.ui.screens.ShareQrScreen
import com.example.keymessage.ui.components.LogPanel
import com.example.keymessage.ui.components.ArranquePanel
import com.example.keymessage.ui.components.ArranqueFranja
import com.example.keymessage.ui.components.TuiP2PBadge
import com.example.keymessage.ui.components.TuiAction
import com.example.keymessage.ui.components.TuiButton
import com.example.keymessage.ui.components.TuiField
import com.example.keymessage.ui.components.TuiFrame
import com.example.keymessage.ui.components.TuiRule
import com.example.keymessage.ui.boot.OverlayArranque
import com.example.keymessage.ui.boot.debeMostrarOverlay
import com.example.keymessage.ui.nav.Pantalla
import com.example.keymessage.ui.nav.TuiNavegacion
import com.example.keymessage.ui.permisos.PermisosNotificaciones
import com.example.keymessage.ui.theme.KeyMessageTheme
import com.example.keymessage.ui.theme.TerminalBlack
import com.example.keymessage.ui.theme.TerminalBorder
import com.example.keymessage.ui.theme.TerminalCyan
import com.example.keymessage.ui.theme.TerminalDarkGray
import com.example.keymessage.ui.theme.TerminalGray
import com.example.keymessage.ui.theme.TerminalGray30
import com.example.keymessage.ui.theme.TerminalRed
import com.example.keymessage.ui.theme.TerminalWhite
import com.example.keymessage.ui.theme.TuiTokens
import com.example.keymessage.util.KMLog
import com.google.zxing.integration.android.IntentIntegrator
import com.km.model.IdentityId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    /** El mensaje estable cuando el permiso de camara no esta concedido. */
    private var avisoCamara by mutableStateOf<String?>(null)
    private var permisoCamaraLauncher: ActivityResultLauncher<String>? = null

    /**
     * 7.2 — POST_NOTIFICATIONS (API 33+): concedido o no, y su launcher.
     *
     * En API < 33 el permiso no existe como runtime, asi que el estado arranca
     * en `true` y la UI no pide nada ([PermisosNotificaciones]). En API 33+ se
     * consulta el permiso REAL; la denegacion NO bloquea nada (el servicio
     * foreground sigue siendo el dueno de la conexion) y se informa en
     * DIAGNOSTICO.
     */
    private var notificacionesConcedidas by mutableStateOf(true)
    private var permisoNotificacionesLauncher: ActivityResultLauncher<String>? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val container = (application as KeyMessageApp).container

        notificacionesConcedidas = if (Build.VERSION.SDK_INT < PermisosNotificaciones.API_RUNTIME) {
            true
        } else {
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.POST_NOTIFICATIONS,
            ) == PackageManager.PERMISSION_GRANTED
        }

        /**
         * 7.2 — La denegacion de notificaciones se DICE, no se fuerza.
         *
         * No se insiste ni se bloquea: si el usuario deniega, la app sigue
         * funcionando sin avisos visibles y DIAGNOSTICO muestra el estado. Pedir
         * otra vez desde el dialogo ya denegado no aporta nada en API 33 (el
         * sistema puede no volver a mostrarlo), asi que el aviso se queda.
         */
        permisoNotificacionesLauncher = registerForActivityResult(
            ActivityResultContracts.RequestPermission(),
        ) { concedido ->
            // El estado se actualiza y la pantalla lo DERIVA con la funcion
            // pura: no hay un segundo texto que se pueda desincronizar.
            notificacionesConcedidas = concedido
        }

        val scanLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { _ -> }

        /**
         * El permiso de CAMARA se pide EXPLICITAMENTE y su denegacion tiene una
         * rama ESTABLE.
         *
         * Antes, ESCANEAR QR lanzaba el escaner y, si el permiso estaba denegado,
         * la app se quedaba sin feedback (el escaner volvia sin codigo). Aqui, si
         * el permiso NO esta, se pide; si el usuario lo DENIEGA, se fija un
         * aviso estable ("PERMISO DE CAMARA REQUERIDO") que la pantalla pinta. No
         * se lanza el escaner sin permiso, y la app nunca queda sin decir nada.
         */
        permisoCamaraLauncher = registerForActivityResult(
            ActivityResultContracts.RequestPermission(),
        ) { concedido ->
            if (concedido) {
                avisoCamara = null
                lanzarEscaner()
            } else {
                avisoCamara = "PERMISO DE CAMARA REQUERIDO"
            }
        }

        setContent {
            KeyMessageTheme {
                RootUi(
                    container = container,
                    // D5 — el gate del overlay distingue un arranque del PROCESO
                    // de una recreacion (rotacion, cambio de tema): solo el
                    // primero es "arranque".
                    inicioFrio = savedInstanceState == null,
                    avisoCamara = avisoCamara,
                    notificacionesConcedidas = notificacionesConcedidas,
                    onPedirNotificaciones = {
                        if (Build.VERSION.SDK_INT >= PermisosNotificaciones.API_RUNTIME) {
                            permisoNotificacionesLauncher?.launch(Manifest.permission.POST_NOTIFICATIONS)
                        }
                    },
                    onScanRequest = {
                        if (ContextCompat.checkSelfPermission(
                                this@MainActivity,
                                Manifest.permission.CAMERA,
                            ) == PackageManager.PERMISSION_GRANTED
                        ) {
                            avisoCamara = null
                            lanzarEscaner()
                        } else {
                            permisoCamaraLauncher?.launch(Manifest.permission.CAMERA)
                        }
                    },
                )
            }
        }
        // La Activity NO arranca la conexion. El DUEÑO de la conexion es
        // `KeyMessageService`, que `KeyMessageApp.onCreate` arranca siempre al
        // crear el proceso; cuando esta Activity existe, el servicio ya esta en
        // camino. Que los dos llamaran a `arrancar()` era la carrera del
        // CRITICAL #1: dos intentos con la misma identidad y un `AUTH_FAILED`
        // no determinista. Aunque `arrancar()` es ahora atomico, la segunda
        // fuente de llamadas era la causa raiz y se elimina.
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        val result = IntentIntegrator.parseActivityResult(requestCode, resultCode, data)
        if (result != null && result.contents != null) {
            // Guardamos el texto escaneado en un singleton simple para que la UI lo recoja.
            scannedQrText = result.contents
        }
    }

    /**
     * Lanza el escaner zxing. Solo se llega aqui con el permiso concedido.
     *
     * El preview del escaner tiene que seguir la orientacion de la pantalla
     * (portrait): si no se bloquea, zxing hereda `sensorLandscape` y el preview
     * aparece rotado 90 grados.
     */
    private fun lanzarEscaner() {
        val integrator = IntentIntegrator(this)
        integrator.setDesiredBarcodeFormats(IntentIntegrator.QR_CODE)
        integrator.setPrompt("Escanea el QR de tu contacto")
        integrator.setBeepEnabled(false)
        integrator.setOrientationLocked(true)
        integrator.initiateScan()
    }

    companion object {
        var scannedQrText: String? = null
    }
}

// 6.2 / 8.3 — Las rutas viven en `ui/nav/TuiNavegacion.kt` para que la pila de
// navegacion (y su Back) sea una funcion pura y se pruebe en JVM.

/**
 * Los contactos del libro, en el modelo que la CONVERSACION necesita.
 *
 * ## POR QUE ESTA FUERA DEL COMPOSABLE
 *
 * Por la misma razon que [com.example.keymessage.ui.screens.etiquetaBundle]: no
 * hay `ComposeTestRule` en este modulo, y dentro de un composable no hay forma
 * de afirmar nada en un test de JVM. El mapeo —que raiz es el id y que la
 * huella es el nombre— es una FUNCION, y una funcion se prueba.
 *
 * ## QUE ES Y QUE NO ES
 *
 * `id` es la RAIZ (`identityRoot`), que es la clave de ruteo de la conversacion,
 * y NO el `deviceId`: un contacto que rota de dispositivo sigue siendo la misma
 * conversacion. `name` es la huella corta.
 *
 * La LISTA de INICIO usa ahora [mapearResumenes] (con el ultimo mensaje, F2);
 * este mapeo se mantiene porque fija el contrato "id = raiz, name = huella" que
 * `MapearContactosTest` vigila, y porque la app sigue necesitando el modelo
 * [Contact] para el resto de pantallas.
 */
fun mapearContactos(contactos: List<VerifiedContact>): List<Contact> =
    contactos.map { Contact(id = it.identityRoot.value, name = it.fingerprint) }

@Composable
private fun RootUi(
    container: AppContainer,
    inicioFrio: Boolean,
    avisoCamara: String?,
    notificacionesConcedidas: Boolean,
    onPedirNotificaciones: () -> Unit,
    onScanRequest: () -> Unit,
) {
    /**
     * 8.3 — La navegacion es una PILA, no una pantalla suelta.
     *
     * Con `pantalla = ...` el Back del sistema cerraba la Activity: no habia
     * "pantalla anterior" (verify #381 C3). La pila se guarda en `remember`
     * —la navegacion es estado de UI, no de proceso— y sus transiciones son las
     * funciones puras de [TuiNavegacion], probadas en JVM.
     */
    var pila by remember { mutableStateOf(TuiNavegacion.inicial()) }
    val pantalla = pila.last()

    val irA: (Pantalla) -> Unit = { destino -> pila = TuiNavegacion.avanzar(pila, destino) }
    val sustituir: (Pantalla) -> Unit = { destino -> pila = TuiNavegacion.sustituir(pila, destino) }
    val volver: () -> Unit = { pila = TuiNavegacion.volver(pila) ?: pila }
    val aInicio: () -> Unit = { pila = TuiNavegacion.reiniciar() }

    /**
     * El Back de Android, por NIVELES:
     *  1. si hay pantalla anterior, desapila (dialogo -> pantalla anterior);
     *  2. en la raiz INICIO el handler se DESACTIVA y el back lo trata el
     *     sistema: sale de la app, porque no hay nada detras.
     *
     * El estado protegido (`activo`, el texto escaneado) NO se toca al volver, y
     * el `OwnerDeApertura` de importar se limpia solo al salir del dialogo
     * (`DisposableEffect`), igual que antes.
     */
    BackHandler(enabled = TuiNavegacion.puedeVolver(pila)) { volver() }

    var activo by remember { mutableStateOf<VerifiedContact?>(null) }
    var scannedText by remember { mutableStateOf<String?>(null) }

    /**
     * 6.2 — El arranque pasa por la frase mientras el aviso no este aceptado.
     *
     * La condicion mira el ALMACEN, no un estado de la sesion: asi que sobrevive
     * a un reinicio del proceso, que es justo cuando tiene que seguir pidiendo
     * la frase. Y como `save()` reinicia el aviso, restaurar deja al usuario en
     * la pantalla de la frase NUEVA, que es lo unico honesto: acaba de cambiar
     * de identidad y su frase ha cambiado con ella.
     */
    LaunchedEffect(Unit) {
        if (!container.identityStore.phraseAcknowledged()) {
            // Onboarding forzado: FRASE es la raiz. No se apila INICIO detras
            // para que el back no pueda saltarse el aviso por accidente.
            pila = TuiNavegacion.reiniciar(Pantalla.FRASE)
        }
    }

    // Recoge el resultado del escaner si llego.
    LaunchedEffect(Unit) {
        while (true) {
            val t = MainActivity.scannedQrText
            if (t != null) {
                scannedText = t
                MainActivity.scannedQrText = null
                pila = TuiNavegacion.avanzar(pila, Pantalla.IMPORTAR)
            }
            kotlinx.coroutines.delay(300)
        }
    }

    val estado by container.state.collectAsStateWithLifecycle()
    val error by container.lastError.collectAsStateWithLifecycle()
    val aviso by container.avisoIntercambio.collectAsStateWithLifecycle()
    // F5 — la traza de arranque, observada como estado.
    val traza by container.trazaArranque.hitos.collectAsStateWithLifecycle()

    /**
     * R5 / D5 — El overlay de arranque, visible solo en el PRIMER arranque del
     * proceso.
     *
     * Se guarda en `remember` del composable y no en el contenedor porque su
     * visibilidad es estado de UI; lo que SI vive en el contenedor es el
     * reclamo atomico de "una vez por proceso", que es el invariante.
     */
    var overlayArranque by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        val fraseAvisada = container.identityStore.phraseAcknowledged()
        // `reclamar` devuelve `true` solo la PRIMERA vez del proceso. Se
        // consulta SIEMPRE, incluso cuando el gate va a decir que no: ser el
        // primero en reclamar ES el mecanismo de "una sola vez".
        val primeraVez = container.reclamarOverlayArranque()
        overlayArranque = debeMostrarOverlay(
            inicioFrio = inicioFrio,
            yaReclamado = !primeraVez,
            fraseAvisada = fraseAvisada,
        )
    }

    /**
     * La lista de contactos es VIVA, no una fotografia.
     *
     * Antes era `remember(pantalla) { container.contactBook.all()... }`: una
     * clave que solo cambia al cambiar de pestana, sobre un libro que ademas
     * no emitia nada. El resultado era que el contacto auto-registrado por el
     * `contact_exchange` NO aparecia hasta que el usuario navegaba a CONFIG y
     * volvia (obs #326, D1).
     *
     * Ahora se COLETA el flujo del libro —que es la fuente, y esta en el
     * libro porque el auto-registro escribe ahi directo— y el mapeo a
     * [Contact] sale de [mapearContactos], que es una funcion pura.
     */
    val contactosDelLibro by container.contactBook.contacts.collectAsStateWithLifecycle()

    /**
     * F2 — El ULTIMO mensaje por par, colectado UNA vez.
     *
     * Una sola suscripcion alimenta toda la lista: sin esto, cada fila dispararia
     * su propia consulta (N+1). El repositorio ya entrega un MAPA indexado por
     * raiz de par, y [mapearResumenes] solo hace `get`.
     */
    val ultimosPorPar by container.resumenes.observeResumenes()
        .collectAsStateWithLifecycle(initialValue = emptyMap())
    val contactos = remember(contactosDelLibro, ultimosPorPar) {
        mapearResumenes(contactosDelLibro, ultimosPorPar)
    }

    // Las barras de acciones quedan por encima de la barra de navegacion: con
    // `enableEdgeToEdge` el contenido llega hasta el borde, y sin insets los
    // botones de abajo quedan debajo del sistema. D5: la raiz NO consume el IME
    // —el unico que lo necesita es el COMPOSITOR del chat— ni la parte baja, que
    // la reservan las barras con `navigationBarsPadding()`. Asi no hay doble
    // hueco cuando el teclado sube: solo el compositor se mueve.
    androidx.compose.foundation.layout.Box(
        modifier = Modifier
            .fillMaxSize()
            .windowInsetsPadding(
                WindowInsets.safeDrawing.only(
                    WindowInsetsSides.Top + WindowInsetsSides.Horizontal,
                ),
            ),
    ) {
    when (pantalla) {
        Pantalla.INICIO -> Column(modifier = Modifier.fillMaxSize()) {
            // F5 — la franja de arranque: fases REALES, sin porcentajes. No
            // bloquea: la lista de contactos se pinta igual debajo.
            ArranqueFranja(hitos = traza)
            MainScreen(
                contacts = contactos,
                myIdentityId = container.identity.identityId.value,
                connectionState = estado,
                connectionError = error,
                onNewContactClick = { irA(Pantalla.IMPORTAR) },
                onShareQrClick = { irA(Pantalla.COMPARTIR) },
                onSettingsClick = { irA(Pantalla.DIAGNOSTICO) },
                onDiagnosticsClick = { irA(Pantalla.DIAGNOSTICO) },
                banner = aviso,
                onContactClick = { contacto ->
                    activo = container.contactBook.find(IdentityId(contacto.id))
                    irA(Pantalla.CONVERSACION)
                    // CRITICAL-1 (salida): abrir la conversacion abre tambien la
                    // sesion E2E y ENTREGA el frame PREKEY al peer. Sin esto no hay
                    // sesion y todo envio se rechaza. Va en IO porque abre un
                    // binding sobre el motor y cifra, y no puede correr en el hilo
                    // principal de Compose.
                    container.contactBook.find(IdentityId(contacto.id))?.let { c ->
                        CoroutineScope(Dispatchers.IO).launch {
                            container.abrirSesionYEntregar(c)
                        }
                    }
                },
            )
        }

        Pantalla.IMPORTAR -> ImportContactDialog(
            scannedText = scannedText,
            avisoCamara = avisoCamara,
            onDismiss = { volver() },
            onScanRequest = onScanRequest,
            onShareQrRequest = { sustituir(Pantalla.COMPARTIR) },
            onImported = { contacto ->
                // EL UNICO punto de efectos del evento "importado".
                //
                // Antes lo disparaban DOS: el `ImportContactViewModel` al
                // importar y el `LaunchedEffect(state.contact)` de este
                // composable. Con el `ViewModel` retenido por la Activity, el
                // segundo se re-disparaba al reabrir el dialogo y RELANZABA el
                // intercambio entero con el contacto ANTERIOR (obs #326, D2).
                //
                // Ahora solo dispara el `ViewModel`, y este `ViewModel` es de
                // una APERTURA: al cerrar el dialogo se descarta. Si el
                // registro falla, la excepcion SUBE: el `ViewModel` la
                // convierte en un rechazo con codigo estable y el dialogo lo
                // pinta. NO se navega y NO se abre sesion, porque el contacto
                // no quedo guardado.
                container.registrarContactoImportado(contacto)
                activo = contacto
                // El dialogo se CIERRA al importar: se sustituye, no se apila.
                // Apilar dejaria el back reabriendo el dialogo (estado perdido).
                sustituir(Pantalla.CONVERSACION)
                // El mismo camino que al pulsar un contacto, PERO explicito:
                // importar es el otro momento en que la app tiene un contacto y
                // puede/DEBE abrir la sesion E2E. Y ademas RE-ANUNCIA aunque ya
                // haya sesion: re-importar es pedir re-negociar (el otro pudo
                // reiniciar y perder la suya). Importar sin establecimiento fue
                // justo lo que dejo a los dos nodos ADB sin poder enviarse nada.
                CoroutineScope(Dispatchers.IO).launch {
                    container.reanunciarYEntregar(contacto)
                }
            },
        )

        Pantalla.CONVERSACION -> {
            val contacto = activo
            if (contacto == null) {
                // Sin contacto activo no hay conversacion que pintar: se vuelve
                // a INICIO como efecto, no escribiendo estado en composicion.
                LaunchedEffect(Unit) { aInicio() }
            } else {
                /**
                 * EL GUARD DEL NUCLEO (R8).
                 *
                 * `container.requireCore()` LANZA si todavia no hay nucleo, y
                 * estaba dentro de un `remember {}`: la app se caia al entrar
                 * en la conversacion sin conexion. Con D2 arreglado ese camino
                 * ya es alcanzable de verdad —el segundo import sin rele llega
                 * hasta aqui—, asi que la conversion tiene que DEGRADAR, no
                 * reventar.
                 *
                 * Lo que se muestra es el estado de la CONEXION y su ultimo
                 * error: los dos son codigos que ya existen (`DISCONNECTED`,
                 * `BOOTSTRAP_ALL_FAILED`, ...) y no un stack interno. Y no se
                 * recuerda: en cuanto la conexion llega, `nucleo` deja de ser
                 * `null` y la conversacion aparece sola.
                 */
                val nucleo = runCatching { container.requireCore() }.getOrNull()
                if (nucleo == null) {
                    SinNucleoScreen(
                        estado = estado.name,
                        codigo = error ?: "NO_CORE",
                        onBack = { volver() },
                    )
                } else {
                    // R14: el repositorio se recuerda con el CONTENEDOR, no con
                    // el nucleo. Un `remember(nucleo)` dejaba al repositorio (y
                    // al VM por identityRoot) apuntando al nucleo viejo tras
                    // una reconexion: los envios morian en un transporte
                    // cerrado. El provider resuelve SIEMPRE el vigente.
                    val repo = remember(container) {
                        ConversationRepository(
                            coreProvider = { runCatching { container.requireCore() }.getOrNull() },
                            messageDao = container.messageDao,
                            localIdentity = container.identity.identityId,
                        )
                    }
                    val vm: ConversationViewModel = viewModel(
                        key = contacto.identityRoot.value,
                        factory = ConversationViewModel.Factory(
                            repository = repo,
                            peerId = contacto.identityRoot,
                            peerName = contacto.fingerprint,
                        ),
                    )
                    val ui by vm.uiState.collectAsStateWithLifecycle()
                    ChatScreen(
                        contactName = ui.peerName,
                        contactId = contacto.identityRoot.value,
                        allMessages = mapOf(contacto.identityRoot.value to ui.messages),
                        connectionState = estado,
                        banner = ui.lastRejection,
                        onSend = vm::send,
                        onRetry = vm::resend,
                        onDiscard = vm::discard,
                        onBack = { volver() },
                    )
                }
            }
        }

        Pantalla.DIAGNOSTICO -> DiagnosticsScreen(
            onBack = { volver() },
            onVerFrase = { irA(Pantalla.FRASE) },
            onRestaurar = { irA(Pantalla.RESTAURAR) },
            connectionState = estado,
            endpoint = container.endpoints.preferred,
            connectionError = error,
            notificacionesConcedidas = notificacionesConcedidas,
            onPedirNotificaciones = onPedirNotificaciones,
            traza = traza,
        )

        Pantalla.FRASE -> FraseScreen(
            material = container.identity,
            onConfirmarAviso = { container.identityStore.setPhraseAcknowledged(true) },
            onIrARestaurar = { irA(Pantalla.RESTAURAR) },
            onVolver = { aInicio() },
        )

        Pantalla.RESTAURAR -> RestaurarScreen(
            hayIdentidadLocal = container.recoveryFlow.hayIdentidadLocal(),
            idActual = container.identity.identityId.takeIf { container.recoveryFlow.hayIdentidadLocal() },
            validar = container.recoveryFlow::validar,
            restaurar = container.recoveryFlow::restaurar,
            onBack = { volver() },
            onRestaurado = { sustituir(Pantalla.FRASE) },
        )

        Pantalla.COMPARTIR -> ShareQrScreen(
            container = container,
            onBack = { volver() },
        )
    }

    // R5 — el overlay se pinta ENCIMA de la pantalla actual, sin sustituirla:
    // debajo, INICIO ya esta compuesto y es navegable en cuanto se cierra. Si el
    // relay esta offline no se espera nada: las lineas dicen lo que hay y la
    // cota de 2 s lo cierra.
    if (overlayArranque) {
        OverlayArranque(
            lineas = lineasBoot(
                env = container.bootEnvironment,
                hitos = traza,
                estado = estado,
                error = error,
            ),
            onCerrar = { overlayArranque = false },
        )
    }
    }
}

/**
 * La conversacion SIN NUCLEO: no hay red, y la app lo DICE.
 *
 * ## POR QUE ESTO EXISTE
 *
 * Porque la conversacion necesita el nucleo cifrado, y el nucleo solo existe
 * cuando hay conexion. Entrar en la conversacion sin ella no es un caso raro:
 * es el caso normal de un import sin rele, y de un telefono sin cobertura.
 *
 * ## QUE SE MUESTRA
 *
 * El estado de la conexion y su ultimo error. Los dos son codigos que YA
 * existen (`DISCONNECTED`, `BOOTSTRAP_ALL_FAILED`, ...), y el `NO_CORE` es el
 * unico caso en que no hay ningun error todavia —la conexion ni ha podido
 * empezar—. Nunca un stack: el usuario no puede hacer nada con el, y por
 * pantalla solo se cuela informacion que no debe.
 */
@Composable
private fun SinNucleoScreen(
    estado: String,
    codigo: String,
    onBack: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(TerminalBlack)
            .padding(16.dp),
    ) {
        TuiFrame(titulo = "SIN CONEXION", acento = TerminalRed) {
            Column(modifier = Modifier.padding(TuiTokens.Unit * 3)) {
                Text(
                    text = "ESTADO  $estado",
                    color = TerminalGray30,
                    fontFamily = FontFamily.Monospace,
                    fontSize = TuiTokens.FontSize.md,
                )
                Text(
                    text = "CODIGO  $codigo",
                    color = TerminalCyan,
                    fontFamily = FontFamily.Monospace,
                    fontSize = TuiTokens.FontSize.md,
                )
                Spacer(modifier = Modifier.height(12.dp))
                TuiAction(texto = "[<- VOLVER]", onClick = onBack)
            }
        }
    }
}

/**
 * El `ViewModelStoreOwner` de UNA APERTURA del dialogo de importar.
 *
 * ## POR QUE UN PROPIO Y NO EL DE LA ACTIVITY
 *
 * Porque `viewModel()` (de `lifecycle-viewmodel-compose`) resuelve
 * `LocalViewModelStoreOwner.current`, y en una Activity eso es el
 * `ViewModelStore` de la Activity: el `ImportContactViewModel` sobrevivia a
 * la navegacion. Con su `state.contact` NO nulo, el `LaunchedEffect` de la
 * pantalla se volvia a disparar al reabrir el dialogo y RELANZABA el
 * intercambio de contactos con el contacto ANTERIOR (obs #326, D2).
 *
 * ## POR QUE UN STORE PROPIO Y NO UNA CLAVE
 *
 * Porque con una clave los `ViewModel` viejos se acumulan en el store de la
 * Activity, y porque `reset()` es fragil: el `LaunchedEffect` puede correr con
 * una clave obsoleta. Con un store anidado, el invariante "un estado por
 * apertura" es ESTRUCTURAL: al salir, `clear()` se lleva el estado con el.
 */
private class OwnerDeApertura : ViewModelStoreOwner {
    override val viewModelStore: ViewModelStore = ViewModelStore()
}

/**
 * El owner de una apertura, y su limpieza al salir.
 *
 * El `store.clear()` es lo que hace que el estado MUERA con el dialogo: sin el,
 * el `ViewModel` sobrevive al `onDispose` por el `ViewModelStore` que lo
 * guarda, y el defecto D2 vuelve.
 */
@Composable
private fun rememberOwnerDeApertura(): ViewModelStoreOwner {
    val owner = remember { OwnerDeApertura() }
    DisposableEffect(owner) {
        onDispose { owner.viewModelStore.clear() }
    }
    return owner
}

@Composable
private fun ImportContactDialog(
    scannedText: String? = null,
    avisoCamara: String? = null,
    onDismiss: () -> Unit = {},
    onScanRequest: () -> Unit = {},
    onShareQrRequest: () -> Unit = {},
    onImported: (VerifiedContact) -> Unit = {},
) {
    // El owner anidado ENVUELVE todo el dialogo: el `ViewModel` de import se
    // crea dentro de el, asi que pertenece a ESTA apertura y a ninguna mas.
    val owner = rememberOwnerDeApertura()
    CompositionLocalProvider(LocalViewModelStoreOwner provides owner) {
        DialogoDeImportacion(
            scannedText = scannedText,
            avisoCamara = avisoCamara,
            onDismiss = onDismiss,
            onScanRequest = onScanRequest,
            onShareQrRequest = onShareQrRequest,
            onImported = onImported,
        )
    }
}

@Composable
private fun DialogoDeImportacion(
    scannedText: String?,
    avisoCamara: String?,
    onDismiss: () -> Unit,
    onScanRequest: () -> Unit,
    onShareQrRequest: () -> Unit,
    onImported: (VerifiedContact) -> Unit,
) {
    var texto by remember { mutableStateOf("") }
    var esperado by remember { mutableStateOf("") }

    /**
     * Rechazo de la LECTURA del fichero, local a este dialogo.
     *
     * No se mete en [ImportUiState]: ese estado es del `ViewModel` y describe el
     * resultado de VERIFICAR. Un `.km` de 10 MB no ha llegado a verificar — ni ha
     * podido: ni siquiera se ha leido— y meter su codigo en el estado de
     * importacion haria que `rejectionCode` significara dos cosas distintas.
     */
    var fileError by remember { mutableStateOf<String?>(null) }

    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()

    /**
     * SAF `OpenDocument`: el `.km` solo RELLENA el campo.
     *
     * ## POR QUE NO HAY UN SEGUNDO CAMINO DE IMPORTACION
     *
     * El fichero no se verifica aqui. Rellena `texto` y ya: IMPORTAR sigue siendo
     * `vm.import(texto, esperado)` -> `ContactImporter.import`, el MISMO codigo que
     * el QR y que el pegado. Un camino de importacion mas habria sido un segundo
     * sitio donde una firma se puede comprobar peor.
     *
     * ## POR QUE EL FILTRO ES "CUALQUIER TIPO" Y NO UN TIPO CONCRETO
     *
     * El contenido es un portador ISO-8859-1 sin tipo MIME propio, y el usuario
     * puede haberlo renombrado a `.txt` al moverlo: la extension no valida nada.
     * Filtrar por tipo dejaria fuera ficheros que SÍ son correctos, y el
     * filtro de la app seria mas estricto que su propia verificacion.
     */
    val abrirLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        // Cancelar el selector no es un error: el campo se queda como estaba y no
        // se pinta nada.
        if (uri == null) return@rememberLauncherForActivityResult
        val resolver = context.contentResolver
        // En IO: abrir un `ParcelFileDescriptor` y leer hasta 64 KB no puede
        // correr en el hilo de Compose, que ademas esta dibujando.
        scope.launch(Dispatchers.IO) {
            val resultado = runCatching {
                resolver.openInputStream(uri)?.use { ContactFileCodec.read(it) }
            }
            resultado.fold(
                onSuccess = { leido ->
                    if (leido == null) {
                        // El `Uri` existe pero no se puede abrir para lectura:
                        // sigue siendo un rechazo con codigo, no un silencio.
                        fileError = "FILE_READ_FAILED"
                        KMLog.event("[FILE]", "rechazado codigo=FILE_READ_FAILED", KMLog.Level.WARN)
                    } else {
                        fileError = null
                        texto = leido
                        KMLog.event("[FILE]", "leido .km caracteres=${leido.length}")
                    }
                },
                onFailure = { fallo ->
                    // El codec ya trae el codigo ESTABLE (`FILE_OVERSIZED` ->
                    // `FILE_TOO_LARGE`, `FILE_EMPTY` -> `EMPTY_ENVELOPE`), asi que
                    // el rechazo se pinta con la MISMA palabra que el de un QR
                    // cualquiera. Un `.km` de 200 MB se dice, no se traga.
                    val codigo = rejectionCodeOf(fallo)
                    fileError = codigo
                    KMLog.event("[FILE]", "rechazado codigo=$codigo", KMLog.Level.WARN)
                },
            )
        }
    }

    LaunchedEffect(scannedText) {
        if (!scannedText.isNullOrBlank()) texto = scannedText
    }

    /**
     * El `ViewModel` de import, que pertenece a ESTA apertura.
     *
     * El `factory` no lleva clave: el store es el anidado de [ImportContactDialog],
     * y ahi vive el `ViewModel` mientras el dialogo este en pantalla.
     *
     * [onImported] se lee por [rememberUpdatedState] para que el `ViewModel`
     * siempre llame a la version ACTUAL del callback, sin que haya que recrear
     * el `ViewModel` en cada recomposicion.
     */
    val onImportedActual by rememberUpdatedState(onImported)
    val vm: ImportContactViewModel = viewModel(
        factory = object : androidx.lifecycle.ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>): T =
                ImportContactViewModel { onImportedActual(it) } as T
        },
    )
    val state by vm.state.collectAsStateWithLifecycle()

    // NO hay `LaunchedEffect(state.contact)`.
    //
    // Ese era el SEGUNDO dueno del evento "importado": el `ImportContactViewModel`
    // ya lo dispara al importar (unico dueno), y el `LaunchedEffect` lo repetia
    // con cada recomposicion en la que `state.contact` Cambiaba. Con el
    // `ViewModel` retenido por la Activity, reabrir el dialogo volvia a
    // disparar el `LaunchedEffect` con el contacto ANTERIOR y relanzaba el
    // intercambio entero (obs #326, D2).

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(TerminalBlack),
    ) {
        Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
            TuiFrame(titulo = "NUEVO CONTACTO") {
                Column(modifier = Modifier.padding(TuiTokens.Unit * 3)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TuiButton(
                            texto = "ESCANEAR QR",
                            onClick = onScanRequest,
                            modifier = Modifier.weight(1f),
                        )
                        TuiButton(
                            texto = "VER MI QR",
                            onClick = onShareQrRequest,
                            modifier = Modifier.weight(1f),
                        )
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    TuiButton(
                        texto = "IMPORTAR FICHERO (.km)",
                        onClick = { abrirLauncher.launch(arrayOf("*/*")) },
                        activo = TerminalGray30,
                        contenido = TerminalBlack,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text(
                        text = "usando el selector del sistema (SAF)",
                        color = TerminalGray30,
                        fontFamily = FontFamily.Monospace,
                        fontSize = TuiTokens.FontSize.sm,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    TuiField(
                        value = texto,
                        onValueChange = { texto = it },
                        etiqueta = "bundle .kmc",
                        placeholder = "pega aqui, o deja que un .km lo rellene",
                        multilinea = true,
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    TuiField(
                        value = esperado,
                        onValueChange = { esperado = it },
                        etiqueta = "identityId esperada (opcional)",
                    )

                    // --- resultado de la verificacion, honesto ---
                    state.contact?.let { contacto ->
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "VERIFICADO ${contacto.fingerprint}",
                            color = TerminalCyan,
                            fontFamily = FontFamily.Monospace,
                            fontSize = TuiTokens.FontSize.md,
                        )
                    }
                    state.rejectionCode?.let { codigo ->
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "RECHAZADO $codigo",
                            color = TerminalRed,
                            fontFamily = FontFamily.Monospace,
                            fontSize = TuiTokens.FontSize.md,
                        )
                    }
                    state.rejectionDetail?.let { detalle ->
                        Text(
                            text = detalle,
                            color = TerminalGray30,
                            fontFamily = FontFamily.Monospace,
                            fontSize = TuiTokens.FontSize.sm,
                        )
                    }
                    fileError?.let { codigo ->
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "RECHAZADO $codigo",
                            color = TerminalRed,
                            fontFamily = FontFamily.Monospace,
                            fontSize = TuiTokens.FontSize.md,
                        )
                    }
                }
            }

            // NOTA HONESTA: el explorador interno `.km` NO existe (R4.3).
            Text(
                text = "IMPORTACION INTERNA .km: PENDIENTE - usa el selector SAF",
                color = TerminalGray30,
                fontFamily = FontFamily.Monospace,
                fontSize = TuiTokens.FontSize.sm,
                modifier = Modifier.padding(vertical = 6.dp),
            )

            // La denegacion de CAMERA tiene una rama ESTABLE: se dice, no se calla.
            avisoCamara?.let { aviso ->
                Text(
                    text = aviso,
                    color = TerminalRed,
                    fontFamily = FontFamily.Monospace,
                    fontSize = TuiTokens.FontSize.md,
                    fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
                    modifier = Modifier.padding(vertical = 4.dp),
                )
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                TuiButton(
                    texto = "IMPORTAR",
                    onClick = { vm.import(texto, esperado.ifBlank { null }) },
                    modifier = Modifier.weight(1f),
                )
                TuiButton(
                    texto = "VOLVER",
                    onClick = onDismiss,
                    activo = TerminalDarkGray,
                    contenido = TerminalWhite,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
private fun DiagnosticsScreen(
    onBack: () -> Unit,
    onVerFrase: () -> Unit,
    onRestaurar: () -> Unit,
    connectionState: com.km.protocol.ConnectionState,
    endpoint: String?,
    connectionError: String?,
    notificacionesConcedidas: Boolean,
    onPedirNotificaciones: () -> Unit,
    traza: List<com.example.keymessage.boot.HitoFase>,
) {
    var lineas by remember { mutableStateOf(KMLog.lines()) }

    LaunchedEffect(Unit) {
        while (true) {
            lineas = KMLog.lines()
            kotlinx.coroutines.delay(1000)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(TerminalBlack)
            // R1 / D2 — DIAGNOSTICO es CONTENEDOR_FIJO: el unico dueno del
            // inset inferior es este Column. LOGS conserva su `weight(1f)` y
            // VOLVER queda por encima de la barra de navegacion.
            .navigationBarsPadding()
            .padding(12.dp),
    ) {
        TuiFrame(titulo = "DIAGNOSTICO") {
            Column(modifier = Modifier.padding(TuiTokens.Unit * 2)) {
                // 6.2 — CONFIG: aqui es donde una identidad ya creada llega a su frase.
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TuiAction(texto = "[ VER FRASE ]", onClick = onVerFrase)
                    TuiAction(texto = "[ RESTAURAR CON FRASE ]", onClick = onRestaurar)
                }
                // F1 — el indicador del ENLACE AL RELAY, con su leyenda. La
                // leyenda es lo que impide leer `P2P[o]` como "el contacto esta
                // online": habla del transporte.
                Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    TuiP2PBadge(estado = connectionState)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = com.example.keymessage.ui.components.IndicadorP2P.LEYENDA,
                        color = TerminalGray30,
                        fontFamily = FontFamily.Monospace,
                        fontSize = TuiTokens.FontSize.sm,
                    )
                }
                // Estado REAL de la conexion, con el codigo real (endpoint ahi).
                Text(
                    text = com.example.keymessage.ui.components.etiquetaConexion(
                        connectionState,
                        endpoint,
                        connectionError,
                    ),
                    color = if (connectionState == com.km.protocol.ConnectionState.ONLINE) {
                        com.example.keymessage.ui.theme.TerminalGreen
                    } else {
                        TerminalRed
                    },
                    fontFamily = FontFamily.Monospace,
                    fontSize = TuiTokens.FontSize.md,
                    fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
                )

                // 7.2 — POST_NOTIFICATIONS: el estado se DICE y se ofrece pedirlo.
                // En API < 33 la funcion pura devuelve null y aqui no se pinta
                // nada: no se pide un permiso que ese nivel no tiene.
                PermisosNotificaciones.mensaje(Build.VERSION.SDK_INT, notificacionesConcedidas)?.let { aviso ->
                    Text(
                        text = aviso,
                        color = TerminalRed,
                        fontFamily = FontFamily.Monospace,
                        fontSize = TuiTokens.FontSize.sm,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
                PermisosNotificaciones.accion(Build.VERSION.SDK_INT, notificacionesConcedidas)?.let { etiqueta ->
                    TuiAction(texto = etiqueta, onClick = onPedirNotificaciones)
                }
            }
        }
        Spacer(modifier = Modifier.height(8.dp))
        // F5 — la TABLA de fases de arranque, con duraciones MEDIDAS.
        TuiFrame(titulo = "ARRANQUE") {
            Column(modifier = Modifier.padding(TuiTokens.Unit * 2)) {
                ArranquePanel(hitos = traza)
            }
        }
        Spacer(modifier = Modifier.height(8.dp))
        // F3 — LOGS es la seccion que CEDE espacio: `weight(1f)` la deja crecer y
        // el cuerpo de LogPanel scrollea por dentro. Las acciones viven DENTRO de
        // LogPanel (pinned al fondo del panel) y VOLVER queda FUERA del scroll:
        // con 200 lineas y 200% de font scale, las cinco acciones siguen
        // alcanzables.
        TuiFrame(titulo = "LOGS", modifier = Modifier.weight(1f)) {
            Column(modifier = Modifier.padding(TuiTokens.Unit * 2)) {
                LogPanel(lineas = lineas, modifier = Modifier.weight(1f))
            }
        }
        Spacer(modifier = Modifier.height(8.dp))
        TuiAction(texto = "[<- VOLVER]", onClick = onBack)
    }
}