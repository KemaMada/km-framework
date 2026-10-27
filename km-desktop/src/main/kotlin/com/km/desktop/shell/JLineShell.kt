package com.km.desktop.shell

import com.km.desktop.Session
import com.km.desktop.client.ClientEvent
import com.km.desktop.net.BootstrapAllFailed
import com.km.desktop.net.EndpointSequence
import com.km.desktop.ui.EffectResult
import com.km.desktop.ui.Key
import com.km.desktop.ui.KeyStroke
import com.km.desktop.ui.LinkState
import com.km.desktop.ui.Screen
import com.km.desktop.ui.ShellAction
import com.km.desktop.ui.ShellEffect
import com.km.desktop.ui.ShellReducer
import com.km.desktop.ui.ShellRenderer
import com.km.desktop.ui.ShellState
import com.km.desktop.ui.Size
import com.km.desktop.ui.StatusLine
import org.jline.terminal.Terminal
import org.jline.utils.NonBlockingReader
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executors

/**
 * El bucle de la TUI de pantallas: UN escritor, UN hilo de estado.
 *
 * ## EL MODELO DE HILOS
 *
 * - El hilo del bucle (el que llamo a [run]) es el UNICO que reduce y el UNICO
 *   que escribe. Recibe teclas, medidas y eventos ya encolados.
 * - Los handlers del core/transporte publican [ClientEvent] y aqui se ENCOLAN
 *   como [ShellAction.Event]. Nunca escriben.
 * - Un unico hilo de trabajo ejecuta los [ShellEffect] (IO) y devuelve
 *   [EffectResult]; un solo hilo preserva el orden de los efectos y evita dos
 *   importaciones a la vez.
 *
 * ## QUE PASA AL SALIR
 *
 * `finally` desuscribe, apaga el worker, restaura los atributos del terminal y
 * sale de la pantalla alternativa. El shutdown hook cubre SIGINT/SIGTERM y una
 * excepcion no capturada: el terminal se restaura aunque el bucle no termine
 * solo.
 *
 * ## ARRANQUE
 *
 * La identidad YA esta abierta cuando este bucle empieza (`UnlockGate` la abrio
 * antes de tocar la red). Aqui se piden los contactos y se lanza la conexion en
 * el worker: `connect()` puede tardar hasta 20 s y bloquear la pintura seria
 * dejar el teclado muerto justo al arrancar.
 */
class JLineShell(
    private val terminal: Terminal,
    private val writer: ScreenWriter,
    private val session: Session,
    /** `--ascii`: cajas ASCII y sin QR. */
    private val ascii: Boolean,
) {

    private val inbox = ConcurrentLinkedQueue<ShellAction>()

    private val worker = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "km-tui-effects").apply { isDaemon = true }
    }

    private val effects = ShellEffects(
        session = session,
        copy = { texto -> writer.copy(texto) },
        warn = { texto -> inbox += ShellAction.Event(ClientEvent.Warn(texto)) },
    )

    @Volatile
    private var running = true

    @Volatile
    private var restored = false

    private var state: ShellState = initialState()
    private var lastScreen: Screen = state.screen

    fun run() {
        val reader = terminal.reader()
        val anterior = runCatching { terminal.enterRawMode() }.getOrNull()
        val hook = Thread({ writer.exit() }, "km-tui-restore")
        var baja: (() -> Unit)? = null
        runCatching { Runtime.getRuntime().addShutdownHook(hook) }
        runCatching {
            writer.enter()
            baja = session.events.subscribe { evento -> inbox += ShellAction.Event(evento) }
            // Los efectos iniciales (cargar contactos) y la conexion, fuera del
            // hilo de pintura.
            apply(ShellReducer.reduce(state, ShellAction.Resize(writer.size())))
            ShellReducer.initialEffects().forEach(::launch)
            connectLater()

            while (running) {
                drain()
                if (!running) break
                if (restored) break
                syncCounters()
                checkResize()
                paint()
                val code = reader.read(READ_TIMEOUT_MS)
                if (code == NonBlockingReader.EOF) {
                    // EOF restaura el terminal y sale: un proceso colgado en un
                    // pipe cerrado no es una TUI.
                    running = false
                    continue
                }
                if (code < 0) continue
                val stroke = if (code == ESC) readEscape(reader) else KeyCodec.decode(code)
                if (stroke != null) inbox += ShellAction.Key(stroke)
            }
        }.onFailure { fallo ->
            // Un fallo del bucle NO puede dejar el terminal roto: se restaura
            // antes de propagar.
            writer.exit()
            writer.plain("la TUI se detuvo: " + (fallo.message ?: fallo::class.simpleName))
        }
        writer.exit()
        baja?.invoke()
        runCatching { Runtime.getRuntime().removeShutdownHook(hook) }
        anterior?.let { runCatching { terminal.setAttributes(it) } }
        worker.shutdownNow()
        if (restored) {
            // La identidad del proceso ya NO es la del almacen: seguir usando la
            // vieja seria enviar con claves que el usuario cree sustituidas.
            writer.plain(
                "identidad restaurada en " + session.keystorePath +
                    "\nreinicia km-desktop para usarla: este proceso sigue con la anterior",
            )
        }
    }

    // ------------------------------------------------------------------
    // Estado
    // ------------------------------------------------------------------

    private fun initialState(): ShellState {
        val status = session.status()
        return ShellState(
            viewport = writer.size(),
            status = StatusLine(
                link = LinkState.of(status.state.name),
                endpoint = session.bootstraps.firstOrNull() ?: status.endpoint,
                relay = status.relayIdentityId,
                attempt = status.reconnectAttempt,
                nextRetryInMs = status.nextRetryInMs,
                lastError = status.lastError,
                localFingerprint = session.identity.identityId.value.take(16) + "\u2026",
                localIdentityId = session.identity.identityId.value,
                sessions = session.sessionPeers().size,
                ackKeys = session.peerKeys.known().size,
            ),
        )
    }

    /** Contadores que el estado no puede derivar: salen de los registros vivos. */
    private fun syncCounters() {
        val sesiones = session.sessionPeers().size
        val claves = session.peerKeys.known().size
        if (state.status.sessions != sesiones || state.status.ackKeys != claves) {
            state = state.copy(
                status = state.status.copy(sessions = sesiones, ackKeys = claves),
            )
        }
    }

    private fun checkResize() {
        val actual = writer.size()
        if (actual.columns > 0 && actual.rows > 0 && actual != state.viewport) {
            state = ShellReducer.reduce(state, ShellAction.Resize(actual)).state
        }
    }

    private fun paint() {
        val lineas = ShellRenderer.render(state, ascii)
        if (!writer.sameFrame(lineas)) writer.draw(lineas)
    }

    /** Vacía la cola de acciones: cada una se reduce en el hilo del bucle. */
    private fun drain() {
        var accion = inbox.poll()
        while (accion != null) {
            apply(ShellReducer.reduce(state, accion))
            if (!running) return
            accion = inbox.poll()
        }
    }

    private fun apply(reduced: com.km.desktop.ui.Reduced) {
        state = reduced.state
        reduced.effects.forEach(::launch)
        if (state.screen == Screen.DIAGNOSTICO && lastScreen != Screen.DIAGNOSTICO) {
            // La pantalla de logs pide el buffer al abrirse: son las lineas de
            // `DiagLog` ya redactadas, no una composicion nueva.
            inbox += ShellAction.EffectDone(EffectResult.LogsLoaded(session.diagnostics.rows()))
        }
        lastScreen = state.screen
    }

    private fun launch(effect: ShellEffect) {
        when (effect) {
            ShellEffect.Quit -> {
                running = false
            }

            ShellEffect.Redraw -> {
                // El siguiente `paint` repinta: no hay nada que pedir al worker.
            }

            else -> worker.submit {
                val resultado = runCatching { effects.execute(effect) }
                    .getOrElse { EffectResult.Failed("EFFECT_FAILED", it.message) }
                if (resultado is EffectResult.Restored) restored = true
                if (resultado != null) inbox += ShellAction.EffectDone(resultado)
            }
        }
    }

    // ------------------------------------------------------------------
    // Conexion
    // ------------------------------------------------------------------

    /**
     * Recorre los bootstraps en orden, fuera del hilo de pintura.
     *
     * El fallo se publica con un CODIGO estable, no con el texto de la
     * excepcion: la pantalla tiene que decir que paso de una forma que un test
     * pueda afirmar, y el detalle ya esta en el log del transporte.
     */
    private fun connectLater() {
        worker.submit {
            val fallo = runCatching {
                EndpointSequence(session.bootstraps) { destino ->
                    session.switchEndpoint(destino).getOrThrow()
                }.recorrer()
            }.exceptionOrNull()
            if (fallo != null) {
                val codigo = when (fallo) {
                    is BootstrapAllFailed -> "RELAY BOOTSTRAP_ALL_FAILED"
                    is UnsupportedOperationException -> "RELAY ENDPOINT_IMMUTABLE"
                    else -> "RELAY CONNECT_FAILED"
                }
                inbox += ShellAction.Event(ClientEvent.Error(codigo))
            }
        }
    }

    // ------------------------------------------------------------------
    // Entrada
    // ------------------------------------------------------------------

    /** Lee el resto de una secuencia de escape, o `Esc` si no llega nada. */
    private fun readEscape(reader: NonBlockingReader): KeyStroke? {
        val sb = StringBuilder("\u001b")
        var siguiente = reader.read(KeyCodec.ESC_TIMEOUT_MS)
        if (siguiente < 0) return KeyStroke(Key.Escape)
        sb.append(siguiente.toChar())
        if (siguiente != '['.code && siguiente != 'O'.code) {
            return KeyCodec.decodeSequence(sb.toString())
        }
        while (sb.length < MAX_SEQUENCE) {
            val c = reader.read(KeyCodec.ESC_TIMEOUT_MS)
            if (c < 0) break
            sb.append(c.toChar())
            // Byte final de una CSI: 0x40..0x7E. Se exige longitud >= 3 para no
            // confundir el '[' de la cabecera con el final.
            if (c in 0x40..0x7E && sb.length >= 3) break
        }
        return KeyCodec.decodeSequence(sb.toString())
    }

    companion object {
        /** Espera maxima por una tecla antes de revisar colas y medidas. */
        const val READ_TIMEOUT_MS: Long = 100L

        /** ESC en ASCII. */
        private const val ESC: Int = 27

        /** Tope de una secuencia de escape: mas larga es basura del terminal. */
        private const val MAX_SEQUENCE: Int = 16
    }
}
