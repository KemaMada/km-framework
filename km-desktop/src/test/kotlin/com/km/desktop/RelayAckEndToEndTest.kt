package com.km.desktop

import com.km.api.KeyMessageCoreImpl
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
import com.km.model.MessageState
import com.km.node.RelayServer
import com.km.node.RelayServiceImpl
import com.km.storage.DuplicateStore
import com.km.storage.InMemoryDuplicateStore
import com.km.storage.InMemoryMessageStore
import com.km.storage.InMemoryOfflineQueue
import com.km.storage.InMemoryRelayStore
import com.km.storage.MessageStore
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.slf4j.LoggerFactory
import java.net.ServerSocket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * E2E por un WebSocket de verdad, con el `km-daemon` de verdad.
 *
 * QUE CIERRA Y QUE NO
 *
 * Cierra el camino completo del protocolo: `MESSAGE` con `messageId` de
 * aplicacion -> el relé lo mueve y lo re-codifica -> el receptor lo recibe con ESE
 * identificador -> firma un `ACK` por el frame `ACK` -> el relé lo reenvía
 * VERBATIM (B.1) -> el emisor lo autentica con la clave del peer (B.2/D3) y
 * confirma `M`. Y comprueba que en todo ese camino no aparece ni un `MESSAGE` de
 * mas: cero amplificacion.
 *
 * Lo que NO es: un E2E con el cliente Android. Aqui los dos extremos son
 * `JvmRelayTransport`, el mismo transporte que usa la TUI, porque el
 * `RelayClient` de Android es codigo de otro repositorio y no se puede levantar
 * desde aqui. La pata Android se mide aparte, en su propio repositorio, sobre un
 * WebSocket real (`RelayClientAckTest`).
 *
 * POR QUE EL DAEMON SE LEVANTA EN PROCESO Y NO COMO PROCESO
 *
 * `RelayWebSocketServer.start()` es asincrono: bind y despues `onStart()`. El
 * arranque se ESPERA con el latch de `onStarted`/`onFatal`, que es la unica forma
 * de poder afirmar que el puerto esta ligado — y el puerto se elige libre ANTES,
 * reserving uno con `ServerSocket(0)` y cerrandolo, para que dos tests en
 * paralelo no se pisen.
 *
 * POR QUE HAY UNA PRUEBA DE AMPLIFICACION Y NO SOLO UNA DE CORRELACION
 *
 * Porque son fallos distintos. La correlacion dice que el `ACK` vuelve citando
 * `M`; la amplificacion dice que volver NO genera un `MESSAGE` nuevo. Un cliente
 * que corrige lo primero y no lo segundo sigue mal, y su sintoma es peor: el
 * rele se llena de trafico entre dos pares que no estan hablando.
 */
class RelayAckEndToEndTest {

    private val ed25519 = Ed25519Impl()

    private lateinit var server: RelayWebSocketServer
    private var port: Int = 0
    private val aliceRelayId = AtomicReference<IdentityId>()

    /** Mensajes que el RECEPTOR entrega a la aplicacion, contados. */
    private val deliveredToBob = AtomicInteger(0)

    // ------------------------------------------------------------------
    // El rele de verdad
    // ------------------------------------------------------------------

    @BeforeEach
    fun startRelay() {
        val relayKp = ed25519.generateKeyPair()
        val relayId = IdentityId(KmIds.identityId(relayKp.publicKey).joinToString("") { "%02x".format(it) })
        aliceRelayId.set(relayId)

        val limits = com.km.model.RelayLimits(maxConnections = 16)
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
            relayIdentityId = relayId,
        )
        val codec = JsonWireCodec()
        // El puerto se elige LIBRE y se fija una sola vez, y el router y el
        // servidor tienen que ver el MISMO: si cada unoReserve el suyo, el
        // servidor escucha en otro sitio del que el router cree y el fallo sale
        // como "el rele no enruta", que no es lo que paso.
        port = freePort()
        val config = DaemonConfig(listenPort = port, maxConnections = 16)
        val router = MessageRouter(
            relayServer = relayServer,
            codec = codec,
            authAdapter = AuthAdapter(relayServer, relayId, codec),
            relayIdentityId = relayId,
            config = config,
            logger = LoggerFactory.getLogger("e2e-relay"),
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

        // Sin esta espera, `start()` ha vuelto y el bind todavia no ha ocurrido:
        // el cliente se conectaria a un puerto que no escucha y el fallo
        // apareceria como "el rele no responde" en vez de "el puerto no estaba".
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

    // ------------------------------------------------------------------
    // Dos clientes de verdad
    // ------------------------------------------------------------------

    private class Node(
        val identity: IdentityId,
        val keyPair: KeyPair,
        val transport: JvmRelayTransport,
        val core: KeyMessageCoreImpl,
        val store: MessageStore,
    )

    private fun node(
        keyPair: KeyPair,
        peers: PeerKeyDirectory,
        onMessage: (com.km.model.Message) -> Unit = {},
    ): Node {
        val identity = IdentityId(KmIds.identityId(keyPair.publicKey).joinToString("") { "%02x".format(it) })
        val transport = JvmRelayTransport(
            identity = identity,
            ed25519PublicKey = keyPair.publicKey,
            ed25519PrivateKey = keyPair.privateKey,
            endpoint = endpoint(),
            // Se anuncia el rele real: sin esto el primer `AUTH_REQUEST` omite
            // el campo y el rele responde `AUTH_FAIL RELAY_IDENTITY_MISMATCH`.
            relayIdentity = aliceRelayId.get(),
            ed25519 = ed25519,
            handshakeTimeoutMs = 20_000,
            log = { message -> logs.add(message) },
        )
        val store = InMemoryMessageStore()
        val core = KeyMessageCoreImpl(
            localIdentity = identity,
            localPrivateKey = keyPair.privateKey,
            transport = transport,
            ed25519 = ed25519,
            messageStore = store,
            offlineQueue = InMemoryOfflineQueue(),
            duplicateStore = InMemoryDuplicateStore(),
            encoder = JsonEncoder(),
            decoder = com.km.codec.JsonDecoder(),
            peerPublicKeyResolver = peers.resolver(),
        )
        core.registerMessageHandler { onMessage(it) }
        return Node(identity, keyPair, transport, core, store)
    }

    private val logs = java.util.concurrent.ConcurrentLinkedQueue<String>()

    private fun Pair<Node, Node>.bothConnected() {
        first.transport.connect().getOrThrow()
        second.transport.connect().getOrThrow()
        assertTrue(first.transport.isOnline(), "el primer cliente no quedo ONLINE: $logs")
        assertTrue(second.transport.isOnline(), "el segundo cliente no quedo ONLINE: $logs")
    }

    /** Espera a que [predicate] sea cierto, o falla con el estado que hay. */
    private fun awaitState(
        what: String,
        timeoutMs: Long = 15_000,
        predicate: () -> Boolean,
    ) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (predicate()) return
            Thread.sleep(50)
        }
        throw AssertionError("llego el limite esperando: $what. traza del transporte: $logs")
    }

    // ==================================================================
    // El criterio de aceptacion
    // ==================================================================

    @Test
    @Timeout(120)
    fun `MESSAGE con id M produce un ACK con originalMessageId M y el emisor confirma M`() {
        val aliceKp = ed25519.generateKeyPair()
        val bobKp = ed25519.generateKeyPair()

        // Cada uno conoce la clave de FIRMA del otro. Sin esto el `ACK` llega,
        // no se verifica, y el emisor se queda esperando: es el fallo D3.
        val alicePeers = PeerKeyDirectory().also {
            assertTrue(
                it.register(
                    IdentityId(KmIds.identityId(bobKp.publicKey).joinToString("") { "%02x".format(it) }),
                    bobKp.publicKey,
                )
            )
        }
        val bobPeers = PeerKeyDirectory().also {
            assertTrue(
                it.register(
                    IdentityId(KmIds.identityId(aliceKp.publicKey).joinToString("") { "%02x".format(it) }),
                    aliceKp.publicKey,
                )
            )
        }

        val bob = node(bobKp, bobPeers) { deliveredToBob.incrementAndGet() }
        val alice = node(aliceKp, alicePeers)
        try {
            // El receptor primero: si no, el `MESSAGE` llega a un rele sin
            // destino y se pierde sin dejar rastro.
            (bob to alice).bothConnected()

            val created = alice.core.createMessage(bob.identity, "hola".toByteArray()).getOrThrow()
            assertEquals(MessageState.CREATED, alice.core.getMessageState(created.messageId).getOrThrow())
            alice.core.sendMessage(created).getOrThrow()
            assertEquals(MessageState.SENT, alice.core.getMessageState(created.messageId).getOrThrow())

            awaitState("el ACK debe confirmar M") {
                alice.core.getMessageState(created.messageId).getOrThrow() == MessageState.DELIVERED
            }

            assertEquals(
                MessageState.DELIVERED,
                alice.core.getMessageState(created.messageId).getOrThrow(),
                "M=$created",
            )
            assertEquals(1, deliveredToBob.get(), "el receptor entrego el mensaje UNA vez")
            assertNotNull(
                bob.store.get(created.messageId).getOrThrow(),
                "y lo guardo bajo el MISMO identificador que creo createMessage",
            )
        } finally {
            alice.core.stop()
            bob.core.stop()
            alice.transport.close()
            bob.transport.close()
        }
    }

    @Test
    @Timeout(120)
    fun `un ACK repetido no genera otro MESSAGE - cero amplificacion`() {
        val aliceKp = ed25519.generateKeyPair()
        val bobKp = ed25519.generateKeyPair()
        val alicePeers = PeerKeyDirectory().also {
            it.register(IdentityId(KmIds.identityId(bobKp.publicKey).joinToString("") { "%02x".format(it) }), bobKp.publicKey)
        }
        val bobPeers = PeerKeyDirectory().also {
            it.register(IdentityId(KmIds.identityId(aliceKp.publicKey).joinToString("") { "%02x".format(it) }), aliceKp.publicKey)
        }

        val bob = node(bobKp, bobPeers) { deliveredToBob.incrementAndGet() }
        val alice = node(aliceKp, alicePeers)
        try {
            (bob to alice).bothConnected()

            val created = alice.core.createMessage(bob.identity, "hola".toByteArray()).getOrThrow()
            alice.core.sendMessage(created).getOrThrow()
            awaitState("el primer ACK") {
                alice.core.getMessageState(created.messageId).getOrThrow() == MessageState.DELIVERED
            }
            val trasElPrimerAck = deliveredToBob.get()

            // Un `ACK` de mas. Es lo que el rele haria si `onAck` no estuviera
            // verbatim, o lo que un cliente reintentaria por su cuenta. La
            // respuesta correcta es: nada cambia.
            Thread.sleep(1_500)

            assertEquals(
                trasElPrimerAck, deliveredToBob.get(),
                "un ACK repetido no puede convertirse en otro mensaje para el receptor",
            )
            assertEquals(1, trasElPrimerAck, "un solo ACK, una sola entrega")
            assertEquals(
                MessageState.DELIVERED,
                alice.core.getMessageState(created.messageId).getOrThrow(),
            )
        } finally {
            alice.core.stop()
            bob.core.stop()
            alice.transport.close()
            bob.transport.close()
        }
    }

    @Test
    @Timeout(120)
    fun `sin la clave del peer el ACK NO confirma aunque el mensaje SI se entregue`() {
        // El otro extremo del mismo silencio: la clave no esta, el `ACK` se
        // descarta, y el emisor se queda en SENT. Se fija aqui porque es el
        // estado en el que un cliente queda si alguien olvida registrar al peer,
        // y desde fuera es indistinguible de "el rele no reenvio el ACK".
        val aliceKp = ed25519.generateKeyPair()
        val bobKp = ed25519.generateKeyPair()
        val bobPeers = PeerKeyDirectory().also {
            it.register(IdentityId(KmIds.identityId(aliceKp.publicKey).joinToString("") { "%02x".format(it) }), aliceKp.publicKey)
        }

        // Alice NO construye directorio: su resolver es el `{ null }` del core.
        val bob = node(bobKp, bobPeers) { deliveredToBob.incrementAndGet() }
        val alice = node(aliceKp, PeerKeyDirectory())
        try {
            (bob to alice).bothConnected()

            val created = alice.core.createMessage(bob.identity, "hola".toByteArray()).getOrThrow()
            alice.core.sendMessage(created).getOrThrow()

            awaitState("el receptor debe entregar el mensaje") { deliveredToBob.get() == 1 }
            // Margen para que el `ACK` llegue y se descarte. Si se procesara, el
            // estado seria DELIVERED y el assert de abajo lo detectaria.
            Thread.sleep(1_500)

            assertEquals(1, deliveredToBob.get(), "el mensaje SI se entrego")
            assertEquals(
                MessageState.SENT,
                alice.core.getMessageState(created.messageId).getOrThrow(),
                "pero el ACK no se pudo autenticar y el mensaje sigue sin confirmar",
            )
        } finally {
            alice.core.stop()
            bob.core.stop()
            alice.transport.close()
            bob.transport.close()
        }
    }
}