package com.km.transport

import com.km.api.KeyMessageCoreImpl
import com.km.codec.JsonDecoder
import com.km.codec.JsonEncoder
import com.km.crypto.Ed25519Impl
import com.km.crypto.KeyPair
import com.km.identity.KmIds
import com.km.model.IdentityId
import com.km.model.Message
import com.km.model.MessageId
import com.km.model.MessageState
import com.km.node.PeerTransportManager
import com.km.protocol.ConnectionState
import com.km.storage.InMemoryDuplicateStore
import com.km.storage.InMemoryMessageStore
import com.km.storage.InMemoryOfflineQueue
import com.km.transport.SocketSecureWorld.awaitOrFail
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.websocket.WebSockets
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/**
 * T1 — LLEGADA REAL, CONTADA POR ID, CON CORTE DE RED Y RELE DE VERDAD.
 *
 * ## QUE MIDE ESTE TEST, Y QUE NO
 *
 * `SenderDeliveryOrderTest` mide TRANSMISION con un enlace falso. Este mide
 * **llegada**: el `KeyMessageCoreImpl` del receptor, su `MessageStore` y el
 * `getConversation`. El criterio es el numero de FILAS y el orden por
 * `messageId`/payload, nunca "cuantas burbujas se ven": el autoscroll de la
 * pantalla puede dejar la primera fuera de la ventana y eso NO es perdida.
 *
 * ## MONTAJE
 *
 * Rele real (`RelayWebSocketServer` de `km-daemon`), dos nucleos reales y un
 * proxy TCP que puede LEER Y DESCARTAR sin cerrar (blackhole). El corte se
 * aplica al emisor: su socket parece vivo, los bytes se tiran, y cuando el ping
 * de OkHttp lo saca de `ONLINE` los envios nuevos caen en la cola. Al abrir el
 * proxy, la reconexion pasiva vuelve y la cabeza entrega el resto.
 *
 * ## CONTROLES
 *
 * - **Positivo**: dos rafagas sin corte -> 10 filas en orden. Sin esto, un fallo
 *   del arnes se confundiria con el defecto.
 * - **Negativo**: `ArrivalCheck` rechaza `[2,3,4,5]` (la forma del sintoma: 5
 *   enviados, falta la cabeza). Un detector que aceptara eso no valdria.
 * - **Puerta de cabeza** (test 3): cabeza `SENT` con retry programado + un
 *   seguidor `QUEUED`, el par vuelve, y se exige que el seguidor NO salga hasta
 *   que la cabeza tenga ACK. ESTE es el control que mata el drenado avaro: los
 *   tests 1 y 2 NO lo matan, y se dice aqui para no volver a creerlo. El motivo
 *   esta medido: `InMemoryMessageStore.listByConversation` ordena por
 *   `timestamp`, asi que `getConversation` devuelve el orden de CREACION pase lo
 *   que pase con el de llegada — el conteo y el orden de esas dos pruebas no
 *   pueden ver un drenado que recorre la cola en orden. El test 3 mide dos cosas
 *   que si lo ven: (a) el instante exacto en que el seguidor sale de `QUEUED`,
 *   leido desde el hilo del emisor, y (b) el orden real de llegada capturado en
 *   el handler del receptor (`onMessage`), no en `getConversation`.
 */
class SenderBurstArrivalOrderE2ETest {

    private val ed25519 = Ed25519Impl()
    private lateinit var relay: LocalRelay
    private val open = CopyOnWriteArrayList<JvmRelayTransport>()
    private val cores = CopyOnWriteArrayList<KeyMessageCoreImpl>()
    private val trace = CopyOnWriteArrayList<String>()

    @BeforeEach
    fun startRelay() {
        relay = LocalRelay.start("burst-arrival")
    }

    @AfterEach
    fun tearDown() {
        cores.forEach { runCatching { it.stop() } }
        cores.clear()
        open.forEach { runCatching { it.close() } }
        open.clear()
        relay.shutdown()
    }

    // ==================================================================
    // Montaje
    // ==================================================================

    private class Node(
        val identity: IdentityId,
        val transport: JvmRelayTransport,
        val core: KeyMessageCoreImpl,
        val store: InMemoryMessageStore,
        val queue: InMemoryOfflineQueue,
    )

    private fun identityOf(kp: KeyPair): IdentityId =
        IdentityId(KmIds.identityId(kp.publicKey).joinToString("") { "%02x".format(it) })

    private fun node(
        keyPair: KeyPair,
        peers: PeerKeyDirectory,
        url: String = relay.endpoint(),
        clientFactory: (() -> HttpClient)? = null,
        onMessage: (Message) -> Unit = {},
    ): Node {
        val identity = identityOf(keyPair)
        val transport = JvmRelayTransport(
            identity = identity,
            ed25519PublicKey = keyPair.publicKey,
            ed25519PrivateKey = keyPair.privateKey,
            endpoint = url,
            relayIdentity = relay.relayIdentityId,
            ed25519 = ed25519,
            reconnect = ReconnectPolicy(initialDelayMs = 150, maxDelayMs = 400),
            handshakeTimeoutMs = 2_000,
            clientFactory = clientFactory ?: { JvmRelayTransport.defaultClient() },
            log = { message -> trace.add("[${identity.value.take(6)}] $message") },
        )
        open.add(transport)
        val store = InMemoryMessageStore()
        val queue = InMemoryOfflineQueue()
        val core = KeyMessageCoreImpl(
            localIdentity = identity,
            localPrivateKey = keyPair.privateKey,
            transport = transport,
            ed25519 = ed25519,
            messageStore = store,
            offlineQueue = queue,
            duplicateStore = InMemoryDuplicateStore(),
            encoder = JsonEncoder(),
            decoder = JsonDecoder(),
            // El ACK se firma con la clave de la raiz y se verifica contra la
            // que este directorio resuelve. Sin esto el ACK llega y no procesa,
            // la cabeza no avanza y el test mediria el techo de 155 s.
            peerPublicKeyResolver = peers.resolver(),
        )
        core.registerMessageHandler(onMessage)
        cores.add(core)
        return Node(identity, transport, core, store, queue)
    }

    private fun peersOf(vararg otros: KeyPair): PeerKeyDirectory =
        PeerKeyDirectory().also { dir ->
            otros.forEach { otro -> dir.register(identityOf(otro), otro.publicKey) }
        }

    private fun okHttpWithPing(pingMs: Long): () -> HttpClient = {
        HttpClient(OkHttp) {
            install(WebSockets)
            engine { config { pingInterval(pingMs, TimeUnit.MILLISECONDS) } }
        }
    }

    private fun send(node: Node, peer: IdentityId, texto: String) {
        val mensaje = node.core.createMessage(peer, texto.toByteArray()).getOrThrow()
        node.core.sendMessage(mensaje).getOrThrow()
    }

    /** Las llegadas al receptor, en orden y como numero de escritura. */
    private fun arrivals(bob: Node, alice: Node): List<String> =
        bob.core.getConversation(alice.identity).getOrThrow().map { String(it.payload) }

    /**
     * El estado que el emisor tiene escrito para [id] — la verdad observable.
     *
     * Se lee del `MessageStore` del NODO y no de un contador del test: el
     * `store` es lo que sobrevive al proceso y lo que la app pinta.
     */
    private fun estado(node: Node, id: MessageId): MessageState =
        requireNotNull(node.store.get(id).getOrThrow()) { "no hay fila para $id" }.state

    // ==================================================================
    // 1. CONTROL POSITIVO — dos rafagas sin corte: 10/10 y en orden
    // ==================================================================

    @Test
    @Timeout(180)
    fun `control positivo dos rafagas sin corte llegan completas y en orden`() {
        val aliceKp = ed25519.generateKeyPair()
        val bobKp = ed25519.generateKeyPair()
        val bob = node(bobKp, peersOf(aliceKp))
        val alice = node(aliceKp, peersOf(bobKp))

        try {
            bob.transport.connect().getOrThrow()
            alice.transport.connect().getOrThrow()
            awaitOrFail("los dos motores ONLINE", trace = { trace.toString() }) {
                bob.transport.isOnline() && alice.transport.isOnline()
            }

            repeat(2) { rafaga ->
                (1..5).forEach { n -> send(alice, bob.identity, "${n + rafaga * 5}") }
            }

            awaitOrFail("las DIEZ filas llegan al receptor", timeoutMs = 60_000, trace = { trace.toString() }) {
                arrivals(bob, alice).size == 10
            }
            assertEquals(
                (1..10).map { it.toString() },
                arrivals(bob, alice),
                "dos rafagas 1..5,1..5: 10 filas y en orden de creacion",
            )
            assertEquals(10, bob.store.listByConversation(bob.identity, alice.identity).getOrThrow().size)
            assertTrue(
                alice.queue.peekAll().getOrThrow().isEmpty(),
                "y la cola persistida del emisor queda vacia tras los ACK",
            )
        } finally {
            alice.core.stop(); bob.core.stop()
            alice.transport.close(); bob.transport.close()
        }
    }

    // ==================================================================
    // 2. CORTE — dos rafagas con blackhole: 10/10 en orden, sin perder la cabeza
    // ==================================================================

    @Test
    @Timeout(300)
    fun `dos rafagas con corte de red llegan completas y en orden por ID`() {
        val aliceKp = ed25519.generateKeyPair()
        val bobKp = ed25519.generateKeyPair()

        CutProxy(relay.port).use { proxy ->
            val bob = node(bobKp, peersOf(aliceKp))
            val alice = node(
                aliceKp,
                peersOf(bobKp),
                url = proxy.endpoint(),
                clientFactory = okHttpWithPing(300),
            )
            try {
                bob.transport.connect().getOrThrow()
                alice.transport.connect().getOrThrow()
                awaitOrFail("los dos ONLINE antes del corte", trace = { trace.toString() }) {
                    bob.transport.isOnline() && alice.transport.isOnline()
                }

                repeat(2) { rafaga ->
                    // El cable del EMISOR se corta ANTES de escribir: el socket
                    // parece vivo, los bytes se descartan, y el ping de 300 ms lo
                    // saca de ONLINE. Los envios de la rafaga caen en la cola.
                    proxy.blackhole()
                    awaitOrFail(
                        "el ping saca a alice de ONLINE (rafaga ${rafaga + 1})",
                        timeoutMs = 15_000,
                        trace = { trace.toString() },
                    ) { !alice.transport.isOnline() }

                    (1..5).forEach { n ->
                        send(alice, bob.identity, "${n + rafaga * 5}")
                        Thread.sleep(60)
                    }

                    // R12: mientras el cable esta cortado, NINGUNO de esta rafaga
                    // puede constar como DELIVERED. No hay ACK, y sin ACK no hay
                    // evidencia de entrega.
                    val rafagaActual = (1..5).map { (it + rafaga * 5).toString() }.toSet()
                    val filasEmisor = alice.store.listByConversation(alice.identity, bob.identity).getOrThrow()
                    assertEquals(5 * (rafaga + 1), filasEmisor.size, "las filas del emisor existen")
                    assertTrue(
                        filasEmisor
                            .filter { String(it.payload) in rafagaActual }
                            .none { it.state == MessageState.DELIVERED },
                        "sin ACK no puede haber DELIVERED en la rafaga recien escrita",
                    )

                    proxy.restaurar()
                    awaitOrFail(
                        "la rafaga ${rafaga + 1} llega completa al receptor",
                        timeoutMs = 120_000,
                        trace = { trace.toString() },
                    ) { arrivals(bob, alice).size == 5 * (rafaga + 1) }
                }

                assertEquals(
                    (1..10).map { it.toString() },
                    arrivals(bob, alice),
                    "10 FILAS por ID, en el orden en que se escribieron (no solo 10 burbujas)",
                )
                assertEquals(
                    10,
                    bob.store.listByConversation(bob.identity, alice.identity).getOrThrow().size,
                    "el conteo es de filas, no de items visibles",
                )
                assertEquals(
                    arrivals(bob, alice).distinct().size,
                    arrivals(bob, alice).size,
                    "sin duplicados: el receptor deduplica por (messageId, from)",
                )
            } finally {
                alice.core.stop(); bob.core.stop()
                alice.transport.close(); bob.transport.close()
            }
        }
    }

    // ==================================================================
    // 3. PUERTA DE CABEZA — cabeza SENT con retry + seguidor QUEUED
    // ==================================================================

    /**
     * R2/R10 en el camino INTEGRADO: el seguidor no sale hasta que la cabeza
     * tiene ACK firmado.
     *
     * ## LA SECUENCIA, Y POR QUE LAS RAMAS COMPITEN DE VERDAD
     *
     * 1. Alice conectada, **Bob todavia no**. El rele no hace store-and-forward
     *    (`RelayServer.deliverData` -> `PEER_NOT_ONLINE`), asi que el `1` sale
     *    por el socket local (estado `SENT`) y se pierde: sin ACK, su retry
     *    queda PROGRAMADO. Es la primera rama.
     * 2. El `2` se crea y se envia con Bob aun fuera. No es cabeza, luego NO se
     *    transmite: queda `QUEUED` en la cola del par y en el `OfflineQueue`. Es
     *    la segunda rama.
     * 3. Bob vuelve. El rele emite `PEER_ONLINE` y el nucleo llama a
     *    `SenderImpl.onPeerOnline`, que en produccion SOLO despierta la cabeza.
     *    Aqui es donde el drenado AVARO se delata: recorre la cola y transmite
     *    al seguidor sin puerta.
     *
     * ## QUE SE MIDE (y por que NO es "el orden de llegada")
     *
     * El aserto central es el estado de la CABEZA en el INSTANTE en que el
     * SEGUIDOR sale de `QUEUED`, leido desde el propio hilo del emisor en
     * `registerStateChangeHandler`: en produccion el seguidor solo se promueve
     * dentro de `onAckConfirmed` de la cabeza, es decir con la cabeza ya
     * `DELIVERED`; con el drenado avaro sale con la cabeza todavia `SENT`.
     * Ese instante no depende de ningun `sleep`, y por eso no es una carrera.
     *
     * El drenado avaro recorre `OfflineQueue` en orden de creacion, asi que el
     * ORDEN DE LLEGADA resultante sigue siendo `[1, 2]`: aqui esta escrito para
     * que nadie vuelva a suponer que el orden de llegada basta. El orden de
     * llegada SI se captura (en el handler del receptor, no en `getConversation`
     * que ordena por `timestamp`) y se afirma al final, porque es la promesa del
     * test; lo que mata la mutacion es la puerta.
     */
    @Test
    @Timeout(120)
    fun `una cabeza en vuelo no deja que el seguidor en cola salga antes de su ACK`() {
        val aliceKp = ed25519.generateKeyPair()
        val bobKp = ed25519.generateKeyPair()

        // Orden REAL de llegada: se captura en el handler del receptor.
        val llegadasEnOrden = CopyOnWriteArrayList<String>()
        val bob = node(bobKp, peersOf(aliceKp)) { mensaje ->
            llegadasEnOrden.add(String(mensaje.payload))
        }
        val alice = node(aliceKp, peersOf(bobKp))

        // Estado de la cabeza en cada transicion del seguidor (hilo del emisor).
        val cabezaAlSalirElSeguidor = CopyOnWriteArrayList<MessageState>()
        var seguidor: MessageId? = null

        try {
            alice.transport.connect().getOrThrow()
            awaitOrFail("alice ONLINE (bob sigue fuera)", trace = { trace.toString() }) {
                alice.transport.isOnline()
            }

            // --- Rama 1: SENT, sin peer, SIN ACK -> retry programado ----------
            val uno = alice.core.createMessage(bob.identity, "1".toByteArray()).getOrThrow()
            alice.core.sendMessage(uno).getOrThrow()
            assertEquals(
                MessageState.SENT,
                estado(alice, uno.messageId),
                "el 1 salio por el socket local (sin ACK): esa es la rama con retry programado",
            )

            // --- Rama 2: QUEUED, detras de la cabeza --------------------------
            val dos = alice.core.createMessage(bob.identity, "2".toByteArray()).getOrThrow()
            alice.core.sendMessage(dos).getOrThrow()
            assertEquals(
                MessageState.QUEUED,
                estado(alice, dos.messageId),
                "el 2 NO es cabeza: espera su turno, no sale",
            )
            assertTrue(
                llegadasEnOrden.isEmpty(),
                "bob esta fuera y el rele no guarda: no puede haber llegado nada",
            )

            // Se instala DESPUES de crear el 2 para no capturar su QUEUED.
            seguidor = dos.messageId
            alice.core.registerStateChangeHandler { id, state ->
                if (id == seguidor && state != MessageState.QUEUED) {
                    cabezaAlSalirElSeguidor.add(estado(alice, uno.messageId))
                }
            }

            // --- Bob vuelve: PEER_ONLINE -> onPeerOnline en el nucleo ---------
            bob.transport.connect().getOrThrow()
            awaitOrFail("bob ONLINE", trace = { trace.toString() }) {
                bob.transport.isOnline()
            }

            // Control positivo de la secuencia: la cabeza SI llega (su retry la
            // reenvia en cuanto el par esta) y el seguidor la sigue.
            awaitOrFail(
                "las DOS filas llegan al receptor",
                timeoutMs = 30_000,
                trace = { trace.toString() },
            ) { llegadasEnOrden.size == 2 }

            // Sensibilidad al drenado avaro: el seguidor salio con la cabeza aun
            // sin confirmar. En produccion toda observacion es DELIVERED.
            assertTrue(
                cabezaAlSalirElSeguidor.isNotEmpty(),
                "el seguidor tiene que haber salido; si no, el test no mide la puerta",
            )
            assertEquals(
                listOf(MessageState.DELIVERED),
                cabezaAlSalirElSeguidor.distinct(),
                "R2: el seguidor solo puede salir cuando la cabeza ya esta DELIVERED " +
                    "(ACK firmado). Estados de la cabeza observados al salir el seguidor: " +
                    "$cabezaAlSalirElSeguidor. Un drenado avaro la encuentra en SENT.",
            )

            // Orden de LLEGADA, medido en el handler (no en getConversation).
            assertEquals(
                listOf("1", "2"),
                llegadasEnOrden.toList(),
                "el orden de llegada es el de creacion: la cabeza primero",
            )
            assertEquals(
                MessageState.DELIVERED,
                estado(alice, uno.messageId),
                "la cabeza termina confirmada por el ACK del par",
            )
            assertEquals(
                MessageState.DELIVERED,
                estado(alice, dos.messageId),
                "y el seguidor tambien, despues de su turno",
            )
            assertEquals(
                2,
                bob.store.listByConversation(bob.identity, alice.identity).getOrThrow().size,
                "dos filas por ID en el receptor, sin duplicados",
            )
            assertTrue(
                alice.queue.peekAll().getOrThrow().isEmpty(),
                "y la cola persistida del emisor queda vacia tras los ACK",
            )
        } finally {
            alice.core.stop(); bob.core.stop()
            alice.transport.close(); bob.transport.close()
        }
    }

    // ==================================================================
    // 4. NEGATIVO — el detector rechaza la forma del sintoma
    // ==================================================================

    @Test
    fun `control negativo el detector rechaza cinco enviados con la cabeza perdida`() {
        val enviados = listOf("1", "2", "3", "4", "5")
        val esperado = (1..5).map { it.toString() }

        assertTrue(ArrivalCheck.completa(enviados, esperado), "control: 5 de 5 en orden es completo")
        assertFalse(
            ArrivalCheck.completa(listOf("2", "3", "4", "5"), esperado),
            "la forma del sintoma (falta el 1) NO puede pasar como exito",
        )
        assertFalse(
            ArrivalCheck.completa(listOf("3", "2", "1", "4", "5"), esperado),
            "y una entrega completa pero FUERA DE ORDEN tampoco",
        )
        assertTrue(
            ArrivalCheck.completa(
                listOf("1", "2", "3", "4", "5", "1", "2", "3", "4", "5"),
                esperados = (1..5).map { it.toString() } + (1..5).map { it.toString() },
            ),
            "dos rafagas 1..5,1..5 son completas",
        )
    }

    /**
     * Regla de aceptacion, pura: la secuencia ENTERA, no el tamano.
     *
     * `[2,3,4,5]` tiene 4 elementos y `[3,2,1,4,5]` tiene 5, y ninguno de los
     * dos es aceptable: el criterio es orden de creacion + conteo por ID.
     */
    object ArrivalCheck {
        fun completa(llegadas: List<String>, esperados: List<String>): Boolean =
            llegadas == esperados
    }

    // ==================================================================
    // Proxy TCP: reenvia, o LEE Y DESCARTA sin FIN ni RST
    // ==================================================================

    private class CutProxy(private val targetPort: Int) : Closeable {

        private val server = ServerSocket(0, 64, InetAddress.getLoopbackAddress())
        private val bloqueado = AtomicBoolean(false)
        private val cerrado = AtomicBoolean(false)
        private val sockets = CopyOnWriteArrayList<Socket>()

        init {
            thread(isDaemon = true, name = "cut-accept") {
                while (!cerrado.get()) {
                    val cliente = runCatching { server.accept() }.getOrNull() ?: break
                    runCatching { enlazar(cliente) }
                }
            }
        }

        fun endpoint(): String = "ws://127.0.0.1:${server.localPort}/km"

        fun blackhole() = bloqueado.set(true)

        fun restaurar() = bloqueado.set(false)

        private fun enlazar(cliente: Socket) {
            val arriba = Socket(InetAddress.getLoopbackAddress(), targetPort)
            sockets.add(cliente)
            sockets.add(arriba)
            bomba(cliente, cliente.getInputStream(), arriba.getOutputStream(), arriba)
            bomba(arriba, arriba.getInputStream(), cliente.getOutputStream(), cliente)
        }

        private fun bomba(dueno: Socket, entrada: InputStream, salida: OutputStream, pareja: Socket) {
            thread(isDaemon = true, name = "cut-pump-${dueno.port}") {
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
        }

        override fun close() {
            cerrado.set(true)
            runCatching { server.close() }
            sockets.forEach { runCatching { it.close() } }
        }
    }
}
