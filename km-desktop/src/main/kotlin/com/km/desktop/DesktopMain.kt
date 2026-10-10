package com.km.desktop

import com.km.api.KeyMessageCore
import com.km.api.KeyMessageCoreImpl
import com.km.model.Ack
import com.km.model.IdentityId
import com.km.model.Message
import com.km.model.MessageId
import com.km.model.MessageState
import com.km.protocol.ConnectionState
import org.jline.reader.EndOfFileException
import org.jline.reader.LineReader
import org.jline.reader.LineReaderBuilder
import org.jline.reader.UserInterruptException
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

        val TABLE: List<Pair<String, String>> = listOf(
            "connect [endpoint]" to "conecta al rele; sin argumento usa $DEFAULT",
            "identity" to "muestra identityId y claves publicas de esta sesion",
            "status" to "estado del transporte, sesion y ultimo error",
            "send <identityIdHex> <texto>" to "crea y envia un mensaje a un peer",
            "peer <identityIdHex> <claveBase64>" to "registra la clave de firma de un peer, para poder verificar sus ACK",
            "help" to "esta ayuda",
            "quit" to "desconecta y sale",
        )
    }
}

/**
 * Bucle de lectura.
 *
 * `LineReader` se construye una vez y sobrevive a los errores: una excepcion de
 * una linea se imprime y el bucle sigue. Perder el reader porque un usuario
 * escribio un `to` corto seria peor que el error.
 */
class DesktopTui(
    private val terminal: Terminal,
    private val reader: LineReader,
    private val out: PrintWriter,
    private val session: Session,
) {
    private var running = true

    fun run() {
        banner()
        while (running) {
            out.print(Ansi.prompt())
            out.flush()
            val line = try {
                reader.readLine()
            } catch (_: UserInterruptException) {
                continue
            } catch (_: EndOfFileException) {
                out.println()
                dispatch(Command.Quit)
                continue
            }
            dispatch(Command.parse(line))
        }
    }

    private fun dispatch(command: Command) {
        when (command) {
            is Command.Help -> help()
            is Command.Identity -> identity()
            is Command.Status -> status()
            is Command.Quit -> quit()
            is Command.Connect -> connect(command.endpoint)
            is Command.Send -> send(command)
            is Command.Peer -> peer(command)
            is Command.Unknown -> {
                if (command.input.isNotEmpty()) {
                    out.println(Ansi.red("comando desconocido: '${command.input.take(40)}'"))
                    out.println(Ansi.dim("escribe 'help'"))
                }
            }
        }
    }

    private fun banner() {
        out.println(Ansi.bold("KeyMessage Desktop") + Ansi.dim("  ·  cliente TUI de km-framework"))
        out.println(Ansi.dim("  connect · identity · status · send · help · quit"))
        out.println()
        out.println(Ansi.dim("  almacen de identidad: ${session.keystorePath}"))
        out.println()
    }

    private fun help() {
        out.println(Ansi.bold("Comandos"))
        Command.TABLE.forEach { (name, description) ->
            out.println("  " + Ansi.cyan(name.padEnd(30)) + description)
        }
        out.println()
    }

    private fun identity() {
        val identity = session.identity
        out.println(Ansi.bold("Identidad local"))
        out.println("  identityId   ${identity.identityId.value}")
        out.println("  ed25519      ${fingerprint(identity.ed25519PublicKey)}  (firma)")
        out.println("  x25519       ${fingerprint(identity.x25519PublicKey)}  (acuerdo)")
        out.println("  almacen      ${session.keystorePath}")
        out.println("  sha256       ${session.identityDigest.take(16)}…")
        out.println()
    }

    private fun status() {
        val status = session.status()
        val state = when (status.state) {
            ConnectionState.ONLINE -> Ansi.green(status.state.name)
            ConnectionState.RECONNECTING -> Ansi.yellow(status.state.name)
            ConnectionState.CONNECTING, ConnectionState.AUTHENTICATING -> Ansi.yellow(status.state.name)
            ConnectionState.DISCONNECTED -> Ansi.dim(status.state.name)
        }
        out.println(Ansi.bold("Estado"))
        out.println("  transporte   ${session.transport.name}")
        out.println("  conexion     $state")
        out.println("  endpoint     ${status.endpoint}")
        out.println("  sessionId    ${status.sessionId ?: Ansi.dim("—")}")
        out.println("  rele         ${status.relayIdentityId?.let { it.take(16) + "…" } ?: Ansi.dim("—")}")
        out.println("  reconexion   ${status.reconnectAttempt}  ${status.nextRetryInMs?.let { "(en ${it}ms)" } ?: ""}")
        out.println("  online       ${if (session.transport.isOnline()) Ansi.green("si") else Ansi.dim("no")}")
        status.lastError?.let { out.println("  ultimo error ${Ansi.red(it)}") }
        val online = session.onlinePeers()
        out.println("  peers        ${if (online.isEmpty()) Ansi.dim("ninguno") else online.joinToString { it.value.take(16) + "…" }}")
        // Distinguir "no hay peers en linea" de "no hay claves de firma" importa:
        // lo primero es estado del rele, lo segundo es por que los `ACK` no se
        // verifican. Una linea de estado que no los distinga deja al usuario
        // buscando en el sitio equivocado.
        val keys = session.peerKeys.known()
        out.println(
            "  claves       " + when {
                keys.isEmpty() -> Ansi.yellow("ninguna: los ACK entrantes NO se podran autenticar")
                keys.size == online.size && keys.isNotEmpty() -> Ansi.green("${keys.size} de los peers en linea")
                else -> Ansi.dim("${keys.size} registradas")
            }
        )
        out.println()
    }

    private fun connect(endpoint: String?) {
        val target = endpoint ?: JvmRelayTransport.DEFAULT_ENDPOINT
        out.println("conectando a ${Ansi.cyan(target)} …")
        val result = session.transport.connect()
        if (result.isSuccess) {
            out.println(Ansi.green("conectado") + "  sesion ${session.status().sessionId ?: "—"}")
        } else {
            out.println(Ansi.red("no se pudo conectar") + ": ${result.exceptionOrNull()?.message}")
        }
        out.println()
    }

    private fun send(command: Command.Send) {
        val core: KeyMessageCore = session.core
        core.createMessage(command.to, command.text.toByteArray(Charsets.UTF_8))
            .mapCatching { core.sendMessage(it).getOrThrow() }
            .onSuccess { result ->
                out.println(
                    Ansi.green("enviado") + " ${result.messageId.value.toString().take(8)}… " +
                        Ansi.dim("estado=${result.state}")
                )
            }
            .onFailure { out.println(Ansi.red("no se pudo enviar") + ": ${it.message}") }
        out.println()
    }

    /**
     * Registra la clave de firma de un peer.
     *
     * Sin esto, `AckManagerImpl` recibe la clave por defecto del core —`{ null }`—
     * y descarta todos los `ACK` sin verificar: llegan, y el emisor no se entera.
     * El error que se reporta es el de [PeerKeyDirectory], no uno generico de
     * "comando mal", porque lo que se rechaza no es la sintaxis: es una clave que
     * no deriva la identidad a la que se quiere asignar.
     */
    private fun peer(command: Command.Peer) {
        val problem = session.peerKeys.registerEncoded(command.identityIdHex, command.publicKeyBase64)
        if (problem == null) {
            session.peerKeys.save()
            out.println(Ansi.green("peer registrado") + " ${command.identityIdHex.take(16)}…")
        } else {
            out.println(Ansi.red("no se pudo registrar el peer") + ": $problem")
        }
        out.println()
    }

    private fun quit() {
        running = false
        session.close()
        out.println("hasta luego")
    }

    /** Huella corta de una clave publica: SHA-256 en hex, los primeros 16 nibbles. */
    private fun fingerprint(publicKey: ByteArray): String {
        val digest = java.security.MessageDigest.getInstance("SHA-256").digest(publicKey)
        return digest.take(FINGERPRINT_BYTES).joinToString("") { "%02x".format(it) }
    }

    companion object {
        private const val FINGERPRINT_BYTES = 8
    }
}

/**
 * Estado vivo de la sesion: identidad, transporte, core y peers vistos.
 *
 * Existe para que la TUI no tenga ningun campo mutable propio, y para que los
 * handlers del core, que llegan desde otros hilos, escriban SIEMPRE en el mismo
 * objeto que imprime la pantalla.
 */
class Session(
    val identity: DesktopIdentity,
    val keystorePath: Path,
    val transport: JvmRelayTransport,
    val core: KeyMessageCore,
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
) {
    private val peers = java.util.concurrent.ConcurrentHashMap.newKeySet<IdentityId>()

    /** SHA-256 del `identityId` en hex: la huella corta que muestra `identity`. */
    val identityDigest: String =
        java.security.MessageDigest.getInstance("SHA-256")
            .digest(identity.identityId.value.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    fun onlinePeers(): Set<IdentityId> = peers.toSet()

    /** Estado del transporte. La TUI no lo interroga por dentro. */
    fun status(): RelayStatus = transport.status()

    fun close() {
        core.stop()
        transport.close()
        peers.clear()
    }

    fun bind(emit: (String) -> Unit) {
        transport.onStateChange { state -> emit("conexion: ${state.name}") }
        transport.onPeerOnline { peer ->
            peers.add(peer)
            emit("peer en linea: ${peer.value.take(16)}…")
        }
        transport.onPeerOffline { peer ->
            peers.remove(peer)
            emit("peer fuera de linea: ${peer.value.take(16)}…")
        }
        transport.setStoredHandler { receipt ->
            emit("STORED: el rele guardo ${receipt.originalMessageId.value.toString().take(8)}… hasta ${receipt.expiresAt}")
        }
        transport.setRelayExpiredHandler { notice ->
            emit("RELAY_EXPIRED: ${notice.originalMessageId.value.toString().take(8)}… (${notice.reason})")
        }
        core.registerMessageHandler { message: Message -> emit("mensaje de ${message.from.value.take(8)}… (${message.payload.size}B)") }
        core.registerAckHandler { ack: Ack -> emit("ACK ${ack.status} de ${ack.from.value.take(8)}…") }
        core.registerStateChangeHandler { id: MessageId, state: MessageState -> emit("mensaje ${id.value.toString().take(8)}… -> ${state.name}") }
    }
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

    val keystorePath = options.keystorePath ?: runCatching { IdentityKeystore.defaultPath() }.getOrElse {
        System.err.println(it.message)
        kotlin.system.exitProcess(2)
    }
    // El directorio de peers se resuelve DESPUES del almacen, porque su
    // defecto es "junto a el". Resolverlo antes obligaria a repetir el
    // `defaultPath()` y las dos rutas podrian divergir.
    val peersPath = options.peersPath
        ?: keystorePath.toAbsolutePath().parent?.resolve(DesktopOptions.PEERS_FILE_NAME)
        ?: Paths.get(DesktopOptions.PEERS_FILE_NAME)
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
    Ansi.detect(stdoutTerminal = System.console() != null, env = System.getenv())

    val reader = runCatching { LineReaderBuilder.builder().terminal(terminal).build() }.getOrElse {
        System.err.println("no se pudo iniciar el lector de lineas: ${it.message}")
        kotlin.system.exitProcess(1)
    }

    val identity = try {
        IdentityKeystore.loadOrCreate(keystorePath, jlinePassphraseSource(reader, out))
    } catch (e: KeystoreException) {
        out.println(Ansi.red(e.message ?: "no se pudo abrir el almacen"))
        out.flush()
        terminal.close()
        kotlin.system.exitProcess(1)
    }

    val transport = JvmRelayTransport(
        identity = identity.identityId,
        ed25519PublicKey = identity.ed25519PublicKey,
        ed25519PrivateKey = identity.ed25519PrivateKey,
        endpoint = options.endpoint,
        relayIdentity = options.relayIdentity,
        log = { message -> if (options.verbose) System.err.println("[relay] $message") },
    )

    // F2/D3, la pieza que faltaba: sin esto el core recibe el valor por defecto
    // `{ null }`, `AckManagerImpl` no puede verificar ninguna firma y devuelve
    // `success` sin procesar. Cada `ACK` que llega —autentico o no— se pierde en
    // silencio, y el emisor no ve confirmarse nunca un mensaje.
    //
    // La clave de cada peer sale del directorio, y no se deriva ni se adivina:
    // una clave inventada autentica contra la identidad equivocada, que es peor
    // que no autenticar nada.
    val peers = PeerKeyDirectory(peersPath).load()
    if (peers.known().isEmpty()) {
        // Se avisa en stderr y no en la pantalla: la TUI todavia no ha escrito
        // su banner cuando se construye la sesion.
        System.err.println(
            "sin claves de peers registradas ($peersPath); los ACK entrantes no se podran autenticar. " +
                "usa 'peer <identityIdHex> <claveBase64>'"
        )
    }

    val core = KeyMessageCoreImpl(
        localIdentity = identity.identityId,
        localPrivateKey = identity.ed25519PrivateKey,
        transport = transport,
        peerPublicKeyResolver = peers.resolver(),
    )

    val session = Session(identity, keystorePath, transport, core, peers)
    session.bind { message -> out.println(Ansi.dim(message)) }

    val tui = DesktopTui(terminal, reader, out, session)
    try {
        tui.run()
    } finally {
        session.close()
        terminal.close()
    }
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
    val endpoint: String,
    val relayIdentity: IdentityId?,
    val keystorePath: Path?,
    val peersPath: Path?,
    val reset: Boolean,
    val verbose: Boolean,
    val help: Boolean,
) {
    companion object {
        const val RELAY_ENV: String = "KM_RELAY_URL"
        const val RELAY_IDENTITY_ENV: String = "KM_RELAY_IDENTITY"
        const val PEERS_FILE_NAME: String = "peers.json"

        fun parse(args: Array<String>): DesktopOptions {
            var endpoint = System.getenv(RELAY_ENV)?.takeIf { it.isNotEmpty() }
                ?: JvmRelayTransport.DEFAULT_ENDPOINT
            var relayIdentity = System.getenv(RELAY_IDENTITY_ENV)?.takeIf { it.isNotEmpty() }?.let { raw ->
                requireIdentity(raw, RELAY_IDENTITY_ENV)
            }
            var keystore: Path? = null
            var peers: Path? = null
            var reset = false
            var verbose = false
            var help = false
            var i = 0
            while (i < args.size) {
                when (args[i]) {
                    "--relay" -> endpoint = args.getOrNull(++i) ?: throw IllegalArgumentException("--relay sin valor")
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
                    "--help", "-h" -> help = true
                    else -> throw IllegalArgumentException("opcion desconocida: ${args[i]}")
                }
                i++
            }
            return DesktopOptions(endpoint, relayIdentity, keystore, peers, reset, verbose, help)
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
              --relay-identity <id> identityId del rele, 64 hex, que se anuncia en
                                     el AUTH_REQUEST (tambien $RELAY_IDENTITY_ENV).
                                     Sin ella el campo se omite y se anuncia el
                                     que venga en el AUTH_CHALLENGE.
              --keystore <ruta>     almacen de identidad
                                     (por defecto XDG: ~/.config/keymessage/${IdentityKeystore.IDENTITY_FILE_NAME})
              --peers <ruta>        directorio de claves de firma de los peers,
                                     del que sale el resolver que autentica los ACK
                                     (por defecto, junto al almacen: $PEERS_FILE_NAME)
              --reset               borra el almacen y el directorio de peers antes de arrancar
              --verbose             traza del transporte por stderr
              -h, --help            esta ayuda

            Comandos dentro de la sesion: connect, identity, status, send, peer, help, quit.
        """.trimIndent()
    }
}
