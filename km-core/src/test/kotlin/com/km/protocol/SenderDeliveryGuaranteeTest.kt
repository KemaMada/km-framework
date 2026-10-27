package com.km.protocol

import com.km.api.KeyMessageCoreImpl
import com.km.codec.JsonDecoder
import com.km.codec.JsonEncoder
import com.km.codec.ValidatorImpl
import com.km.crypto.Ed25519
import com.km.crypto.Ed25519Impl
import com.km.model.Ack
import com.km.model.FailureCode
import com.km.model.IdentityId
import com.km.model.Message
import com.km.model.MessageId
import com.km.model.MessageState
import com.km.storage.InMemoryDuplicateStore
import com.km.storage.InMemoryMessageStore
import com.km.storage.InMemoryOfflineQueue
import com.km.storage.MessageStore
import org.junit.jupiter.api.Test
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * R10-R14 — LA GARANTIA DE ENTREGA: conteo por ID, tombstone, ACK y ciclo de vida.
 *
 * ## QUE MIDE ESTE TEST QUE `SenderDeliveryOrderTest` NO MIDE
 *
 * Aquel mide ORDEN DE TRANSMISION con un enlace falso. Este mide LLEGADA y
 * PERDIDA contra un `ReceiverImpl` REAL (dedupe por `(messageId, from)`,
 * re-emision de `ACK`) y un `AckManagerImpl` REAL que verifica Ed25519. Es la
 * unica forma de afirmar "una fila, no dos" y "DELIVERED solo con ACK firmado".
 *
 * ## REGLA DE CONTEO
 *
 * Todo se cuenta por `MessageId` y por FILA del store. Ninguna asercion habla de
 * "burbujas visibles" ni de texto pintado.
 */
class SenderDeliveryGuaranteeTest {

    private val ed25519: Ed25519 = Ed25519Impl()
    private val localKp = ed25519.generateKeyPair()
    private val peerKp = ed25519.generateKeyPair()
    private val local = IdentityId("11".repeat(32))
    private val peer = IdentityId("22".repeat(32))

    private fun mensaje(ts: Long, id: MessageId = MessageId(UUID.randomUUID())): Message = Message(
        messageId = id,
        from = local,
        to = peer,
        payload = "carga $ts".toByteArray(),
        timestamp = ts,
        state = MessageState.CREATED,
    )

    // ===================================================================
    // R13 — la cabeza que agota NO desaparece; la cola avanza; reenvio
    // ===================================================================

    @Test
    fun `la cabeza expirada sigue siendo una fila visible con motivo y el reenvio la recupera`() {
        val h = Loop()
        val ids = (1..5).map { h.create(it * 1_000L) }
        h.link.reachable = false
        ids.forEach { h.send(it) }

        // Los 155 s del techo: el 1 expira, la cola avanza y 2..5 salen cuando
        // el par vuelve.
        h.advance(SenderImpl.HEAD_WAIT_CEILING_MS)
        h.link.reachable = true
        h.sender.onPeerOnline(peer)
        h.advance(5_000)

        assertEquals(
            listOf(2, 3, 4, 5),
            h.arrivals(ids),
            "llegada por ID: 2,3,4,5 en orden; el 1 no se inventa",
        )
        assertEquals(4, h.receiverStore.listByConversation(peer, local).getOrThrow().size, "cuatro filas RECIBIDAS")

        val fila = assertNotNull(h.senderStore.get(ids[0]).getOrThrow(), "la fila del 1 NO desaparece")
        assertEquals(MessageState.EXPIRED, fila.state, "queda EXPIRED, visible")
        assertEquals(FailureCode.TIMEOUT, fila.failureCode, "con su motivo: TIMEOUT")
        assertEquals(5, h.rows(), "las cinco filas del emisor siguen: 4 entregadas + 1 tombstone")
        assertTrue(
            h.offlineQueue.peekAll().getOrThrow().none { it.messageId == ids[0] },
            "fuera del OfflineQueue: un terminal no se rehidrata como pendiente",
        )

        // R13: accion explicita. Mismo messageId, mismo timestamp, re-cifrado una
        // vez y ACK firmado que la deja en DELIVERED.
        val timestampOriginal = fila.timestamp
        assertEquals(Result.success(Unit), h.resend(ids[0]))
        val reenviada = assertNotNull(h.senderStore.get(ids[0]).getOrThrow())
        assertEquals(MessageState.DELIVERED, reenviada.state, "el ACK firmado del par la confirma")
        assertEquals(timestampOriginal, reenviada.timestamp, "conserva el timestamp ORIGINAL")
        assertEquals(ids[0], reenviada.messageId, "y el MISMO messageId")
        assertEquals(
            1,
            h.receiverStore.listByConversation(peer, local).getOrThrow().count { it.messageId == ids[0] },
            "el receptor la tiene UNA vez, no dos",
        )
        assertEquals(5, h.rows(), "y el emisor tampoco creo otra fila")
        assertEquals(
            2,
            h.link.encryptions(ids[0]),
            "el reenvio cifra UNA vez: dos en total (el original que se perdio + el reenvio)",
        )
    }

    @Test
    fun `discard solo funciona sobre un terminal y borra la fila`() {
        val h = Loop()
        val id = h.create(1_000L)
        h.link.reachable = false
        h.transport.online = false
        h.send(id)
        assertEquals(MessageState.QUEUED, h.state(id), "transporte offline: quedo esperando")

        assertTrue(h.sender.discard(id).isFailure, "un QUEUED no se descarta: aun podia salir")
        assertNotNull(h.senderStore.get(id).getOrThrow(), "y la fila sigue")

        h.advance(SenderImpl.HEAD_WAIT_CEILING_MS)
        assertEquals(MessageState.EXPIRED, h.state(id))
        assertEquals(Result.success(Unit), h.sender.discard(id))
        assertNull(h.senderStore.get(id).getOrThrow(), "el descarte explicito SI borra la fila")
        assertTrue(h.offlineQueue.peekAll().getOrThrow().none { it.messageId == id })
    }

    // ===================================================================
    // R11/R12 — ACK perdido y duplicado
    // ===================================================================

    @Test
    fun `un ACK perdido se recupera con el MISMO messageId y deja UNA sola fila`() {
        val h = Loop()
        val id = h.create(1_000L)
        h.link.dropNextAck = true
        h.send(id)

        assertEquals(MessageState.SENT, h.state(id), "sin ACK, el mensaje sigue sin confirmar")
        assertTrue(h.receiverStore.listByConversation(peer, local).getOrThrow().isNotEmpty(), "el par SI lo recibio")
        assertEquals(1, h.link.encryptions(id), "un solo cifrado hasta aqui")

        h.advance(5_000)

        assertEquals(MessageState.DELIVERED, h.state(id), "el reintento provoca el re-ACK y se confirma")
        assertEquals(1, h.link.encryptions(id), "el reintento NO re-cifra")
        assertEquals(1, h.link.retransmissions(id), "REENVIA el mismo wire")
        assertEquals(0, h.link.wireMismatches, "y el wire es identico")
        assertEquals(
            1,
            h.receiverStore.listByConversation(peer, local).getOrThrow().size,
            "el duplicado se deduplica por (messageId, from): UNA fila",
        )
        assertEquals(2, h.acksEmitidos.get(), "el receptor RE-EMITE el ACK en el duplicado")
    }

    @Test
    fun `un ACK duplicado no avanza dos veces el estado`() {
        val store = CountingStore()
        val ackManager = AckManagerImpl(store, ed25519) { if (it == peer) peerKp.publicKey else null }
        val id = MessageId(UUID.randomUUID())
        store.insert(mensaje(1_000L, id).copy(state = MessageState.SENT)).getOrThrow()
        ackManager.expectAck(id)
        val notificados = AtomicInteger()
        ackManager.registerAckHandler { notificados.incrementAndGet() }

        val ack1 = ackFirmado(id, ts = 2_000L)
        val ack2 = ackFirmado(id, ts = 3_000L)
        ackManager.onAckReceived(ack1)
        ackManager.onAckReceived(ack2)

        assertEquals(1, notificados.get(), "el ACK duplicado NO vuelve a notificar")
        assertEquals(1, store.deliveredUpdates, "ni reescribe DELIVERED dos veces")
    }

    // ===================================================================
    // R14 — un dueno: close apaga el scheduler y el sucesor rehidrata
    // ===================================================================

    @Test
    fun `close es idempotente apaga el scheduler y deja tombstone lo que estaba en vuelo`() {
        val clock = ManualRetryScheduler()
        val retry = RetryManagerImpl(scheduler = clock)
        val store = InMemoryMessageStore()
        val queue = InMemoryOfflineQueue()
        val transport = CountingTransport(online = true)
        val core = KeyMessageCoreImpl(
            localIdentity = local,
            localPrivateKey = localKp.privateKey,
            transport = transport,
            messageStore = store,
            offlineQueue = queue,
            retryManager = retry,
        )

        val uno = core.createMessage(peer, "uno".toByteArray()).getOrThrow()
        val dos = core.createMessage(peer, "dos".toByteArray()).getOrThrow()
        core.sendMessage(uno).getOrThrow()
        core.sendMessage(dos).getOrThrow()
        assertEquals(MessageState.SENT, core.getMessageState(uno.messageId).getOrThrow())
        assertEquals(MessageState.QUEUED, core.getMessageState(dos.messageId).getOrThrow())
        assertTrue(clock.pendingCount > 0, "hay un temporizador vivo antes de cerrar")

        core.close()
        core.close() // idempotente: no lanza y no hace nada nuevo

        assertEquals(0, clock.pendingCount, "el scheduler anterior queda APAGADO")
        assertEquals(0, retry.scheduledCount(), "y sin tareas registradas")
        val enVuelo = assertNotNull(store.get(uno.messageId).getOrThrow(), "la fila en vuelo NO se pierde")
        assertEquals(MessageState.EXPIRED, enVuelo.state, "se convierte en tombstone")
        assertEquals(FailureCode.TIMEOUT, enVuelo.failureCode, "con motivo: al cerrar no hay ACK posible")
        assertEquals(
            MessageState.QUEUED,
            store.get(dos.messageId).getOrThrow()?.state,
            "el que esperaba turno sigue QUEUED para el sucesor",
        )
        val enviados = transport.sent.size
        clock.advanceBy(200_000)
        assertEquals(enviados, transport.sent.size, "un nucleo cerrado no vuelve a emitir nunca")
        assertTrue(core.start().isFailure, "un nucleo cerrado no se reinicia")
    }

    @Test
    fun `el sucesor rehidrata la cola del anterior y la entrega en orden sin duplicar`() {
        val clockViejo = ManualRetryScheduler()
        val retryViejo = RetryManagerImpl(scheduler = clockViejo)
        val store = InMemoryMessageStore()
        val queue = InMemoryOfflineQueue()
        val transportViejo = CountingTransport(online = false)
        val viejo = SenderImpl(
            store, queue, JsonEncoder(), ValidatorImpl(), ed25519, transportViejo,
            AckManagerImpl(store, ed25519), retryViejo, local, localKp.privateKey, secureOutbound = null,
        )
        val ids = (1..3).map { mensaje(it * 1_000L).also { m -> store.insert(m).getOrThrow() } }
        ids.forEach { viejo.send(it) }
        assertEquals(3, queue.peekAll().getOrThrow().size, "los tres esperan en la cola persistida")
        retryViejo.shutdown()

        // Sucesor: mismo almacen y misma cola, scheduler NUEVO y enlace vivo.
        val h = Loop(senderStore = store, offlineQueue = queue)
        val report = h.rehydrate()
        assertEquals(3, report.admitted, "el sucesor admite los TRES pendientes")
        assertEquals(0, report.swept, "y no barre ninguno: todos eran QUEUED")

        h.sender.onPeerOnline(peer)
        h.drain()

        assertEquals(listOf(1, 2, 3), h.arrivals(ids.map { it.messageId }), "orden de creacion, por ID")
        assertEquals(3, h.rows(), "tres filas, ninguna duplicada")
        assertEquals(3, h.link.encryptionsTotal(), "cada mensaje se cifro una sola vez")
        assertTrue(
            h.offlineQueue.peekAll().getOrThrow().isEmpty(),
            "y tras los ACK la cola persistida queda vacia",
        )
        val transmisionesViejas = transportViejo.sent.size
        clockViejo.advanceBy(200_000)
        assertEquals(transmisionesViejas, transportViejo.sent.size, "el emisor viejo ya no emite nada")
    }

    // ===================================================================
    // R10 — monotonia de creacion
    // ===================================================================

    @Test
    fun `dos createMessage en el mismo milisegundo NO empatan y conservan el orden`() {
        val core = KeyMessageCoreImpl(
            localIdentity = local,
            localPrivateKey = localKp.privateKey,
            transport = CountingTransport(online = false),
            retryManager = RetryManagerImpl(scheduler = ManualRetryScheduler()),
        )
        val creados = (1..50).map { core.createMessage(peer, "m$it".toByteArray()).getOrThrow() }

        val tiempos = creados.map { it.timestamp }
        assertEquals(tiempos.sorted(), tiempos, "las marcas crecen con el orden de creacion")
        assertEquals(tiempos.distinct(), tiempos, "y ninguna empata: el UUID no decide el orden")
        creados.zipWithNext().forEach { (a, b) ->
            assertTrue(b.timestamp > a.timestamp, "marca estrictamente creciente: ${a.timestamp} -> ${b.timestamp}")
        }
        core.close()
    }

    // ===================================================================
    // Fixture: sender real + receiver real + ACK firmado real
    // ===================================================================

    private fun ackFirmado(id: MessageId, ts: Long): Ack {
        val data = AckTranscript.build(peer, local, id, com.km.model.AckStatus.DELIVERED, ts)
        return Ack(
            from = peer,
            to = local,
            originalMessageId = id,
            status = com.km.model.AckStatus.DELIVERED,
            timestamp = ts,
            signature = ed25519.sign(peerKp.privateKey, data).bytes,
        )
    }

    private inner class Loop(
        val senderStore: MessageStore = InMemoryMessageStore(),
        val offlineQueue: InMemoryOfflineQueue = InMemoryOfflineQueue(),
    ) {
        val clock = ManualRetryScheduler()
        val retryManager = RetryManagerImpl(scheduler = clock)
        val receiverStore = InMemoryMessageStore()
        val duplicateStore = InMemoryDuplicateStore()
        val transport = CountingTransport(online = true)
        val acksEmitidos = AtomicInteger()
        private val pendientes = ArrayDeque<Ack>()

        val link = LoopbackLink()
        val ackManager = AckManagerImpl(senderStore, ed25519) { if (it == peer) peerKp.publicKey else null }
        val sender: SenderImpl

        init {
            val receiver = ReceiverImpl(
                messageStore = receiverStore,
                duplicateStore = duplicateStore,
                decoder = JsonDecoder(),
                encoder = JsonEncoder(),
                validator = ValidatorImpl(),
                ed25519 = ed25519,
                transport = transport,
                orderingManager = OrderingManagerImpl(),
                localIdentity = peer,
                localPrivateKey = peerKp.privateKey,
                ackEmitter = { ack ->
                    acksEmitidos.incrementAndGet()
                    if (link.dropNextAck) link.dropNextAck = false else pendientes.add(ack)
                    Result.success(Unit)
                },
            )
            link.receiver = receiver
            sender = SenderImpl(
                messageStore = senderStore,
                offlineQueue = offlineQueue,
                encoder = JsonEncoder(),
                validator = ValidatorImpl(),
                ed25519 = ed25519,
                transport = transport,
                ackManager = ackManager,
                retryManager = retryManager,
                localIdentity = local,
                localPrivateKey = localKp.privateKey,
                secureOutbound = link,
            )
            ackManager.registerAckHandler { ack -> sender.onAckConfirmed(ack.originalMessageId) }
            retryManager.onRetry { id, attempt ->
                sender.onRetryExpired(id, attempt)
                drain()
            }
        }

        fun create(ts: Long): MessageId = mensaje(ts).also { senderStore.insert(it).getOrThrow() }.messageId

        fun send(id: MessageId) {
            sender.send(requireNotNull(senderStore.get(id).getOrThrow()))
            drain()
        }

        fun resend(id: MessageId): Result<Unit> =
            sender.resend(id).also { drain() }

        fun rehydrate(): RehydrateReport = sender.rehydrate()

        fun advance(ms: Long) {
            clock.advanceBy(ms)
            drain()
        }

        fun drain() {
            var guard = 0
            while (pendientes.isNotEmpty() && guard++ < 500) {
                // El ACK entra por el `AckManagerImpl` REAL: si la firma no
                // verifica, el estado no avanza. Llamar a `onAckConfirmed`
                // directamente se saltaria justo la garantia que R12 exige.
                ackManager.onAckReceived(pendientes.removeFirst())
            }
            check(guard < 500) { "ACK sin fin: la promocion de cabeza no converge" }
        }

        fun state(id: MessageId): MessageState =
            requireNotNull(senderStore.get(id).getOrThrow()).state

        fun rows(): Int = senderStore.listByConversation(local, peer).getOrThrow().size

        fun arrivals(ids: List<MessageId>): List<Int> =
            link.deliveries.map { ids.indexOf(it) + 1 }
    }

    /**
     * Enlace cifrado falso que entrega al `ReceiverImpl` REAL.
     *
     * Registrar el frame y ENTREGARLO son dos cosas: `transmissions` cuenta
     * bytes puestos en el cable (incluidos reenvios); `deliveries` cuenta
     * llegadas UNICAS, que es lo que el receptor deduplica.
     */
    private inner class LoopbackLink : com.km.messaging.SecureOutbound {
        lateinit var receiver: ReceiverImpl
        var reachable: Boolean = true
        var dropNextAck: Boolean = false

        val transmissions = mutableListOf<MessageId>()
        val deliveries = mutableListOf<MessageId>()
        var wireMismatches: Int = 0
            private set

        private val wires = mutableMapOf<MessageId, ByteArray>()
        private val encryptions = mutableMapOf<MessageId, Int>()
        private val retransmissions = mutableMapOf<MessageId, Int>()

        override fun send(message: Message): com.km.messaging.SecureSendOutcome {
            val wire = "wire:${message.messageId}".toByteArray()
            wires[message.messageId] = wire
            encryptions.merge(message.messageId, 1, Int::plus)
            record(message)
            return com.km.messaging.SecureSendOutcome.Encrypted(wire, wire.size)
        }

        override fun retransmit(message: Message, wire: ByteArray): com.km.messaging.SecureSendOutcome {
            retransmissions.merge(message.messageId, 1, Int::plus)
            val original = wires[message.messageId]
            if (original == null || !original.contentEquals(wire)) wireMismatches++
            record(message)
            return com.km.messaging.SecureSendOutcome.Encrypted(wire, wire.size)
        }

        fun encryptions(id: MessageId): Int = encryptions[id] ?: 0
        fun retransmissions(id: MessageId): Int = retransmissions[id] ?: 0
        fun encryptionsTotal(): Int = encryptions.size

        private fun record(message: Message) {
            transmissions.add(message.messageId)
            if (!reachable) return
            if (deliveries.none { it == message.messageId }) deliveries.add(message.messageId)
            receiver.onMessageReceived(message)
        }
    }

    /** Transport que solo existe para satisfacer el contrato y contar bytes. */
    private class CountingTransport(var online: Boolean) : Transport {
        val sent = mutableListOf<ByteArray>()

        override fun connect(): Result<Unit> {
            online = true
            return Result.success(Unit)
        }

        override fun disconnect() {
            online = false
        }

        override fun send(data: ByteArray, to: IdentityId): Result<Unit> {
            if (!online) return Result.failure(IllegalStateException("offline"))
            sent.add(data.copyOf())
            return Result.success(Unit)
        }

        override fun sendAck(ackBytes: ByteArray): Result<Unit> = Result.success(Unit)
        override fun setMessageHandler(handler: (ByteArray) -> Unit) {}
        override fun setAckHandler(handler: (ByteArray) -> Unit) {}
        override fun isOnline(): Boolean = online
        override fun connectionState(): ConnectionState =
            if (online) ConnectionState.ONLINE else ConnectionState.DISCONNECTED
        override fun onStateChange(handler: (ConnectionState) -> Unit) {}
        override fun onPeerOnline(handler: (IdentityId) -> Unit) {}
        override fun onPeerOffline(handler: (IdentityId) -> Unit) {}
    }

    /** Cuenta las escrituras de `DELIVERED`, para probar que un ACK duplicado no re-avanza. */
    private class CountingStore(
        private val delegate: InMemoryMessageStore = InMemoryMessageStore(),
    ) : MessageStore {
        var deliveredUpdates: Int = 0
            private set

        override fun insert(message: Message): Result<Unit> = delegate.insert(message)

        override fun updateState(messageId: MessageId, state: MessageState, failureCode: FailureCode?): Result<Unit> {
            if (state == MessageState.DELIVERED) deliveredUpdates++
            return delegate.updateState(messageId, state, failureCode)
        }

        override fun get(messageId: MessageId): Result<Message?> = delegate.get(messageId)

        override fun listByConversation(localId: IdentityId, peerId: IdentityId): Result<List<Message>> =
            delegate.listByConversation(localId, peerId)

        override fun delete(messageId: MessageId): Result<Unit> = delegate.delete(messageId)
    }
}
