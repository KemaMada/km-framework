package com.km.desktop

import com.km.api.KeyMessageCore
import com.km.crypto.Ed25519Impl
import com.km.crypto.provider.BcX25519
import com.km.daemon.config.DaemonConfig
import com.km.daemon.server.AuthAdapter
import com.km.daemon.server.JsonWireCodec
import com.km.daemon.server.MessageRouter
import com.km.daemon.server.RelayWebSocketServer
import com.km.desktop.client.ChatService
import com.km.desktop.client.ClientEvents
import com.km.desktop.client.ContactService
import com.km.desktop.core.CoreFactory
import com.km.desktop.net.RelayStackFactory
import com.km.desktop.support.DesktopManualRetryScheduler
import com.km.identity.ContactIntake
import com.km.identity.InMemoryContactRegistry
import com.km.identity.KmIds
import com.km.identity.LocalDeviceKeyFactory
import com.km.identity.LocalDeviceKeys
import com.km.model.FailureCode
import com.km.model.IdentityId
import com.km.model.Message
import com.km.model.MessageId
import com.km.model.MessageState
import com.km.model.RelayLimits
import com.km.node.RelayServer
import com.km.node.RelayServiceImpl
import com.km.protocol.SenderImpl
import com.km.storage.InMemoryRelayStore
import com.km.transport.JvmRelayTransport
import com.km.transport.RelayClientStack
import com.km.transport.contact.ContactExchangeCoordinator
import kotlinx.coroutines.runBlocking
import java.net.ServerSocket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.slf4j.LoggerFactory

/**
 * F7.4 — EXPIRED al techo REAL y `ChatService.resend` conservando identidad.
 *
 * ## QUE CIERRA
 *
 * El hueco que dejo la verificacion independiente (task 4.3): `km-core` probaba
 * `EXPIRED + resend` con un enlace falso, pero `km-desktop` no podia reproducirlo
 * porque `CoreFactory` no permitia inyectar el reloj del reintento y el techo es
 * de 155 s. Ahora [CoreFactory.create] acepta un [com.km.protocol.RetryScheduler]
 * y este test lo avanza a mano: **cero esperas reales de 155 s**.
 *
 * ## LOS DOS EXTREMOS SON EL PRODUCTO
 *
 * Relay `km-daemon` REAL por WebSocket, dos [ContactService] reales, import real
 * (que ademas registra la clave de firma del par: F7.1), SIGNAL -> PREKEY via
 * `announce`, sesion X3DH real y frames cifrados reales. No hay enlace falso ni
 * frames inventados: lo unico simulado es QUIEN mueve el reloj.
 *
 * ## QUE SE AFIRMA, EN ORDEN
 *
 * 1. Con el par fuera, dos mensajes se agotan al techo: `EXPIRED` con motivo
 *    `TIMEOUT`, FIFO intacta (el segundo queda como tombstone, no se adelanta).
 * 2. `ChatService.resend(id)` con el par de vuelta: `DELIVERED` de verdad, con
 *    el MISMO `messageId` y el MISMO timestamp original (no se crea una fila).
 * 3. El receptor ve cada mensaje UNA vez y en el orden de creacion.
 */
class DesktopExpiredResendTest {

    private val ed25519 = Ed25519Impl()

    private var server: RelayWebSocketServer? = null
    private var port: Int = 0
    private var relayId: IdentityId? = null

    @BeforeEach
    fun startRelay() {
        val relayKp = ed25519.generateKeyPair()
        val relayIdentity = IdentityId(KmIds.identityId(relayKp.publicKey).joinToString("") { "%02x".format(it) })
        relayId = relayIdentity
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
            relayIdentityId = relayIdentity,
        )
        val codec = JsonWireCodec()
        port = ServerSocket(0).use { it.localPort }
        val config = DaemonConfig(listenPort = port, maxConnections = 16)
        val router = MessageRouter(
            relayServer = relayServer,
            codec = codec,
            authAdapter = AuthAdapter(relayServer, relayIdentity, codec),
            relayIdentityId = relayIdentity,
            config = config,
            logger = LoggerFactory.getLogger("g1-expired"),
        )
        val started = CountDownLatch(1)
        var fatal: Exception? = null
        server = RelayWebSocketServer(
            config = config,
            router = router,
            codec = codec,
            onStarted = { started.countDown() },
            onFatal = { started.countDown(); fatal = it },
        )
        server!!.start()
        assertTrue(started.await(20, TimeUnit.SECONDS), "el rele no arranco: ${fatal?.message}")
        assertEquals(null, fatal)
    }

    @AfterEach
    fun stopRelay() {
        server?.shutdown()
    }

    // ------------------------------------------------------------------
    // Un cliente completo, con reloj manual
    // ------------------------------------------------------------------

    private class Holder(var keys: LocalDeviceKeys)

    private class Side(
        val nombre: String,
        val identity: DesktopIdentity,
        val transport: JvmRelayTransport,
        val stack: RelayClientStack,
        val core: CoreFactory.CoreBundle,
        val peerKeys: PeerKeyDirectory,
        val contacts: InMemoryContactRegistry,
        val events: ClientEvents,
        val service: ContactService,
        val chat: ChatService,
        val scheduler: DesktopManualRetryScheduler,
        val recibidos: AtomicInteger,
    ) {
        fun close() {
            runCatching { core.core.stop() }
            runCatching { stack.close() }
        }
    }

    private fun side(nombre: String): Side {
        val relayIdentity = requireNotNull(relayId) { "el rele no esta arrancado" }
        val firma = ed25519.generateKeyPair()
        val acuerdo = BcX25519().generateKeyPair()
        val identity = DesktopIdentity(
            identityId = IdentityId(KmIds.identityId(firma.publicKey).joinToString("") { "%02x".format(it) }),
            ed25519PublicKey = firma.publicKey,
            ed25519PrivateKey = firma.privateKey,
            x25519PublicKey = acuerdo.publicKey,
            x25519PrivateKey = acuerdo.privateKey,
        )
        val holder = Holder(LocalDeviceKeyFactory(firma.publicKey).generate(3))
        val transport = JvmRelayTransport(
            identity = identity.identityId,
            ed25519PublicKey = firma.publicKey,
            ed25519PrivateKey = firma.privateKey,
            endpoint = "ws://127.0.0.1:$port/km",
            relayIdentity = relayIdentity,
            ed25519 = ed25519,
            handshakeTimeoutMs = 20_000,
        )
        val stack = RelayStackFactory.stackFor(transport)
        val peerKeys = PeerKeyDirectory()
        val scheduler = DesktopManualRetryScheduler()
        val core = CoreFactory().create(
            localIdentity = identity.identityId,
            localPrivateKey = firma.privateKey,
            transport = transport,
            manager = stack.manager,
            peerPublicKeyResolver = peerKeys.resolver(),
            retryScheduler = scheduler,
        )
        val contacts = InMemoryContactRegistry()
        val events = ClientEvents()
        val coordinator = ContactExchangeCoordinator(
            conocido = { emisor -> core.registry.find(emisor) != null || contacts.find(emisor) != null },
            esPrekey = { wire -> com.km.frame.PrekeyFrame.esPrekey(wire) },
        )
        val recibidos = AtomicInteger(0)
        val service = ContactService(
            events = events,
            contacts = contacts,
            intake = ContactIntake(contacts) { identity.identityId },
            stack = { stack },
            sessionRegistry = { core.registry },
            identity = { identity },
            deviceKeys = { holder.keys },
            peerKeys = { peerKeys },
            onOneTimePreKeyConsumed = { keyId -> holder.keys = holder.keys.withoutOneTimePreKey(keyId) },
        )

        service.attachInbound(stack, coordinator)
        transport.onPeerOnline { peer -> stack.driverOf(peer)?.onPeerOnline(peer) }
        transport.onPeerOffline { peer -> stack.driverOf(peer)?.onPeerOffline(peer) }
        core.registry.onInstall { sesion ->
            val conductor = stack.driverFor(sesion.peerContext)
            if (transport.isPeerPresent(sesion.remotePeerId)) {
                conductor.onPeerPresent(sesion.remotePeerId)
            }
        }
        core.core.registerMessageHandler { recibidos.incrementAndGet() }

        return Side(
            nombre, identity, transport, stack, core, peerKeys, contacts, events,
            service, ChatService(events) { core.core }, scheduler, recibidos,
        )
    }

    private fun Side.connect() {
        transport.connect().getOrThrow()
        assertTrue(transport.isOnline(), "$nombre no quedo ONLINE")
    }

    private fun await(que: String, timeoutMs: Long = 20_000, predicate: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (predicate()) return
            Thread.sleep(50)
        }
        throw AssertionError("se agoto el tiempo esperando: $que")
    }

    private fun core(node: Side): KeyMessageCore = node.core.core

    /** No existe `getMessage` en el contrato publico: la fila sale de la conversacion. */
    private fun fila(core: KeyMessageCore, peer: IdentityId, id: MessageId): Message =
        core.getConversation(peer).getOrThrow().first { it.messageId == id }

    // ------------------------------------------------------------------
    // El test
    // ------------------------------------------------------------------

    @Test
    @Timeout(180)
    fun `al techo expira FIFO y el resend conserva id timestamp y entrega una vez`() {
        val bob = side("bob")
        val alice = side("alice")
        try {
            bob.connect()
            alice.connect()

            // Import REAL: registra la clave de firma del par (F7.1), de modo que
            // el ACK del resend se pueda autenticar sin tocar el directorio a mano.
            val contactoBob = alice.service.importText(bob.service.exportOwn().text)
            assertNotNull(alice.peerKeys.signingKeyOf(bob.identity.identityId))
            assertTrue(runBlocking { alice.service.announce(contactoBob) }.isSuccess, "sin SIGNAL->PREKEY no hay sesion")
            await("bob registra a alice") { bob.contacts.find(alice.identity.identityId) != null }
            await("bob acepta el PREKEY") { bob.core.registry.find(alice.identity.identityId) != null }

            // Bob se va: el rele NO guarda, asi que sus mensajes no llegaran.
            // `disconnect()` y no `close()`: close cancela el scope propio del
            // motor y un reconnect posterior ya no puede completar el handshake.
            bob.transport.disconnect()
            await("alice ve a bob fuera") { !alice.transport.isPeerPresent(bob.identity.identityId) }

            val primero = core(alice).createMessage(bob.identity.identityId, "uno".toByteArray()).getOrThrow()
            val segundo = core(alice).createMessage(bob.identity.identityId, "dos".toByteArray()).getOrThrow()
            core(alice).sendMessage(primero).getOrThrow()
            core(alice).sendMessage(segundo).getOrThrow()

            val timestampPrimero = fila(core(alice), bob.identity.identityId, primero.messageId).timestamp
            val timestampSegundo = fila(core(alice), bob.identity.identityId, segundo.messageId).timestamp
            assertTrue(timestampSegundo > timestampPrimero, "FIFO: las marcas respetan el orden de creacion")

            // Los DOS se agotan: 155 s para la cabeza + 155 s para la que se
            // promociona detras. Cero esperas reales: el reloj es el del test.
            alice.scheduler.advanceBy(SenderImpl.HEAD_WAIT_CEILING_MS * 2)
            assertEquals(0, alice.scheduler.pendingCount(), "no queda ningun temporizador vivo")

            listOf(primero.messageId, segundo.messageId).forEach { id ->
                val tombstone = fila(core(alice), bob.identity.identityId, id)
                assertEquals(MessageState.EXPIRED, tombstone.state, "al techo la fila queda EXPIRED")
                assertEquals(FailureCode.TIMEOUT, tombstone.failureCode, "con motivo TIMEOUT")
            }
            assertEquals(0, bob.recibidos.get(), "el par ausente no recibio nada")

            // Bob vuelve, con la MISMA sesion (el registro vive en memoria).
            bob.transport.connect().getOrThrow()
            await("alice ve a bob de vuelta") { alice.transport.isPeerPresent(bob.identity.identityId) }
            await("el canal del par vuelve a CONNECTED") {
                alice.stack.driverOf(bob.identity.identityId)?.isConnected(bob.identity.identityId) == true
            }

            // Reintento EXPLICITO del primero: mismo id, mismo timestamp.
            val resendPrimero = alice.chat.resend(primero.messageId)
            assertTrue(resendPrimero.isSuccess, "resend del tombstone: ${resendPrimero.exceptionOrNull()?.message}")
            await("el ACK confirmado del primero") {
                alice.chat.stateOf(primero.messageId) == MessageState.DELIVERED
            }
            val trasResendPrimero = fila(core(alice), bob.identity.identityId, primero.messageId)
            assertEquals(MessageState.DELIVERED, trasResendPrimero.state)
            assertEquals(primero.messageId, trasResendPrimero.messageId, "no se fabrico otro id")
            assertEquals(timestampPrimero, trasResendPrimero.timestamp, "conserva el timestamp ORIGINAL")

            val resendSegundo = alice.chat.resend(segundo.messageId)
            assertTrue(resendSegundo.isSuccess, "resend del segundo: ${resendSegundo.exceptionOrNull()?.message}")
            await("el ACK confirmado del segundo") {
                alice.chat.stateOf(segundo.messageId) == MessageState.DELIVERED
            }
            val trasResendSegundo = fila(core(alice), bob.identity.identityId, segundo.messageId)
            assertEquals(timestampSegundo, trasResendSegundo.timestamp, "y el suyo, tambien original")

            // El emisor no creo filas nuevas: dos, las mismas.
            val filasEmisor = core(alice).getConversation(bob.identity.identityId).getOrThrow()
            assertEquals(2, filasEmisor.size, "dos filas, no cuatro")
            assertEquals(
                listOf(primero.messageId, segundo.messageId),
                filasEmisor.sortedBy { it.timestamp }.map { it.messageId },
                "FIFO del emisor intacto",
            )

            // El receptor ve cada mensaje UNA vez, en el orden de creacion.
            await("bob entrega los dos") { bob.recibidos.get() == 2 }
            val recibidos = core(bob).getConversation(alice.identity.identityId).getOrThrow()
            assertEquals(
                listOf(primero.messageId, segundo.messageId),
                recibidos.sortedBy { it.timestamp }.map { it.messageId },
                "el receptor los tiene en orden y una sola vez",
            )
            assertEquals(listOf("uno", "dos"), recibidos.sortedBy { it.timestamp }.map { String(it.payload) })
            assertEquals(2, recibidos.size, "ni un duplicado: el dedupe es por (messageId, from)")
        } finally {
            alice.close()
            bob.close()
        }
    }
}
