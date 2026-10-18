package com.km.transport

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
import com.km.model.MessageId
import com.km.model.RelayLimits
import com.km.node.RelayServer
import com.km.node.RelayServiceImpl
import com.km.storage.InMemoryRelayStore
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.slf4j.LoggerFactory
import java.net.ServerSocket
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.assertContentEquals

/**
 * LR — la CLASIFICACION del `onMessage` del motor, probada por socket real.
 *
 * `onMessage` hace UNA pregunta —"puedo leer esta carga como mi `Message`?"— y
 * de ella salen dos caminos:
 *
 *  - carga LEGIBLE en el dialecto de km-core -> la cara legada (compositor de
 *    frames, o `messageHandler` de siempre si no hay compositor);
 *  - carga OPACA -> la cara de par (`onDataReceived`), o el FALLBACK legado si
 *    no hay compositor de pares.
 *
 * El verify de p4 dejo el hueco abierto: con la clasificacion desactivada
 * (mutacion M2-d) TODO cae al camino legado, y ninguna prueba lo detectaba.
 * Estos tests la atan al rele real: un frame tiene que recorrer el socket para
 * que el motor lo clasifique.
 *
 * ## LR-04 NO ESTA AQUI, Y NO ES UN OLVIDO
 *
 * El cuarto escenario del diseno ("`inbound.to != identity` -> nada, sin
 * excepcion") NO es alcanzable a traves del rele: este solo entrega a
 * `envelope.to`, luego Bob jamas recibe un frame dirigido a otro. Probarlo
 * exigiria llamar a un metodo `private` del motor, que es exactamente el tipo de
 * atajo que falsea un test. Se declara aqui en vez de fingirse cubierto.
 */
class LegacyAndPeerChannelRoutingTest {

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
            logger = LoggerFactory.getLogger("lr-relay"),
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
        assertEquals(null, failure.get())
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

    /** Una carga LEGADA de verdad: un `Message` de km-core codificado por `JsonEncoder`. */
    private fun legacyPayload(): ByteArray = JsonEncoder().encodeMessage(
        Message(
            messageId = MessageId(UUID.randomUUID()),
            from = IdentityId("11".repeat(32)),
            to = IdentityId("22".repeat(32)),
            payload = "carga legada no constante 4471".toByteArray(),
            timestamp = 1_790_000_000_000,
        ),
    ).getOrThrow()

    // ==================================================================
    // LR-01 — carga legada SIN compositor -> messageHandler
    // ==================================================================

    @Test
    @Timeout(120)
    fun `LR-01 carga legada sin compositor va al messageHandler`() {
        val aliceKp = ed25519.generateKeyPair()
        val bobKp = ed25519.generateKeyPair()
        val aliceId = identityOf(aliceKp)
        val bobId = identityOf(bobKp)

        val alice = node(aliceId, aliceKp)
        val bob = node(bobId, bobKp)
        val porPar = CopyOnWriteArrayList<ByteArray>()
        val legado = CopyOnWriteArrayList<ByteArray>()
        try {
            bob.connect().getOrThrow()
            alice.connect().getOrThrow()

            // SIN compositor de pares: el camino legado es el de siempre.
            bob.eventCallbacks = bob.eventCallbacks.copy(
                onDataReceived = { _, data -> porPar.add(data) },
            )
            bob.setMessageHandler { legado.add(it) }

            assertTrue(alice.send(legacyPayload(), bobId).isSuccess)

            await("el messageHandler debe recibir la carga legada") { legado.size == 1 }

            assertEquals(1, legado.size, "la carga legada va al camino en claro")
            assertTrue(porPar.isEmpty(), "y NO a la cara de par")
            val mensaje = JsonDecoder().decodeMessage(legado.single()).getOrThrow()
            assertContentEquals("carga legada no constante 4471".toByteArray(), mensaje.payload)
        } finally {
            alice.close()
            bob.close()
        }
    }

    // ==================================================================
    // LR-02 — carga opaca CON compositor -> onDataReceived, NO messageHandler
    // ==================================================================

    @Test
    @Timeout(120)
    fun `LR-02 carga opaca con compositor va a onDataReceived y NO a messageHandler`() {
        val aliceKp = ed25519.generateKeyPair()
        val bobKp = ed25519.generateKeyPair()
        val aliceId = identityOf(aliceKp)
        val bobId = identityOf(bobKp)

        val alice = node(aliceId, aliceKp)
        val bob = node(bobId, bobKp)
        val porPar = CopyOnWriteArrayList<Pair<IdentityId, ByteArray>>()
        val legado = CopyOnWriteArrayList<ByteArray>()
        try {
            bob.connect().getOrThrow()
            alice.connect().getOrThrow()

            bob.eventCallbacks = bob.eventCallbacks.copy(
                onDataReceived = { from, data -> porPar.add(from to data) },
            )
            bob.setMessageHandler { legado.add(it) }

            val opaco = ByteArray(256) { (it * 19 + 7).toByte() }
            assertTrue(alice.send(opaco, bobId).isSuccess)

            await("el compositor de pares debe recibir el byte opaco") { porPar.size == 1 }

            assertEquals(aliceId, porPar.single().first)
            assertContentEquals(opaco, porPar.single().second)
            assertTrue(legado.isEmpty(), "un SecureFrame NO puede caer al camino en claro: $logs")
        } finally {
            alice.close()
            bob.close()
        }
    }

    // ==================================================================
    // LR-03 — carga opaca SIN compositor -> FALLBACK al messageHandler
    // ==================================================================

    @Test
    @Timeout(120)
    fun `LR-03 carga opaca sin compositor cae al fallback del messageHandler`() {
        val aliceKp = ed25519.generateKeyPair()
        val bobKp = ed25519.generateKeyPair()
        val aliceId = identityOf(aliceKp)
        val bobId = identityOf(bobKp)

        val alice = node(aliceId, aliceKp)
        val bob = node(bobId, bobKp)
        val legado = CopyOnWriteArrayList<ByteArray>()
        try {
            bob.connect().getOrThrow()
            alice.connect().getOrThrow()

            // SIN compositor de pares: el byte opaco no tiene a donde ir, y el
            // motor cae al camino de siempre (bit a bit el de antes de p4).
            bob.setMessageHandler { legado.add(it) }

            val opaco = ByteArray(128) { (it * 23 + 3).toByte() }
            assertTrue(alice.send(opaco, bobId).isSuccess)

            await("el fallback debe entregar la carga opaca") { legado.size == 1 }

            assertEquals(1, legado.size, "sin compositor de pares, el opaco cae al legado")
            val mensaje = JsonDecoder().decodeMessage(legado.single()).getOrThrow()
            assertContentEquals(opaco, mensaje.payload, "el fallback envuelve el byte opaco intacto")
        } finally {
            alice.close()
            bob.close()
        }
    }
}
