package com.km.transport

import com.km.api.KeyMessageCoreImpl
import com.km.codec.JsonDecoder
import com.km.codec.JsonEncoder
import com.km.crypto.Ed25519Impl
import com.km.crypto.KeyPair
import com.km.daemon.config.DaemonConfig
import com.km.daemon.server.AuthAdapter
import com.km.daemon.server.JsonWireCodec
import com.km.daemon.server.MessageRouter
import com.km.daemon.server.RelayWebSocketServer
import com.km.identity.KmIds
import com.km.model.IdentityId
import com.km.model.Message
import com.km.model.MessageState
import com.km.model.RelayLimits
import com.km.node.PeerTransportManager
import com.km.node.RelayServer
import com.km.node.RelayServiceImpl
import com.km.protocol.ConnectionState
import com.km.storage.InMemoryDuplicateStore
import com.km.storage.InMemoryMessageStore
import com.km.storage.InMemoryOfflineQueue
import com.km.storage.InMemoryRelayStore
import com.km.storage.MessageStore
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.websocket.WebSockets
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.slf4j.LoggerFactory
import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

/**
 * Renegociacion de sesion ante un cambio de red, por un socket de VERDAD.
 *
 * ## QUE CIERRA
 *
 * Los tres caminos que `#349` dejo abiertos, contra el `km-daemon` real y un
 * proxy TCP que puede dejar de reenviar SIN cerrar (blackhole):
 *
 *  1. `forceRedial()` vuelve a `ONLINE` con un `AUTH` nuevo, colapsa los
 *     eventos repetidos y NO toca la sesion E2E (`messageId` intacto).
 *  2. Un reintento del backoff anterior queda INVALIDADO por la epoca: un
 *     redial durante un `RECONNECTING` pendiente no produce dos `AUTH`.
 *  3. Un socket blackholeado deja de creerse `ONLINE` por el ping RFC 6455
 *     del motor de Android (OkHttp), `send()` deja de dar exito y la
 *     reconexion PASIVA sigue funcionando.
 *  4. La presencia de un par que se fue durante el corte NO sobrevive al
 *     redial.
 *
 * ## POR QUE EL PROXY ES UN CONTROL POSITIVO ADEMAS DE UN CORTE
 *
 * Un test que solo mide "0 entregados tras el corte" pasa tambien si el arnes
 * estaba roto. Por eso cada escenario entrega un byte ANTES del corte con el
 * proxy en el camino: si no llegara, el fallo seria del test y no del motor.
 *
 * ## POR QUE OKHTTP Y NO CIO
 *
 * Porque el camino Android es OkHttp (`RelayStackFactory.androidClient`) y la
 * cota de deteccion es SUYA: ping cada P, fallo si no hay PONG dentro de P
 * (<=2P). En CIO la espera es 2P y la cota 3P. Probar el motor equivocado
 * mediria una cota que el dispositivo no tiene.
 */
class NetworkRedialTest {

    private val ed25519 = Ed25519Impl()

    private lateinit var server: RelayWebSocketServer
    /**
     * Identidad del rele. `IdentityId` es un value class: no admite
     * `lateinit`, asi que se declara nullable y se exige donde se usa.
     */
    private var relayId: IdentityId? = null
    private var port: Int = 0

    private fun relay(): IdentityId = requireNotNull(relayId) { "el rele no arranco" }

    private val logs = CopyOnWriteArrayList<String>()
    private val deliveredToBob = AtomicInteger(0)

    // ------------------------------------------------------------------
    // El rele de verdad
    // ------------------------------------------------------------------

    @BeforeEach
    fun startRelay() {
        val relayKp = ed25519.generateKeyPair()
        val identidadRele = IdentityId(KmIds.identityId(relayKp.publicKey).joinToString("") { "%02x".format(it) })
        relayId = identidadRele

        val limits = RelayLimits(maxConnections = 32)
        val relayServer = RelayServer(
            relayKeyPair = relayKp,
            ed25519 = ed25519,
            relayService = RelayServiceImpl(
                relayStore = InMemoryRelayStore(
                    maxStoredMessages = limits.maxStoredMessages,
                    maxStorageBytes = limits.maxStorageBytes,
                ),
                limits = limits,
            ),
            relayIdentityId = identidadRele,
        )
        val codec = JsonWireCodec()
        port = ServerSocket(0).use { it.localPort }
        val config = DaemonConfig(listenPort = port, maxConnections = 32)
        val router = MessageRouter(
            relayServer = relayServer,
            codec = codec,
            authAdapter = AuthAdapter(relayServer, identidadRele, codec),
            relayIdentityId = identidadRele,
            config = config,
            logger = LoggerFactory.getLogger("redial-relay"),
        )
        val started = CountDownLatch(1)
        val failure = AtomicReference<Exception?>(null)
        server = RelayWebSocketServer(
            config = config,
            router = router,
            codec = codec,
            onStarted = { started.countDown() },
            onFatal = { started.countDown(); failure.set(it) },
        )
        server.start()
        assertTrue(started.await(20, TimeUnit.SECONDS), "el rele no arranco: ${failure.get()?.message}")
        assertNull(failure.get(), "el rele no pudo ligar el puerto")
    }

    @AfterEach
    fun stopRelay() {
        if (::server.isInitialized) server.shutdown()
    }

    private fun endpoint(): String = "ws://127.0.0.1:$port/km"

    // ------------------------------------------------------------------
    // Clientes
    // ------------------------------------------------------------------

    private class Node(
        val identity: IdentityId,
        val keyPair: KeyPair,
        val transport: JvmRelayTransport,
        val manager: PeerTransportManager,
        val core: KeyMessageCoreImpl,
        val store: MessageStore,
    )

    private fun identityOf(keyPair: KeyPair): IdentityId =
        IdentityId(KmIds.identityId(keyPair.publicKey).joinToString("") { "%02x".format(it) })

    /**
     * Un nodo de verdad: motor + manager + nucleo.
     *
     * @param clientFactory motor Ktor. `null` deja el CIO del modulo (sin ping
     *   corto); el test de blackhole inyecta OkHttp con ping de 300 ms.
     */
    private fun node(
        keyPair: KeyPair,
        peers: PeerKeyDirectory,
        url: String = endpoint(),
        clientFactory: (() -> HttpClient)? = null,
        reconnect: ReconnectPolicy = ReconnectPolicy(initialDelayMs = 150, maxDelayMs = 400),
        handshakeTimeoutMs: Long = 5_000,
        onState: (ConnectionState) -> Unit = {},
        onMessage: (Message) -> Unit = {},
    ): Node {
        val identity = identityOf(keyPair)
        val transport = JvmRelayTransport(
            identity = identity,
            ed25519PublicKey = keyPair.publicKey,
            ed25519PrivateKey = keyPair.privateKey,
            endpoint = url,
            relayIdentity = relay(),
            ed25519 = ed25519,
            reconnect = reconnect,
            handshakeTimeoutMs = handshakeTimeoutMs,
            clientFactory = clientFactory ?: { JvmRelayTransport.defaultClient() },
            log = { message -> logs.add("[${identity.value.take(6)}] $message") },
        )
        transport.onStateChange(onState)
        val store = InMemoryMessageStore()
        val manager = PeerTransportManager(transport)
        val core = KeyMessageCoreImpl(
            localIdentity = identity,
            localPrivateKey = keyPair.privateKey,
            transport = transport,
            ed25519 = ed25519,
            messageStore = store,
            offlineQueue = InMemoryOfflineQueue(),
            duplicateStore = InMemoryDuplicateStore(),
            encoder = JsonEncoder(),
            decoder = JsonDecoder(),
            peerPublicKeyResolver = peers.resolver(),
        )
        core.registerMessageHandler { onMessage(it) }
        return Node(identity, keyPair, transport, manager, core, store)
    }

    private fun peersOf(vararg otros: KeyPair): PeerKeyDirectory =
        PeerKeyDirectory().also { dir ->
            otros.forEach { otro -> dir.register(identityOf(otro), otro.publicKey) }
        }

    private fun await(what: String, timeoutMs: Long = 20_000, predicate: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (predicate()) return
            Thread.sleep(25)
        }
        throw AssertionError("no se cumplio en ${timeoutMs}ms: $what\nrelay=${server.address}\ntraza:\n" + logs.joinToString("\n"))
    }

    private fun okHttpWithPing(pingMs: Long): () -> HttpClient = {
        HttpClient(OkHttp) {
            install(WebSockets)
            engine { config { pingInterval(pingMs, TimeUnit.MILLISECONDS) } }
        }
    }

    // ==================================================================
    // 1. forceRedial: AUTH nuevo, single-flight y E2E intacto
    // ==================================================================

    @Test
    @Timeout(120)
    fun `forceRedial vuelve a ONLINE con AUTH nuevo y no duplica el redial`() {
        val aliceKp = ed25519.generateKeyPair()
        val bobKp = ed25519.generateKeyPair()
        val online = AtomicInteger(0)
        val sesiones = CopyOnWriteArrayList<String?>()

        val bob = node(bobKp, peersOf(aliceKp)) { deliveredToBob.incrementAndGet() }
        // R12: el redial repara el SOCKET, no la sesion E2E. Ninguna senal de
        // re-negociacion (PREKEY/X3DH) puede salir por este camino; si saliera,
        // el mensaje ya entregado dejaria de ser el mismo.
        val senales = CopyOnWriteArrayList<String>()
        bob.transport.onSignal { if (it is DecodedSignal.Known) senales.add(it.kind) }
        // `lateinit`: el callback de estado necesita el nodo que se esta
        // construyendo, y el callback solo corre en el `connect()` posterior.
        lateinit var alice: Node
        alice = node(
            aliceKp,
            peersOf(bobKp),
            onState = { estado ->
                if (estado == ConnectionState.ONLINE) {
                    online.incrementAndGet()
                    sesiones.add(alice.transport.status().sessionId)
                }
            },
        )

        try {
            bob.transport.connect().getOrThrow()
            alice.transport.connect().getOrThrow()

            // Control positivo de la presencia: sin esto, "el par esta" no
            // probaria que el snapshot del rele funciona.
            await("alice ve a bob presente") { alice.transport.isPeerPresent(bob.identity) }
            val session1 = alice.transport.status().sessionId
            assertNotNull(session1, "la primera sesion tiene sessionId")

            // Control positivo del canal: un mensaje ENTREGADO antes del redial.
            val primero = alice.core.createMessage(bob.identity, "antes".toByteArray()).getOrThrow()
            alice.core.sendMessage(primero).getOrThrow()
            await("el primer mensaje se entrega") {
                alice.core.getMessageState(primero.messageId).getOrThrow() == MessageState.DELIVERED
            }
            assertEquals(1, deliveredToBob.get(), "control positivo: el byte cruzo el rele")
            val enBob = bob.store.get(primero.messageId).getOrThrow()
            assertNotNull(enBob, "el receptor lo guardo con el MISMO messageId")

            val antesDelRedial = online.get()

            // DOS disparos seguidos: el segundo es el evento de red repetido.
            assertTrue(alice.transport.forceRedial().isSuccess)
            assertTrue(
                alice.transport.forceRedial().isSuccess,
                "un segundo forceRedial mientras hay uno en vuelo es un no-op con exito",
            )

            assertEquals(
                ConnectionState.RECONNECTING,
                alice.transport.connectionState(),
                "el redial se declara RECONNECTING antes de que el AUTH vuelva",
            )

            await("vuelve a ONLINE tras el redial") {
                alice.transport.isOnline() && alice.transport.status().sessionId != session1
            }

            val session2 = alice.transport.status().sessionId
            assertNotNull(session2)
            assertNotEquals(session1, session2, "el AUTH nuevo tiene que dar un sessionId DISTINTO")

            // Margen para que un segundo AUTH (si lo hubiera) aparezca.
            Thread.sleep(1_500)
            assertEquals(
                antesDelRedial + 1,
                online.get(),
                "dos eventos, UN AUTH: el single-flight y el epoch no pueden dejar dos ONLINE. sesiones=$sesiones\n" +
                    logs.joinToString("\n"),
            )

            // R2/R12: la sesion E2E no se rehizo. El mensaje entregado conserva
            // su estado y su identificador; no hay PREKEY en el camino.
            assertEquals(
                MessageState.DELIVERED,
                alice.core.getMessageState(primero.messageId).getOrThrow(),
                "el redial no re-cifra ni re-crea el mensaje ya entregado",
            )
            assertNotNull(bob.store.get(primero.messageId).getOrThrow(), "el receptor conserva el MISMO messageId")
            assertTrue(
                senales.none { it.contains("PREKEY", ignoreCase = true) },
                "R12: cero re-negociacion X3DH/PREKEY en el redial. senales=$senales",
            )

            // Y el canal sigue sirviendo DESPUES del redial.
            val segundo = alice.core.createMessage(bob.identity, "despues".toByteArray()).getOrThrow()
            alice.core.sendMessage(segundo).getOrThrow()
            await("el mensaje posterior al redial se entrega") {
                alice.core.getMessageState(segundo.messageId).getOrThrow() == MessageState.DELIVERED
            }
            assertEquals(2, deliveredToBob.get(), "dos mensajes, dos entregas: ni una de mas")
        } finally {
            alice.core.stop()
            bob.core.stop()
            alice.transport.close()
            bob.transport.close()
        }
    }

    // ==================================================================
    // 2. La epoca invalida el reintento del backoff anterior
    // ==================================================================

    @Test
    @Timeout(120)
    fun `forceRedial durante un backoff pendiente invalida el reintento viejo`() {
        val aliceKp = ed25519.generateKeyPair()
        val bobKp = ed25519.generateKeyPair()
        val online = AtomicInteger(0)
        val sesiones = CopyOnWriteArrayList<String?>()

        BlackholeProxy(port).use { proxy ->
            val bob = node(bobKp, peersOf(aliceKp))
            lateinit var alice: Node
            alice = node(
                aliceKp,
                peersOf(bobKp),
                url = proxy.endpoint(),
                // Backoff LARGO a proposito: el reintento pasivo queda esperando
                // mientras llega el redial. Es el unico modo de ver la carrera.
                reconnect = ReconnectPolicy(initialDelayMs = 1_500, maxDelayMs = 1_500),
                onState = { estado ->
                    if (estado == ConnectionState.ONLINE) {
                        online.incrementAndGet()
                        sesiones.add(alice.transport.status().sessionId)
                    }
                },
            )
            try {
                bob.transport.connect().getOrThrow()
                alice.transport.connect().getOrThrow()
                val session1 = alice.transport.status().sessionId
                assertEquals(1, online.get(), "arranque: un ONLINE")

                // Cierre REAL del socket (FIN), no blackhole: el camino pasivo
                // programa su reintento con el backoff de 1,5 s.
                proxy.cortarTodas()
                await("el cierre deja el transporte en RECONNECTING") {
                    alice.transport.connectionState() == ConnectionState.RECONNECTING
                }

                // El redial llega DURANTE la espera del backoff.
                assertTrue(alice.transport.forceRedial().isSuccess)
                await("el redial gana y vuelve ONLINE") {
                    alice.transport.isOnline() && alice.transport.status().sessionId != session1
                }
                val session2 = alice.transport.status().sessionId

                // Mas que el backoff pendiente (1,5 s): si la epoca no lo
                // invalidara, el trabajo viejo despertaria aqui y abriria OTRO
                // AUTH, con otro sessionId.
                Thread.sleep(3_000)

                assertEquals(
                    2,
                    online.get(),
                    "un redial durante un backoff pendiente deja UN AUTH, no dos. sesiones=$sesiones",
                )
                assertEquals(
                    session2,
                    alice.transport.status().sessionId,
                    "el reintento viejo no puede pisar la sesion del redial",
                )
            } finally {
                alice.core.stop()
                bob.core.stop()
                alice.transport.close()
                bob.transport.close()
            }
        }
    }

    // ==================================================================
    // 3. Blackhole: el ping saca al motor de ONLINE y send() deja de mentir
    // ==================================================================

    @Test
    @Timeout(120)
    fun `un socket blackholeado deja de estar ONLINE por el ping y el envio falla`() {
        val aliceKp = ed25519.generateKeyPair()
        val bobKp = ed25519.generateKeyPair()

        BlackholeProxy(port).use { proxy ->
            val bob = node(bobKp, peersOf(aliceKp)) { deliveredToBob.incrementAndGet() }
            val alice = node(
                aliceKp,
                peersOf(bobKp),
                url = proxy.endpoint(),
                // 300 ms y NO 20 s: la cota que se prueba es la MISMA relacion
                // (2 x intervalo) que en produccion, medida en segundos.
                clientFactory = okHttpWithPing(300),
                handshakeTimeoutMs = 2_000,
            )
            try {
                bob.transport.connect().getOrThrow()
                alice.transport.connect().getOrThrow()
                val session1 = alice.transport.status().sessionId

                // CONTROL POSITIVO: con el proxy reenviando, el byte llega.
                val antes = alice.core.createMessage(bob.identity, "antes".toByteArray()).getOrThrow()
                alice.core.sendMessage(antes).getOrThrow()
                await("control positivo: el byte cruza el proxy") {
                    alice.core.getMessageState(antes.messageId).getOrThrow() == MessageState.DELIVERED
                }
                assertEquals(1, deliveredToBob.get())

                // Desde aqui el proxy lee y DESCARTA: ni FIN ni RST.
                proxy.blackhole()

                val inicio = System.currentTimeMillis()
                await("el ping saca al motor de ONLINE (R4/S7)", timeoutMs = 10_000) {
                    !alice.transport.isOnline()
                }
                val detectadoMs = System.currentTimeMillis() - inicio

                assertTrue(
                    detectadoMs <= 3_000,
                    "con ping de 300 ms la deteccion tiene que caer en la cota 2x (<=600 ms) mas margen; fue ${detectadoMs}ms",
                )
                assertFalse(
                    alice.transport.send(byteArrayOf(1, 2, 3), bob.identity).isSuccess,
                    "send() no puede dar exito sobre un socket que ya no entrega (R4)",
                )
                assertNull(
                    alice.transport.status().sessionId,
                    "sin sesion no se conserva el sessionId viejo como si siguiera viva",
                )

                // La reconexion pasiva (S10) sigue siendo el camino: al abrir el
                // proxy, el reintento del backoff entra solo, sin observador de
                // red y sin volver a llamar a forceRedial.
                proxy.restaurar()
                await("reconecta solo con el proxy abierto", timeoutMs = 25_000) {
                    alice.transport.isOnline() && alice.transport.status().sessionId != session1
                }

                val despues = alice.core.createMessage(bob.identity, "despues".toByteArray()).getOrThrow()
                alice.core.sendMessage(despues).getOrThrow()
                await("el canal sirve tras la reconexion pasiva") {
                    alice.core.getMessageState(despues.messageId).getOrThrow() == MessageState.DELIVERED
                }
            } finally {
                alice.core.stop()
                bob.core.stop()
                alice.transport.close()
                bob.transport.close()
            }
        }
    }

    // ==================================================================
    // 4. Presencia: el par que se fue durante el corte no resucita
    // ==================================================================

    @Test
    @Timeout(120)
    fun `la presencia de un par ido durante el corte no sobrevive al redial`() {
        val aliceKp = ed25519.generateKeyPair()
        val bobKp = ed25519.generateKeyPair()

        BlackholeProxy(port).use { proxy ->
            val bob = node(bobKp, peersOf(aliceKp))
            val alice = node(
                aliceKp,
                peersOf(bobKp),
                url = proxy.endpoint(),
                clientFactory = okHttpWithPing(300),
                handshakeTimeoutMs = 2_000,
            )
            try {
                bob.transport.connect().getOrThrow()
                alice.transport.connect().getOrThrow()

                // Control positivo: la presencia se VE antes del corte.
                await("alice ve a bob presente antes del corte") {
                    alice.transport.isPeerPresent(bob.identity)
                }

                proxy.blackhole()
                // Bob se va SIN que alice pueda enterarse: su PEER_OFFLINE viaja
                // por el socket blackholeado y se descarta.
                bob.core.stop()
                bob.transport.close()

                await("el ping saca a alice de ONLINE", timeoutMs = 10_000) {
                    !alice.transport.isOnline()
                }
                proxy.restaurar()

                await("alice reconecta") { alice.transport.isOnline() }

                // El snapshot del rele es ADITIVO. Si la vista local no se
                // limpia al cerrar la sesion, bob sigue "presente" para siempre
                // (#349, PROBE-A: stalePresence=true).
                assertFalse(
                    alice.transport.isPeerPresent(bob.identity),
                    "un par que se fue durante el corte NO puede seguir presente (R5/S8)",
                )
                assertFalse(
                    alice.transport.onlinePeerIds().contains(bob.identity),
                    "la vista de pares se limpia con la sesion",
                )
            } finally {
                alice.core.stop()
                bob.core.stop()
                alice.transport.close()
                bob.transport.close()
            }
        }
    }

    // ==================================================================
    // 5. Cierre real del socket: la reconexion pasiva sigue intacta (S10)
    // ==================================================================

    @Test
    @Timeout(120)
    fun `un cierre real del socket reconecta solo sin observador de red - S10`() {
        val aliceKp = ed25519.generateKeyPair()
        val bobKp = ed25519.generateKeyPair()

        BlackholeProxy(port).use { proxy ->
            val bob = node(bobKp, peersOf(aliceKp)) { deliveredToBob.incrementAndGet() }
            val alice = node(
                aliceKp,
                peersOf(bobKp),
                url = proxy.endpoint(),
            )
            try {
                bob.transport.connect().getOrThrow()
                alice.transport.connect().getOrThrow()
                val session1 = alice.transport.status().sessionId

                // FIN en las dos direcciones: es el caso "el rele cae" de S10.
                proxy.cortarTodas()

                await("reconecta sola") {
                    alice.transport.isOnline() && alice.transport.status().sessionId != session1
                }

                val mensaje = alice.core.createMessage(bob.identity, "tras el cierre".toByteArray()).getOrThrow()
                alice.core.sendMessage(mensaje).getOrThrow()
                await("entrega tras la reconexion pasiva") {
                    alice.core.getMessageState(mensaje.messageId).getOrThrow() == MessageState.DELIVERED
                }
                assertEquals(1, deliveredToBob.get())
            } finally {
                alice.core.stop()
                bob.core.stop()
                alice.transport.close()
                bob.transport.close()
            }
        }
    }

    // ==================================================================
    // Proxy TCP: reenvia, o LEE Y DESCARTA (blackhole sin FIN ni RST)
    // ==================================================================

    /**
     * Proxy de bucle local entre el cliente y el rele.
     *
     * En modo normal reenvia byte a byte en las dos direcciones. En modo
     * blackhole sigue leyendo —para no llenar los buffers del socket— y tira lo
     * leido: no hay FIN, no hay RST, no hay error. Es lo que se parece a un
     * cambio de red que deja el socket abierto.
     */
    private class BlackholeProxy(private val targetPort: Int) : Closeable {

        private val server = ServerSocket(0, 64, InetAddress.getLoopbackAddress())
        private val bloqueado = AtomicBoolean(false)
        private val cerrado = AtomicBoolean(false)
        private val sockets = CopyOnWriteArrayList<Socket>()
        private val pumps = CopyOnWriteArrayList<Thread>()

        val port: Int get() = server.localPort

        init {
            thread(isDaemon = true, name = "blackhole-accept") {
                while (!cerrado.get()) {
                    val cliente = runCatching { server.accept() }.getOrNull() ?: break
                    runCatching { enlazar(cliente) }
                }
            }
        }

        fun endpoint(): String = "ws://127.0.0.1:$port/km"

        fun blackhole() {
            bloqueado.set(true)
        }

        fun restaurar() {
            bloqueado.set(false)
        }

        /** Cierra las conexiones proxificadas con un FIN de verdad. */
        fun cortarTodas() {
            sockets.toList().forEach { runCatching { it.close() } }
            sockets.clear()
        }

        private fun enlazar(cliente: Socket) {
            val arriba = Socket(InetAddress.getLoopbackAddress(), targetPort)
            sockets.add(cliente)
            sockets.add(arriba)
            bomba(cliente, cliente.getInputStream(), arriba.getOutputStream(), arriba)
            bomba(arriba, arriba.getInputStream(), cliente.getOutputStream(), cliente)
        }

        private fun bomba(dueno: Socket, entrada: InputStream, salida: OutputStream, pareja: Socket) {
            val t = thread(isDaemon = true, name = "blackhole-pump-${dueno.port}") {
                val buffer = ByteArray(8 * 1024)
                try {
                    while (!cerrado.get()) {
                        val n = entrada.read(buffer)
                        if (n < 0) break
                        if (bloqueado.get()) continue // lee y DESCARTA
                        salida.write(buffer, 0, n)
                        salida.flush()
                    }
                } catch (_: IOException) {
                    // Un cierre del otro extremo es normal aqui.
                } finally {
                    runCatching { dueno.close() }
                    runCatching { pareja.close() }
                }
            }
            pumps.add(t)
        }

        override fun close() {
            cerrado.set(true)
            runCatching { server.close() }
            cortarTodas()
        }
    }
}
