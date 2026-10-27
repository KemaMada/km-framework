package com.km.desktop

import com.km.api.KeyMessageCore
import com.km.crypto.Ed25519Impl
import com.km.crypto.provider.BcX25519
import com.km.daemon.config.DaemonConfig
import com.km.daemon.server.AuthAdapter
import com.km.daemon.server.JsonWireCodec
import com.km.daemon.server.MessageRouter
import com.km.daemon.server.RelayWebSocketServer
import com.km.desktop.client.ClientEvents
import com.km.desktop.client.ContactService
import com.km.desktop.core.CoreFactory
import com.km.desktop.net.RelayStackFactory
import com.km.identity.ContactIntake
import com.km.identity.InMemoryContactRegistry
import com.km.identity.KmIds
import com.km.identity.LocalDeviceKeyFactory
import com.km.identity.LocalDeviceKeys
import com.km.model.IdentityId
import com.km.model.MessageState
import com.km.model.RelayLimits
import com.km.node.RelayServer
import com.km.node.RelayServiceImpl
import com.km.storage.InMemoryDuplicateStore
import com.km.storage.InMemoryMessageStore
import com.km.storage.InMemoryOfflineQueue
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
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.slf4j.LoggerFactory

/**
 * E2E por un WebSocket REAL con el `km-daemon` REAL: dos clientes de escritorio.
 *
 * ## QUE CIERRA
 *
 * El criterio de salida de G1, por el camino de producto: import de un `.km`
 * generado, `SIGNAL/contact_exchange`, PREKEY SOLO tras Delivered, texto cifrado
 * ida y vuelta, `ACK` verificado -> `DELIVERED` con el MISMO `messageId`.
 *
 * ## LOS DOS EXTREMOS SON CLIENTES DE VERDAD
 *
 * Cada lado es identidad + material de dispositivo + `JvmRelayTransport` +
 * `RelayClientStack` + nucleo de km-core + [ContactService]. No hay atajos: la
 * sesion se abre por X3DH y el PREKEY cruza el rele como un frame mas.
 *
 * ## QUE NO PRUEBA
 *
 * No prueba el cliente Android: sus `.km` golden no traen clave privada, asi que
 * con ellos no se puede establecer sesion. La interoperabilidad del PORTADOR se
 * prueba en `DesktopAndroidGoldenImportTest`, y la del bundle de escritorio en
 * `DesktopBundleInteropTest` de la app.
 */
class DesktopContactExchangeE2ETest {

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
            logger = LoggerFactory.getLogger("g1-e2e"),
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
    // Un cliente completo, con servicios
    // ------------------------------------------------------------------

    private class Holder(var keys: LocalDeviceKeys)

    private class Side(
        val nombre: String,
        val identity: DesktopIdentity,
        val holder: Holder,
        val transport: JvmRelayTransport,
        val stack: RelayClientStack,
        val core: CoreFactory.CoreBundle,
        val peerKeys: PeerKeyDirectory,
        val contacts: InMemoryContactRegistry,
        val events: ClientEvents,
        val coordinator: ContactExchangeCoordinator,
        val service: ContactService,
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
        val core = CoreFactory().create(
            localIdentity = identity.identityId,
            localPrivateKey = firma.privateKey,
            transport = transport,
            manager = stack.manager,
            peerPublicKeyResolver = peerKeys.resolver(),
        )
        val contacts = InMemoryContactRegistry()
        val events = ClientEvents()
        val coordinator = ContactExchangeCoordinator(
            conocido = { emisor -> core.registry.find(emisor) != null || contacts.find(emisor) != null },
            esPrekey = { wire -> com.km.frame.PrekeyFrame.esPrekey(wire) },
        )
        val recibidos = AtomicInteger(0)
        lateinit var service: ContactService
        service = ContactService(
            events = events,
            contacts = contacts,
            intake = ContactIntake(contacts) { identity.identityId },
            stack = { stack },
            sessionRegistry = { core.registry },
            identity = { identity },
            deviceKeys = { holder.keys },
            // El cable de PRODUCTO: el mismo directorio que resuelve los ACK se
            // llena con el import y con el contact_exchange aceptado. El test no
            // registra claves a mano en ningun sitio.
            peerKeys = { peerKeys },
            onOneTimePreKeyConsumed = { keyId -> holder.keys = holder.keys.withoutOneTimePreKey(keyId) },
        )

        // El cable de producto: PREKEY por el canal de par, bundle por la senal.
        service.attachInbound(stack, coordinator)
        // Presencia -> conductor del par, y conductor al instalar una sesion.
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
            nombre, identity, holder, transport, stack, core, peerKeys, contacts, events,
            coordinator, service, recibidos,
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

    // ------------------------------------------------------------------
    // El criterio: un solo scan
    // ------------------------------------------------------------------

    @Test
    @Timeout(180)
    fun `import SIGNAL PREKEY y texto con ACK en el MISMO messageId`() {
        val bob = side("bob")
        val alice = side("alice")
        try {
            bob.connect()
            alice.connect()

            // Bob publica su portador; Alice lo IMPORTA por el camino unico.
            // El import registra la clave de firma del contacto, que es la que
            // autentica el ACK que Bob mande: no hay registro manual.
            val bundleBob = bob.service.exportOwn()
            val contactoBob = alice.service.importText(bundleBob.text)
            assertEquals(bundleBob.identityId, contactoBob.identityRoot)
            assertNotNull(
                alice.peerKeys.signingKeyOf(bob.identity.identityId),
                "importText registra la clave de firma del contacto aceptado",
            )

            // La secuencia del emisor: exportar una vez -> SIGNAL -> PREKEY.
            val anuncio = runBlocking { alice.service.announce(contactoBob) }
            assertTrue(anuncio.isSuccess, "el intercambio salio: ${anuncio.exceptionOrNull()?.message}")

            // Bob auto-registro a Alice por el contact_exchange y drenó su PREKEY.
            await("bob registra a alice") { bob.contacts.find(alice.identity.identityId) != null }
            await("bob acepta el PREKEY") { bob.core.registry.find(alice.identity.identityId) != null }
            assertEquals(1, bob.contacts.all().size)

            // La clave de firma de Alice la registro el PROPIO receiveBundle.
            assertNotNull(
                bob.peerKeys.signingKeyOf(alice.identity.identityId),
                "receiveBundle registra la clave de firma del emisor aceptado",
            )

            // Texto cifrado de ida, con ACK verificado.
            val texto = "hola desde el escritorio G1"
            val creado = core(alice).createMessage(bob.identity.identityId, texto.toByteArray()).getOrThrow()
            val envio = core(alice).sendMessage(creado).getOrThrow()
            assertEquals(MessageState.SENT, envio.state, "SENT significa OUT, no entrega")

            await("el ACK confirma el MISMO messageId") {
                core(alice).getMessageState(creado.messageId).getOrThrow() == MessageState.DELIVERED
            }
            assertEquals(1, bob.recibidos.get(), "el receptor entrego el mensaje UNA vez")
            assertEquals(
                MessageState.DELIVERED,
                core(alice).getMessageState(creado.messageId).getOrThrow(),
            )
            assertNotEquals(MessageState.SENT, MessageState.DELIVERED)
        } finally {
            alice.close()
            bob.close()
        }
    }

    @Test
    @Timeout(180)
    fun `sin Delivered NO hay PREKEY ni sesion`() {
        val alice = side("alice")
        val bob = side("bob")
        try {
            // Alice en linea, Bob NO: el rele contesta PEER_NOT_FOUND y el
            // retry acotado se agota.
            alice.connect()
            val bundleBob = bob.service.exportOwn()
            val contactoBob = alice.service.importText(bundleBob.text)

            val anuncio = runBlocking { alice.service.announce(contactoBob) }
            assertTrue(anuncio.isFailure, "sin par no hay Delivered")
            assertEquals(
                null,
                alice.core.registry.find(bob.identity.identityId),
                "y NO se abrio sesion: el PREKEY no se emitio",
            )
            assertEquals(0, bob.recibidos.get())
        } finally {
            alice.close()
            bob.close()
        }
    }

    @Test
    @Timeout(180)
    fun `un contact_exchange con la raiz de OTRO no registra al emisor`() {
        val bob = side("bob")
        val alice = side("alice")
        val mallory = side("mallory")
        try {
            bob.connect()
            alice.connect()

            // Alice manda el bundle de Mallory firmando el SIGNAL con su sesion:
            // el bundle VERIFICA, pero no es de quien lo envia.
            val bundleMallory = mallory.service.exportOwn()
            alice.stack.sendContactBundle(
                bob.identity.identityId,
                bundleMallory.text.toByteArray(Charsets.ISO_8859_1),
            ).getOrThrow()

            await("bob procesa el SIGNAL") {
                bob.events.tail().any { it.contains("contact_exchange rechazado") }
            }
            assertEquals(0, bob.contacts.all().size, "raiz != from no crea contacto")
            assertEquals(null, bob.core.registry.find(mallory.identity.identityId))
            assertTrue(
                bob.peerKeys.known().isEmpty(),
                "un bundle RECHAZADO no envenena PeerKeyDirectory: cero claves registradas",
            )
        } finally {
            alice.close()
            bob.close()
            mallory.close()
        }
    }

    @Test
    @Timeout(180)
    fun `sin ACK no hay DELIVERED`() {
        val bob = side("bob")
        val alice = side("alice")
        try {
            bob.connect()
            alice.connect()
            val bundleBob = bob.service.exportOwn()
            val contactoBob = alice.service.importText(bundleBob.text)
            assertTrue(runBlocking { alice.service.announce(contactoBob) }.isSuccess)
            await("sesion en bob") { bob.core.registry.find(alice.identity.identityId) != null }

            // Bob se va: el rele no guarda, y el ACK no puede volver.
            bob.transport.close()

            val creado = core(alice).createMessage(bob.identity.identityId, "sin par".toByteArray()).getOrThrow()
            core(alice).sendMessage(creado).getOrThrow()

            // Se le da tiempo de sobra a un ACK que no existe.
            Thread.sleep(2_000)
            assertNotEquals(
                MessageState.DELIVERED,
                core(alice).getMessageState(creado.messageId).getOrThrow(),
                "sin ACK verificado NO se declara entregado",
            )
        } finally {
            alice.close()
            bob.close()
        }
    }
}
