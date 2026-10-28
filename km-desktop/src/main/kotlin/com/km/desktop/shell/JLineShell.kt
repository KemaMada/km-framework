package com.km.desktop.shell

import com.km.desktop.Session
import com.km.desktop.client.ClientEvent
import com.km.desktop.net.BootstrapAllFailed
import com.km.desktop.net.EndpointSequence
import com.km.desktop.ui.EffectResult
import com.km.desktop.ui.FocusField
import com.km.desktop.ui.Key
import com.km.desktop.ui.KeyStroke
import com.km.desktop.ui.MouseIntent
import com.km.desktop.ui.RenderClock
import com.km.desktop.ui.LinkState
import com.km.desktop.ui.Screen
import com.km.desktop.ui.ShellAction
import com.km.desktop.ui.ShellEffect
import com.km.desktop.ui.ShellReducer
import com.km.desktop.ui.ShellRenderer
import com.km.desktop.ui.ShellState
import com.km.desktop.ui.Size
import com.km.desktop.ui.StatusLine
import com.km.desktop.ui.layout.HitMap
import org.jline.terminal.Terminal
import org.jline.utils.NonBlockingReader
import java.time.ZoneId
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
    /** Valor EFECTIVO del tracking del raton (`auto` ya resuelto). */
    private val mouse: Boolean = false,
    /** Filas por muesca de rueda (`--wheel-rows`). */
    private val wheelRows: Int = 3,
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

    private var state: ShellState = initialState(System.currentTimeMillis())
    private var lastScreen: Screen = state.screen

    /** Gesto de raton en curso: press y release sobre la MISMA region. */
    private val click = ClickTracker()

    /** Hit-map del ULTIMO frame pintado: resuelve el siguiente clic/rueda. */
    private var hitMap: HitMap = HitMap.EMPTY

    /** Ultimo instante en que se inyecto un Tick: como maximo uno cada 250 ms. */
    private var lastTickMs: Long = 0L

    fun run() {
        val reader = terminal.reader()
        val anterior = runCatching { terminal.enterRawMode() }.getOrNull()
        val hook = Thread({ writer.exit() }, "km-tui-restore")
        var baja: (() -> Unit)? = null
        runCatching { Runtime.getRuntime().addShutdownHook(hook) }
        runCatching {
            writer.enter(mouse = mouse)
            baja = session.events.subscribe { evento -> inbox += ShellAction.Event(evento) }
            // Los efectos iniciales (cargar contactos) y la conexion, fuera del
            // hilo de pintura.
            apply(ShellReducer.reduce(state, ShellAction.Resize(writer.size())))
            ShellReducer.initialEffects().forEach(::launch)
            connectLater()

            while (running) {
                // El reloj se refresca ANTES de reducir acciones: un toast
                // creado por una tecla o por un evento necesita una base
                // fresca, no la del ultimo Tick (que puede ser de hace
                // minutos si no habia nada en vuelo).
                val ahora = System.currentTimeMillis()
                if (!inbox.isEmpty()) {
                    apply(ShellReducer.reduce(state, ShellAction.Tick(ahora)))
                    lastTickMs = ahora
                }
                drain()
                if (!running) break
                if (restored) break
                syncCounters()
                checkResize()
                // El reloj SOLO se inyecta cuando hace falta: toast vivo,
                // envio en vuelo o conexion intentandose. Sin eso la TUI sigue
                // dirigida por eventos y no hay repintados de mas.
                if (state.needsTick && ahora - lastTickMs >= TICK_MS) {
                    apply(ShellReducer.reduce(state, ShellAction.Tick(ahora)))
                    lastTickMs = ahora
                }
                paint(ahora)
                val code = reader.read(READ_TIMEOUT_MS)
                if (code == NonBlockingReader.EOF) {
                    // EOF restaura el terminal y sale: un proceso colgado en un
                    // pipe cerrado no es una TUI.
                    running = false
                    continue
                }
                if (code < 0) continue
                val entrada = if (code == ESC) readEscape(reader) else KeyCodec.decode(code)?.let(Input::Stroke)
                when (entrada) {
                    is Input.Stroke -> inbox += ShellAction.Key(entrada.stroke)
                    is Input.Mouse -> onMouse(entrada.event)
                    null -> Unit
                }
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

    private fun initialState(nowMs: Long): ShellState {
        val status = session.status()
        return ShellState(
            viewport = writer.size(),
            nowMs = nowMs,
            mouse = mouse,
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

    /**
     * La zona horaria del SISTEMA, resuelta UNA vez.
     *
     * El renderer es puro y no consulta el reloj; esta es la capa que si puede
     * hacerlo. Se cachea la zona (no cambia sola) y el instante se toma POR
     * FRAME: un reloj congelado al arrancar pintaria siempre la misma hora en
     * los toasts de M5.
     */
    private val zona: ZoneId = ZoneId.systemDefault()

    private fun paint(nowMs: Long) {
        // `color` sale del MISMO escritor que va a pintar: si no puede emitir
        // atributos (NO_COLOR, TERM=dumb, --ascii, MONO), el renderer no ofrece
        // QR porque saldria invertido.
        val reloj = RenderClock(zona, nowMs)
        val frame = ShellRenderer.renderFrame(state, ascii, writer.attributesEnabled, reloj)
        // El hit-map y las lineas vienen de la MISMA pasada: no hay una segunda
        // geometria que pueda quedarse vieja entre el clic y la pintura.
        hitMap = frame.hitMap
        // ScreenWriter compara con el frame anterior y decide que filas escribir.
        // El diff vive ahi, no aqui: dos capas decidiendo "que cambio" es como
        // aparecen los repintados de mas y los restos de pantalla.
        writer.draw(frame.lines)
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
    private fun readEscape(reader: NonBlockingReader): Input? {
        val sb = StringBuilder("\u001b")
        val siguiente = reader.read(KeyCodec.ESC_TIMEOUT_MS)
        if (siguiente < 0) return Input.Stroke(KeyStroke(Key.Escape))
        sb.append(siguiente.toChar())
        if (siguiente != '['.code && siguiente != 'O'.code) {
            return key(sb)
        }
        if (siguiente == '['.code) {
            // ## EL DESVIO AL RATON OCURRE SOLO AQUI
            //
            // El SGR es `ESC[<...`. Se mira el byte siguiente: si no es `<`, la
            // secuencia sigue siendo una tecla normal y `KeyCodec` la decodifica
            // como siempre. Asi flechas, F-keys y Alt no ganan ninguna rama.
            val marca = reader.read(KeyCodec.ESC_TIMEOUT_MS)
            if (marca < 0) return key(sb)
            sb.append(marca.toChar())
            if (marca == '<'.code) return readMouse(reader, sb)
            if (esFinal(marca) && sb.length >= 3) return key(sb)
        }
        while (sb.length < MAX_SEQUENCE) {
            val c = reader.read(KeyCodec.ESC_TIMEOUT_MS)
            if (c < 0) break
            sb.append(c.toChar())
            // Byte final de una CSI: 0x40..0x7E. Se exige longitud >= 3 para no
            // confundir el '[' de la cabecera con el final.
            if (esFinal(c) && sb.length >= 3) break
        }
        return key(sb)
    }

    /**
     * Lee el resto de un SGR de raton (`ESC[<b;x;yM|m`).
     *
     * Si la secuencia se corta (timeout o tope), se DESCARTA y se traga lo que
     * quede hasta el finalizador: los bytes de una secuencia partida no pueden
     * leerse como teclas, o `;` y digitos moverian el foco.
     */
    private fun readMouse(reader: NonBlockingReader, sb: StringBuilder): Input? {
        while (sb.length < MOUSE_MAX_SEQUENCE) {
            val c = reader.read(KeyCodec.ESC_TIMEOUT_MS)
            if (c < 0) {
                swallowMouse(reader)
                return null
            }
            sb.append(c.toChar())
            if (c == 'M'.code || c == 'm'.code) break
        }
        val secuencia = sb.toString()
        if (!secuencia.endsWith("M") && !secuencia.endsWith("m")) {
            swallowMouse(reader)
            return null
        }
        val evento = MouseCodec.decode(secuencia) ?: return null
        return Input.Mouse(evento)
    }

    /** Consume bytes de una secuencia de raton truncada hasta su finalizador. */
    private fun swallowMouse(reader: NonBlockingReader) {
        var guarda = 0
        while (guarda++ < MOUSE_MAX_SEQUENCE * 4) {
            val c = reader.read(KeyCodec.ESC_TIMEOUT_MS)
            if (c < 0) return
            if (c == 'M'.code || c == 'm'.code) return
        }
    }

    private fun key(sb: StringBuilder): Input? =
        KeyCodec.decodeSequence(sb.toString())?.let(Input::Stroke)

    /** Un evento de raton se enruta a una accion o se descarta (B3/B4). */
    private fun onMouse(event: MouseEvent) {
        val accion = MouseRouter.route(event, click, hitMap, state.focus.field, wheelRows)
        if (accion != null) inbox += accion
    }

    /** Lo que devuelve el lector: una tecla o un evento de raton. */
    private sealed interface Input {
        data class Stroke(val stroke: KeyStroke) : Input
        data class Mouse(val event: MouseEvent) : Input
    }

    companion object {
        /** Espera maxima por una tecla antes de revisar colas y medidas. */
        const val READ_TIMEOUT_MS: Long = 100L

        /** Cadencia del reloj inyectado mientras hay un efecto en vuelo. */
        const val TICK_MS: Long = 250L

        /** ESC en ASCII. */
        private const val ESC: Int = 27

        /**
         * Tope de una secuencia de escape normal: mas larga es basura del
         * terminal. Subio de 16 a 32 en el change B para admitir coordenadas de
         * 4 digitos sin cortar la secuencia.
         */
        private const val MAX_SEQUENCE: Int = 32

        /** Tope de un SGR de raton: `ESC[<` + tres parametros de 4 digitos + final. */
        private const val MOUSE_MAX_SEQUENCE: Int = 32

        /** `true` si [c] es un byte final de CSI (0x40..0x7E). */
        private fun esFinal(c: Int): Boolean = c in 0x40..0x7E
    }
}
