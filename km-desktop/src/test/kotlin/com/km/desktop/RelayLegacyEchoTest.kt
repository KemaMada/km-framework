package com.km.desktop

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
import com.km.model.RelayLimits
import com.km.node.RelayServer
import com.km.node.RelayServiceImpl
import com.km.storage.InMemoryDuplicateStore
import com.km.storage.InMemoryMessageStore
import com.km.storage.InMemoryOfflineQueue
import com.km.storage.InMemoryRelayStore
import com.km.transport.JvmRelayTransport
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
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
 * LE — el cliente de escritorio NO recibe lo que envia.
 *
 * ## QUE PINZA
 *
 * El rele es un FAN-OUT a `envelope.to`, no un hub: `MessageRouter.deliverData`
 * busca la conexion de `envelope.to` y solo ahi escribe. Si el rele devolviera
 * el `MESSAGE` al emisor, el cliente veria su propio mensaje como si el par lo
 * hubiera contestado, y la aplicacion lo presentaria dos veces. Este test lo
 * fija con el RELE REAL y dos clientes de escritorio reales: Alice envia, Bob
 * lo recibe UNA vez, y Alice NO recibe nada.
 *
 * ## POR QUE ESTE TEST ESTA EN `km-desktop` Y NO EN `km-core`
 *
 * El invariante "el rele no hace eco al emisor" ya esta cubierto en `km-core`
 * (`MW-01`/`EncryptedReceivePath`), pero alli el rele es `RealRelayTransport`,
 * el doble de proceso. Aqui se cierra la cadena del PRODUCTO de escritorio: el
 * motor movido a `:km-transport`, el `PeerKeyDirectory` de escritorio y el
 * `KeyMessageCoreImpl` tal y como lo construye `DesktopMain`.
 *
 * ## SIN TOCAR `MessageRouter`
 *
 * El test no edita el rele: la mutacion M5-d (reapuntar el rele al emisor) es
 * la que se usa para comprobar que la asercion tiene dientes, y se aplica y se
 * revierte por el arnes de mutaciones.
 */
class RelayLegacyEchoTest {

    private val ed25519 = Ed25519Impl()

    private lateinit var server: RelayWebSocketServer
    private var port: Int = 0
    private val relayId = AtomicReference<IdentityId>()

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
            logger = LoggerFactory.getLogger("le-relay"),
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

    private class Node(
        val identity: IdentityId,
        val transport: JvmRelayTransport,
        val core: KeyMessageCoreImpl,
    )

    private fun node(kp: KeyPair, peers: PeerKeyDirectory, onMessage: () -> Unit = {}): Node {
        val identity = identityOf(kp)
        val transport = JvmRelayTransport(
            identity = identity,
            ed25519PublicKey = kp.publicKey,
            ed25519PrivateKey = kp.privateKey,
            endpoint = endpoint(),
            relayIdentity = relayId.get(),
            ed25519 = ed25519,
            handshakeTimeoutMs = 20_000,
        )
        val core = KeyMessageCoreImpl(
            localIdentity = identity,
            localPrivateKey = kp.privateKey,
            transport = transport,
            ed25519 = ed25519,
            messageStore = InMemoryMessageStore(),
            offlineQueue = InMemoryOfflineQueue(),
            duplicateStore = InMemoryDuplicateStore(),
            encoder = JsonEncoder(),
            decoder = JsonDecoder(),
            peerPublicKeyResolver = peers.resolver(),
        )
        core.registerMessageHandler { onMessage() }
        return Node(identity, transport, core)
    }

    private fun await(what: String, timeoutMs: Long = 15_000, predicate: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (predicate()) return
            Thread.sleep(50)
        }
        throw AssertionError("llego el limite esperando: $what")
    }

    @Test
    @Timeout(120)
    fun `LE-01 el cliente NO recibe lo que envia - Bob recibe 1 y Alice 0`() {
        val aliceKp = ed25519.generateKeyPair()
        val bobKp = ed25519.generateKeyPair()

        val alicePeers = PeerKeyDirectory().also {
            assertTrue(it.register(identityOf(bobKp), bobKp.publicKey))
        }
        val bobPeers = PeerKeyDirectory().also {
            assertTrue(it.register(identityOf(aliceKp), aliceKp.publicKey))
        }

        val deliveredToBob = AtomicInteger(0)
        val deliveredToAlice = AtomicInteger(0)
        val bob = node(bobKp, bobPeers) { deliveredToBob.incrementAndGet() }
        val alice = node(aliceKp, alicePeers) { deliveredToAlice.incrementAndGet() }
        try {
            bob.transport.connect().getOrThrow()
            alice.transport.connect().getOrThrow()
            assertTrue(bob.transport.isOnline() && alice.transport.isOnline())

            val created = alice.core.createMessage(bob.identity, "hola desde el escritorio 9134".toByteArray())
                .getOrThrow()
            alice.core.sendMessage(created).getOrThrow()

            await("Bob debe recibir el mensaje") { deliveredToBob.get() == 1 }

            assertEquals(1, deliveredToBob.get(), "el receptor recibe el mensaje UNA vez")
            assertEquals(
                0,
                deliveredToAlice.get(),
                "el EMISOR no recibe su propio mensaje: el rele es fan-out, no hub",
            )
        } finally {
            alice.core.stop()
            bob.core.stop()
            alice.transport.close()
            bob.transport.close()
        }
    }
}
