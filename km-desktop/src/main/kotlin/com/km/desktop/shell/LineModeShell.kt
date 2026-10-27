package com.km.desktop.shell

import com.km.desktop.Ansi
import com.km.desktop.Command
import com.km.desktop.Session
import com.km.desktop.client.ChatService
import com.km.desktop.client.ClientEvent
import com.km.desktop.net.BootstrapAllFailed
import com.km.desktop.net.EndpointSequence
import com.km.identity.ContactImportException
import com.km.model.IdentityId
import com.km.model.MessageState
import com.km.protocol.ConnectionState
import com.km.transport.contact.ContactExchangeException
import org.jline.reader.EndOfFileException
import org.jline.reader.LineReader
import org.jline.reader.UserInterruptException
import org.jline.terminal.Terminal
import java.io.PrintWriter
import java.nio.file.Paths

/**
 * Bucle de lectura.
 *
 * `LineReader` se construye una vez y sobrevive a los errores: una excepcion de
 * una linea se imprime y el bucle sigue. Perder el reader porque un usuario
 * escribio un `to` corto seria peor que el error.
 */
class LineModeShell(
    private val terminal: Terminal,
    private val reader: LineReader,
    private val out: PrintWriter,
    private val session: Session,
) {
    private var running = true

    fun run() {
        // UN SOLO ESCRITOR: los handlers publican eventos y este bucle los pinta.
        // El binding se registra antes del banner para no perder lo que ocurra
        // durante el arranque de la pila.
        session.events.subscribe { event -> render(event) }
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
            is Command.Import -> importar(command.arg)
            is Command.Export -> exportar(command.path)
            is Command.Announce -> anunciar(command.to)
            is Command.SessionCmd -> abrirSesion(command.to)
            is Command.Contacts -> listarContactos()
            is Command.History -> historial(command.peer)
            is Command.Resend -> runCatching { session.chatService.resend(command.messageId).getOrThrow() }
                .onSuccess { out.println(Ansi.green("reintento aceptado") + " ${command.messageId.value}") }
                .onFailure { out.println(Ansi.red("reintento rechazado") + ": ${it.message}") }
                .also { out.println() }
            is Command.Discard -> runCatching { session.chatService.discard(command.messageId).getOrThrow() }
                .onSuccess { out.println(Ansi.green("descartado") + " ${command.messageId.value}") }
                .onFailure { out.println(Ansi.red("descarte rechazado") + ": ${it.message}") }
                .also { out.println() }
            is Command.Unknown -> {
                if (command.input.isNotEmpty()) {
                    out.println(Ansi.red("comando desconocido: '${command.input.take(40)}'"))
                    out.println(Ansi.dim("escribe 'help'"))
                }
            }
        }
    }

    private fun banner() {
        out.println(Ansi.bold("KeyMessage Desktop") + Ansi.dim("  ·  cliente de km-framework"))
        out.println(
            Ansi.dim("  connect · identity · status · import · export · announce · session") + "\n" +
                Ansi.dim("  contacts · history · send · peer · help · quit"),
        )
        out.println()
        out.println(Ansi.dim("  almacen de identidad: ${session.keystorePath}"))
        // Se declara ANTES de que el usuario meta nada: contactos y mensajes
        // viven en memoria y el rele NO guarda. Descubrirlo al reiniciar seria
        // vender una persistencia que no existe.
        out.println(Ansi.yellow("  contactos y mensajes SOLO EN MEMORIA: se pierden al reiniciar; sin store-and-forward"))
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
        when (val resultado = session.chatService.send(command.to, command.text)) {
            is ChatService.SendOutcome.Accepted -> {
                // `SENT` significa OUT: el socket local acepto los bytes. NO es
                // entrega. La etiqueta lo dice y no se adorna.
                val etiqueta = if (resultado.state == MessageState.SENT) {
                    Ansi.green(resultado.label) + " " + Ansi.dim("(cifrado, sin ACK todavia)")
                } else {
                    Ansi.cyan(resultado.label)
                }
                out.println(
                    "${resultado.messageId.value.toString().take(8)}... $etiqueta " +
                        Ansi.dim("estado=${resultado.state.name}"),
                )
            }
            is ChatService.SendOutcome.Failed -> {
                val sinSesion = !session.sessionPeers().contains(command.to)
                val etiqueta = if (sinSesion) {
                    Ansi.red("NO ENVIADO: no hay sesion cifrada con ${command.to.value.take(16)}...")
                } else {
                    Ansi.red("NO ENVIADO: ${resultado.code} ${resultado.detail}")
                }
                out.println(etiqueta)
            }
        }
        out.println()
    }

    // ------------------------------------------------------------------
    // Contactos, sesion y mensajes
    // ------------------------------------------------------------------

    private fun importar(arg: String) {
        if (arg.isEmpty()) {
            out.println(Ansi.red("falta el portador") + "  usa 'import @ruta.km' o pega el texto")
            out.println()
            return
        }
        val resultado = runCatching {
            if (arg.startsWith("@")) {
                val ruta = Paths.get(arg.substring(1).trim())
                session.contactService.importFile(ruta)
            } else {
                session.contactService.importText(arg)
            }
        }
        resultado.onSuccess { contacto ->
            out.println(
                Ansi.green("contacto verificado") + " " + contacto.fingerprint +
                    "  " + Ansi.dim("(EN MEMORIA: se pierde al reiniciar)"),
            )
            out.println(Ansi.dim("  announce ${contacto.identityRoot.value}") + " para publicar tu bundle")
        }.onFailure { fallo ->
            val codigo = (fallo as? ContactImportException)?.code ?: "IMPORT_FAILED"
            out.println(Ansi.red("RECHAZADO $codigo") + ": ${fallo.message}")
        }
        out.println()
    }

    private fun exportar(ruta: String?) {
        val destino = ruta?.let { Paths.get(it) }
            ?: session.keystorePath.toAbsolutePath().parent?.resolve(OWN_BUNDLE_FILE)
            ?: Paths.get(OWN_BUNDLE_FILE)
        runCatching { session.contactService.exportToFile(destino) }
            .onSuccess { out.println(Ansi.green("bundle propio escrito") + " $it " + Ansi.dim("(0 OPK)")) }
            .onFailure { out.println(Ansi.red("no se pudo exportar") + ": ${it.message}") }
        out.println()
    }

    private fun anunciar(to: IdentityId) {
        val contacto = session.contactService.find(to)
        if (contacto == null) {
            out.println(Ansi.red("no es un contacto importado") + " ${to.value.take(16)}...")
            out.println()
            return
        }
        out.println("anunciando a ${Ansi.cyan(contacto.fingerprint)} (SIGNAL -> Delivered -> PREKEY) ...")
        val resultado = runCatching {
            kotlinx.coroutines.runBlocking { session.contactService.announce(contacto).getOrThrow() }
        }
        resultado.onSuccess {
            out.println(Ansi.green("sesion establecida") + " el PREKEY se entrego tras Delivered")
        }.onFailure { fallo ->
            val codigo = (fallo as? ContactExchangeException)?.code ?: "CONTACT_EXCHANGE_FAILED"
            out.println(
                Ansi.red("SIN SESION: $codigo") +
                    Ansi.dim("  no se emitio PREKEY (el rele no guarda mensajes)"),
            )
        }
        out.println()
    }

    private fun abrirSesion(to: IdentityId) {
        val contacto = session.contactService.find(to)
        if (contacto == null) {
            out.println(Ansi.red("no es un contacto importado") + " ${to.value.take(16)}...")
            out.println()
            return
        }
        runCatching { session.contactService.openAndDeliver(contacto).getOrThrow() }
            .onSuccess { out.println(Ansi.green("sesion abierta") + " PREKEY entregado") }
            .onFailure { out.println(Ansi.red("no se pudo abrir sesion") + ": ${it.message}") }
        out.println()
    }

    private fun listarContactos() {
        val contactos = session.contactService.all()
        out.println(Ansi.bold("Contactos"))
        if (contactos.isEmpty()) {
            out.println("  " + Ansi.dim("ninguno: 'import @ruta.km'"))
        } else {
            contactos.forEach { contacto ->
                val sesion = if (session.sessionPeers().contains(contacto.identityRoot)) "S" else "-"
                val nombre = contacto.displayName.ifEmpty { contacto.fingerprint }
                out.println("  $nombre  " + Ansi.dim("raiz=${contacto.identityRoot.value.take(16)}... sesion=$sesion"))
            }
        }
        out.println("  " + Ansi.yellow("SOLO EN MEMORIA: se pierden al reiniciar"))
        out.println()
    }

    private fun historial(peer: IdentityId) {
        val mensajes = session.chatService.history(peer)
        out.println(Ansi.bold("Historial") + " ${peer.value.take(16)}...")
        if (mensajes.isEmpty()) {
            out.println("  " + Ansi.dim("sin mensajes (la cola vive en memoria)"))
        } else {
            mensajes.forEach { mensaje ->
                val estado = runCatching { session.chatService.stateOf(mensaje.messageId) }.getOrNull()
                out.println(
                    "  ${mensaje.messageId.value.toString().take(8)}... " +
                        "de ${mensaje.from.value.take(8)}... " +
                        Ansi.dim("${mensaje.payload.size}B estado=${estado?.name ?: "?"}"),
                )
            }
        }
        out.println()
    }

    /**
     * Pinta un [ClientEvent]. Es el UNICO sitio de la TUI que escribe por eventos.
     *
     * La presencia del par se pinta como "presente en el rele", nunca como
     * ONLINE: estar en el rele no es estar disponible ni haber entregado nada.
     */
    private fun render(event: ClientEvent) {
        val linea = when (event) {
            is ClientEvent.Info -> Ansi.dim(event.text)
            is ClientEvent.Warn -> Ansi.yellow(event.text)
            is ClientEvent.Error -> Ansi.red(event.text)
            is ClientEvent.ContactImported ->
                Ansi.green(if (event.isNew) "contacto importado " else "contacto conocido ") +
                    event.contact.fingerprint
            is ClientEvent.OwnBundleExported ->
                Ansi.dim("bundle propio ${event.exported.byteLength} B opk=${event.exported.oneTimePreKeyCount}")
            is ClientEvent.Announced -> Ansi.green("anunciado a ") + event.peer.value.take(16) + "..."
            is ClientEvent.SessionOpened -> Ansi.green("sesion cifrada ") + event.peer.value.take(16) + "..."
            is ClientEvent.MessageReceived ->
                Ansi.cyan("mensaje de ") + event.message.from.value.take(8) + "... " +
                    Ansi.dim("(${event.message.payload.size}B)")
            is ClientEvent.AckReceived ->
                Ansi.green("ACK ${event.ack.status} ") + event.ack.from.value.take(8) + "..."
            is ClientEvent.MessageStateChanged ->
                Ansi.dim("mensaje ${event.messageId.value.toString().take(8)}... -> ${event.state.name}")
            is ClientEvent.TransportState -> Ansi.dim("conexion: ${event.state}")
            is ClientEvent.PeerPresence ->
                Ansi.dim(
                    "peer ${if (event.online) "presente en el rele" else "ausente del rele"}: " +
                        event.peer.value.take(16) + "...",
                )
        }
        out.println(linea)
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

        /** Nombre por defecto del `.km` propio, junto al almacen de identidad. */
        const val OWN_BUNDLE_FILE: String = "own.km"
    }
}
