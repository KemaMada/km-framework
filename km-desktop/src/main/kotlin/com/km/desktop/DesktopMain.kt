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
import com.km.desktop.net.P2pMode
import com.km.desktop.net.P2pPlan
import com.km.desktop.net.P2pWiring
import com.km.desktop.platform.ConfigPaths
import com.km.desktop.platform.OwnerPermissions
import com.km.desktop.shell.LineModeShell
import com.km.desktop.shell.JLineShell
import com.km.desktop.shell.IdentityProvisioner
import com.km.desktop.shell.MouseSupport
import com.km.desktop.shell.ScreenWriter
import com.km.desktop.shell.ShellMode
import com.km.desktop.shell.UnlockGate
import com.km.desktop.ui.MouseMode
import com.km.desktop.ui.Size
import com.km.desktop.ui.theme.ColorCapability
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
import com.km.desktop.util.DiagLevel
import com.km.desktop.util.DiagLog
import com.km.transport.contact.ContactLogLevel
import com.km.transport.contact.ContactLogger
import com.km.identity.LocalDeviceKeyStore
import com.km.identity.LocalDeviceKeys
import com.km.node.Medium
import com.km.desktop.net.EndpointSequence
import com.km.desktop.net.RelayStackFactory
import com.km.transport.JvmRelayTransport
import com.km.transport.RelayClientStack
import com.km.transport.RelayDataCodec
import com.km.transport.RelayStatus
import com.km.transport.p2p.ClientTransportBundle
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
 * Lo observable del tier P2P, ya redactado.
 *
 * No lleva `IceConfig` a proposito: la pantalla no tiene por que poder llamar
 * a nada que exponga una credencial. `iceUrls` son las URLs que
 * `IceConfig.redactedUrls()` ya limpio.
 */
data class P2pSnapshot(
    val mode: String,
    val reason: String?,
    val medium: String,
    val iceUrls: List<String>,
    val iceHostOnly: Boolean,
)

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
    /**
     * La composicion P2P del arranque, o `null` en modo `off`.
     *
     * Se guarda entera —y no solo el `transport`— porque al cerrar hay que
     * soltar el coordinador y los bindings P2P, que no cuelgan del motor del
     * relay. Un `null` significa exactamente "no se construyo el medio".
     */
    val p2p: ClientTransportBundle? = null,
    /** Modo P2P efectivo de esta ejecucion, para DIAGNOSTICO. */
    val p2pMode: P2pMode = P2pMode.OFF,
    /**
     * Persistencia de contactos, o `null` si esta instancia no persiste.
     *
     * Se carga AQUI —al construir la sesion, que es el arranque— y siembra el
     * [ContactRegistry] de abajo. Un cliente sin ruta (los tests de transporte)
     * sigue con el registro vacio en memoria, como antes.
     */
    val contactStore: ContactStore? = null,
    /** Volcar `DiagLog` a `km-logs-*.log` al cerrar. La TUI y el modo linea lo activan. */
    val dumpLogOnClose: Boolean = false,
) {
    private val peers = java.util.concurrent.ConcurrentHashMap.newKeySet<IdentityId>()

    /**
     * El registro de contactos del escritorio, SEMBRADO desde `contacts.json`.
     *
     * Antes era solo memoria y al reiniciar se perdia todo: el par seguia
     * enviando con una sesion que aqui ya no existia y el frame se descartaba.
     * Ahora la carga ocurre al arrancar; si el fichero no existe se arranca
     * vacio (primera ejecucion), y si esta corrupto se deja constancia con un
     * codigo estable sin borrar nada.
     */
    val contacts: com.km.identity.ContactRegistry = com.km.identity.InMemoryContactRegistry().also { registro ->
        val store = contactStore ?: return@also
        val cargados = store.loadInto(registro)
        if (store.lastError == null) {
            DiagLog.event("[CONTACT]", "contacts.json cargados=$cargados")
        } else {
            // Codigo ESTABLE y sin borrar nada: el operador decide que hacer
            // con un fichero que no verifica.
            DiagLog.event(
                "[CONTACT]",
                "contacts.json NO cargado codigo=${store.lastError} validos=$cargados " +
                    "descartados=${store.skipped} (fichero intacto)",
                DiagLevel.WARN,
            )
        }
    }

    private val intake = com.km.identity.ContactIntake(contacts) { localIdentity() }

    /** La retencion acotada de PREKEY de pares que aun no son contacto. */
    val exchange: com.km.transport.contact.ContactExchangeCoordinator =
        com.km.transport.contact.ContactExchangeCoordinator(
            conocido = { emisor -> registry.find(emisor) != null || contacts.find(emisor) != null },
            // El clasificador del wire lo aporta el cliente: km-transport no
            // importa `com.km.frame` (su guard de separacion lo prohibe).
            esPrekey = { wire -> com.km.frame.PrekeyFrame.esPrekey(wire) },
            // F3 — el sumidero REAL, no `NONE`. Antes un frame de par
            // desconocido se descartaba sin dejar una sola linea, asi que el
            // sintoma "el otro no recibe" no tenia rastro. Va por `[SESSION]`
            // y sin payload: el coordinador nunca recibe el contenido, solo el
            // tamano y el emisor truncado.
            logger = loggerDeIntercambio(),
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
        // F2 — persistir contactos en `contacts.json` en cada registro aceptado
        // (manual e inbound). Sin esto, un reinicio del escritorio perdia el
        // roster y el par quedaba mudo hasta re-escanear.
        contactStore = { contactStore },
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

    /** F3 — el sumidero del intercambio, sobre el canal `[SESSION]` de [DiagLog]. */
    private fun loggerDeIntercambio(): ContactLogger = ContactLogger { level, message ->
        DiagLog.event(
            "[SESSION]",
            message,
            when (level) {
                ContactLogLevel.INFO -> DiagLevel.INFO
                ContactLogLevel.WARN -> DiagLevel.WARN
                ContactLogLevel.ERROR -> DiagLevel.ERROR
            },
        )
    }

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

    /**
     * Lo que la barra y DIAGNOSTICO pueden decir del tier P2P.
     *
     * El medio inicial es `RELAY`: aun no hay enlace P2P establecido, y decir
     * `P2P` al arrancar seria prometer un medio que todavia no existe. El
     * motivo es el del modo (`p2p_off`, `p2p_no_ice`) o `null` si hay
     * configuracion y aun no se intento.
     */
    fun p2pSnapshot(): P2pSnapshot = P2pSnapshot(
        mode = p2pMode.wireName,
        reason = p2p?.modeReason,
        medium = Medium.RELAY.name,
        iceUrls = p2p?.ice?.redactedUrls() ?: emptyList(),
        iceHostOnly = p2p?.ice?.isHostOnly ?: true,
    )

    /** El registro de sesiones. `peers()` de ahi es lo que muestra `status`. */
    val registry: SecureSessionRegistry get() = core.registry

    /** Lo que ha hecho el camino legado de recepcion. Observable, no un log. */
    val legacyInbound: LegacyInboundObservation get() = core.legacyInbound

    fun close() {
        observadoresInstall.forEach { it() }
        observadoresInstall.clear()
        core.core.stop()
        // El medio P2P se cierra ANTES que el motor: sus establecimientos
        // usan el motor para senalizar, y soltar el socket primero los dejaria
        // sin suelo mientras aun creen tener autoridad.
        runCatching { p2p?.close() }
        stack.close()
        peers.clear()
        volcarLogs()
    }

    /**
     * F3 — deja `DiagLog` en `km-logs-*.log` junto al almacen, AL SALIR.
     *
     * ## POR QUE AL CERRAR Y NO EN CADA LINEA
     *
     * Porque el escritorio corre en una terminal que el usuario cierra o corta,
     * y stderr se pierde con ella. Un volcado unico al salir conserva la traza
     * de la sesion sin inventar rotacion ni un escritor de log concurrente. El
     * nombre lleva marca de tiempo (`ExportService.saveLogs`), asi que dos
     * ejecuciones no se pisan.
     *
     * Un fallo de disco NO impide cerrar: se dice por stderr y se sigue.
     */
    private fun volcarLogs() {
        if (!dumpLogOnClose) return
        runCatching {
            val ruta = exportService.saveLogs(diagnostics.exportText())
            System.err.println("logs de la sesion en $ruta")
        }.onFailure {
            System.err.println("no se pudieron volcar los logs: ${it.message}")
        }
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
        // El medio de DATOS se publica como evento y se registra con CODIGO y
        // URL REDACTADA. Ni candidatos, ni SDP, ni credenciales: el puerto P2P
        // nunca las entrega aqui, y este es el unico cableado de pantalla.
        p2p?.onMediumChanged = { peer, medium, reason ->
            // Canal `[RELAY]`: el escritorio tiene OCHO canales fijados por su
            // spec y un noveno seria un canal muerto para los filtros. El
            // prefijo textual distingue el evento del resto del canal.
            DiagLog.event(
                "[RELAY]",
                "medio peer=${DiagLog.truncar(peer.value)} medio=${medium.name} motivo=$reason",
            )
            events.emit(ClientEvent.MediumChanged(peer, medium.name, reason))
        }

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
        // `auto` no se puede resolver sin medir la terminal, pero `--help` no
        // la abre: se declara el valor que se usaria AHORA con la consola y el
        // `TERM` actuales, y se dice que es efectivo.
        val hayConsola = System.console() != null
        val soporte = MouseSupport.hasMouseSupport(System.getenv("TERM"), console = hayConsola, typeDumb = false)
        val ratonAhora = when (options.mouse) {
            MouseMode.OFF -> false
            MouseMode.ON -> options.mode == ShellMode.TUI && soporte
            MouseMode.AUTO -> options.mode == ShellMode.TUI && soporte
        }
        println(DesktopOptions.usage(mouse = ratonAhora))
        return
    }

    // ## EL TIER P2P SE RESUELVE ANTES DE TOCAR NADA
    //
    // `KM_DESKTOP_P2P` se lee de verdad en la ruta de produccion, no solo en un
    // test de flags. La configuracion ICE invalida y el modo `on` sin ICE/TURN
    // son errores de USO (exit 2) y ocurren aqui, antes de abrir el almacen o
    // el terminal: si el operador pidio P2P obligatorio y no puede haberlo, no
    // se arranca en silencio por el relay.
    //
    // Con `off` NO se parsea `KM_ICE_*`: el rollback tiene que ser
    // incondicional, y una variable que no se usa no puede tumbar el arranque.
    val p2pMode = P2pMode.fromEnv()
    val p2pPlan = try {
        P2pWiring.planFromEnv()
    } catch (invalida: IllegalArgumentException) {
        System.err.println("configuracion ICE invalida: ${invalida.message}")
        kotlin.system.exitProcess(2)
    }
    if (p2pPlan is P2pPlan.Fail) {
        System.err.println("${p2pPlan.reason}: ${p2pPlan.detail}")
        kotlin.system.exitProcess(2)
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
    // F2 — `contacts.json` vive junto a `peers.json` (el mismo directorio de
    // configuracion). Se resuelve del PADRE del keystore, no del cwd: un
    // escritorio lanzado desde otro directorio tiene que encontrar los suyos.
    val contactsPath = keystorePath.toAbsolutePath().parent?.resolve(DesktopOptions.CONTACTS_FILE_NAME)
        ?: Paths.get(DesktopOptions.CONTACTS_FILE_NAME)
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
    // `--reset` tambien borra los contactos: un almacen nuevo con el roster del
    // anterior haria que el escritorio intentara hablar con identidades que ya
    // no tienen su clave privada.
    if (options.reset && java.nio.file.Files.isRegularFile(contactsPath)) {
        java.nio.file.Files.delete(contactsPath)
        System.err.println("contactos eliminados: $contactsPath")
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

    // ## EL RATON SE RESUELVE UNA VEZ
    //
    // `auto` = TUI viable + terminal con soporte. `off` nunca activa. `on` sin
    // soporte o sin TUI es un error de USO (exit 2), no un downgrade silencioso:
    // el operador pidio raton y no lo tendria.
    val ratonSoportado = MouseSupport.hasMouseSupport(
        term = System.getenv("TERM"),
        console = System.console() != null,
        typeDumb = terminal.type == Terminal.TYPE_DUMB || terminal.type == Terminal.TYPE_DUMB_COLOR,
    )
    val ratonEfectivo = when (options.mouse) {
        MouseMode.OFF -> false
        MouseMode.ON -> {
            if (!tuiViable || !ratonSoportado) {
                System.err.println(
                    "--mouse=on no es utilizable aqui (" +
                        (tamanoInicial?.let { "${it.columns}x${it.rows}" } ?: terminal.type) +
                        ", TERM=" + (System.getenv("TERM") ?: "sin TERM") +
                        "): usa --mouse=auto u --mouse=off",
                )
                runCatching { terminal.close() }
                kotlin.system.exitProcess(2)
            }
            true
        }
        MouseMode.AUTO -> tuiViable && ratonSoportado
    }

    if (tuiViable) {
        val capability = if (options.themeMono) {
            // `--theme mono` manda sobre el entorno: la capacidad minima no
            // emite ni un SGR y el QR se degrada a texto/.km, como `--ascii`.
            ColorCapability.MONO
        } else {
            ColorCapability.detect(
                attributes = Ansi.enabled && !options.ascii,
                env = System.getenv(),
            )
        }
        val writer = ScreenWriter(
            terminal,
            attributes = Ansi.enabled && !options.ascii,
            capability = capability,
        )
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
                    composeSession(options, keystorePath, peersPath, contactsPath, fuenteFija(passphrase), p2pMode, p2pPlan)
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
            JLineShell(terminal, writer, sesion, options.ascii, ratonEfectivo, options.wheelRows).run()
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

    val fuente = lineModePassphrase(reader, out, keystorePath)
    if (fuente == null) {
        // Ctrl-C/EOF o un camino de identidad que no se pudo completar: no hay
        // sesion que abrir. El terminal se cierra igual que en el resto de salidas.
        terminal.close()
        kotlin.system.exitProcess(1)
    }
    val sesion = try {
        composeSession(options, keystorePath, peersPath, contactsPath, fuente, p2pMode, p2pPlan)
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
    contactsPath: Path,
    source: PassphraseSource,
    p2pMode: P2pMode,
    p2pPlan: P2pPlan,
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
    // La composicion P2P decide si el manager va sobre el motor (`off`) o sobre
    // el router que enruta por el medio del par. En `off` devuelve exactamente
    // el camino de siempre: manager sobre el motor, `transport = relay`.
    val bundle: ClientTransportBundle = P2pWiring.compose(
        relay = transport,
        identity = identity.identityId,
        plan = p2pPlan,
    )
    val stack = bundle.stack
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
        transport = bundle.transport,
        manager = bundle.manager,
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
        p2p = bundle,
        p2pMode = p2pMode,
        // F2 — el roster verificado sobrevive al proceso. La carga ocurre en el
        // constructor de la sesion y siembra el registro de contactos.
        contactStore = ContactStore(contactsPath),
        // F3 — la traza de la sesion queda en `km-logs-*.log` al salir.
        dumpLogOnClose = true,
    )
    session.bootstraps = options.bootstraps
    DiagLog.event(
        "[RELAY]",
        "p2p modo=${p2pMode.wireName} motivo=${bundle.modeReason ?: "sin-intento"} " +
            "ice=${if (bundle.ice.isHostOnly) "host-only" else bundle.ice.redactedUrls().size.toString()} " +
            "p2p=${if (bundle.p2pEnabled) "compuesto" else "no-construido"}",
    )
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
 * Fuente de frase de paso para `--no-tui` / `--accessible`.
 *
 * ## QUE CONSERVA Y QUE AÑADE
 *
 * Con almacen existente, o con `KM_KEYSTORE_PASSPHRASE` puesta, se mantiene el
 * comportamiento de G1 (entorno primero, prompt enmascarado despues). SIN
 * almacen se ofrece la MISMA eleccion que la TUI: crear o recuperar.
 *
 * La restauracion SOBRESCRIBIENDO un almacen existente NO se ofrece en modo
 * lineas: este modo es append-only y para scripts, y una escritura destructiva
 * detras de un prompt sin pantalla se explica peor. Queda declarado en el
 * README; en la TUI `[R]` si esta disponible con almacen.
 */
private fun lineModePassphrase(
    reader: LineReader,
    out: PrintWriter,
    keystorePath: Path,
): PassphraseSource? {
    val hayEntorno = System.getenv(PassphraseSource.ENV_VAR)?.isNotEmpty() == true
    if (hayEntorno || IdentityKeystore.exists(keystorePath)) return jlinePassphraseSource(reader, out)

    out.println("No hay identidad en este equipo. Elige un camino:")
    out.println("  [C] crear una identidad nueva en este equipo")
    out.println("  [R] recuperar desde la frase de recuperacion (24 palabras)")
    out.println("La frase de paso cifra el almacen; la de recuperacion deriva la IDENTIDAD.")
    out.flush()
    val eleccion = runCatching { reader.readLine("opcion [C/R]: ") }.getOrNull()?.trim()?.lowercase()
        ?: return null
    if (eleccion == "c" || eleccion == "crear") {
        // Crear conserva el prompt de G1: frase nueva, dos veces.
        return jlinePassphraseSource(reader, out)
    }
    if (eleccion != "r" && eleccion != "recuperar") {
        out.println(Ansi.red("CHOICE_REQUIRED: escribe C o R"))
        out.flush()
        return null
    }

    var validada: String? = null
    var intentos = 0
    while (validada == null) {
        val frase = runCatching {
            reader.readLine("frase de recuperacion (24 palabras, no se muestra): ", '*', null)
        }.getOrNull() ?: return null
        val codigo = IdentityProvisioner.validate(frase)
        if (codigo == null) {
            validada = frase
        } else {
            // Se queda en el campo: se vuelve a preguntar, sin escribir nada.
            out.println(Ansi.red(codigo))
            out.flush()
            if (++intentos >= MAX_LINE_RECOVERY_ATTEMPTS) return null
        }
    }
    val fraseValida = validada ?: return null

    val nueva = runCatching { reader.readLine("nueva frase de paso: ", '*', null) }.getOrNull()
        ?: return null
    if (nueva.isEmpty()) {
        out.println(Ansi.red("PASSPHRASE_EMPTY"))
        out.flush()
        return null
    }
    val repetida = runCatching { reader.readLine("repita la frase de paso: ", '*', null) }.getOrNull()
        ?: return null
    if (repetida != nueva) {
        out.println(Ansi.red("PASSPHRASE_MISMATCH"))
        out.flush()
        return null
    }

    return when (
        val resultado = IdentityProvisioner.restore(
            path = keystorePath,
            phrase = fraseValida,
            newPassphrase = nueva.toCharArray(),
            confirmation = "",
            // Solo se llega aqui sin almacen: no hay nada que sobrescribir.
            overwriting = false,
        )
    ) {
        is IdentityProvisioner.Outcome.Restored -> {
            out.println("identidad restaurada: " + resultado.identityId.take(16) + "\u2026")
            out.println(
                "solo identidad: contactos y mensajes NO se recuperan; " +
                    "las claves de dispositivo son nuevas (el peer vera un device nuevo)",
            )
            out.flush()
            object : PassphraseSource {
                override fun passphrase(creation: Boolean): String = nueva
            }
        }

        is IdentityProvisioner.Outcome.Rejected -> {
            out.println(Ansi.red("no se pudo restaurar: " + resultado.code))
            out.flush()
            null
        }
    }
}

/** Intentos de frase de recuperacion en modo lineas antes de abandonar. */
private const val MAX_LINE_RECOVERY_ATTEMPTS: Int = 3

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
    /**
     * `--theme mono`: fuerza [com.km.desktop.ui.theme.ColorCapability.MONO].
     *
     * No es "un tema configurable" (fuera de alcance): es la capacidad de color
     * llevada al minimo de forma explicita, para terminales que dicen tener color
     * y lo pintan mal, o para comprobar la jerarquia sin color. `--theme color`
     * (o `auto`, el defecto) deja decidir a `NO_COLOR`/`TERM`/`COLORTERM`.
     */
    val themeMono: Boolean = false,
    /**
     * `--mouse auto|on|off`. `auto` (defecto) activa el tracking solo si la TUI
     * es viable y el terminal tiene soporte; `--accessible`/`--no-tui` lo
     * apagan siempre y `on` con ellos es un error de uso.
     */
    val mouse: MouseMode = MouseMode.AUTO,
    /** `--wheel-rows 1..10`: filas por muesca de rueda (defecto 3). */
    val wheelRows: Int = DEFAULT_WHEEL_ROWS,
) {
    companion object {
        const val RELAY_ENV: String = "KM_RELAY_URL"
        const val RELAY_IDENTITY_ENV: String = "KM_RELAY_IDENTITY"
        const val PEERS_FILE_NAME: String = "peers.json"

        /** F2 — contactos verificados, junto a `peers.json`. */
        const val CONTACTS_FILE_NAME: String = "contacts.json"

        /** Defecto de `--wheel-rows`: tres filas, como una muesca de X11. */
        const val DEFAULT_WHEEL_ROWS: Int = 3

        /** Tope de `--wheel-rows`: mas de diez saltaria la pantalla entera. */
        const val MAX_WHEEL_ROWS: Int = 10

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
            var themeMono = false
            var mouse = MouseMode.AUTO
            var wheelRows = DEFAULT_WHEEL_ROWS
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

            fun filasDeRueda(raw: String): Int {
                val filas = raw.toIntOrNull()
                    ?: throw IllegalArgumentException("--wheel-rows no es un numero: '$raw'")
                require(filas in 1..MAX_WHEEL_ROWS) {
                    "--wheel-rows fuera de rango: se esperan 1..$MAX_WHEEL_ROWS, hay $filas"
                }
                return filas
            }

            fun modoRaton(raw: String): MouseMode = MouseMode.parse(raw)
                ?: throw IllegalArgumentException("--mouse desconocido: se espera auto, on u off")

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
                    // `--theme mono` fuerza la capacidad minima; no es un tema
                    // configurable, es apagar el color aunque el terminal lo
                    // anuncie. `color`/`auto` dejan decidir al entorno.
                    "--theme" -> themeMono = when (
                        (args.getOrNull(++i) ?: throw IllegalArgumentException("--theme sin valor")).lowercase()
                    ) {
                        "mono" -> true
                        "color", "auto" -> false
                        else -> throw IllegalArgumentException("--theme desconocido: se espera color o mono")
                    }
                    // `--mouse` no es un booleano: `auto` se resuelve al
                    // arrancar contra la viabilidad de la TUI y el terminal.
                    "--mouse" -> mouse = modoRaton(
                        args.getOrNull(++i) ?: throw IllegalArgumentException("--mouse sin valor"),
                    )
                    "--wheel-rows" -> wheelRows = filasDeRueda(
                        args.getOrNull(++i) ?: throw IllegalArgumentException("--wheel-rows sin valor"),
                    )
                    "--help", "-h" -> help = true
                    else -> {
                        // La forma `--flag=valor` es la que documenta el README
                        // para `--mouse=off`; aceptarla evita que una opcion
                        // documentada sea "desconocida".
                        val arg = args[i]
                        when {
                            arg.startsWith("--mouse=") -> mouse = modoRaton(arg.substringAfter('='))
                            arg.startsWith("--wheel-rows=") -> wheelRows = filasDeRueda(arg.substringAfter('='))
                            else -> throw IllegalArgumentException("opcion desconocida: $arg")
                        }
                    }
                }
                i++
            }
            val elegido = mode ?: ShellMode.TUI
            // `--mouse=on` con un modo sin direccionamiento de cursor es un
            // error de USO (exit 2), no un "se ignora": el operador cree que
            // tiene raton y no lo tendria.
            require(!(mouse == MouseMode.ON && elegido != ShellMode.TUI)) {
                "--mouse=on exige la TUI: es incompatible con --accessible/--no-tui"
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
                mode = elegido,
                ascii = ascii,
                themeMono = themeMono,
                mouse = mouse,
                wheelRows = wheelRows,
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

        fun usage(mouse: Boolean? = null): String {
            val base = """
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
              --theme <color|mono>  color (defecto, segun NO_COLOR/TERM) o mono
                                     (sin SGR; el QR se degrada a texto/.km)
              --mouse <auto|on|off> raton: clic izquierdo y rueda. Defecto auto:
                                     ON solo con TUI viable y terminal con soporte;
                                     OFF con --accessible/--no-tui. `on` con esos
                                     modos (o sin soporte) es error de uso (exit 2).
                                     El tracking captura el arrastre: la seleccion
                                     nativa se recupera con --mouse=off (Shift+arrastre
                                     es bypass del terminal, no de la app).
              --wheel-rows <1..10>  filas por muesca de rueda (defecto $DEFAULT_WHEEL_ROWS)
              -h, --help            esta ayuda

            Comandos dentro de la sesion: connect, identity, status, send, peer, help, quit.
            Camino de contacto y mensajeria: import, export, announce, session,
            contacts, history, resend, discard (los mismos de `help`).

            FRASE DE PASO y FRASE DE RECUPERACION son distintas:
              - la frase de paso cifra el almacen de ESTE equipo; no deriva la identidad;
              - la frase de recuperacion son 24 palabras BIP39 que derivan la IDENTIDAD
                (la misma que muestra Android). Restaurarla NO recupera contactos ni
                mensajes y REGENERA las claves de dispositivo: el peer vera un device nuevo.
            TUI: sin almacen se elige [C] crear o [R] recuperar antes de pedir nada.
            Con almacen, [R] restaura y sobrescribe: exige escribir SOBRESOBRESCRIBIR.
            Modo lineas (--no-tui/--accessible): ofrece C/R cuando NO hay almacen;
            sobrescribir con R solo esta en la TUI.
            """.trimIndent()
            // El valor EFECTIVO no se puede prometer antes de medir la
            // terminal: cuando el llamador lo conoce (--help con consola), se
            // declara; si no, se omite en vez de inventarlo.
            return if (mouse == null) {
                base
            } else {
                base + "\n\nRaton (valor efectivo ahora): " + (if (mouse) "ON" else "OFF")
            }
        }
    }
}
