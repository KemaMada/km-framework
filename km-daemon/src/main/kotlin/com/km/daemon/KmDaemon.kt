package com.km.daemon

import com.km.crypto.Ed25519Impl
import com.km.daemon.config.ConfigLoader
import com.km.daemon.config.DaemonConfig
import com.km.daemon.identity.IdentityStore
import com.km.daemon.server.AuthAdapter
import com.km.daemon.server.JsonWireCodec
import com.km.daemon.server.MessageRouter
import com.km.daemon.server.RelayWebSocketServer
import com.km.model.IdentityId
import com.km.model.RelayLimits
import com.km.node.RelayServer
import com.km.node.RelayServiceImpl
import com.km.storage.InMemoryRelayStore
import org.slf4j.LoggerFactory
import java.nio.file.Paths
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.system.exitProcess

/**
 * El `km daemon`: aplicacion headless que habla el dialecto B de
 * `docs/protocol/KM-WIRE-RELAY.md` del lado del relé.
 *
 * Es lo que el propio documento llama P2/P3 en §18.2: el servidor que no
 * existia. Su trabajo tiene una sola regla y todo el diseno se deriva de ella
 * (§6.1): **el frame que entra es byte a byte el frame que sale**.
 *
 * Que hace y que NO hace:
 *
 * - Autentica con KM-0002 (challenge/response sobre los transcripts de 152 y
 *   120 B) **delegando** en `RelayServer` y `AuthVerifier`.
 * - Mueve bytes opacos de `MESSAGE` en Base64, sin interpretarlos.
 * - Serializa presencia (`PEER_ONLINE`/`PEER_OFFLINE`) y heartbeat
 *   (`PING`/`PONG`).
 * - Emite `AUTH_*`, y `RELAY_ERROR` cuando el core rechaza un frame.
 * - NO descifra, NO mira `messageId`, NO implementa semantica de `ACK`, NO
 *   store-and-forward (no esta implementado: §13.3).
 *
 * FRONTERA: este modulo depende SOLO de km-core. Ni de km-webrtc (que
 * arrastra ~25 MB de libwebrtc que el relé no usa) ni del cliente Android.
 *
 * ARRANQUE: el "en marcha" se DECLARA, no se supone. `WebSocketServer.start()`
 * de Java-WebSocket 1.5.6 es no bloqueante —crea el hilo del selector y
 * devuelve—, asi que el `bind` del socket ocurre despues de que `main` ya
 * sigue. El hilo principal espera a que la libreria confirme una de las dos
 * unicas salidas posibles: `onStart()` (puerto ligado) u `onError(null, ...)`
 * (puerto no ligado, con su causa). Sin esa espera, un puerto ocupado
 * producia "km-daemon en marcha" seguido de "Shutdown due to fatal error" en
 * cinco milisegundos: el proceso se declaraba vivo mientras ya estaba muerto,
 * que es exactamente lo que un gestor de procesos (PM2, systemd) no puede
 * distinguir de un arranque correcto.
 */
fun main(args: Array<String>) {
    val logger = LoggerFactory.getLogger("com.km.daemon.KmDaemon")

    val config = ConfigLoader.load()
    logConfig(logger, config)

    val identity = IdentityStore.loadOrCreate(Paths.get(config.identityPath))
    val relayIdentityId: IdentityId = identity.identityId
    logger.info("identidad del rele: {}", relayIdentityId.value)
    logger.info("fichero de identidad: {}", config.identityPath)

    val ed25519 = Ed25519Impl()

    // El store del relé existe porque `RelayServer` lo exige por constructor.
    // El MVP no enruta por el (el store-and-forward no esta implementado,
    // §13.3), pero se construye con los limites de `RelayLimits` y no con los
    // `Int.MAX_VALUE` / `Long.MAX_VALUE` del constructor, para que un dia que
    // se use no nazca sin techo.
    val limits = RelayLimits(maxConnections = config.maxConnections)
    val relayService = RelayServiceImpl(
        relayStore = InMemoryRelayStore(
            maxStoredMessages = limits.maxStoredMessages,
            maxStorageBytes = limits.maxStorageBytes,
        ),
        limits = limits,
    )

    val relayServer = RelayServer(
        relayKeyPair = identity.keyPair(),
        ed25519 = ed25519,
        relayService = relayService,
        relayIdentityId = relayIdentityId,
        sessionExpiryMs = config.sessionExpiryMs,
    )

    val codec = JsonWireCodec()
    val authAdapter = AuthAdapter(relayServer, relayIdentityId, codec)
    val router = MessageRouter(
        relayServer = relayServer,
        codec = codec,
        authAdapter = authAdapter,
        relayIdentityId = relayIdentityId,
        config = config,
        logger = logger,
    )

    // Las dos unicas respuestas posibles del arranque. `startup` se cuenta
    // tanto si el bind tuvo exito como si fallo: lo que decide el destino del
    // proceso es cual de los dos ocurrio, y ese orden lo fija
    // `RelayWebSocketServer`.
    val startup = CountDownLatch(1)
    val bindFailure = AtomicReference<Exception?>(null)
    val server = RelayWebSocketServer(
        config = config,
        router = router,
        codec = codec,
        onStarted = { startup.countDown() },
        onFatal = { failure ->
            bindFailure.compareAndSet(null, failure)
            startup.countDown()
        },
    )

    val shutdown = CountDownLatch(1)
    Runtime.getRuntime().addShutdownHook(
        Thread {
            logger.info("apagando...")
            server.shutdown()
            shutdown.countDown()
        }
    )

    server.start()

    if (!startup.await(STARTUP_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
        // La libreria no ha dicho ni que si ni que no. Un daemon que no puede
        // decir si ha abierto su puerto no debe quedarse vivo fingiendo que si.
        logger.error(
            "km-daemon no arranco: {}:{} no confirmo el bind en {} ms",
            config.listenHost, config.listenPort, STARTUP_TIMEOUT_MS,
        )
        exitProcess(EXIT_STARTUP_FAILED)
    }

    // El fallo del bind se mira en la referencia y no con `?.let`, para que
    // quede a la vista que esta rama no continua hacia el "en marcha".
    val bindError = bindFailure.get()
    if (bindError != null) {
        logger.error(
            "km-daemon no pudo abrir el puerto {}:{}: {}",
            config.listenHost, config.listenPort, describe(bindError),
        )
        exitProcess(EXIT_STARTUP_FAILED)
    }

    // Aqui, y solo aqui, el puerto esta escuchando de verdad: `onStart()` de
    // Java-WebSocket se invoca tras `socket.bind(...)`, nunca antes.
    logger.info("km-daemon en marcha. Ctrl-C para detener.")

    // El hilo principal se queda aqui: `WebSocketServer.start()` devuelve
    // control y el socket vive en su propio hilo. Sin esta espera el proceso
    // terminaria y se llevaria el servidor por delante.
    shutdown.await()
}

/** Codigo de salida de un arranque fallido. Distinto de cero, a proposito. */
private const val EXIT_STARTUP_FAILED: Int = 1

/**
 * Margen para que la libreria confirme el bind.
 *
 * `socket.bind(...)` es una llamada al sistema que tarda microsegundos; diez
 * segundos son un margen de sobra para una maquina razonable. Lo que evita no
 * es el falso positivo, es el contrario: si la confirmacion no llegara nunca,
 * `main` se quedaria esperando en silencio y el proceso quedaria vivo sin
 * puerto. Con el margen, la ausencia de respuesta es tambien un fallo de
 * arranque, y sale con codigo de error como todo lo demas.
 */
private const val STARTUP_TIMEOUT_MS: Long = 10_000L

/**
 * Causa legible de un fallo de arranque, con su clase.
 *
 * La clase importa: el mensaje de un `BindException` sale del locale del JVM
 * (en esta maquina, "La direccion ya se esta usando"), y sin el nombre de la
 * clase el log no distingue un puerto ocupado de un permiso denegado.
 */
private fun describe(failure: Throwable): String =
    failure.message?.let { "${failure::class.java.name}: $it" } ?: failure::class.java.name

/**
 * Registro de la configuracion efectiva.
 *
 * Deliberadamente sin material secreto: solo la ruta del fichero de
 * identidad, nunca su contenido.
 */
private fun logConfig(logger: org.slf4j.Logger, config: DaemonConfig) {
    logger.info("config: listen={}:{}", config.listenHost, config.listenPort)
    logger.info("config: relayEnabled={} signalingEnabled={}", config.relayEnabled, config.signalingEnabled)
    logger.info("config: sessionExpiryMs={} maxConnections={}", config.sessionExpiryMs, config.maxConnections)
}
