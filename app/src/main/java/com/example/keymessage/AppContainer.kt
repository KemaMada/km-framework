package com.example.keymessage

import android.app.Application
import android.provider.Settings
import androidx.room.Room
import com.km.protocol.ConnectionState
import com.km.transport.JvmRelayTransport
import com.km.transport.RelayClientStack
import com.km.transport.RelaySignalSink
import com.km.transport.SignalDelivery
import com.km.transport.SignalOutcome
import com.example.keymessage.config.RelayEndpointConfig
import com.example.keymessage.config.ResolvedEndpoints
import com.example.keymessage.contact.ContactBook
import com.example.keymessage.contact.ContactBootstrap
import com.example.keymessage.contact.EntregaDePar
import com.example.keymessage.contact.ContactExchangeCoordinator
import com.example.keymessage.contact.ContactExchangeException
import com.example.keymessage.contact.ContactImporter
import com.example.keymessage.contact.InMemoryContactBook
import com.example.keymessage.contact.InboundContactRegistrar
import com.km.identity.RegistrationResult
import com.example.keymessage.contact.SelfBundleExporter
import com.example.keymessage.contact.rejectionCodeOf
import com.example.keymessage.boot.FaseArranque
import com.example.keymessage.boot.BootEnvironment
import com.example.keymessage.core.CoreFactory
import com.example.keymessage.data.ConversacionResumenRepository
import com.example.keymessage.identity.AndroidIdentityStore
import com.example.keymessage.identity.AndroidLocalKeyStore
import com.example.keymessage.identity.IdentityMaterial
import com.example.keymessage.identity.IdentityMaterialFactory
import com.example.keymessage.identity.IdentityStore
import com.example.keymessage.identity.LocalKeyFactory
import com.example.keymessage.identity.LocalKeyMaterial
import com.example.keymessage.identity.LocalKeyStore
import com.example.keymessage.identity.fraseDeRecuperacion
import com.example.keymessage.recovery.RecoveryCiclo
import com.example.keymessage.recovery.RecoveryFlow
import com.km.messaging.AcceptedSession
import com.km.messaging.InitiatedSession
import com.km.model.IdentityId
import com.km.node.PeerContext
import com.example.keymessage.contact.VerifiedContact
import com.example.keymessage.net.EndpointSequence
import com.example.keymessage.net.ArranqueUnico
import com.example.keymessage.net.AndroidNetworkMonitor
import com.example.keymessage.net.NetworkWatch
import com.example.keymessage.net.RelayEndpointsExhausted
import com.example.keymessage.net.RelayStackFactory
import com.example.keymessage.session.OpkSelection
import com.example.keymessage.session.PrekeyFrame
import com.example.keymessage.session.SessionCoordinator
import com.example.keymessage.storage.room.database.KeyMessageDatabase
import com.example.keymessage.storage.room.impl.RoomDuplicateStore
import com.example.keymessage.storage.room.impl.RoomMessageStore
import com.example.keymessage.storage.room.impl.RoomOfflineQueue
import com.example.keymessage.util.AndroidLogSink
import com.example.keymessage.util.KMLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * El contenedor de la app: TODO lo que se compone, y en un solo sitio.
 *
 * ## QUE HACE ESTA CLASE Y QUE NO
 *
 * Compone: identidad, configuracion de arranque, motor de rele del framework,
 * nucleo con el registro de sesiones, y el almacenamiento Room. No contiene
 * logica de protocolo ni de criptografia, porque esas dos cosas viven en
 * `km-core` y `km-transport`.
 *
 * ## POR QUE EL FAILOVER RECONSTRUYE EL NUCLEO
 *
 * `JvmRelayTransport` habla con UN endpoint: es una propiedad suya, no un
 * argumento que se pueda cambiar. Ademas el nucleo guarda la REFERENCIA al
 * transporte, de modo que un motor distinto necesita un nucleo distinto. Por
 * eso [arrancar] prueba endpoints EN ORDEN y construye la pila con el que
 * respondio; no "cambia la URL" de un motor ya construido, que es un cambio que
 * no existe en el framework.
 *
 * ## LO QUE NO SE PUEDE PROBAR EN UN TEST DE JVM
 *
 * Esta clase necesita un `Application`: `Settings.Global`, `Room.databaseBuilder`
 * y `EncryptedSharedPreferences` no existen fuera de Android. Lo que SI se prueba,
 * pieza a pieza, es todo lo que aqui se DELega: `RelayEndpointConfig`,
 * `EndpointSequence`, `RelayStackFactory`, `CoreFactory`, `IdentityStore`,
 * `ContactImporter`, `SessionCoordinator` y los `MessageStore`. Se declara en
 * vez de fingir un test de contenedor.
 */
class AppContainer(application: Application) {

    private val app: Application = application

    /**
     * F5 — La TRAZA de arranque con fases REALES.
     *
     * Se declara ANTES de la identidad porque [loadOrCreateIdentity] la marca al
     * cargar/crear: en Kotlin, un inicializador no puede referir una `val`
     * declarada mas abajo. Cada hito lo marca el codigo que HACE la cosa; no hay
     * fases que avancen por tiempo.
     */
    val trazaArranque = com.example.keymessage.boot.ArranqueTraza()

    /** Canales del framework que la app produce, mas el buffer de diagnostico. */
    val messageDao get() = database.messageDao()

    /** El destino del motor. Multiples endpoints, en orden. */
    val endpoints: ResolvedEndpoints = RelayEndpointConfig.resolve(
        runtimeOverride = runtimeOverride(),
        buildConfig = null,
        default = JvmRelayTransport.DEFAULT_ENDPOINT,
    )

    /**
     * R3 — El snapshot de entorno REAL del proceso, una sola vez.
     *
     * Se captura aqui y no en el composable porque el overlay se compone y se
     * recompone: `PackageManager` no cambia en caliente y capturarlo en cada
     * recomposicion seria trabajo repetido sobre los mismos datos. El endpoint
     * sale de [endpoints], que es la MISMA lista con la que se conecta.
     */
    val bootEnvironment: BootEnvironment = BootEnvironment.capture(app, endpoints)

    /**
     * D5 — El overlay de arranque se muestra UNA vez por proceso.
     *
     * `compareAndSet` y no un `var` con `if`: si dos recomposiciones (o una
     * recreacion rapida) llegan a la vez, solo una gana y el overlay no puede
     * aparecer dos veces.
     */
    private val overlayArranqueReclamado: java.util.concurrent.atomic.AtomicBoolean =
        java.util.concurrent.atomic.AtomicBoolean(false)

    /** @return `true` la PRIMERA vez en el proceso; `false` despues. */
    fun reclamarOverlayArranque(): Boolean = overlayArranqueReclamado.compareAndSet(false, true)

    /**
     * Override de runtime: `Settings.Global` con la clave del framework.
     *
     * Se lee del sistema, no de un fichero de la app, porque es lo unico que se
     * puede cambiar por ADB sin recompilar: `adb shell settings put global
     * km_relay_ws_url wss://uno,wss://dos`.
     */
    private fun runtimeOverride(): String? = runCatching {
        Settings.Global.getString(app.contentResolver, RelayEndpointConfig.SETTINGS_GLOBAL_KEY)
    }.getOrNull()

    /**
     * El almacen de identidad, expuesto.
     *
     * Lo necesita [RecoveryFlow] y lo necesita la pantalla de frase: el aviso de
     * "ya has visto tu frase" vive en el mismo `km_identity.xml` que la semilla,
     * y leerlo por otra via seria leerlo de un sitio que puede no ser el mismo.
     */
    val identityStore: IdentityStore = AndroidIdentityStore(app)

    /**
     * La identidad viva.
     *
     * 6.2 — `var` y `@Volatile`, antes `val`.
     *
     * Restaurar una identidad la REEMPLAZA, y el servicio de fondo la lee desde
     * el hilo de recepcion mientras la UI la cambia desde el principal. Con un
     * `val` la identidad nueva no existia; con un `var` sin `@Volatile` podia no
     * existir todavia para el otro hilo, que es la mitad del modo de fallo. Se
     * escribe SOLO desde [recargarIdentidad], que va detras de `parar()`.
     */
    @Volatile
    var identity: IdentityMaterial = loadOrCreateIdentity()
        private set

    private val localKeyStore: LocalKeyStore = AndroidLocalKeyStore(app)

    /**
     * Material de sesion del dispositivo LOCAL.
     *
     * Vive aparte de [identity] porque es otro nivel: `identityId` enruta a la
     * persona y `deviceId` + claves de acuerdo abren sesiones. Sin esto la app
     * podria IMPORTAR contactos pero no hablar con ninguno, que es el modo de
     * fallo mas caro que puede tener una pantalla de "contactos".
     *
     * 6.2 — `@Volatile` por la misma razon que [identity]: restaurar regenera
     * estas claves y las lee el motor desde otro hilo.
     */
    @Volatile
    var localKeys: LocalKeyMaterial = loadOrCreateLocalKeys()
        private set

    private val database: KeyMessageDatabase = Room.databaseBuilder(
        app,
        KeyMessageDatabase::class.java,
        "keymessage.db",
    ).build()

    /** IO: las tres interfaces del framework sobre Room. */
    val messageStore = RoomMessageStore(database.messageDao())
    val offlineQueue = RoomOfflineQueue(database.offlineQueueDao())
    val duplicateStore = RoomDuplicateStore(database.duplicateDao())

    /**
     * F2 — El ULTIMO mensaje por par, para la fila de contactos.
     *
     * Es UNA consulta agregada para toda la lista (`observeUltimos`), no una por
     * fila. El repositorio solo necesita el DAO y la identidad local; no depende
     * del nucleo, porque el ultimo mensaje sale de Room aunque no haya conexion.
     */
    val resumenes = ConversacionResumenRepository(database.messageDao(), identity.identityId)

    val contactBook: ContactBook = InMemoryContactBook()

    /**
     * R4 / D4 — CONTACTOS se completa con la CARGA LOCAL, no con el relay.
     *
     * ## POR QUE AQUI Y NO EN `connectWith`
     *
     * Porque el libro es EN MEMORIA y su carga termina cuando el contenedor
     * esta construido: no hay red que esperar. Antes la fase se completaba
     * dentro de `connectWith`, de modo que con el relay offline CONTACTOS se
     * quedaba PENDIENTE para siempre aunque la lista ya estuviera disponible
     * (spec R4: las fases locales avanzan y CONTACTOS no espera al relay).
     *
     * `0` es una MEDICION real —el libro arranca vacio en frio— y por eso la
     * nota dice el conteo y que no esta persistido. No se infla ni se espera un
     * tiempo artificial.
     */
    init {
        trazaArranque.completar(
            FaseArranque.CONTACTOS,
            nota = "${contactBook.contacts.value.size} en memoria (no persistido)",
        )
    }

    val sessionCoordinator = SessionCoordinator()

    /**
     * El registro de contactos que llegan por `SIGNAL/contact_exchange`.
     *
     * Pasa SIEMPRE por [com.example.keymessage.contact.ContactImporter], que es
     * el punto unico de verificacion: el bundle que llega por la senal se
     * verifica EXACTAMENTE como el que llega por un QR escaneado a mano.
     */
    private val registroEntrante = InboundContactRegistrar(contactBook) { identity.identityId }

    /**
     * La retencion ACOTADA de los PREKEYs de pares que todavia no son contacto.
     *
     * Existe por la carrera de KM-0006 §7.3: el frame PREKEY no transporta el
     * bundle del emisor, asi que si el PREKEY llega antes que el
     * `contact_exchange` hay que guardarlo un rato. TTL de 60 s y tope de 16,
     * con reloj inyectado.
     */
    private val coordinadorIntercambio = ContactExchangeCoordinator(
        conocido = { emisor -> sesionInstalada(emisor) || contactBook.find(emisor) != null },
    )

    private val coreFactory = CoreFactory()

    private val _state = MutableStateFlow(ConnectionState.DISCONNECTED)
    val state: StateFlow<ConnectionState> = _state

    private val _lastError = MutableStateFlow<String?>(null)
    val lastError: StateFlow<String?> = _lastError

    /**
     * El aviso de fallo del INTERCAMBIO de contactos, con su codigo estable.
     *
     * ## POR QUE ESTA SEPARADO DE [lastError]
     *
     * Porque [lastError] es el estado de la CONEXION ("sin conexion a ningun
     * rele") y este es el estado del INTERCAMBIO ("el rele no acepto tu bundle
     * porque el par no estaba"). Son dos hechos distintos y el usuario los
     * entiende distinto: uno se arregla reiniciando, el otro esperando a que
     * el otro dispositivo se conecte.
     *
     * Y R5 dice que el emisor NO puede dar el alta por hecha: si el
     * intercambio no se entrega, el PREKEY no sale y no hay sesion. Eso se
     * DICE, con un codigo estable, en vez de fallar en silencio.
     */
    private val _avisoIntercambio = MutableStateFlow<String?>(null)
    val avisoIntercambio: StateFlow<String?> = _avisoIntercambio

    @Volatile
    private var stack: RelayClientStack? = null

    /**
     * R14 — UN SOLO NUCLEO VIVO POR IDENTIDAD.
     *
     * `core` y su bundle se publican bajo [lifecycleLock] y se leen desde la UI
     * a traves de `requireCore()`. El candado no es cosmetico: `arrancar()` es
     * single-flight, pero la recuperacion de identidad llama `parar()` y luego
     * `arrancar()` desde otro hilo, y sin candado el nucleo viejo podia
     * publicarse DESPUES del nuevo y quedarse como "el vigente" con el scheduler
     * del anterior todavia vivo.
     */
    @Volatile
    private var core: com.km.api.KeyMessageCoreImpl? = null

    @Volatile
    private var coreBundle: com.example.keymessage.core.CoreFactory.CoreBundle? = null

    /** Serializa el reemplazo y el cierre del nucleo: nunca dos a la vez. */
    private val lifecycleLock = Any()

    /**
     * Alcance del observador de red.
     *
     * Vive lo que vive el contenedor, no lo que dura una conexion: `parar()`
     * desregistra el callback pero el alcance sigue disponible para el
     * `arrancar()` de la recuperacion.
     */
    private val networkScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * El observador de cambios de red (R1/R3/S1).
     *
     * ## POR QUE `arrancar` ES UNA ACCION DISTINTA DE `redial`
     *
     * Si el proceso arranco sin red, cuando la red vuelve no hay pila que
     * reconectar: hay que levantar una. La politica pura
     * ([com.example.keymessage.net.RedialPolicy]) distingue los dos casos y este
     * adaptador solo ejecuta la decision.
     *
     * ## POR QUE EL ESTADO NO SE TOCA AQUI
     *
     * El espejo de `onStateChange` de [connectWith] es la UNICA fuente de
     * `_state`. Si este bloque tambien escribiera `_state`, dos escritores
     * podrian contradecirse y la UI volveria a mostrar el estado que no es.
     */
    private val networkWatch = NetworkWatch(
        monitor = AndroidNetworkMonitor(app),
        scope = networkScope,
        pilaPresente = { stack != null },
        redial = {
            val pila = stack
            if (pila != null) {
                pila.transport.forceRedial().onFailure { e ->
                    KMLog.event("[RELAY]", "redial no aplicado: ${e.message}", KMLog.Level.WARN)
                }
            } else {
                // La pila desaparecio entre la decision y la accion: tratar el
                // evento como arranque es mejor que un redial sobre nada.
                arrancar()
            }
        },
        arrancar = { arrancar() },
    )

    /**
     * El candado del arranque.
     *
     * ## POR QUE NO BASTA CON `if (stack != null) return true`
     *
     * Porque ese `if` NO es atomico. [KeyMessageApp] arranca el
     * `KeyMessageService`, y su `onCreate` llama a [arrancar]; la `MainActivity`
     * tambien lo llamaba. Los dos hilos leian `stack == null` a la vez, los dos
     * pasaban el guard, y los dos construian un `JvmRelayTransport` con la MISMA
     * identidad. El rele acepta UN challenge por identidad: la segunda conexion
     * fallaba con `AUTH_FAILED INVALID_SIGNATURE` y el perdedor pisaba
     * `_state`/`_lastError` con DISCONNECTED aunque el ganador tuviera un socket
     * ONLINE. El resultado no era "una conexion mala", era una conexion buena con
     * el estado de la app diciendo que no habia ninguna.
     *
     * `ArranqueUnico` centraliza la regla que tiene que ser una sola: quien
     * intenta conectar, y que el intento ocurra UNA vez aunque lo pidan dos
     * hilos a la vez.
     */
    private val arranque = ArranqueUnico()

    /** El nucleo vivo, o `null` mientras no haya conexion. */
    fun requireCore(): com.km.api.KeyMessageCoreImpl = requireNotNull(core) {
        "el nucleo no existe todavia: connect() tiene que haber tenido exito"
    }

    /** El stack vivo, o `null` mientras no haya conexion. */
    fun requireStack(): RelayClientStack = requireNotNull(stack) {
        "la pila de rele no existe todavia: connect() tiene que haber tenido exito"
    }

    /**
     * Conecta con el primer endpoint que responda, y construye la pila con el.
     *
     * ## ES IDEMPOTENTE Y ATOMICO
     *
     * Todo el intento vive dentro de [ArranqueUnico.unaVez]: el segundo llamante
     * NO empieza un segundo intento, espera a que termine el primero y recibe su
     * resultado. Es lo que impide los dos `JvmRelayTransport` con la misma
     * identidad y el `AUTH_FAILED INVALID_SIGNATURE` no determinista que salia de
     * la carrera entre el `KeyMessageService` y la `MainActivity`.
     *
     * Bloquear aqui es seguro porque los DOS llamantes invocan desde
     * `Dispatchers.IO`, nunca desde el hilo principal; el unico efecto de la
     * espera es que el segundo no duplica la conexion.
     *
     * @return `true` si hubo conexion. `false` deja [lastError] con el motivo,
     *   que ya sera `BOOTSTRAP_ALL_FAILED` cuando se agotaron los endpoints.
     */
    fun arrancar(): Boolean = try {
        arranque.unaVez {
            if (stack != null) return@unaVez true
            KMLog.install(AndroidLogSink())
            // F5 — CONFIG: resolver endpoints es el primer hito REAL. Empieza
            // antes de la secuencia y acaba cuando los endpoints estan resueltos.
            trazaArranque.iniciar(FaseArranque.CONFIG)
            KMLog.event("[CONFIG]", "endpoints=${endpoints.endpoints} origen=${endpoints.source}")
            trazaArranque.completar(FaseArranque.CONFIG, nota = "origen=${endpoints.source}")
            val secuencia = EndpointSequence(endpoints.endpoints)
            try {
                secuencia.connect { endpoint -> connectWith(endpoint) }
                true
            } catch (e: RelayEndpointsExhausted) {
                // F5 — RELAY fallo con el codigo real (los endpoints probados).
                trazaArranque.fallar(FaseArranque.RELAY, "sin conexion a ningun rele: ${e.tried}")
                publicarFallo("sin conexion a ningun rele: ${e.tried}")
                false
            }
        }
    } finally {
        // La vigilancia arranca TAMBIEN cuando el intento falla: un proceso que
        // arranco sin red tiene que enterarse de que la red volvio para
        // REARRANCAR (S11). Es idempotente, asi que las llamadas repetidas de
        // la recuperacion no registran dos callbacks.
        networkWatch.iniciar()
    }

    /**
     * Publica el fallo de un intento, PERO NUNCA pisa una conexion ONLINE.
     *
     * Es la segunda mitad del arreglo de la carrera: aunque el guard ya evita el
     * segundo intento, un fallo tardio (un endpoint que se cae mientras otro hilo
     * ya conecto) tampoco puede escribir DISCONNECTED sobre un socket vivo. El
     * usuario veria "sin conexion" con la app recibiendo mensajes, que es una
     * afirmacion que la pantalla no puede sostener.
     */
    private fun publicarFallo(motivo: String?) {
        if (stack != null) return
        _lastError.value = motivo
        _state.value = ConnectionState.DISCONNECTED
    }

    /**
     * Abre sesion E2E con [contacto] y deja el frame PREKEY listo para entregar.
     *
     * La app NO puede hablar sin esto: sin sesion, `sendMessage` se rechaza y el
     * usuario ve "NO enviado" en todo. El frame que sale hay que entregarlo al
     * otro dispositivo por el medio que corresponda; el establishment no decide
     * como.
     *
     * ## POR QUE NO SE DECODIFICA AQUI
     *
     * El `bundleText` de un [VerifiedContact] es el PORTADOR tal cual lo produjo
     * [com.example.keymessage.contact.SelfBundleExporter] —comprimido—, no
     * base64url puro. Llamar a `ContactBundle.decodeAndVerify` con el era leer
     * otro formato: rechazaba el portador con `ENVELOPE_MALFORMED: InvalidChar`
     * (obs #308) y dejaba al emisor sin sesion, o sea todo envio en
     * `AUTH_FAILED`. Aqui se pide a
     * [com.example.keymessage.contact.ContactImporter.verifiedBundle], que es el
     * UNICO sitio que sabe como se lee este portador.
     */
    fun abrirSesion(contacto: VerifiedContact): Result<InitiatedSession> = runCatching {
        val bundle = ContactImporter.verifiedBundle(contacto)
        val contexto = sessionCoordinator.contextoHacia(bundle)
        val pila = requireStack()
        sessionCoordinator.iniciar(
            localIdentity = identity.identityId,
            peerContext = contexto,
            localKeys = localKeys.initiatorKeys(),
            manager = pila.manager,
            registry = requireCoreBundle().registry,
            // El indice OPK se DERIVA del bundle, no se deja al default `0` de
            // `iniciar`: un QR exporta con 0 OPK, y pedir el 0 sobre un bundle
            // vacio hace que `BootstrapPrekeys.fromVerified` LANCE y no haya
            // sesion E2E posible (`AUTH_FAILED` en todo envio).
            oneTimePrekeyIndex = OpkSelection.paraIniciar(bundle),
        )
    }.onFailure {
        KMLog.event(
            "[SESSION]",
            "no se pudo abrir sesion codigo=${rejectionCodeOf(it)}: ${it.message}",
            KMLog.Level.ERROR,
        )
    }

    /**
     * Abre sesion E2E con [contacto] Y ENTREGA el frame PREKEY al peer.
     *
     * ## POR QUE ESTE METODO Y NO [abrirSesion] A SECAS
     *
     * Porque [abrirSesion] deja el frame PREKEY "listo para entregar" y no lo
     * entrega: produce el `InitiatedSession` con su `prekeyFrame`, pero nadie lo
     * pone en el cable. Ese hueco es el CRITICAL-1 verificado por ADB: la app
     * importaba contactos, abria (si acaso) la sesion, y el receptor nunca
     * recibia el PREKEY, asi que no habia sesion en NINGUN lado y todo envio se
     * rechazaba con `AUTH_FAILED`.
     *
     * ## COMO VIAJA EL PREKEY
     *
     * El frame PREKEY es un `SecureFrame` mas y sale por el MISMO camino de par
     * que los mensajes: `session.sendTranscript(prekeyFrame)` lo entrega al
     * `PeerTransportManager`, que lo manda por el canal de par. `sendTranscript`
     * y no `send` porque el frame ya esta CIFRADO —lo protegio `initiate` con
     * `K_prekey`—: volver a cifrarlo produciria un frame distinto que el
     * receptor no abriria con la clave de establecimiento.
     *
     * Es el mismo camino que los tests del framework usan
     * (`SessionEstablishmentTest`, `DesktopSendCiphertextTest`): el PREKEY cruza
     * el backend, no una llamada directa. Aqui no se inventa una via.
     *
     * ## QUE SIGNIFICA UN FALLO AQUI
     *
     * Un fallo del `Result` es "no se pudo abrir la sesion" (sin stack, sin
     * bundle). Un `SendResult` distinto de `Ok` es "la sesion se abrio pero el
     * PREKEY NO llego al peer": el peer no puede aceptar lo que no recibio, asi
     * que se reporta como fallo, no como exito parcial silencioso.
     *
     * ## EL INTERCAMBIO PRECEDE A LA SESION (R4)
     *
     * El frame PREKEY no transporta el bundle del emisor (su cabecera es fija),
     * de modo que el receptor NO puede aceptar el PREKEY de un par que no
     * conoce. Por eso, si no hay sesion todavia, este metodo publica PRIMERO el
     * portador propio por `SIGNAL/contact_exchange` y solo despues abre la
     * sesion y entrega el PREKEY.
     *
     * ## ARRANQUE NORMAL: CON SESION NO SE TOCA
     *
     * Reiniciar el establecimiento sin motivo crearia una sesion NUEVA en ESTE
     * lado mientras el otro conserva la anterior, y los dos ratchets divergen.
     * El caso del usuario que RE-ESCANEA es distinto y tiene su propio metodo
     * ([reanunciarYEntregar]): ahi el re-keying ES la intencion.
     *
     * @return `Result.success(Unit)` solo si la sesion se abrio Y el frame salio.
     */
    suspend fun abrirSesionYEntregar(contacto: VerifiedContact): Result<Unit> =
        entregar(contacto, explicito = false)

    /**
     * Un import/escaneo EXPLICITO: SIEMPRE re-anuncia y re-negocia.
     *
     * ## POR QUE NO REUTILIZA LA SESION QUE HAYA
     *
     * Porque la sesion local es un hecho de ESTE proceso, no una prueba de que
     * el otro la conserve. El fallo reproducido fue exactamente ese: el
     * escritorio reinicio a las 00:52:56 y perdio contactos y sesiones (eran
     * solo memoria), mientras la app seguia con una sesion de un proceso
     * anterior. Con el `return` temprano por `sesionInstalada()`, la app no
     * publicaba su bundle, no mandaba PREKEY y el mensaje salia con un ratchet
     * que el escritorio descartaba sin log ni ACK.
     *
     * Re-importar el par es la senal de que el usuario quiere re-negociar: se
     * publica el bundle (idempotente en el receptor) y se abre una sesion nueva,
     * cuyo PREKEY reemplaza la anterior en cuanto el receptor lo acepta.
     *
     * ## QUE PASA CON LO QUE ESTABA EN COLA
     *
     * Nada nuevo: la sesion se instala por el camino de siempre
     * (`SecureSessionRegistry.install`, que sustituye) y el gancho de
     * instalacion despierta el canal de par, que es el reintento que ya existe.
     * No se inventa un segundo resend.
     */
    suspend fun reanunciarYEntregar(contacto: VerifiedContact): Result<Unit> =
        entregar(contacto, explicito = true)

    private suspend fun entregar(contacto: VerifiedContact, explicito: Boolean): Result<Unit> {
        // La pila y la identidad se CAPTURAN al empezar: un reenvio tras
        // re-auth tiene que ocurrir bajo la MISMA pila y la MISMA identidad. Si
        // la recuperacion de identidad las cambio mientras se esperaba, un
        // reenvio "por el mismo camino" seria otro AUTH y otra sesion.
        val pila = stack
        val identidad = identity.identityId
        val entrega = EntregaDePar(
            sesionInstalada = ::sesionInstalada,
            exportarPortador = ::portadorPropio,
            enviarPortador = ::enviarPortador,
            abrirSesionYEntregar = { abrirSesionYEntregarSinIntercambio(contacto) },
            esperarReauth = { timeoutMs -> esperarReauthDe(pila, identidad, timeoutMs) },
        )
        return try {
            entrega.ejecutar(contacto.identityRoot, explicito).getOrThrow()
            if (explicito) {
                KMLog.event(
                    "[SESSION]",
                    "re-import explicito: bundle y PREKEY reenviados a peer=" +
                        KMLog.truncar(contacto.identityRoot.value, 16),
                )
            }
            _avisoIntercambio.value = null
            Result.success(Unit)
        } catch (cancelada: CancellationException) {
            // La cancelacion NO es un fallo del intercambio: si se convirtiera
            // en `Result.failure`, el banner diria CONTACT_EXCHANGE_FAILED por
            // un trabajo que el usuario (o el ciclo de vida) cancelo. Y ademas
            // rompe la regla de corrutinas: se propaga tal cual.
            throw cancelada
        } catch (fallo: Throwable) {
            val codigo = (fallo as? ContactExchangeException)?.code
                ?: SignalDelivery.CODE_FAILED
            _avisoIntercambio.value = codigo
            KMLog.event(
                "[SIGNAL]",
                "intercambio de contactos NO entregado codigo=$codigo: ${fallo.message}",
                KMLog.Level.ERROR,
            )
            Result.failure(fallo)
        }
    }

    /**
     * La espera de re-auth que [ContactBootstrap] usa antes de su UNICO reenvio.
     *
     * ## GUARDAS, Y POR QUE CADA UNA
     *
     *  - **Sin pila**: no hay motor que esperar. `false` y el bootstrap cierra
     *    con el codigo estable, que es la verdad.
     *  - **Otra pila**: `arrancar()` reconstruye la pila en un failover. Esperar
     *    al motor VIEJO y reenviar por el `enviarPortador` nuevo mezclaria dos
     *    sesiones; el guard lo corta.
     *  - **Otra identidad**: recuperar identidad reemplaza las claves. Un
     *    reenvio con el portador exportado por la identidad anterior es un
     *    bundle que ya no corresponde a la sesion: no se hace.
     *  - **El transporte conserva la identidad capturada**: la comprobacion es
     *    redundante con la de arriba en el caso normal, y es justo la que
     *    detecta una pila reemplazada en carrera.
     *
     * ## QUE NO HACE
     *
     * No fuerza redial ni reconecta: el motor ya esta renegociando por su
     * backoff. Solo espera y dice si hay sesion util AHORA, con la misma
     * identidad con la que empezo el intercambio.
     */
    private suspend fun esperarReauthDe(
        pilaEsperada: RelayClientStack?,
        identidadEsperada: IdentityId,
        timeoutMs: Long,
    ): Boolean {
        val pila = pilaEsperada ?: return false
        if (stack !== pila) return false
        if (identity.identityId != identidadEsperada) return false
        if (pila.transport.relayIdentityId() != identidadEsperada) return false
        if (!pila.transport.awaitOnline(timeoutMs)) return false
        // La espera cruza tiempo real: se vuelve a mirar lo mismo antes de
        // dejar que el bootstrap reenvie.
        return stack === pila && identity.identityId == identidadEsperada
    }

    /**
     * Abrir la sesion y entregar el PREKEY, SIN publicar el portador.
     *
     * Es el segundo paso de [abrirSesionYEntregar], y esta separado para que
     * [ContactBootstrap] pueda invocarlo como el "paso 2" de la secuencia: el
     * orden (publicar el bundle, LUEGO abrir la sesion) es la garantia, y por
     * eso vive en una clase que se puede probar, no repartido en un `if`.
     */
    private fun abrirSesionYEntregarSinIntercambio(contacto: VerifiedContact): Result<Unit> = runCatching {
        val iniciada = abrirSesion(contacto).getOrThrow()
        val entrega = iniciada.session.sendTranscript(iniciada.prekeyFrame)
        if (entrega !is com.km.messaging.SendResult.Ok) {
            throw IllegalStateException("el frame PREKEY no salio por el canal de par: $entrega")
        }
        KMLog.event(
            "[SESSION]",
            "PREKEY abierto y entregado peer=${KMLog.truncar(contacto.identityRoot.value, 16)} " +
                "opk=${iniciada.usedOneTimePreKeyId ?: "ninguno"} bytes=${iniciada.prekeyFrame.size}",
        )
    }.onFailure {
        KMLog.event("[SESSION]", "fallo abriendo/entregando PREKEY: ${it.message}", KMLog.Level.ERROR)
    }

    /**
     * El portador PROPIO, en los bytes que viajan.
     *
     * Son los bytes ISO-8859-1 del texto COMPRIMIDO —los MISMOS que van en el
     * QR—, porque el receptor los relee con la MISMA regla
     * (`String(bytes, ISO_8859_1)`). Exportar con otro formato seria un
     * segundo portador, y dos portadores se divergen.
     */
    private fun portadorPropio(): ByteArray {
        val exportado = SelfBundleExporter.export(
            identity = identity,
            localKeys = localKeys,
            maxOneTimePreKeys = SelfBundleExporter.QR_ONE_TIME_PREKEYS,
        )
        return exportado.text.toByteArray(Charsets.ISO_8859_1)
    }

    /**
     * Envia el portador al par por `SIGNAL/contact_exchange`, con reintentos.
     *
     * El sink es el motor de rele de verdad: la ventana de veredicto son los
     * 400 ms que espera el `RELAY_ERROR` del rele, que es la unica respuesta
     * que existe para una senal (el rele no encola senales).
     */
    private fun enviarPortador(destino: IdentityId, portador: ByteArray): SignalOutcome {
        val pila = requireStack()
        val delivery = SignalDelivery(RelaySignalSink(pila.transport, destino))
        return delivery.deliver(portador)
    }

    /** Si ya hay una sesion E2E instalada con ese par. */
    private fun sesionInstalada(emisor: IdentityId): Boolean =
        coreBundle?.registry?.find(emisor) != null

    /**
     * Acepta el frame PREKEY que llego de un contacto.
     *
     * El `consumedOneTimePreKeyId` que devuelve el framework lo dice y no lo
     * hace: el llamante tiene que marcar ese OPK como gastado en SU almacen, y
     * un OPK que se vuelve a ofrecer es material reutilizado.
     *
     * ## EL OPK QUE SE USA AL RESPONDER
     *
     * Es el que el EMISOR consumio, no uno cualquiera del almacen local. El
     * frame PREKEY no lleva el `keyId` del OPK, de modo que la unica forma de
     * saberlo es el BUNDLE del emisor —que el receptor tiene, y que para eso
     * esta verificado—. Y tiene que ser el MISMO: si el receptor deriva `DH4`
     * y el emisor no (o al reves), el `SK` sale distinto y el frame no abre
     * con `fallo la autenticacion ChaCha20-Poly1305`.
     *
     * El caso de la app es el DEL QR: el bundle va con 0 OPK, asi que el emisor
     * inicia SIN `DH4` y el receptor tiene que responder SIN `DH4` tambien.
     * Elegir aqui un OPK local "porque hay" era justamente lo que rompia el
     * establecimiento.
     */
    fun aceptarSesion(
        emisor: PeerContext,
        prekeyFrame: ByteArray,
    ): Result<AcceptedSession> = runCatching {
        val pila = requireStack()
        val opkId = emisor.contactBundle?.oneTimePrekeyIdAt(OPK_INDICE_INICIAL)
        sessionCoordinator.aceptar(
            localIdentity = identity.identityId,
            peerContext = emisor,
            localKeys = localKeys.responderKeys(opkId),
            prekeyFrameWire = prekeyFrame,
            manager = pila.manager,
            registry = requireCoreBundle().registry,
        )
    }.onFailure {
        KMLog.event("[SESSION]", "no se pudo aceptar sesion: ${it.message}", KMLog.Level.ERROR)
    }

    /**
     * El CAMINO DE ENTRADA de un frame PREKEY: de bytes opacos a sesion.
     *
     * ## POR QUE HACE FALTA ESTO Y NO BASTA CON [aceptarSesion]
     *
     * [aceptarSesion] exige un `PeerContext` y un frame YA identificado. Pero
     * quien recibe los bytes del canal de par recibe BYTES OPACOS: no sabe si el
     * frame es un PREKEY de establecimiento o un mensaje de una sesion que ya
     * existe. Sin este puente, un PREKEY entrante se queda como bytes que nadie
     * reclama —`SecureInbound` lo cuenta como `unknownPeerFrames`, que es cierto:
     * no hay sesion— y la sesion contraria NUNCA se abre. Es la mitad receptora
     * del CRITICAL-1.
     *
     * ## QUE HACE, PASO A PASO
     *
     *  1. Clasifica el byte con [PrekeyFrame.esPrekey]. Si no es un PREKEY
     *     —un mensaje de sesion, un fragmento que llego partido, una version
     *     desconocida— NO se toca: ese byte es de otro camino.
     *  2. Si el emisor YA es un contacto (o ya hay sesion con el), el frame se
     *     acepta por el camino normal.
     *  3. Si NO es un contacto, el frame se RETIENE un rato: el emisor todavia
     *     no ha publicado su bundle, y el `contact_exchange` puede estar de
     *     camino. Cuando llegue, el frame retenido se entrega al mismo camino de
     *     aceptacion.
     *
     * ## POR QUE EL PASO 3 NO ES "DESCARTAR"
     *
     * Porque el frame PREKEY no transporta el bundle del emisor (su cabecera es
     * fija de 44 B) y `km-core` exige el bundle verificado del emisor para
     * aceptar un PREKEY (KM-0006 §7.3). Tirarlo equivalia a que el otro
     * establishing se quedara colgado, y a que el unico camino de un SOLO
     * escaneo no existiera. Retenerlo, en cambio, es esperar el bundle que ya
     * esta en camino.
     *
     * ## POR QUE NO LANZA
     *
     * Esto corre en el hilo de recepcion del motor. Un PREKEY que no verifica no
     * puede tumbar la conexion: se registra y se sigue. Es la misma disciplina
     * que el resto del camino entrante, donde un rechazo es un resultado, no una
     * excepcion.
     *
     * ## POR QUE EL SOBRE SE RELEE DENTRO DE UN `try`
     *
     * Porque el portador guardado del contacto puede no verificar —un almacen
     * corrupto, un bundle revocado— y esa lectura ocurre ANTES de llamar a
     * [aceptarSesion], de modo que su `runCatching` no la cubre: sin este `try`,
     * la excepcion subia por el hilo de recepcion (obs #308). Un rechazo se
     * reporta con [rejectionCodeOf] y el frame se descarta; la recepcion sigue.
     */
    fun aceptarPrekeyEntrante(emisor: IdentityId, wire: ByteArray) {
        if (!PrekeyFrame.esPrekey(wire)) return
        coordinadorIntercambio.onPrekey(emisor, wire) { emisorReal, frame ->
            aceptarPrekeyAhora(emisorReal, frame)
        }
    }

    /**
     * Aceptar un PREKEY de un emisor que YA es contacto.
     *
     * Es el camino de siempre, y esta separado de [aceptarPrekeyEntrante] para
     * que el DRENAJE del frame retenido y la recepcion normal sean el MISMO
     * codigo: si fueran dos, el retenido se aceptaria por un camino distinto
     * del que se acaba de probar.
     */
    private fun aceptarPrekeyAhora(emisor: IdentityId, wire: ByteArray) {
        val contacto = contactBook.find(emisor)
        if (contacto == null) {
            KMLog.event(
                "[SESSION]",
                "PREKEY de un contacto desconocido peer=${KMLog.truncar(emisor.value, 16)}: no se acepta",
                KMLog.Level.WARN,
            )
            return
        }
        if (sesionInstalada(emisor)) {
            KMLog.event(
                "[SESSION]",
                "PREKEY de peer=${KMLog.truncar(emisor.value, 16)} con sesion ya instalada: reenvio, no se reacepta",
            )
            return
        }
        val bundle = try {
            ContactImporter.verifiedBundle(contacto)
        } catch (e: Exception) {
            KMLog.event(
                "[SESSION]",
                "PREKEY descartado: el sobre guardado de peer=${KMLog.truncar(emisor.value, 16)} " +
                    "no se puede releer codigo=${rejectionCodeOf(e)}",
                KMLog.Level.WARN,
            )
            return
        }
        val resultado = aceptarSesion(
            emisor = sessionCoordinator.contextoHacia(bundle),
            prekeyFrame = wire,
        )
        resultado.onSuccess {
            KMLog.event(
                "[SESSION]",
                "PREKEY aceptado peer=${KMLog.truncar(emisor.value, 16)} " +
                    "opk=${it.consumedOneTimePreKeyId ?: "ninguno"}",
            )
        }
    }

    /**
     * Un portador de contacto que llega por `SIGNAL/contact_exchange`.
     *
     * ## EL HANDLER NO DECODIFICA
     *
     * Solo convierte los bytes en el TEXTO del portador —`String(bytes,
     * ISO_8859_1)`, que es la regla con la que se escribio— y se lo pasa a
     * [InboundContactRegistrar], que lo verifica por el PUNTO UNICO
     * ([ContactImporter]). La app no hace criptografia: la hace el framework.
     *
     * ## Y SI LA RAIZ NO ES LA DEL EMISOR, NO HAY CONTACTO
     *
     * El `from` lo autentica el rele contra la sesion, y el bundle lo firma su
     * raiz. Si no coinciden, el bundle es de otro: se rechaza con
     * `BUNDLE_ROOT_MISMATCH` y no se crea ningun contacto.
     */
    private fun recibirPortadorDeContacto(emisor: IdentityId, bytes: ByteArray) {
        try {
            val portador = String(bytes, Charsets.ISO_8859_1)
            when (val resultado = registroEntrante.register(emisor, portador)) {
                is RegistrationResult.Accepted -> {
                    KMLog.event(
                        "[CONTACT]",
                        "contacto recibido por contact_exchange root=" +
                            KMLog.truncar(resultado.contact.identityRoot.value, 16),
                    )
                    drenarRetenidos(resultado.contact.identityRoot)
                }
                is RegistrationResult.Rejected -> {
                    KMLog.event(
                        "[CONTACT]",
                        "contact_exchange rechazado codigo=${resultado.code} emisor=" +
                            KMLog.truncar(emisor.value, 16),
                        KMLog.Level.WARN,
                    )
                }
            }
        } catch (e: Exception) {
            // La recepcion NO se tumba: un bundle raro es un rechazo, no un
            // fallo del motor.
            KMLog.event(
                "[CONTACT]",
                "contact_exchange fallo codigo=${rejectionCodeOf(e)}",
                KMLog.Level.WARN,
            )
        }
    }

    /**
     * Entrega lo que se hubiera retenido del par que acaba de registrarse.
     *
     * Es el otro final de la retencion: el `contact_exchange` llego, el par ya
     * es contacto, y el PREKEY que se habia guardado se entrega al camino de
     * aceptacion de siempre.
     */
    private fun drenarRetenidos(emisor: IdentityId) {
        val entregados = coordinadorIntercambio.onContactoRegistrado(emisor) { emisorReal, frame ->
            aceptarPrekeyAhora(emisorReal, frame)
        }
        if (entregados > 0) {
            KMLog.event(
                "[SESSION]",
                "PREKEY retenido entregado tras registrar el contacto peer=" +
                    KMLog.truncar(emisor.value, 16),
            )
        }
    }

    /**
     * Registra un contacto IMPORTADO A MANO y drena lo que hubiera retenido.
     *
     * El import manual es el otro camino por el que un par pasa a ser
     * contacto, y lo retenido tiene que drainearse igual: si no, un PREKEY que
     * llego antes del import se quedaria guardado hasta que expirara.
     */
    fun registrarContactoImportado(contacto: VerifiedContact) {
        contactBook.upsert(contacto)
        drenarRetenidos(contacto.identityRoot)
    }

    /** Intenta UN endpoint. Falla con un `Result`, que es lo que espera la secuencia. */
    private fun connectWith(endpoint: String): Result<RelayClientStack> = runCatching {
        val candidate = RelayStackFactory.stackFor(identity, endpoint)
        // F5 — RELAY empieza ANTES de conectar: la fase cubre el intento de
        // enlace (DNS, socket, handshake), no solo su desenlace.
        trazaArranque.iniciar(FaseArranque.RELAY, nota = "endpoint=${KMLog.truncar(endpoint)}")
        // El espejo de estado se registra ANTES de conectar y se guarda con la
        // identidad de la pila: un candidato perdedor no puede escribir el
        // estado del ganador ni el de la pila que ya se solto.
        candidate.transport.onStateChange { estado ->
            if (stack === candidate) _state.value = estado
        }
        val conectado = candidate.transport.connect()
        if (conectado.isFailure) {
            candidate.close()
            throw conectado.exceptionOrNull() ?: IllegalStateException("fallo de conexion sin causa")
        }
        candidate
    }.onSuccess { pila ->
        // R14 — EL REEMPLAZO ES ATOMICO Y ORDENADO: close(viejo) -> build(nuevo)
        // -> swap -> hooks. Todo dentro del mismo candado para que la UI nunca
        // vea un nucleo a medias ni dos emisores compitiendo por la misma
        // identidad. `close()` cancela la suscripcion del canal de par, apaga el
        // scheduler anterior y convierte lo que estaba en vuelo en tombstone; el
        // sucesor rehidrata desde el `OfflineQueue` los que esperaban turno.
        synchronized(lifecycleLock) {
            core?.let { anterior ->
                KMLog.event("[CONFIG]", "cerrando nucleo anterior antes de reemplazarlo")
                anterior.close()
            }
            core = null
            coreBundle = null
            stack = pila
            _state.value = ConnectionState.ONLINE
            _lastError.value = null
            // F5 — RELAY: el socket acepto. Se completa aqui (antes se inicio).
            trazaArranque.completar(FaseArranque.RELAY, nota = "endpoint=${KMLog.truncar(endpoint)}")
            KMLog.event("[RELAY]", "en linea como ${KMLog.truncar(pila.identity.value)} en $endpoint")
            // F5 — NUCLEO: mide crear + arrancar el nucleo, que es lo que de
            // verdad cuesta y lo que puede fallar. Empieza ahora y acaba tras
            // `core.start()`.
            trazaArranque.iniciar(FaseArranque.NUCLEO)
            val nucleo = coreFactory.create(
                localIdentity = identity.identityId,
                localPrivateKey = identity.keyPair.privateKey,
                transport = pila.transport,
                manager = pila.manager,
                messageStore = messageStore,
                offlineQueue = offlineQueue,
                duplicateStore = duplicateStore,
                contactBook = contactBook,
            )
            pila.attachLegacy()
            nucleo.core.start()
            core = nucleo.core
            coreBundle = nucleo
            trazaArranque.completar(FaseArranque.NUCLEO)
            // F5 — AUTH: el unico estado que la app considera "autenticado" es
            // ONLINE tras el arranque del nucleo. Se marca DESPUES de RELAY y
            // NUCLEO, porque el handshake va con la pila ya aceptada.
            trazaArranque.iniciar(FaseArranque.AUTH)
            trazaArranque.completar(FaseArranque.AUTH, nota = "ONLINE")
            // CRITICAL-1 (entrada): el canal de par entrega bytes OPACOS. Aqui es
            // donde un frame PREKEY entrante deja de ser bytes sin dueno y se
            // convierte en sesion: sin esta suscripcion, la mitad receptora del
            // establecimiento no existe y ningun PREKEY se acepta nunca.
            pila.manager.onData { from, wire -> aceptarPrekeyEntrante(from, wire) }
            // La senalizacion: el portador del EMISOR llega por aqui. Sin esta
            // suscripcion, el receptor no sabe quien es el emisor y el PREKEY se
            // queda retenido hasta que expire (o para siempre, si no hay TTL).
            pila.onContactBundle { from, bytes -> recibirPortadorDeContacto(from, bytes) }
            // El canal de par: sin el conductor, ningun binding llega a CONNECTED y
            // TODO envio se rechaza (tambien el frame PREKEY, que es lo que abre la
            // sesion).
            instalarGanchoDeCanalDePar(pila)
            // R4 / D4 — CONTACTOS NO se completa aqui: se completo en la
            // construccion del contenedor con la carga LOCAL. Completarlo tras
            // conectar era lo que dejaba la fase pendiente con el relay offline.
        }
    }.recoverCatching { failure ->
        // Un fallo de ESTE candidato no puede tumbar el estado global si otro
        // intento (o un endpoint anterior) ya dejo un socket ONLINE: ese socket
        // sigue vivo y anunciar DISCONNECTED seria mentir sobre el.
        publicarFallo(failure.message)
        throw failure
    }

    /** El nucleo con sus piezas de sesion, o excepcion mientras no haya conexion. */
    private fun requireCoreBundle(): com.example.keymessage.core.CoreFactory.CoreBundle = requireNotNull(coreBundle) {
        "el nucleo no existe todavia: connect() tiene que haber tenido exito"
    }

    /**
     * El gancho de INSTALACION: cada sesion que entra en el registro abre su
     * canal de par en el rele.
     *
     * ## POR QUE ESTO NO ES OPCIONAL
     *
     * Porque `PeerTransportManager.sendData` EXIGE un binding `CONNECTED`, y a
     * ese estado lo conduce el conductor del canal de par
     * ([com.km.transport.RelayPeerChannelDriver]). Sin el, el binding se queda
     * en `NEW` y TODO envio se rechaza con `TRANSPORT_REJECTED` — el frame
     * PREKEY incluido, que es lo que abre la sesion. El sintoma era "el
     * cripografia falla" cuando lo que fallaba era el enlace: el rele receive
     * bien, el mensaje no sale.
     *
     * El escritorio hace exactamente esto (su `registry.onInstall` llama a
     * `stack.driverFor`), y la app no lo hacia: por eso el envio nunca habia
     * salido por el canal de par.
     */
    private fun instalarGanchoDeCanalDePar(pila: RelayClientStack) {
        pila.transport.onPeerOnline { peer ->
            pila.driverOf(peer)?.onPeerOnline(peer)
        }
        pila.transport.onPeerOffline { peer ->
            pila.driverOf(peer)?.onPeerOffline(peer)
        }
        requireCoreBundle().registry.onInstall { sesion ->
            val conductor = pila.driverFor(sesion.peerContext)
            // La presencia se le PREGUNTA AL MOTOR, y no se guarda en un set
            // propio de la app: el snapshot de presencia del rele llega DURANTE
            // el `connect()`, y el suscriptor se da de alta DESPUES. Un set
            // propio se quedaria vacio justo en el caso que importa —el recien
            // llegado, al que el rele le manda el snapshot— y el binding nunca
            // llegaria a `CONNECTED`. El motor guarda ESTADO, no eventos: ahi
            // la pregunta es correcta siempre.
            val presente = pila.transport.isPeerPresent(sesion.remotePeerId)
            if (presente) {
                conductor.onPeerPresent(sesion.remotePeerId)
            }
            KMLog.event(
                "[SESSION]",
                "canal de par abierto peer=${KMLog.truncar(sesion.remotePeerId.value, 16)} " +
                    "presente=$presente",
            )
        }
    }

    /** Corta la conexion y cierra el nucleo (R14: sin scheduler vivo). */
    fun parar() {
        // R8: primero se deja de escuchar la red. Si se soltara la pila antes,
        // un evento que llegue entre las dos lineas encontraria `stack == null`
        // y pediria un arranque sobre una app que se esta parando.
        networkWatch.parar()
        // El nucleo se saca del campo DENTRO del candado y se cierra FUERA:
        // `close()` toma el suyo y serializar dos candados en distinto orden
        // invitaria a un interbloqueo. Una vez fuera del campo, nadie puede
        // volver a usarlo: `requireCore()` ya no lo ve.
        val (nucleo, pila) = synchronized(lifecycleLock) {
            val c = core
            val p = stack
            core = null
            coreBundle = null
            stack = null
            c to p
        }
        // R14: `close()` (no `stop()`) es lo que cancela la suscripcion, apaga
        // el `retryManager` y deja tombstone lo que estaba en vuelo conservando
        // la fila. Antes el scheduler del nucleo viejo seguia vivo tras parar.
        nucleo?.close()
        pila?.close()
        _state.value = ConnectionState.DISCONNECTED
        // Lo retenido se suelta con la conexion: un PREKEY guardado de una
        // sesion que ya no existe no se puede aceptar de nuevo.
        coordinadorIntercambio.parar()
        _avisoIntercambio.value = null
        // Se vuelve a habilitar el arranque: tras parar, un `arrancar()` posterior
        // tiene que poder reconectar de verdad en vez de encontrarse el candado
        // cerrado con el resultado del intento anterior.
        arranque.reiniciar()
    }

    // ------------------------------------------------------------------
    // Identidad
    // ------------------------------------------------------------------

    private fun loadOrCreateLocalKeys(): LocalKeyMaterial {
        localKeyStore.load()?.let { return it }
        val nuevo = LocalKeyFactory(identity.keyPair.publicKey).generate()
        localKeyStore.save(nuevo)
        KMLog.event("[CONFIG]", "claves de sesion de dispositivo generadas")
        return nuevo
    }

    private fun loadOrCreateIdentity(): IdentityMaterial {
        // F5 — IDENTIDAD: el hito cubre cargar o crear. No hay "unlock": la
        // identidad se carga del almacen o se genera, y ese es el evento real.
        // NUNCA se registra la semilla ni la frase; solo el id truncado.
        trazaArranque.iniciar(FaseArranque.IDENTIDAD)
        val existente = identityStore.load()
        if (existente != null) {
            trazaArranque.completar(FaseArranque.IDENTIDAD, nota = "cargada")
            return existente
        }
        val nueva = IdentityMaterialFactory().generate()
        identityStore.save(nueva)
        KMLog.event("[CONFIG]", "identidad creada ${KMLog.truncar(nueva.identityId.value)}")
        trazaArranque.completar(FaseArranque.IDENTIDAD, nota = "creada")
        return nueva
    }

    // ------------------------------------------------------------------
    // 6.2 — Recuperacion
    // ------------------------------------------------------------------

    /**
     * Relee la identidad del almacen y la publica en memoria.
     *
     * 6.2 — Se llama DESPUES de [IdentityStore.save], nunca antes: leerla antes
     * devolveria la identidad vieja y el resto de la app seguiria firmando con
     * ella.
     */
    fun recargarIdentidad(): IdentityMaterial {
        val guardada = requireNotNull(identityStore.load()) {
            "el almacen de identidad quedo vacio justo despues de guardar"
        }
        identity = guardada
        KMLog.event("[CONFIG]", "identidad recargada ${KMLog.truncar(guardada.identityId.value)}")
        return guardada
    }

    /**
     * Lo que [RecoveryFlow] necesita para cambiar de identidad de verdad.
     *
     * 6.2 — [RecoveryCiclo.regenerarClavesLocales] recarga la identidad ANTES de
     * derivar el `deviceId` de las claves nuevas, y ese es el orden entero:
     * parar, guardar, regenerar, arrancar. Si el `deviceId` saliera de la clave
     * de firma VIEJA, el rele tendria dos dispositivos con el mismo `deviceId` y
     * el handshake rechazaria el PREKEY.
     *
     * Las tres operaciones bloquean: se llaman desde `Dispatchers.IO`, nunca
     * desde el hilo de la UI, que es la misma regla que sigue `KeyMessageService`.
     *
     * ## POR QUE LAS TRES LAMBDA Dicen `this@AppContainer`
     *
     * Porque aqui se escribio el bug que mataba el proceso con
     * `StackOverflowError`: el `object : RecoveryFlow.Ciclo` de antes tenia
     * `override fun parar() { parar() }`, y ese `parar()` resolvia al override
     * —el miembro gana al receptor externo— en vez de a este [parar]. Como
     * [RecoveryFlow] llama `ciclo.parar()` en TODA restauracion, la app reventaba
     * siempre (ADB v5, obs #285). Ahora el ciclo es [RecoveryCiclo], cuyos
     * delegados tienen otros nombres, y encima el receptor se escribe entero:
     * las dos barreras, porque la segunda sola exige acordarse.
     */
    val cicloDeRecuperacion: RecoveryFlow.Ciclo = RecoveryCiclo(
        detenerNucleo = { this@AppContainer.parar() },
        levantarNucleo = { this@AppContainer.arrancar() },
        regenerarClaves = {
            recargarIdentidad()
            val nuevo = LocalKeyFactory(identity.keyPair.publicKey).generate()
            localKeyStore.save(nuevo)
            localKeys = nuevo
            KMLog.event("[CONFIG]", "claves de sesion regeneradas para la identidad nueva")
        },
    )

    /** El coordinador de recuperacion de este contenedor. */
    val recoveryFlow: RecoveryFlow = RecoveryFlow(
        store = identityStore,
        factory = IdentityMaterialFactory(),
        ciclo = cicloDeRecuperacion,
    )

    /**
     * La frase de 24 palabras de la identidad viva.
     *
     * 6.2 — Se deriva en el momento y no se guarda: el resultado vive lo que
     * vive la pantalla que la pinta.
     */
    fun fraseActual(): com.km.mnemonic.MnemonicPhrase = fraseDeRecuperacion(identity)

    companion object {
        /**
         * El indice de OPK que pide el EMISOR al iniciar.
         *
         * Es el MISMO que usa [OpkSelection.paraIniciar] al elegir: `0` si el
         * bundle trae OPKs, `null` si no trae ninguno. El receptor tiene que
         * mirar el bundle del emisor en ESE indice, no en uno cualquiera: si
         * deriva `DH4` y el emisor no, el `SK` sale distinto y el frame no
         * abre.
         */
        const val OPK_INDICE_INICIAL: Int = 0
    }
}