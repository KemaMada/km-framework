package com.km.daemon.server

import com.km.daemon.config.DaemonConfig
import org.java_websocket.WebSocket
import org.java_websocket.framing.CloseFrame
import org.java_websocket.handshake.ClientHandshake
import org.java_websocket.server.WebSocketServer
import org.slf4j.LoggerFactory
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Adaptador de Java-WebSocket a [MessageRouter].
 *
 * Es la UNICA pieza que sabe de sockets. Todo lo que hay por debajo de
 * [MessageRouter] habla de identidades y de bytes opacos; todo lo que hay por
 * encima (Java-WebSocket) habla de conexiones y de buffers. Este objeto hace
 * de frontera y no anade semantica: no autentica, no enruta, no mira cargas.
 *
 * Decisiones que si son de este nivel:
 *
 * - **Text frames only** (§7). Un frame binario se cierra: el canal binario
 *   es §6.4, "documentado y deliberadamente sin cerrar".
 * - **`maxConnections`** se aplica en la apertura, antes de que exista sesion
 *   alguna que limpiar (§15.3).
 * - **Heartbeat de aplicacion**: un `PING` JSON cada [PING_INTERVAL_MS].
 *   Es DISTINTO del ping RFC 6455 de Java-WebSocket, que este servidor envia
 *   por su cuenta y que no pasa por aqui (§12.3: "un daemon MUST NOT confundir
 *   ambos niveles").
 * - **El arranque se declara, no se supone**: `start()` de Java-WebSocket 1.5.6
 *   devuelve el control ANTES de ligar el socket (crea un hilo y el `bind`
 *   ocurre ahi). Quien llama no puede afirmar nada del puerto hasta que la
 *   libreria responda, y responde de dos maneras excluyentes — [onStarted]
 *   desde `onStart()`, que Java-WebSocket invoca "when the server started up
 *   successfully", o [onFatal] desde `onError(null, ...)` cuando el `bind`
 *   falla. Los dos ganchos estan verificados en el codigo de la libreria, no
 *   supuestos: ver `doSetupSelectorAndServerThread()`, donde el orden es
 *   `socket.bind(...)` y DESPUES `onStart()`.
 */
class RelayWebSocketServer(
    private val config: DaemonConfig,
    private val router: MessageRouter,
    private val codec: JsonWireCodec,

    /**
     * El puerto ya esta ligado y escuchando.
     *
     * Se invoca desde `onStart()`, o sea en el hilo del selector de
     * Java-WebSocket y no en el hilo principal: quien lo reciba tiene que
     * ser consciente de eso.
     */
    private val onStarted: () -> Unit = {},

    /**
     * El servidor no ha podido arrancar (tipicamente, el puerto esta ocupado).
     *
     * Llega con [onError] en `conn == null`, que es como Java-WebSocket
     * 1.5.6 documenta los errores "que no pertenecen a ninguna conexion. Por
     * ejemplo, si el puerto del servidor no se pudo ligar". Se invoca con el
     * hilo del selector, no con el principal.
     */
    private val onFatal: (Exception) -> Unit = {},
) : WebSocketServer(InetSocketAddress(config.listenHost, config.listenPort)) {

    private val logger = LoggerFactory.getLogger(RelayWebSocketServer::class.java)

    private val connections = ConcurrentHashMap<WebSocket, RelayWebSocket>()

    private val running = AtomicBoolean(false)
    private var heartbeat: ScheduledExecutorService? = null

    // =====================================================================
    // Callbacks de Java-WebSocket
    // =====================================================================

    override fun onOpen(conn: WebSocket, handshake: ClientHandshake?) {
        if (connections.size >= config.maxConnections) {
            logger.warn(
                "conexion rechazada: {} conexiones ya abiertas (maximo {})",
                connections.size, config.maxConnections,
            )
            // 1013 "Try Again Later" (RFC 6455 §7.4.1): el servidor esta vivo,
            // esta ocupado. No es un rechazo por culpa del cliente.
            conn.close(CloseFrame.TRY_AGAIN_LATER, "max connections")
            return
        }

        val connection = RelayWebSocket(conn, logger)
        connections[conn] = connection
        router.register(conn, connection)
        logger.info("conexion abierta desde {} ({} activas)", conn.remoteSocketAddress, connections.size)
    }

    override fun onClose(conn: WebSocket, code: Int, reason: String?, remote: Boolean) {
        connections.remove(conn)?.let { router.release(conn, it) }
    }

    override fun onMessage(conn: WebSocket, text: String?) {
        val connection = connections[conn] ?: return
        if (text == null) return
        router.onText(connection, text)
    }

    /**
     * Frames binarios: no hay dialecto para ellos.
     *
     * §6.4 deja el canal binario escrito y sin cerrar para mas adelante. Hoy
     * se rechaza el frame con 1003 "unsupported data" en vez de ignorarlo en
     * silencio, porque un cliente que ya envie binarios esta hablando un
     * dialecto que este rele no implementa, y decirlo es mas util que
     *tragarse el frame.
     */
    override fun onMessage(conn: WebSocket, bytes: ByteBuffer?) {
        logger.warn("frame binario rechazado: el dialecto B es JSON sobre frames de texto (§7)")
        conn.close(CLOSE_UNSUPPORTED_DATA, "text frames only")
    }

    override fun onError(conn: WebSocket?, ex: Exception) {
        if (conn == null) {
            // Java-WebSocket 1.5.6, `onError`: "`conn` can be null if the error
            // does not belong to one specific websocket. For example if the
            // servers port could not be bound". Es decir, `conn == null` ES el
            // aviso de fallo de arranque, y no un error de una conexion: sin
            // esta distincion un puerto ocupado pasaria por un error de socket
            // cualquiera y el proceso seguiria declarandose en marcha.
            logger.error("fallo fatal del servidor WebSocket: {}", ex.toString())
            onFatal(ex)
            return
        }
        logger.warn("error de socket: {}", ex.message)
    }

    override fun onStart() {
        running.set(true)
        startHeartbeat()
        logger.info(
            "km-daemon escuchando en {}:{} (datos={}, senalizacion={}, maxConexiones={})",
            config.listenHost, config.listenPort, config.relayEnabled, config.signalingEnabled,
            config.maxConnections,
        )
        // A partir de aqui, y solo aqui, el puerto esta ligado. Quien decide
        // si el proceso vive o se va lo sabe a traves de [onStarted].
        onStarted()
    }

    // =====================================================================
    // Heartbeat de aplicacion (§12.2)
    // =====================================================================

    private fun startHeartbeat() {
        val executor = Executors.newSingleThreadScheduledExecutor { runnable ->
            Thread(runnable, "km-daemon-heartbeat").apply { isDaemon = true }
        }
        executor.scheduleWithFixedDelay(
            {
                runCatching { sendApplicationPings() }
                    .onFailure { logger.warn("heartbeat fallo: {}", it.message) }
            },
            PING_INTERVAL_MS,
            PING_INTERVAL_MS,
            TimeUnit.MILLISECONDS,
        )
        heartbeat = executor
    }

    /**
     * Un `PING` por conexion autenticada.
     *
     * A los no autenticados no se les manda nada: antes del `AUTH_OK` el
     * cliente solo espera el challenge, y un `PING` lo podria descolocar del
     * flujo de autenticacion.
     *
     * El `messageId` se genera por conexion, no por barrido: es lo que permite
     * correlacionar el `PONG` si algun dia se necesita, y el coste es
     * irrelevante a 30 s de periodo.
     */
    private fun sendApplicationPings() {
        val authenticated = router.authenticatedConnections()
        if (authenticated.isEmpty()) return
        authenticated.forEach { it.send(codec.ping()) }
    }

    /** Cierre ordenado: para el heartbeat y para el socket. */
    fun shutdown(timeoutMs: Int = SHUTDOWN_TIMEOUT_MS) {
        if (!running.compareAndSet(true, false)) return
        heartbeat?.shutdownNow()
        heartbeat = null
        runCatching { stop(timeoutMs) }
            .onFailure { logger.warn("parada del servidor incompleta: {}", it.message) }
        logger.info("km-daemon detenido")
    }

    private companion object {
        /**
         * Periodo del `PING` de APLICACION.
         *
         * No es un parametro de configuracion en el MVP, asi que es una
         * constante y se dice donde esta. El valor no lo fija el dialecto:
         * esta por debajo de la ventana de sesion de 300 s y bastante por
         * encima de lo que tardaria una sesion muerta en notarse.
         */
        const val PING_INTERVAL_MS: Long = 30_000L

        const val SHUTDOWN_TIMEOUT_MS: Int = 2_000

        /** 1003 "Unsupported Data" (RFC 6455 §7.4.1). */
        const val CLOSE_UNSUPPORTED_DATA: Int = 1003
    }
}
