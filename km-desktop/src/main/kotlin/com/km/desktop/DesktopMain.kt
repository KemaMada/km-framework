package com.km.desktop

import com.km.api.KeyMessageCore
import com.km.desktop.client.ChatService
import com.km.desktop.client.ClientEvent
import com.km.desktop.client.ClientEvents
import com.km.desktop.client.ContactService
import com.km.desktop.client.DiagnosticsService
import com.km.desktop.client.ExportService
import com.km.desktop.client.RecoveryService
import com.km.desktop.config.BootstrapConfig
import com.km.desktop.core.CoreFactory
import com.km.desktop.platform.ConfigPaths
import com.km.desktop.platform.OwnerPermissions
import com.km.desktop.shell.LineModeShell
import com.km.desktop.shell.JLineShell
import com.km.desktop.shell.ScreenWriter
import com.km.desktop.shell.ShellMode
import com.km.desktop.shell.UnlockGate
import com.km.desktop.ui.Size
import com.km.model.Ack
import com.km.model.IdentityId
import com.km.model.Message
import com.km.model.MessageId
import com.km.model.MessageState
import com.km.messaging.LegacyInboundObservation
import com.km.messaging.SecureSessionRegistry
import com.km.protocol.ConnectionState
// p4/R-8.a: el motor del rele (cliente WebSocket + codec del dialecto
// KM-WIRE-RELAY) vive en `:km-transport` y se consume POR ARTEFACTO. La copia
// que habia aqui en `com.km.desktop` se ELIMINO: dos clientes de wire
// significarian dos protocolos, dos `MAX_RESEND` y dos vectores de ratchet
// duplicados. Ver `km-transport/src/main/kotlin/com/km/transport`.
import com.km.desktop.util.DiagLog
import com.km.identity.LocalDeviceKeyStore
import com.km.identity.LocalDeviceKeys
import com.km.desktop.net.EndpointSequence
import com.km.desktop.net.RelayStackFactory
import com.km.transport.JvmRelayTransport
import com.km.transport.RelayClientStack
import com.km.transport.RelayDataCodec
import com.km.transport.RelayStatus
import org.jline.reader.LineReader
import org.jline.reader.LineReaderBuilder
import org.jline.terminal.Terminal
import org.jline.terminal.TerminalBuilder
import java.io.PrintWriter
import java.nio.file.Path
import java.nio.file.Paths

/**
 * Comando del bucle de la TUI.
 *
 * Se modelan como dato y no como `when` sobre el texto porque asi la pantalla
 * de ayuda y el despacho comparten la MISMA tabla: anadir un comando sin
 * actualizar `help` es un fallo visible en el primer test que lo mire.
 */
sealed class Command {
    data object Help : Command()
    data object Identity : Command()
    data object Status : Command()
    data object Quit : Command()

    /** `connect [endpoint]`. Sin argumento, el endpoint por defecto. */
    data class Connect(val endpoint: String?) : Command()

    /** `send <identityIdHex> <texto>` */
    data class Send(val to: IdentityId, val text: String) : Command()

    /** `peer <identityIdHex> <clavePublicaBase64>` */
    data class Peer(val identityIdHex: String, val publicKeyBase64: String) : Command()

    /** `import @<ruta>` o `import <portador>`. */
    data class Import(val arg: String) : Command()

    /** `export [ruta]`: escribe el `.km` propio. */
    data class Export(val path: String?) : Command()

    /** `announce <identityIdHex>`: SIGNAL del bundle propio y, tras Delivered, PREKEY. */
    data class Announce(val to: IdentityId) : Command()

    /** `session <identityIdHex>`: abre la sesion cifrada con un contacto. */
    data class SessionCmd(val to: IdentityId) : Command()

    /** `contacts`: lista los contactos verificados (EN MEMORIA). */
    data object Contacts : Command()

    /** `history <identityIdHex>`: historial conocido del par. */
    data class History(val peer: IdentityId) : Command()

    /** `resend <uuid>`: reintenta un tombstone; conserva el id. */
    data class Resend(val messageId: MessageId) : Command()

    /** `discard <uuid>`: descarta un tombstone. */
    data class Discard(val messageId: MessageId) : Command()

    data class Unknown(val input: String) : Command()

    companion object {
        const val DEFAULT: String = JvmRelayTransport.DEFAULT_ENDPOINT

        /**
         * Parsea una linea de entrada.
         *
         * Un `identityId` se valida por FORMA (64 hex). Es lo unico que se puede
         * comprobar sin red: que exista en el rele es una consulta, no un
         * requisito de sintaxis, y exigirla aqui haria que `send` no se pudiera
         * ni escribir.
         */
        fun parse(line: String): Command {
            val trimmed = line.trim()
            if (trimmed.isEmpty()) return Unknown(trimmed)
            val parts = trimmed.split(Regex("\\s+"), limit = 4)
            return when (parts[0].lowercase()) {
                "help", "?", "h" -> Help
                "identity", "id" -> Identity
                "status" -> Status
                "quit", "exit", "q" -> Quit
                "connect", "conectar" -> Connect(parts.getOrNull(1))
                "send", "enviar" -> parseSend(trimmed, parts)
                "peer" -> parsePeer(trimmed, parts)
                "import", "importar" -> Import(trimmed.substringAfter(' ', "").trim())
                "export", "exportar" -> Export(parts.getOrNull(1))
                "contacts", "contactos" -> Contacts
                "announce", "anunciar" -> parseIdentity(trimmed, parts)?.let { Announce(it) } ?: Unknown(trimmed)
                "session", "sesion" -> parseIdentity(trimmed, parts)?.let { SessionCmd(it) } ?: Unknown(trimmed)
                "history", "historial" -> parseIdentity(trimmed, parts)?.let { History(it) } ?: Unknown(trimmed)
                "resend", "reintentar" -> parseUuid(parts.getOrNull(1))?.let { Resend(it) } ?: Unknown(trimmed)
                "discard", "descartar" -> parseUuid(parts.getOrNull(1))?.let { Discard(it) } ?: Unknown(trimmed)
                else -> Unknown(trimmed)
            }
        }

        private fun parseSend(original: String, parts: List<String>): Command {
            if (parts.size < 3) return Unknown(original)
            val hex = parts[1].lowercase()
            if (hex.length != RelayDataCodec.IDENTITY_ID_HEX || !hex.all { it in "0123456789abcdef" }) {
                return Unknown(original)
            }
            return Send(IdentityId(hex), parts.drop(2).joinToString(" "))
        }

        /**
         * `peer <identityIdHex> <claveBase64>`.
         *
         * NO se valida la coherencia aqui: eso es trabajo de [PeerKeyDirectory],
         * que es quien tiene la derivacion. Si se comprobara en el parser, el
         * comando tendria que devolver dos cosas —un comando y un error— y el
         * unico sitio donde se reporta el error es el dispatch, que ya lo hace.
         */
        private fun parsePeer(original: String, parts: List<String>): Command {
            if (parts.size != 3) return Unknown(original)
            return Peer(parts[1], parts[2])
        }

        /** Un `identityId` de 64 hex, validado por FORMA. */
        private fun parseIdentity(original: String, parts: List<String>): IdentityId? {
            if (parts.size != 2) return null
            val hex = parts[1].lowercase()
            if (hex.length != RelayDataCodec.IDENTITY_ID_HEX) return null
            if (hex.any { it !in "0123456789abcdef" }) return null
            return IdentityId(hex)
        }

        /** Un `messageId`, que es un UUID. Se acepta la forma completa. */
        private fun parseUuid(raw: String?): MessageId? {
            if (raw == null) return null
            return runCatching { MessageId.fromString(raw.trim()) }.getOrNull()
        }

        val TABLE: List<Pair<String, String>> = listOf(
            "connect [endpoint]" to "conecta al rele; sin argumento usa $DEFAULT",
            "identity" to "muestra identityId y claves publicas de esta sesion",
            "status" to "estado del transporte, sesion y ultimo error",
            "send <identityIdHex> <texto>" to "crea y envia un mensaje a un peer",
            "peer <identityIdHex> <claveBase64>" to "registra la clave de firma de un peer, para poder verificar sus ACK",
            "import <portador|@ruta>" to "importa un contacto .km verificado (ISO-8859-1)",
            "export [ruta]" to "escribe TU bundle .km (0 OPK) para compartirlo",
            "announce <identityIdHex>" to "publica tu bundle por SIGNAL y, tras Delivered, abre sesion y entrega PREKEY",
            "session <identityIdHex>" to "abre la sesion cifrada con un contacto ya importado",
            "contacts" to "lista los contactos verificados (SOLO EN MEMORIA)",
            "history <identityIdHex>" to "historial conocido del par",
            "resend <uuid>" to "reintenta un mensaje EXPIRADO conservando su id",
            "discard <uuid>" to "descarta un mensaje EXPIRADO",
            "help" to "esta ayuda",
            "quit" to "desconecta y sale",
        )
    }
}

/**
 * Estado vivo de la sesion: identidad, pila del rele, core y peers vistos.
 *
 * Existe para que la TUI no tenga ningun campo mutable propio, y para que los
 * handlers del core, que llegan desde otros hilos, escriban SIEMPRE en el mismo
 * objeto que imprime la pantalla.
 *
 * ## POR QUE GUARDA LA PILA Y NO EL MOTOR
 *
 * Porque lo que hace falta casi siempre es el conjunto —el manager para abrir
 * enlaces, el driver de un par, el camino legado— y no el motor suelto. Un
 * `Session` que guardara el motor obligaria a recomponer la pila en cada sitio
 * que la necesite, y a hacerlo de forma ligeramente distinta en cada uno.
 */
class Session(
    val identity: DesktopIdentity,
    val keystorePath: Path,
    /** La pila del rele. El motor se alcanza por `stack.transport`. */
    val stack: RelayClientStack,
    /**
     * El nucleo Y las dos piezas cuya presencia hay que poder mirar.
     *
     * Se guarda el [CoreFactory.CoreBundle] entero y no el `KeyMessageCore` a
     * pelo porque lo que la sesion necesita observar —el registro de sesiones y
     * el estado del camino legado— vive AL LADO del nucleo, no dentro de la
     * interfaz. Guardar solo el nucleo obligaria a mantener una segunda
     * referencia al registro, y dos referencias a un registro son dos
     * oportunidades de que se desincronicen.
     */
    val core: CoreFactory.CoreBundle,
    /**
     * Claves de FIRMA de los peers conocidos.
     *
     * Es de donde sale el `peerPublicKeyResolver` que el core usa para
     * autenticar los `ACK`. Vive en la `Session` y no en `main` porque el
     * comando `peer` lo modifica DESPUES de que el core este construido, y el
     * resolver que se le paso es una referencia viva a este directorio:
     * registrar un peer surte efecto sin reconstruir el core.
     *
     * NO se llama `peers` a proposito: `peers` es el conjunto de los que estan
     * EN LINEA, que es estado del rele y se borra al desconectar. Esto es un
     * directorio de claves y sobrevive a la sesion. Que las dos cosas compartan
     * nombre invita a limpiarla cuando hay que conservarla.
     */
    val peerKeys: PeerKeyDirectory = PeerKeyDirectory(),
    /**
     * El material PRIVADO del dispositivo (SPK, OPK, IK), si el almacen lo tiene.
     *
     * `null` en las pruebas que solo ejercitan transporte y en un arranque sin
     * aprovisionar. Los servicios de contacto responden entonces con
     * `MISSING_LOCAL_IDENTITY`, que es la verdad: sin material no hay bundle
     * propio que publicar.
     */
    val deviceKeys: LocalDeviceKeys? = null,
    /** Donde se persiste el consumo de un OPK. `null` si no hay almacen cableado. */
    val deviceKeyStore: LocalDeviceKeyStore? = null,
    /** El bus de eventos: es lo UNICO que los handlers publican. */
    val events: ClientEvents = ClientEvents(),
    /**
     * La frase de paso del almacen, EN MEMORIA y solo mientras vive el proceso.
     *
     * Hace falta para dos cosas que reescriben el contenedor cifrado: guardar el
     * consumo de un OPK y RESTAURAR la identidad desde la frase. No se persiste
     * en ningun sitio ni se registra; si es `null` (arranque sin retenerla), las
     * operaciones que la necesitan responden con un codigo en vez de inventarla.
     */
    val passphrase: CharArray? = null,
) {
    private val peers = java.util.concurrent.ConcurrentHashMap.newKeySet<IdentityId>()

    /**
     * El registro de contactos del escritorio.
     *
     * En MEMORIA y se dice: no hay persistencia de contactos (persist-contact-book
     * sigue sin apply). El usuario tiene que saber que al cerrar el proceso se
     * pierden, en vez de descubrirlo.
     */
    val contacts: com.km.identity.ContactRegistry = com.km.identity.InMemoryContactRegistry()

    private val intake = com.km.identity.ContactIntake(contacts) { localIdentity() }

    /** La retencion acotada de PREKEY de pares que aun no son contacto. */
    val exchange: com.km.transport.contact.ContactExchangeCoordinator =
        com.km.transport.contact.ContactExchangeCoordinator(
            conocido = { emisor -> registry.find(emisor) != null || contacts.find(emisor) != null },
            // El clasificador del wire lo aporta el cliente: km-transport no
            // importa `com.km.frame` (su guard de separacion lo prohibe).
            esPrekey = { wire -> com.km.frame.PrekeyFrame.esPrekey(wire) },
        )

    /** Los servicios de contacto: sin terminal y sin protocolo propio. */
    val contactService = ContactService(
        events = events,
        contacts = contacts,
        intake = intake,
        stack = { stack },
        sessionRegistry = { core.registry },
        identity = { identity },
        deviceKeys = { deviceKeys },
        // El MISMO directorio que se paso al nucleo como resolver: el import y
        // el contact_exchange registran la clave de firma del contacto aceptado,
        // y el mapa vivo hace que el ACK se autentique sin reconstruir nada.
        peerKeys = { peerKeys },
        onOneTimePreKeyConsumed = { keyId -> deviceKeyStore?.consumeOneTimePreKey(keyId) },
    )

    /** Los servicios de mensajeria. */
    val chatService = ChatService(events) { core.core }

    /** La frase, la validacion y la restauracion de la identidad. */
    val recovery: RecoveryService by lazy {
        RecoveryService(
            identity = { identity },
            keystorePath = keystorePath,
            passphrase = { passphrase },
        )
    }

    /** Diagnostico y logs de la pantalla: solo lineas del buffer redactado. */
    val diagnostics: DiagnosticsService by lazy { DiagnosticsService() }

    /** Escrituras de `.km` y export de logs, junto al almacen. */
    val exportService: ExportService by lazy {
        ExportService(
            baseDir = keystorePath.toAbsolutePath().parent
                ?: java.nio.file.Paths.get("."),
        )
    }

    private fun localIdentity(): IdentityId? = identity.identityId

    /** SHA-256 del `identityId` en hex: la huella corta que muestra `identity`. */
    val identityDigest: String =
        java.security.MessageDigest.getInstance("SHA-256")
            .digest(identity.identityId.value.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    fun onlinePeers(): Set<IdentityId> = peers.toSet()

    /**
     * Pares con los que HAY SESION CIFRADA establecida.
     *
     * No es lo mismo que [onlinePeers]: aquellos son los que el rele ha
     * declarado PRESENTES, que es estado del rele y se borra al desconectar.
     * Estos son los que tienen una sesion en el registro, y sin sesion el envio
     * se RECHAZA. Confundir las dos dos lineas haria que un par en linea
     * pareciera escribible cuando lo que sale es un rechazo.
     */
    fun sessionPeers(): Set<IdentityId> = registry.peers()

    /** El motor del rele. Es la MISMA instancia que `stack.transport`. */
    val transport: JvmRelayTransport get() = stack.transport

    /**
     * Los bootstraps configurados, en orden.
     *
     * Los fija el nucleo de la sesion —no el rele— porque son una decision de
     * ARRANQUE: son los destinos que el operador ha configurado para esta
     * ejecucion, y no cambian por conectar.
     */
    var bootstraps: List<String> = listOf(transport.status().endpoint)

    /**
     * Intenta conectar por [target].
     *
     * ## LO QUE NO PUEDE HACER, Y SE DICE
     *
     * **No cambia el endpoint del motor en caliente.** `JvmRelayTransport` toma
     * el endpoint en su CONSTRUCTOR (`private val endpoint`), no como `var`, y
     * su `status()` lo lee de ahi. Cambiarlo exigiria que `:km-transport` lo
     * expusiera, que es una decision de ESE modulo y no de este.
     *
     * Por eso este metodo solo conecta cuando [target] es el endpoint con el que
     * se construyo el motor, y devuelve un FALLO OBSERVABLE para el resto. Un
     * `connect` que aceptara cualquier endpoint y no cambiara nada seria un
     * failover de mentira: el cliente diria que ha reintentado con otro y habria
     * reintentado con el mismo.
     *
     * El RECORRIDO en orden —que es lo que [EndpointSequence] decide— si esta
     * probado y funciona; lo que falta es la costura del lado del transporte, y
     * esta excepcion la nombra para que quien la lea sepa que el fallo es de
     *表达能力 del transporte y no de la logica.
     */
    fun switchEndpoint(target: String): Result<Unit> {
        val actual = transport.status().endpoint
        if (target != actual) {
            return Result.failure(
                UnsupportedOperationException(
                    "el motor se construyo con '$actual' y su endpoint no es mutable: " +
                        "arranca el cliente con --bootstrap $target para usar ese destino",
                ),
            )
        }
        return transport.connect()
    }

    /** Estado del transporte. La TUI no lo interroga por dentro. */
    fun status(): RelayStatus = transport.status()

    /** El registro de sesiones. `peers()` de ahi es lo que muestra `status`. */
    val registry: SecureSessionRegistry get() = core.registry

    /** Lo que ha hecho el camino legado de recepcion. Observable, no un log. */
    val legacyInbound: LegacyInboundObservation get() = core.legacyInbound

    fun close() {
        observadoresInstall.forEach { it() }
        observadoresInstall.clear()
        core.core.stop()
        stack.close()
        peers.clear()
    }

    /**
     * Cablea los ganchos del motor y del core.
     *
     * ## LOS HANDLERS NO IMPRIMEN
     *
     * Publican [ClientEvent]s en [events]. Antes esto recibia una lambda que
     * hacia `out.println` desde el hilo del motor: en un REPL de lineas se
     * toleraba, pero es exactamente lo que corrompe una pantalla y lo que
     * impide que el terminal tenga un solo escritor. El que pinta es la TUI,
     * suscrita al bus.
     */
    fun bind() {
        // El camino LEGADO se da de alta EXPLICITAMENTE y una sola vez.
        //
        // Sin esto, cablear las dos caras deja el camino en claro sin nada: el
        // motor entrega lo opaco por `onDataReceived` (que alimenta al
        // `PeerTransportManager` y de ahi a las sesiones cifradas) y lo
        // clasificado por `onLegacyInboundFrame`, que sin compositor no entrega
        // nada. El resultado se parece muchisimo a "funciona": el cifrado entra,
        // y el legado desaparece sin decir por que.
        //
        // El `false` de un segundo `attachLegacy` es un RECHAZO, no un no-op
        // silencioso: dos conductores sobre el mismo slot harian que cada frame
        // se entregase DOS veces por el camino en claro.
        val montado = stack.attachLegacy()
        events.emit(
            ClientEvent.Info(
                if (montado) "camino legado: dado de alta"
                else "camino legado: ya estaba dado de alta (no se duplica)",
            ),
        )

        transport.onStateChange { state -> events.emit(ClientEvent.TransportState(state.name)) }
        transport.onPeerOnline { peer ->
            peers.add(peer)
            // La presencia se le pasa al CONDUCTOR de ese par, si existe: sin
            // esto el binding se queda en `NEW` y `sendData` rechaza. La app ya
            // lo hacia; el escritorio no, porque hasta G1 nunca abria sesion.
            stack.driverOf(peer)?.onPeerOnline(peer)
            events.emit(ClientEvent.PeerPresence(peer, online = true))
        }
        transport.onPeerOffline { peer ->
            peers.remove(peer)
            stack.driverOf(peer)?.onPeerOffline(peer)
            events.emit(ClientEvent.PeerPresence(peer, online = false))
        }
        transport.setStoredHandler { receipt ->
            events.emit(
                ClientEvent.Info(
                    "STORED: el rele guardo ${receipt.originalMessageId.value.toString().take(8)}... " +
                        "hasta ${receipt.expiresAt}",
                ),
            )
        }
        transport.setRelayExpiredHandler { notice ->
            events.emit(
                ClientEvent.Warn(
                    "RELAY_EXPIRED: ${notice.originalMessageId.value.toString().take(8)}... " +
                        "(${notice.reason})",
                ),
            )
        }
        // El gancho de INSTALACION: cada sesion que entra en el registro abre
        // su canal de par en el rele.
        //
        // Sin esto, la sesion existe, el cifrado funciona y el enlace nunca
        // pasa de `NEW`: `PeerTransportManager.sendData` exige `CONNECTED`, y el
        // envio se rechazaria por un motivo que no tiene nada que ver con la
        // criptografia. El fallo se veria como "el escritorio no envia", que es
        // justo el sintoma que hace que se sospeche del rele.
        //
        // Se observa el REGISTRO y no el manager porque el registro es quien
        // sabe que una sesion es VIGENTE: sustituirla en el registro tiene que
        // re-conducir el enlace, y un `onData` del manager no distingue la
        // sesion nueva de la vieja.
        val observarInstalacion = registry.onInstall { sesion ->
            val conductor = stack.driverFor(sesion.peerContext)
            // La presencia se PREGUNTA al motor y no se guarda en un set propio:
            // el snapshot de presencia del rele llega DURANTE `connect()` y el
            // suscriptor se da de alta DESPUES. Preguntar siempre es correcto;
            // recordar eventos se queda vacio justo en el caso que importa.
            if (transport.isPeerPresent(sesion.remotePeerId)) {
                conductor.onPeerPresent(sesion.remotePeerId)
            }
            DiagLog.event("[SESSION]", "instalada peer=${DiagLog.truncar(sesion.remotePeerId.value)}")
            events.emit(ClientEvent.SessionOpened(sesion.remotePeerId))
        }

        val motor = core.core
        motor.registerMessageHandler { message: Message ->
            DiagLog.messageReceived(message.messageId.value.toString(), message.from.value)
            events.emit(ClientEvent.MessageReceived(message))
        }
        motor.registerAckHandler { ack: Ack -> events.emit(ClientEvent.AckReceived(ack)) }
        motor.registerStateChangeHandler { id: MessageId, state: MessageState ->
            events.emit(ClientEvent.MessageStateChanged(id, state))
        }

        // Las DOS caras de la senalizacion: bytes opacos del canal de par
        // (PREKEY) y portador del contacto. Se cablean en el SERVICIO, no aqui,
        // para que el orden SIGNAL/PREKEY y la retencion tengan un solo dueno.
        contactService.attachInbound(stack, exchange)

        // Un `bind` por gancho vivo. `bind` es idempotente por el mismo motivo
        // que `attachLegacy`: un segundo `bind` LOWBEA el gancho anterior antes de
        // suscribir el nuevo, en vez de acumular observadores. Dos ganchos vivos
        // abririan DOS drivers por par y cada frame se contaria dos veces.
        observadoresInstall.forEach { it() }
        observadoresInstall.clear()
        observadoresInstall += observarInstalacion
    }

    /** Tokens de baja de los ganchos de instalacion, uno por `bind`. */
    private val observadoresInstall = mutableListOf<() -> Unit>()
}

/**
 * Punto de entrada.
 *
 * Orden deliberado: la identidad se abre ANTES de tocar la red, y si falla el
 * programa termina con un codigo de salida distinto de cero. Un cliente que
 * arrancara sin identidad y la inventara al vuelo produciria una `identityId`
 * distinta en cada ejecucion, y todos los contactos lo verian como alguien
 * nuevo.
 */
fun main(args: Array<String>) {
    val options = runCatching { DesktopOptions.parse(args) }.getOrElse {
        System.err.println("argumentos invalidos: ${it.message}")
        // La ayuda completa sale de `usage()`, unica fuente de verdad: antes
        // habia aqui una copia literal que se quedo atras al anadir `--verbose`
        // y `-h`, asi que un argumento mal escrito ocultaba opciones reales.
        System.err.println(DesktopOptions.usage())
        kotlin.system.exitProcess(2)
    }
    if (options.help) {
        println(DesktopOptions.usage())
        return
    }

    // La ruta por defecto la decide `ConfigPaths`: XDG en Linux, APPDATA en
    // Windows y ~/Library en macOS. `IdentityKeystore.defaultPath` delega en
    // ella, asi que este arranque y el resto del modulo no pueden discrepar.
    val keystorePath = options.keystorePath ?: runCatching { IdentityKeystore.defaultPath() }.getOrElse {
        System.err.println(it.message)
        kotlin.system.exitProcess(2)
    }
    // El directorio de peers se resuelve DESPUES del almacen, porque su
    // defecto es "junto a el". Resolverlo antes obligaria a repetir la
    // resolucion de la ruta y las dos podrian divergir.
    val peersPath = options.peersPath
        ?: keystorePath.toAbsolutePath().parent?.resolve(DesktopOptions.PEERS_FILE_NAME)
        ?: Paths.get(DesktopOptions.PEERS_FILE_NAME)
    // El directorio que contiene el almacen es material secreto: si ya existe,
    // se asegura antes de escribir nada. Si no existe, lo crea y asegura la
    // primera escritura (`IdentityKeystore.write`).
    keystorePath.toAbsolutePath().parent
        ?.takeIf { java.nio.file.Files.isDirectory(it) }
        ?.let { OwnerPermissions.DEFAULT.secureDirectory(it) }
    if (options.reset && IdentityKeystore.exists(keystorePath)) {
        java.nio.file.Files.delete(keystorePath)
        System.err.println("almacen eliminado: $keystorePath")
    }
    // `--reset` borra tambien las claves de peers: dejarlas haria que un
    // `identityId` nuevo se confirmara contra un `ACK` firmado por una clave que
    // el usuario ya no tiene, y el fallo seria de protocolo y no de arranque.
    if (options.reset && java.nio.file.Files.isRegularFile(peersPath)) {
        java.nio.file.Files.delete(peersPath)
        System.err.println("directorio de peers eliminado: $peersPath")
    }

    val terminal = runCatching { TerminalBuilder.builder().build() }.getOrElse {
        System.err.println("no se pudo abrir la terminal: ${it.message}")
        kotlin.system.exitProcess(1)
    }
    val out = PrintWriter(terminal.writer(), true)
    // El color se decide por si HAY una terminal en stdout, no por si JLine ha
    // conseguido abrir una: `km-desktop | tee log` tiene terminal para JLine y
    // no la tiene para el pipe, y es el pipe el que no quiere secuencias SGR.
    Ansi.detect(
        // `--ascii` apaga el color ademas de los glifos: es el modo que pide
        // una salida limpia, y las secuencias SGR tambien son "no ASCII".
        stdoutTerminal = System.console() != null && !options.ascii,
        env = System.getenv(),
    )

    // La TUI de pantallas exige una terminal con direccionamiento de cursor y
    // al menos 60x20. Si no la hay, se DICE y se usa el modo lineas: adaptar una
    // pantalla a 40 columnas esconde datos sin avisar.
    val tamanoInicial = runCatching { terminal.size }.getOrNull()
    val tuiViable = options.mode == ShellMode.TUI &&
        terminal.type != Terminal.TYPE_DUMB &&
        terminal.type != Terminal.TYPE_DUMB_COLOR &&
        tamanoInicial != null &&
        tamanoInicial.columns >= Size.MIN_COLUMNS &&
        tamanoInicial.rows >= Size.MIN_ROWS

    if (options.mode == ShellMode.TUI && !tuiViable) {
        out.println(
            Ansi.yellow(
                "TUI no disponible en esta terminal (" + (tamanoInicial?.let { "${it.columns}x${it.rows}" } ?: terminal.type) +
                    "): minimo ${Size.MIN_COLUMNS}x${Size.MIN_ROWS}; se usa el modo lineas",
            ),
        )
        out.flush()
    }

    if (tuiViable) {
        val writer = ScreenWriter(terminal, attributes = Ansi.enabled && !options.ascii)
        val gate = UnlockGate(
            terminal = terminal,
            writer = writer,
            keystorePath = keystorePath,
            bootstraps = options.bootstraps,
            ascii = options.ascii,
        )
        val sesion = try {
            gate.run { passphrase ->
                runCatching {
                    composeSession(options, keystorePath, peersPath, fuenteFija(passphrase))
                }
            }
        } catch (fallo: Exception) {
            // Un fallo de la propia pantalla (I/O del terminal) no puede dejar el
            // usuario dentro de la alternativa ni propagar sin decir nada.
            runCatching { writer.exit() }
            runCatching { terminal.close() }
            System.err.println("no se pudo abrir la interfaz: ${fallo.message}")
            kotlin.system.exitProcess(1)
        }
        if (sesion == null) {
            // Ctrl-C o EOF en el desbloqueo: no se toco la red y no hay nada que
            // cerrar. El terminal SI hay que sacarlo de la pantalla alternativa.
            runCatching { writer.exit() }
            runCatching { terminal.close() }
            return
        }
        try {
            JLineShell(terminal, writer, sesion, options.ascii).run()
        } finally {
            sesion.close()
            runCatching { terminal.close() }
        }
        return
    }

    val reader = runCatching { LineReaderBuilder.builder().terminal(terminal).build() }.getOrElse {
        System.err.println("no se pudo iniciar el lector de lineas: ${it.message}")
        kotlin.system.exitProcess(1)
    }

    val fuente = jlinePassphraseSource(reader, out)
    val sesion = try {
        composeSession(options, keystorePath, peersPath, fuente)
    } catch (e: KeystoreException) {
        out.println(Ansi.red(e.message ?: "no se pudo abrir el almacen"))
        out.flush()
        terminal.close()
        kotlin.system.exitProcess(1)
    }

    // `--no-tui` y `--accessible` comparten el bucle lineal: es append-only, no
    // direcciona el cursor y por tanto sirve para scripts y lectores de pantalla.
    val shell = LineModeShell(terminal, reader, out, sesion)
    try {
        shell.run()
    } finally {
        sesion.close()
        terminal.close()
    }
}

/**
 * Deja el `--verbose` del motor en el canal `[RELAY]` del diagnostico.
 */
private fun relayLog(options: DesktopOptions): (String) -> Unit =
    { message -> if (options.verbose) DiagLog.event("[RELAY]", message) }

/**
 * Una fuente de frase de paso FIJA: la que ya se tecleo en la pantalla de
 * desbloqueo.
 *
 * ## POR QUE NO SE VUELVE A PREGUNTAR
 *
 * Porque la TUI ya la pidio enmascarada ANTES de tocar la red. Volver a
 * preguntarla por el `LineReader` sacaria al usuario de la pantalla alternativa y
 * dejaria el terminal en un estado intermedio. La frase se entrega como
 * `CharArray` y solo vive lo que dura la apertura.
 */
private fun fuenteFija(passphrase: CharArray): PassphraseSource = object : PassphraseSource {
    override fun passphrase(creation: Boolean): String? = String(passphrase)
}

/**
 * Compone la sesion COMPLETA: identidad, material, transporte, pila, nucleo,
 * directorio de peers y enlace de handlers.
 *
 * ## EL ORDEN NO ES NEGOCIABLE
 *
 * 1. Identidad + material (cifrado en reposo) — ANTES de cualquier red.
 * 2. Transporte y pila.
 * 3. Nucleo con el resolver de claves.
 * 4. `bind()` de los ganchos, que solo publican eventos.
 *
 * Se extrajo de `main` para que la TUI pueda abrirla DESPUES del desbloqueo sin
 * duplicar el cableado y sin que el modo lineas cambie de comportamiento.
 */
private fun composeSession(
    options: DesktopOptions,
    keystorePath: Path,
    peersPath: Path,
    source: PassphraseSource,
): Session {
    // La frase se pide UNA vez y se conserva SOLO para el adaptador del material
    // de dispositivo: cada guardado de OPK reescribe el contenedor cifrado, y
    // para eso hace falta la misma frase. Vive en memoria.
    var fraseDePaso: CharArray? = null
    val fuenteQueRecuerda = object : PassphraseSource {
        override fun passphrase(creation: Boolean): String? =
            source.passphrase(creation)?.also { fraseDePaso = it.toCharArray() }
    }
    val contenido = IdentityKeystore.loadOrCreateWithDeviceKeys(keystorePath, fuenteQueRecuerda)

    val identity = contenido.identity
    val transport = JvmRelayTransport(
        identity = identity.identityId,
        ed25519PublicKey = identity.ed25519PublicKey,
        ed25519PrivateKey = identity.ed25519PrivateKey,
        endpoint = options.endpoint,
        relayIdentity = options.relayIdentity,
        log = relayLog(options),
    )
    val stack = RelayStackFactory.stackFor(transport)
    val peers = PeerKeyDirectory(peersPath).load()
    if (peers.known().isEmpty()) {
        // stderr y no pantalla: esto ocurre antes de que la TUI (o el REPL)
        // escriba su primer frame, y es una condicion de arranque, no un evento
        // de la sesion.
        System.err.println(
            "sin claves de peers registradas ($peersPath); los ACK entrantes no se podran autenticar. " +
                "usa 'peer <identityIdHex> <claveBase64>'"
        )
    }
    val core = CoreFactory().create(
        localIdentity = identity.identityId,
        localPrivateKey = identity.ed25519PrivateKey,
        transport = transport,
        manager = stack.manager,
        peerPublicKeyResolver = peers.resolver(),
    )

    DiagLog.event("[AUTH]", "identidad cargada desde el almacen")
    DiagLog.event("[CONTACT]", "directorio de peers leido: ${peers.known().size}")
    DiagLog.integrity("arranque", ok = true)

    val events = ClientEvents()
    val deviceKeyStore = fraseDePaso?.let { KeystoreDeviceKeyStore(keystorePath, it) }
    val session = Session(
        identity = contenido.identity,
        keystorePath = keystorePath,
        stack = stack,
        core = core,
        peerKeys = peers,
        deviceKeys = contenido.deviceKeys,
        deviceKeyStore = deviceKeyStore,
        events = events,
        // La MISMA frase que abrio el almacen: restaurar y guardar un OPK
        // reescriben el contenedor y no pueden pedirla por otra via.
        passphrase = fraseDePaso,
    )
    session.bootstraps = options.bootstraps
    DiagLog.event("[BOOTSTRAP]", "origen=linea-o-entorno destinos=${options.bootstraps.size}")
    DiagLog.event(
        "[CONTACT]",
        "material de dispositivo ${if (contenido.deviceKeys == null) "AUSENTE" else "cargado"} " +
            "opk=${contenido.deviceKeys?.oneTimePreKeys?.size ?: 0}",
    )
    session.bind()
    return session
}


/**
 * Fuente de frase de paso: variable de entorno primero, prompt enmascarado si no.
 *
 * Se anuncia el uso de la variable cuando esta puesta: es comodo, y tambien es
 * la via por la que una frase de paso acaba en la historia del shell.
 */
private fun jlinePassphraseSource(reader: LineReader, out: PrintWriter): PassphraseSource =
    PassphraseSource.envThen { creation ->
        if (System.getenv(PassphraseSource.ENV_VAR)?.isNotEmpty() == true) {
            out.println(Ansi.yellow("frase de paso leida de ${PassphraseSource.ENV_VAR}"))
            out.flush()
        }
        val prompt = if (creation) {
            "frase de paso para la identidad NUEVA (se cifra con Argon2id): "
        } else {
            "frase de paso de la identidad: "
        }
        val confirmation = if (creation) "repita la frase de paso: " else null
        reader.readLine(prompt, '*', null)?.takeIf { it.isNotEmpty() }
            ?.also { first ->
                if (confirmation != null) {
                    val again = reader.readLine(confirmation, '*', null)
                    if (again != first) {
                        throw KeystoreException("las dos frases de paso no coinciden")
                    }
                }
            }
    }

/** Argumentos de linea de comandos. Minimos a proposito: esto es una TUI, no un CLI. */
class DesktopOptions(
    /**
     * El endpoint con el que arranca. Es el PRIMERO de [bootstraps].
     *
     * Se conserva el campo singular por dos razones: `Command.DEFAULT` y el
     * comando `connect` sin argumento necesitan un valor concreto, y el motor
     * se construye con UN endpoint. La lista completa vive en [bootstraps] y es
     * la que usa el failover.
     */
    val endpoint: String,
    /**
     * Los bootstraps, EN ORDEN.
     *
     * Viene de [BootstrapConfig.resolve]: `--bootstrap` repetible, o
     * `KM_BOOTSTRAPS`, o el default del motor. Nunca vacia.
     */
    val bootstraps: List<String>,
    val relayIdentity: IdentityId?,
    val keystorePath: Path?,
    val peersPath: Path?,
    val reset: Boolean,
    val verbose: Boolean,
    val help: Boolean,
    /**
     * Como se presenta la sesion. Por defecto [ShellMode.TUI]; en esta fase el
     * unico shell implementado es el lineal, y la Fase 3 sustituye TUI y
     * ACCESSIBLE por el renderer de pantallas. El flag existe ya para que el
     * lanzador `km`/`km.cmd` y el manual no cambien de contrato despues.
     */
    val mode: ShellMode = ShellMode.TUI,
    /** `--ascii`: sin glifos Unicode ni secuencias de color. */
    val ascii: Boolean = false,
) {
    companion object {
        const val RELAY_ENV: String = "KM_RELAY_URL"
        const val RELAY_IDENTITY_ENV: String = "KM_RELAY_IDENTITY"
        const val PEERS_FILE_NAME: String = "peers.json"

        fun parse(args: Array<String>): DesktopOptions {
            // Los bootstraps se resuelven con UNA sola fuente de verdad
            // (`BootstrapConfig`), que ademas es la que decide la precedencia.
            // `--relay` se recoge aparte y se aplica ENCIMA, porque es el nombre
            // historico y un operador que lo usa espera que gane.
            val bootstrapsDeLinea = mutableListOf<String>()
            var relayDeLinea: String? = null
            var relayIdentity = System.getenv(RELAY_IDENTITY_ENV)?.takeIf { it.isNotEmpty() }?.let { raw ->
                requireIdentity(raw, RELAY_IDENTITY_ENV)
            }
            var keystore: Path? = null
            var peers: Path? = null
            var reset = false
            var verbose = false
            var help = false
            var ascii = false
            var mode: ShellMode? = null
            var i = 0

            /**
             * Los tres modos son EXCLUYENTES: pedir dos a la vez es un error de
             * uso, no "gana el ultimo". Un operador que escribe `--no-tui
             * --accessible` cree que ha pedido una cosa y no sabria cual se
             * aplico.
             */
            fun elegirModo(candidate: ShellMode, flag: String) {
                require(mode == null || mode == candidate) {
                    "modo ya elegido ($mode); $flag es incompatible con la opcion anterior"
                }
                mode = candidate
            }

            while (i < args.size) {
                when (args[i]) {
                    "--relay" ->
                        relayDeLinea = args.getOrNull(++i) ?: throw IllegalArgumentException("--relay sin valor")
                    // Repetible y en orden: es la lista de failover.
                    "--bootstrap" ->
                        bootstrapsDeLinea += args.getOrNull(++i)
                            ?: throw IllegalArgumentException("--bootstrap sin valor")
                    "--relay-identity" ->
                        relayIdentity = requireIdentity(
                            args.getOrNull(++i) ?: throw IllegalArgumentException("--relay-identity sin valor"),
                            "--relay-identity",
                        )
                    "--keystore" -> keystore = Paths.get(args.getOrNull(++i) ?: throw IllegalArgumentException("--keystore sin valor"))
                    // El directorio de peers va junto al almacen de identidad por defecto, que es
                    // donde ya vive lo unico que el usuario tiene que proteger.
                    // `--peers` lo mueve, y no lo fusiona con el almacen: son
                    // datos de distinta naturaleza —claves publicas de terceros
                    // frente a material secreto— y mezclarlos obligaria a
                    // cifrar lo que no necesita cifrarse.
                    "--peers" -> peers = Paths.get(args.getOrNull(++i) ?: throw IllegalArgumentException("--peers sin valor"))
                    "--reset" -> reset = true
                    "--verbose" -> verbose = true
                    "--tui" -> elegirModo(ShellMode.TUI, "--tui")
                    "--accessible" -> elegirModo(ShellMode.ACCESSIBLE, "--accessible")
                    "--no-tui" -> elegirModo(ShellMode.LINE, "--no-tui")
                    "--ascii" -> ascii = true
                    "--help", "-h" -> help = true
                    else -> throw IllegalArgumentException("opcion desconocida: ${args[i]}")
                }
                i++
            }
            val bootstraps = BootstrapConfig.resolve(cli = bootstrapsDeLinea)
            val ordered = if (relayDeLinea != null) listOf(relayDeLinea) + bootstraps else bootstraps
            return DesktopOptions(
                endpoint = ordered.first(),
                bootstraps = ordered,
                relayIdentity = relayIdentity,
                keystorePath = keystore,
                peersPath = peers,
                reset = reset,
                verbose = verbose,
                help = help,
                mode = mode ?: ShellMode.TUI,
                ascii = ascii,
            )
        }

        /**
         * Un `identityId` son 64 hex. Se valida aqui, al parsear, y no al
         * conectar: un valor mal escrito debe fallar con un mensaje util de
         * linea de comandos, no con un `AUTH_FAIL` veinte segundos despues.
         */
        private fun requireIdentity(raw: String, source: String): IdentityId {
            val hex = raw.trim().lowercase()
            require(hex.length == RelayDataCodec.IDENTITY_ID_HEX && hex.all { it in "0123456789abcdef" }) {
                "$source no es un identityId: se esperan ${RelayDataCodec.IDENTITY_ID_HEX} caracteres hex,-oxrece '$raw'"
            }
            return IdentityId(hex)
        }

        fun usage(): String = """
            km-desktop — cliente TUI de KeyMessage

              --relay <endpoint>    rele (por defecto ${JvmRelayTransport.DEFAULT_ENDPOINT};
                                     tambien se lee de $RELAY_ENV)
              --bootstrap <url>     bootstrap, REPETIBLE y en orden: se prueban
                                     uno detras de otro hasta que uno responda.
                                     Por defecto, los de ${BootstrapConfig.ENV_MUCHOS}
                                     (separados por coma) o ${BootstrapConfig.ENV_UNO},
                                     y en su defecto el default del motor.
              --relay-identity <id> identityId del rele, 64 hex, que se anuncia en
                                     el AUTH_REQUEST (tambien $RELAY_IDENTITY_ENV).
                                     Sin ella el campo se omite y se anuncia el
                                     que venga en el AUTH_CHALLENGE.
              --keystore <ruta>     almacen de identidad. Por defecto:
                                     Linux  ${'$'}XDG_CONFIG_HOME/keymessage/${IdentityKeystore.IDENTITY_FILE_NAME}
                                            (o ~/.config/keymessage/...)
                                     Windows %APPDATA%\keymessage\${IdentityKeystore.IDENTITY_FILE_NAME}
                                     macOS  ~/Library/Application Support/keymessage/${IdentityKeystore.IDENTITY_FILE_NAME}
              --peers <ruta>        directorio de claves de firma de los peers,
                                     del que sale el resolver que autentica los ACK
                                     (por defecto, junto al almacen: $PEERS_FILE_NAME)
              --reset               borra el almacen y el directorio de peers antes de arrancar
              --verbose             traza del transporte por stderr
              --tui                 TUI de pantallas (defecto)
              --accessible          salida lineal, sin direccionamiento de cursor
              --no-tui              REPL de lineas de G1 (scripts y emergencia)
              --ascii               sin glifos Unicode ni color
              -h, --help            esta ayuda

            Comandos dentro de la sesion: connect, identity, status, send, peer, help, quit.
            Camino de contacto y mensajeria: import, export, announce, session,
            contacts, history, resend, discard (los mismos de `help`).
        """.trimIndent()
    }
}
