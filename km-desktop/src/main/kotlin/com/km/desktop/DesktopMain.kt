package com.km.desktop

import com.km.api.KeyMessageCore
import com.km.desktop.config.BootstrapConfig
import com.km.desktop.core.CoreFactory
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
import com.km.desktop.net.BootstrapAllFailed
import com.km.desktop.net.EndpointSequence
import com.km.desktop.net.RelayStackFactory
import com.km.transport.JvmRelayTransport
import com.km.transport.RelayClientStack
import com.km.transport.RelayDataCodec
import com.km.transport.RelayStatus
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
        // Sesiones CIFRADAS, que es lo que decide si un `send` sale. Se imprime
        // junto a los peers en linea y NO en la misma linea: "esta en linea" y
        // "puedo escribirle" son dos hechos distintos, y una linea que los
        // mezcla deja al usuario escribiendo a alguien a quien el escritorio le
        // va a rechazar el mensaje.
        val conSesion = session.sessionPeers()
        out.println(
            "  sesiones    " + when {
                conSesion.isEmpty() -> Ansi.yellow("ninguna: 'send' se RECHAZARA hasta establecer sesion")
                else -> Ansi.green("${conSesion.size} con sesion cifrada")
            }
        )
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

    /**
     * Conectar, recorriendo los bootstraps EN ORDEN.
     *
     * Sin argumento, recorre la lista configurada. Con un endpoint explicito,
     * prueba solo ese: un operador que escribe `connect wss://x` ha pedido x, y
     * caer de vuelta a la lista seria una arquitectura distinta de la que pide.
     *
     * El FAILOVER no es una excepcion: `EndpointSequence` prueba el siguiente
     * cuando uno falla, y solo lanza `BootstrapAllFailed` cuando se han probado
     * todos. Ese es el unico caso que se imprime como error, y dice cuales.
     */
    private fun connect(endpoint: String?) {
        val objetivo = endpoint?.let { listOf(it) } ?: session.bootstraps
        out.println("conectando a ${Ansi.cyan(objetivo.joinToString(" → "))} …")
        val fallo = runCatching {
            EndpointSequence(objetivo) { target ->
                session.switchEndpoint(target).getOrThrow()
            }.recorrer()
        }.exceptionOrNull()
        if (fallo == null) {
            out.println(Ansi.green("conectado") + "  sesion ${session.status().sessionId ?: "—"}")
        } else if (fallo is BootstrapAllFailed) {
            out.println(Ansi.red("ningun bootstrap respondio") + ": ${fallo.tried.joinToString(", ")}")
        } else {
            out.println(Ansi.red("no se pudo conectar") + ": ${fallo.message}")
        }
        out.println()
    }

    /**
     * Enviar, por el camino CIFRADO y solo por ese.
     *
     * Lo que se imprime distingue tres casos que antes se confundian en un solo
     * "no se pudo enviar":
     *
     *  - `SENT` — salio por el backend como `SecureFrame`.
     *  - `FAILED` con `NO_SESSION` — no hay sesion con ese par, y el mensaje NO
     *    salio. No es un reintento pendiente: es un rechazo.
     *  - otro `FAILED` — fallo de transporte o de cifrado, distinto del anterior.
     *
     * El caso que no admite matiz es el segundo: si el texto dijera "pendiente"
     * o "encolado", el usuario creeria que su mensaje saldra, y no saldra nunca
     * por esa via.
     */
    private fun send(command: Command.Send) {
        val core: KeyMessageCore = session.core.core
        core.createMessage(command.to, command.text.toByteArray(Charsets.UTF_8))
            .mapCatching { core.sendMessage(it).getOrThrow() }
            .onSuccess { result ->
                DiagLog.messageSent(
                    messageId = result.messageId.value.toString(),
                    peerId = command.to.value,
                    resultado = result.state.name,
                    failureCode = result.failureCode?.name,
                )
                if (result.state == MessageState.SENT) {
                    out.println(
                        Ansi.green("enviado") + " ${result.messageId.value.toString().take(8)}… " +
                            Ansi.dim("estado=${result.state} · cifrado")
                    )
                } else {
                    val sinSesion = !session.sessionPeers().contains(command.to)
                    val motivo = result.failureCode?.name ?: "sin motivo declarado"
                    val etiqueta = if (sinSesion) {
                        Ansi.red("NO ENVIADO: no hay sesion cifrada con ${command.to.value.take(16)}…")
                    } else {
                        Ansi.red("NO ENVIADO: $motivo")
                    }
                    out.println(etiqueta + " " + Ansi.dim("(estado=${result.state})"))
                }
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
) {
    private val peers = java.util.concurrent.ConcurrentHashMap.newKeySet<IdentityId>()

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

    fun bind(emit: (String) -> Unit) {
        // El camino LEGADO se da de alta EXPLICITAMENTE y una sola vez.
        //
        // Sin esto, cablear las dos caras deja el camino en claro sin nada: el
        // motor entrega lo opaco por `onDataReceived` (que alimenta al
        // `PeerTransportManager` y de ahi a las sesiones cifradas) y lo
        // clasificado por `onLegacyInboundFrame`, que sin compositor no
        // entrega nada. El resultado se parece muchisimo a "funciona": el
        // cifrado entra, y el legado desaparece sin decir por que.
        //
        // El `false` de un segundo `attachLegacy` es un RECHAZO, no un no-op
        // silencioso: dos conductores sobre el mismo slot harian que cada
        // frame se entregase DOS veces por el camino en claro.
        val montado = stack.attachLegacy()
        emit(
            if (montado) "camino legado: dado de alta"
            else "camino legado: ya estaba dado de alta (no se duplica)",
        )

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
            stack.driverFor(sesion.peerContext)
            DiagLog.event("[SESSION]", "instalada peer=${DiagLog.truncar(sesion.remotePeerId.value)}")
            emit("sesion cifrada con ${sesion.remotePeerId.value.take(16)}…")
        }

        val motor = core.core
        motor.registerMessageHandler { message: Message ->
            DiagLog.messageReceived(message.messageId.value.toString(), message.from.value)
            emit("mensaje de ${message.from.value.take(8)}… (${message.payload.size}B)")
        }
        motor.registerAckHandler { ack: Ack -> emit("ACK ${ack.status} de ${ack.from.value.take(8)}…") }
        motor.registerStateChangeHandler { id: MessageId, state: MessageState -> emit("mensaje ${id.value.toString().take(8)}… -> ${state.name}") }

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
        log = { message -> if (options.verbose) DiagLog.event("[RELAY]", message) },
    )

    // La pila del rele, NO el motor suelto: motor + manager (con ESE motor por
    // backend) + drivers. Se compone con `RelayStackFactory` para que el
    // escritorio no tenga que recordar el orden de las piezas ni pueda montar
    // un manager con otro backend por debajo, que es el fallo silencioso que
    // `RelayClientStack.compose` rechaza.
    val stack = RelayStackFactory.stackFor(transport)

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

    val core = CoreFactory().create(
        localIdentity = identity.identityId,
        localPrivateKey = identity.ed25519PrivateKey,
        transport = transport,
        // El manager de la PILA, no uno propio: el cable de recepcion de los
        // `SecureFrame` tiene que estar en el mismo motor por el que salen los
        // bytes, o el envio pareceria funcionar y no llegaria.
        manager = stack.manager,
        peerPublicKeyResolver = peers.resolver(),
    )

    DiagLog.event("[AUTH]", "identidad cargada desde el almacen")
    DiagLog.event("[CONTACT]", "directorio de peers leido: ${peers.known().size}")
    DiagLog.integrity("arranque", ok = true)

    val session = Session(identity, keystorePath, stack, core, peers)
    // Los bootstraps configurados los fija la sesion: son una decision de
    // arranque, no de conexion, y `connect` los recorre en orden.
    session.bootstraps = options.bootstraps
    DiagLog.event("[BOOTSTRAP]", "origen=linea-o-entorno destinos=${options.bootstraps.size}")
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
            var i = 0
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
