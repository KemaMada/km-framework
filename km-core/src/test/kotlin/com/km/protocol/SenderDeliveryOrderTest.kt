package com.km.protocol

import com.km.codec.JsonEncoder
import com.km.codec.ValidatorImpl
import com.km.crypto.Ed25519Impl
import com.km.messaging.SecureOutbound
import com.km.messaging.SecureSendOutcome
import com.km.model.FailureCode
import com.km.model.IdentityId
import com.km.model.Message
import com.km.model.MessageId
import com.km.model.MessageState
import com.km.storage.InMemoryMessageStore
import com.km.storage.InMemoryOfflineQueue
import org.junit.jupiter.api.Test
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * R1, R2, R4, R5, R8 — LA SONDA G, en `km-core`, midiendo ORDEN DE TRANSMISION.
 *
 * ## QUE SE MIDE, Y POR QUE AQUI Y NO CONTRA EL RELE
 *
 * Se mide el ORDEN EN QUE EL EMISOR PONE LOS MENSAJES EN EL CABLE. El rele es
 * FIFO por construccion (un solo canal, `RelayServer.deliverData`), asi que el
 * orden de transmision ES el orden de llegada cuando el par esta; y `km-core` no
 * puede depender de `km-transport` (el motor de red vive en un hermano), luego
 * montar el rele real aqui romperia el limite del modulo. El relé NO entra en
 * este cambio: su FIFO ya esta probado en `km-transport`.
 *
 * El enlace falso modela dos cosas que el defecto necesita y que no son lo
 * mismo:
 *
 * - `transportOnline`: el `Transport` LOCAL del emisor. Si esta apagado en el
 *   instante de `send`, el mensaje cae en la COLA (`QUEUED`), que es el caso
 *   que disparaba el desorden.
 * - `peerReachable`: si el rele puede entregar al PAR. Con el par sin red, el
 *   camino cifrado devuelve exito LOCAL y el frame se pierde en el rele
 *   (`PEER_NOT_FOUND`), exactamente como midio la sonda G del verify (#356).
 *
 * El ACK no se simula dentro de la transmision: se encola y se drena DESPUES,
 * que es lo que hace un rele real (un frame de vuelta, otro instante).
 *
 * ## LOS TRES CONTROLES
 *
 * 1. **Regresion**: corte en el 2 -> `[1,2,3,4]`, 3 corridas.
 * 2. **Positivo**: rafaga de 12 con el par presente -> `[1..12]`. Sin el, el
 *    `[1,2,3,4]` de arriba no seria interpretable (podria no entregar nada).
 * 3. **Negativo**: el MISMO escenario contra un doble de la semantica VIEJA
 *    (`send` :91 con cola + `onPeerOnline` :297-313 drenando con avidez) da
 *    `[2,1,3,4]`. Es la prueba de que el arnes DETECTA el defecto.
 */
class SenderDeliveryOrderTest {

    private val local = IdentityId("11".repeat(32))
    private val peer = IdentityId("22".repeat(32))

    // ===================================================================
    // 1. REGRESION — la sonda G con la semantica nueva
    // ===================================================================

    @Test
    fun `corte en el 2 entrega 1 2 3 4 y no 2 1 3 4`() {
        repeat(3) { corrida ->
            val h = Harness()
            val ids = (1..4).map { h.create(it * 1_000L) }

            // B sin red desde el principio: lo que salga se pierde en el rele.
            h.peerReachable = false

            h.send(ids[0])                       // 1: sale, sin ACK
            h.transport.online = false
            h.send(ids[1])                       // 2: A sin canal -> COLA
            h.transport.online = true
            h.send(ids[2])                       // 3: espera su turno
            h.send(ids[3])                       // 4: espera su turno

            assertEquals(
                listOf(ids[0]),
                h.transmissions,
                "solo la cabeza ha puesto bytes en el cable (corrida $corrida)",
            )
            assertEquals(MessageState.QUEUED, h.state(ids[1]), "el 2 quedo en la cola")

            // R8: el par aparece. El drenado AVARO ponia aqui el 2 delante.
            h.sender.onPeerOnline(peer)
            h.sender.onPeerConnected(peer)
            assertEquals(
                listOf(ids[0]),
                h.transmissions,
                "onPeerOnline SOLO despierta: no drena la cola ni adelanta al 2 (corrida $corrida)",
            )

            // B recupera la red. La cabeza (1) se reenvia a los 5 s con el MISMO
            // wire; su ACK promueve al 2, y asi hasta el 4.
            h.peerReachable = true
            h.clock.advanceBy(5_000)

            assertEquals(
                listOf(1, 2, 3, 4),
                h.orderOf(ids),
                "el orden de entrega es el de escritura, no [2,1,3,4] (corrida $corrida)",
            )
            assertTrue(h.offlineQueue.peekAll().getOrThrow().isEmpty(), "y no queda nada pendiente")
        }
    }

    @Test
    fun `la cabeza reenvia el MISMO wire y no lo re-cifra`() {
        val h = Harness()
        val ids = (1..2).map { h.create(it * 1_000L) }
        h.peerReachable = false
        h.send(ids[0])

        h.peerReachable = true
        h.clock.advanceBy(5_000)

        assertEquals(1, h.link.encryptions(ids[0]), "un solo cifrado para el mensaje 1")
        assertTrue(h.link.retransmissions(ids[0]) >= 1, "el reenvio es un retransmit")
        assertEquals(0, h.link.wireMismatches, "el reintento NO produce un frame distinto (R5)")
        assertEquals(1, h.orderOf(ids).size, "el 1 llego una vez: el receptor deduplica")
    }

    // ===================================================================
    // 2. CONTROL POSITIVO — sin corte, la rafaga entera en orden
    // ===================================================================

    @Test
    fun `rafaga de 12 con el par presente entrega 1 a 12`() {
        val h = Harness()
        h.peerReachable = true
        val ids = (1..12).map { h.create(it * 1_000L) }

        ids.forEach { h.send(it) }

        assertEquals(
            (1..12).toList(),
            h.orderOf(ids),
            "sin corte el orden es trivialmente el de escritura: el arnes ENTREGA",
        )
        assertEquals(12, h.transmissions.size, "y cada mensaje se transmite UNA vez")
    }

    // ===================================================================
    // 3. CONTROL NEGATIVO — la semantica VIEJA reproduce [2,1,3,4]
    // ===================================================================

    /**
     * El mismo escenario contra el doble de la semantica anterior.
     *
     * El doble reproduce las TRES piezas del defecto, y las reproduce en el
     * mismo orden que el codigo viejo:
     *
     * - `send` (:91) con `queueOffline` (:319) cuando no hay canal;
     * - `scheduleRetry(id, 1)` (:128/:149) para los que salieron;
     * - `onPeerOnline` (:297-313) recorriendo `peekAll()` y llamando a `send`
     *   por mensaje, que es el drenado AVARO.
     *
     * No se toca `SenderImpl` para "activar" la semantica vieja: un flag de
     * compatibilidad en produccion seria exactamente el tipo de puerta que este
     * cambio cierra.
     */
    @Test
    fun `la semantica vieja reproduce 2 1 3 4 y el control negativo vale`() {
        val clock = ManualRetryScheduler()
        var online = true
        var reachable = false
        val entregados = mutableListOf<Message>()
        val viejo = LegacyEagerSender(
            clock = clock,
            isOnline = { online },
            isReachable = { reachable },
            // El receptor DEDUPLICA por mensaje (`DuplicateStore`): un reenvio
            // del 2 no es una segunda entrega. Sin esto el control negativo
            // mediria tramas, no orden de llegada.
            deliver = { m -> if (entregados.none { it.messageId == m.messageId }) entregados.add(m) },
        )
        val ids = (1..4).map { mensaje(it * 1_000L) }

        viejo.send(ids[0])                 // 1: SENT, se pierde en el rele
        online = false
        viejo.send(ids[1])                 // 2: QUEUED
        online = true
        viejo.send(ids[2])                 // 3: SENT, se pierde
        viejo.send(ids[3])                 // 4: SENT, se pierde

        reachable = true
        viejo.onPeerOnline(peer)           // drenado AVARO: el 2 primero
        clock.advanceBy(5_000)             // y los reintentos de 1, 3 y 4 despues

        assertEquals(
            listOf(2, 1, 3, 4),
            entregados.map { ids.indexOf(it) + 1 },
            "el defecto es reproducible en el arnes: [2,1,3,4]",
        )
    }

    // ===================================================================
    // R2 / R4 — la puerta de cabecera y su techo
    // ===================================================================

    @Test
    fun `un mensaje nuevo no adelanta a la cabeza en backoff, y a los 155 s la cabeza expira`() {
        val h = Harness()
        val ids = (1..4).map { h.create(it * 1_000L) }
        h.peerReachable = false
        h.send(ids[0])
        h.transport.online = false
        h.send(ids[1])
        h.transport.online = true
        h.send(ids[2])
        h.send(ids[3])

        // 1 intento inicial + 4 reintentos (5, 15, 35, 75 s) = 75 s. A los
        // 154_999 todavia NO ha vencido el quinto (80 s), luego sigue siendo la
        // cabeza y los nuevos NO han transmitido.
        h.clock.advanceBy(SenderImpl.HEAD_WAIT_CEILING_MS - 1)
        assertEquals(ids[0], h.sender.headOf(peer), "a 154_999 la cabeza sigue siendo el 1")
        assertEquals(
            listOf(ids[0]),
            h.transmissions.distinct(),
            "y ninguno de los nuevos se ha adelantado durante el backoff (R2)",
        )
        assertEquals(MessageState.SENT, h.state(ids[0]), "sin ACK, el 1 sigue en vuelo")

        // El quinto vencimiento (80 s) cae justo en el techo: la cabeza agotada
        // se descarta y la siguiente entra (R4).
        h.clock.advanceBy(1)
        assertEquals(MessageState.EXPIRED, h.state(ids[0]), "a los 155 s la cabeza expira")
        assertEquals(ids[1], h.sender.headOf(peer), "y el 2 ocupa su sitio: no bloquea al par")
        assertEquals(
            ids[1],
            h.transmissions.last(),
            "el 2 transmite en cuanto se promociona",
        )
    }

    @Test
    fun `un ACK perdido no borra la cabeza expirada y los siguientes llegan en orden`() {
        val h = Harness()
        val ids = (1..5).map { h.create(it * 1_000L) }
        h.peerReachable = false
        ids.forEach { h.send(it) }

        // El 1 nunca recibe ACK: se agota y expira. Despues el par SI responde.
        h.clock.advanceBy(SenderImpl.HEAD_WAIT_CEILING_MS)
        h.peerReachable = true
        h.sender.onPeerOnline(peer)
        h.clock.advanceBy(5_000)

        // R13: lo entregado se cuenta por ID, no por "burbujas visibles". El 1
        // NO se entrego; 2..5 llegaron en su orden de creacion.
        assertEquals(
            listOf(2, 3, 4, 5),
            h.orderOf(ids),
            "el 1 expiro sin entregarse; los que si llegan conservan su orden relativo",
        )
        // Y la cabeza agotada NO desaparece: su fila sigue siendo evidencia y
        // lleva motivo. El test VIEJO afirmaba `[2,3]` con el 1 EXPIRED como si
        // la perdida fuera el resultado correcto; ahora la fila se exige.
        val fila = requireNotNull(h.store.get(ids[0]).getOrThrow()) {
            "la fila de la cabeza expirada NO puede desaparecer: es el tombstone que el usuario puede reenviar"
        }
        assertEquals(MessageState.EXPIRED, fila.state, "el 1 queda EXPIRED, visible")
        assertEquals(FailureCode.TIMEOUT, fila.failureCode, "y con su motivo: TIMEOUT")
        assertTrue(
            h.offlineQueue.peekAll().getOrThrow().none { it.messageId == ids[0] },
            "pero sale del OfflineQueue: un terminal no se rehidrata como pendiente",
        )
        assertEquals(5, h.filas(), "las CINCO filas siguen vivas: 4 entregadas + 1 expirada")

        // R13: la accion explicita lo recupera, con el MISMO messageId.
        assertEquals(Result.success(Unit), h.resend(ids[0]))
        assertEquals(
            listOf(2, 3, 4, 5, 1),
            h.orderOf(ids),
            "el reenvio llega como el MISMO mensaje (el receptor no lo duplica) y es el ULTIMO en llegar",
        )
        assertEquals(
            MessageState.SENT,
            h.state(ids[0]),
            "el tombstone se reencola y vuelve a salir. Esta sonda mide TRANSMISION y su ACK es sintetico; " +
                "la transicion a DELIVERED con firma real la pinza SenderDeliveryGuaranteeTest",
        )
        assertEquals(5, h.filas(), "sin filas nuevas: el reenvio no crea otro mensaje")
    }

    // ===================================================================
    // Fixture
    // ===================================================================

    private fun mensaje(ts: Long, to: IdentityId = peer): Message = Message(
        messageId = MessageId(UUID.randomUUID()),
        from = local,
        to = to,
        payload = "carga $ts".toByteArray(),
        timestamp = ts,
        state = MessageState.CREATED,
    )

    /**
     * Un emisor REAL —`SenderImpl` + `RetryManagerImpl` + `InMemoryOfflineQueue`
     * — con el enlace falso. Nada de `SenderImpl` de mentira: lo que se prueba
     * es el camino de produccion.
     */
    private inner class Harness {
        val clock = ManualRetryScheduler()
        val retryManager = RetryManagerImpl(scheduler = clock)
        val store = InMemoryMessageStore()
        val offlineQueue = InMemoryOfflineQueue()
        val transport = ToggleTransport()
        val link = RecordingSecureOutbound()
        val sender: SenderImpl

        /** Lo que el emisor puso en el cable, en orden (incluye reenvios). */
        val transmissions: List<MessageId> get() = link.transmissions

        /** Lo que llego AL PAR, en orden y sin duplicados (dedup del receptor). */
        val deliveries: List<MessageId> get() = link.deliveries

        var peerReachable: Boolean
            get() = link.peerReachable
            set(value) {
                link.peerReachable = value
            }

        private val pendingAcks = ArrayDeque<MessageId>()

        init {
            sender = SenderImpl(
                messageStore = store,
                offlineQueue = offlineQueue,
                encoder = JsonEncoder(),
                validator = ValidatorImpl(),
                ed25519 = Ed25519Impl(),
                transport = transport,
                ackManager = AckManagerImpl(store, Ed25519Impl()),
                retryManager = retryManager,
                localIdentity = local,
                localPrivateKey = ByteArray(32),
                secureOutbound = link,
            )
            retryManager.onRetry { messageId, attempt ->
                sender.onRetryExpired(messageId, attempt)
                drainAcks()
            }
            // El enlace entrega y el "peer" contesta: el ACK se encola y se
            // drena fuera de la transmision, como un frame de vuelta.
            link.onDelivered = { id -> pendingAcks.add(id) }
        }

        fun create(ts: Long): MessageId =
            mensaje(ts).also { store.insert(it).getOrThrow() }.messageId

        fun send(id: MessageId) {
            sender.send(requireNotNull(store.get(id).getOrThrow()))
            drainAcks()
        }

        /** Reenvio explicito, con el ACK que el par devuelve ya drenado. */
        fun resend(id: MessageId): Result<Unit> =
            sender.resend(id).also { drainAcks() }

        /** Filas del par en el store del emisor: cuenta por ID, no por burbujas. */
        fun filas(): Int = store.listByConversation(local, peer).getOrThrow().size

        fun state(id: MessageId): MessageState =
            requireNotNull(store.get(id).getOrThrow()).state

        /** Traduce los ids entregados a su numero de escritura (1..n). */
        fun orderOf(ids: List<MessageId>): List<Int> = deliveries.map { ids.indexOf(it) + 1 }

        private fun drainAcks() {
            while (pendingAcks.isNotEmpty()) {
                sender.onAckConfirmed(pendingAcks.removeFirst())
            }
        }
    }

    /**
     * El `Transport` local del emisor: solo su bandera. Lo que importa es
     * `isOnline()`, que es lo que decide si el mensaje sale o cae en la cola.
     */
    private class ToggleTransport : Transport {
        @Volatile
        var online: Boolean = true

        override fun connect(): Result<Unit> {
            online = true
            return Result.success(Unit)
        }

        override fun disconnect() {
            online = false
        }

        override fun send(data: ByteArray, to: IdentityId): Result<Unit> = Result.success(Unit)
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

    /**
     * El enlace cifrado falso: registra el wire, cuenta cifrados y reenvios, y
     * "entrega" solo si el par es alcanzable.
     *
     * `retransmit` EXIGE el mismo wire que se cifro. Si el emisor re-cifrara,
     * `wireMismatches` sube y el test de R5 falla: no basta con que el orden
     * sea bueno, el reintento tiene que ser el mismo frame.
     */
    private class RecordingSecureOutbound : SecureOutbound {
        val transmissions = mutableListOf<MessageId>()
        val deliveries = mutableListOf<MessageId>()
        private val encryptionCount = mutableMapOf<MessageId, Int>()
        private val retransmissionCount = mutableMapOf<MessageId, Int>()
        private val wireByMessage = mutableMapOf<MessageId, ByteArray>()

        var peerReachable: Boolean = false
        var wireMismatches: Int = 0
            private set
        var onDelivered: (MessageId) -> Unit = {}

        override fun send(message: Message): SecureSendOutcome {
            val wire = "wire:${message.messageId}".toByteArray()
            wireByMessage[message.messageId] = wire
            encryptionCount.merge(message.messageId, 1, Int::plus)
            record(message)
            return SecureSendOutcome.Encrypted(wire, wire.size)
        }

        override fun retransmit(message: Message, wire: ByteArray): SecureSendOutcome {
            retransmissionCount.merge(message.messageId, 1, Int::plus)
            val original = wireByMessage[message.messageId]
            if (original == null || !original.contentEquals(wire)) wireMismatches++
            record(message)
            return SecureSendOutcome.Encrypted(wire, wire.size)
        }

        fun encryptions(id: MessageId): Int = encryptionCount[id] ?: 0
        fun retransmissions(id: MessageId): Int = retransmissionCount[id] ?: 0

        private fun record(message: Message) {
            transmissions.add(message.messageId)
            if (!peerReachable) return
            // El par DEDUPLICA por mensaje: dos frames del mismo mensaje son
            // una sola entrega (que es lo que hace `DuplicateStore`).
            if (deliveries.none { it == message.messageId }) deliveries.add(message.messageId)
            onDelivered(message.messageId)
        }
    }

    /**
     * Doble de la semantica ANTERIOR del emisor, para el control negativo.
     *
     * Reproduce, linea por linea de comportamiento, las piezas del defecto:
     * salida inmediata si hay canal (`send` :91), cola si no lo hay
     * (`queueOffline` :319), reintento a 5 s (`scheduleRetry(id, 1)` :128/:149)
     * y DRENADO AVARO de la cola al aparecer el par (`onPeerOnline` :297-313).
     */
    private class LegacyEagerSender(
        private val clock: ManualRetryScheduler,
        private val isOnline: () -> Boolean,
        private val isReachable: () -> Boolean,
        private val deliver: (Message) -> Unit,
    ) {
        private val queued = mutableListOf<Message>()

        fun send(message: Message) {
            if (!isOnline()) {
                queued.add(message)
                return
            }
            transmit(message)
        }

        private fun transmit(message: Message) {
            if (isReachable()) deliver(message)
            clock.schedule(5_000) {
                if (isReachable()) deliver(message)
            }
        }

        /** :297-313 — recorre `peekAll()` y llama a `send` por mensaje. */
        fun onPeerOnline(peerId: IdentityId) {
            queued.filter { it.to == peerId }.forEach { message ->
                send(message)
                queued.remove(message)
            }
        }
    }
}
