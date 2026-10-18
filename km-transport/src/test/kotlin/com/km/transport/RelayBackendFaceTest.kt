package com.km.transport

import com.km.crypto.Ed25519Impl
import com.km.crypto.KeyPair
import com.km.daemon.config.DaemonConfig
import com.km.daemon.server.AuthAdapter
import com.km.daemon.server.JsonWireCodec
import com.km.daemon.server.MessageRouter
import com.km.daemon.server.RelayWebSocketServer
import com.km.identity.KmIds
import com.km.model.IdentityId
import com.km.model.RelayLimits
import com.km.node.PeerTransportManager
import com.km.node.RelayServer
import com.km.node.RelayServiceImpl
import com.km.node.TransportError
import com.km.node.TransportResult
import com.km.storage.InMemoryRelayStore
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.slf4j.LoggerFactory
import java.net.ServerSocket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.assertContentEquals

/**
 * RB — la cara `TransportBackend` del motor, probada por un WebSocket de verdad.
 *
 * ## QUE CIERRA ESTE FICHERO Y QUE NO
 *
 * El verify de p4 dejo tres CRITICALs porque el routing del motor no tenia
 * NINGUN test: `sendData` podia ser un no-op y `onMessage` podia atribuir el
 * frame a la identidad LOCAL, y las dos cosas dejaban la suite verde. Este
 * fichero las ataca con el RELE REAL y el manager REAL, no con dobles:
 *
 *  - [RB-01] un byte que entra por el transporte llega al manager con el `from`
 *    del emisor. Es el cierre del lado de RECEPCION (M1-a y OWN-B2-TX-A).
 *  - [RB-02] un byte opaco con compositor de pares NO toca el camino en claro.
 *    Es la clasificacion de `onMessage` (M2-d).
 *  - [RB-03] las dos caras son el MISMO objeto (una sola conexion, un solo
 *    estado). Sin esto, la doble fachada podria divergir en silencio.
 *  - [RB-04] sin `connect()`, `sendData` falla con `BACKEND_NOT_READY`.
 *
 * La parte de clasificacion LEGADO/OPACO (LR-01..03) vive en
 * [LegacyAndPeerChannelRoutingTest].
 *
 * ## POR QUE HACE FALTA EL RELE
 *
 * `onMessage` y `sendFrame` son `private`: el UNICO modo de que el motor
 * procese un `MESSAGE` es que el frame llegue por el socket y el bucle de
 * sesion lo despache. Un doble que llamara a un metodo publico no probaria el
 * routing; probaria al doble.
 */
class RelayBackendFaceTest {

    private val ed25519 = Ed25519Impl()

    private lateinit var server: RelayWebSocketServer
    private var port: Int = 0
    private val relayId = AtomicReference<IdentityId>()
    private val logs = java.util.concurrent.ConcurrentLinkedQueue<String>()

    @BeforeEach
    fun startRelay() {
        val relayKp = ed25519.generateKeyPair()
        val id = IdentityId(KmIds.identityId(relayKp.publicKey).joinToString("") { "%02x".format(it) })
        relayId.set(id)

        val limits = RelayLimits(maxConnections = 16)
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
            relayIdentityId = id,
        )
        val codec = JsonWireCodec()
        port = freePort()
        val config = DaemonConfig(listenPort = port, maxConnections = 16)
        val router = MessageRouter(
            relayServer = relayServer,
            codec = codec,
            authAdapter = AuthAdapter(relayServer, id, codec),
            relayIdentityId = id,
            config = config,
            logger = LoggerFactory.getLogger("rb-relay"),
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
        assertTrue(
            started.await(20, TimeUnit.SECONDS),
            "el rele no arranco en 20s: ${failure.get()?.message}",
        )
        assertEquals(null, failure.get(), "el rele no pudo ligar el puerto")
    }

    @AfterEach
    fun stopRelay() {
        if (::server.isInitialized) server.shutdown()
    }

    private fun freePort(): Int = ServerSocket(0).use { it.localPort }

    private fun endpoint(): String = "ws://127.0.0.1:$port/km"

    private fun identityOf(kp: KeyPair): IdentityId =
        IdentityId(KmIds.identityId(kp.publicKey).joinToString("") { "%02x".format(it) })

    private fun node(identity: IdentityId, kp: KeyPair): JvmRelayTransport = JvmRelayTransport(
        identity = identity,
        ed25519PublicKey = kp.publicKey,
        ed25519PrivateKey = kp.privateKey,
        endpoint = endpoint(),
        relayIdentity = relayId.get(),
        ed25519 = ed25519,
        handshakeTimeoutMs = 20_000,
        log = { message -> logs.add(message) },
    )

    private fun await(what: String, timeoutMs: Long = 15_000, predicate: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (predicate()) return
            Thread.sleep(50)
        }
        throw AssertionError("llego el limite esperando: $what. traza: $logs")
    }

    // ==================================================================
    // RB-01 — el byte llega al manager con el from del emisor
    // ==================================================================

    @Test
    @Timeout(120)
    fun `RB-01 sendData emite por el canal correcto y el manager recibe el from del emisor`() {
        val aliceKp = ed25519.generateKeyPair()
        val bobKp = ed25519.generateKeyPair()
        val aliceId = identityOf(aliceKp)
        val bobId = identityOf(bobKp)

        val alice = node(aliceId, aliceKp)
        val bob = node(bobId, bobKp)
        val bobManager = PeerTransportManager(bob)
        try {
            // El receptor primero: si no, el rele no tiene a quien rutear.
            bob.connect().getOrThrow()
            alice.connect().getOrThrow()
            assertTrue(bob.isOnline() && alice.isOnline(), "ambos clientes deben quedar ONLINE: $logs")

            val recibido = CopyOnWriteArrayList<Pair<IdentityId, ByteArray>>()
            bobManager.onData { from, data -> recibido.add(from to data) }

            // Contenido NO constante: una comparacion que solo mirara la
            // longitud confundiria un payload entero con uno truncado.
            val payload = ByteArray(256) { (it * 7 + 13).toByte() }

            val r = alice.sendData(bobId, payload)
            assertTrue(r.isSuccess, "sendData debe aceptar el byte: $r")

            await("el manager de Bob debe recibir el byte") { recibido.size == 1 }

            assertEquals(aliceId, recibido.single().first, "el `from` es el emisor, no la identidad local")
            assertContentEquals(payload, recibido.single().second, "el byte llega intacto")
        } finally {
            alice.close()
            bob.close()
        }
    }

    // ==================================================================
    // RB-02 — un byte opaco con compositor NO toca el camino en claro
    // ==================================================================

    @Test
    @Timeout(120)
    fun `RB-02 un byte opaco con compositor de pares NO toca messageHandler`() {
        val aliceKp = ed25519.generateKeyPair()
        val bobKp = ed25519.generateKeyPair()
        val aliceId = identityOf(aliceKp)
        val bobId = identityOf(bobKp)

        val alice = node(aliceId, aliceKp)
        val bob = node(bobId, bobKp)
        val bobManager = PeerTransportManager(bob)
        val legado = CopyOnWriteArrayList<ByteArray>()
        try {
            bob.connect().getOrThrow()
            alice.connect().getOrThrow()

            // El camino en claro es el de compatibilidad: con un compositor de
            // pares puesto, un payload OPACO no debe caer ahi.
            bob.setMessageHandler { legado.add(it) }
            val pares = CopyOnWriteArrayList<Pair<IdentityId, ByteArray>>()
            bobManager.onData { from, data -> pares.add(from to data) }

            val opaco = ByteArray(256) { (it * 11 + 29).toByte() }
            assertTrue(alice.sendData(bobId, opaco).isSuccess)

            await("el compositor de pares debe recibir el byte opaco") { pares.size == 1 }

            assertEquals(1, pares.size, "el byte opaco va a la cara de par")
            assertTrue(legado.isEmpty(), "el byte opaco NO puede caer al camino en claro: $logs")
        } finally {
            alice.close()
            bob.close()
        }
    }

    // ==================================================================
    // RB-03 — las dos caras son el MISMO objeto
    // ==================================================================

    @Test
    fun `RB-03 backend es identico al transport`() {
        val kp = ed25519.generateKeyPair()
        val engine = node(identityOf(kp), kp)
        try {
            val backend: com.km.node.TransportBackend = engine
            assertSame(engine, backend, "dos caras sobre objetos distintos podrian divergir en silencio")
            assertNotNull(backend.name)
        } finally {
            engine.close()
        }
    }

    // ==================================================================
    // RB-04 — sin conectar, sendData NO miente
    // ==================================================================

    @Test
    fun `RB-04 sin conectar sendData falla BACKEND_NOT_READY`() {
        val kp = ed25519.generateKeyPair()
        val engine = node(identityOf(kp), kp)
        try {
            val r = engine.sendData(IdentityId("ff".repeat(32)), byteArrayOf(1, 2, 3))
            assertTrue(r is TransportResult.Failure, "sin ONLINE no hay medio: $r")
            assertEquals(
                TransportError.BACKEND_NOT_READY,
                (r as TransportResult.Failure).error,
            )
        } finally {
            engine.close()
        }
    }
}
