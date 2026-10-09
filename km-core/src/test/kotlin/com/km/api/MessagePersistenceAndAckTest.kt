package com.km.api

import com.km.codec.Encoder
import com.km.codec.JsonDecoder
import com.km.codec.JsonEncoder
import com.km.crypto.Ed25519Impl
import com.km.model.Ack
import com.km.model.AckStatus
import com.km.model.FailureCode
import com.km.model.IdentityId
import com.km.model.Message
import com.km.model.MessageId
import com.km.model.MessageState
import com.km.protocol.AckTranscript
import com.km.protocol.ConnectionState
import com.km.protocol.Transport
import com.km.storage.DuplicateStore
import com.km.storage.InMemoryDuplicateStore
import com.km.storage.InMemoryMessageStore
import com.km.storage.InMemoryOfflineQueue
import com.km.storage.MessageStore
import com.km.storage.OfflineQueue
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * F1 (persistencia) y F2 (amplificacion del `ACK`).
 *
 * POR QUE UN CONTADOR Y NO UNA INSPECCION DE ESTADO
 *
 * R1.1 pide "exactamente una vez". Inspeccionar el `MessageStore` al final
 * demuestra que hay un registro, no CUANTAS VECES se escribio: un segundo
 * `insert` del mismo `messageId` es invisible en un mapa. Por eso el doble de
 * prueba CUENTA las llamadas. Un doble que solo mira el estado final no puede
 * distinguir `insert x1` de `insert x2`, y pasaria con el defecto puesto.
 *
 * Y al reves: el transporte cuenta `MESSAGE` y `ACK` en DOS listas. Un `ACK` que
 * acabara en la lista de `MESSAGE` significa que alguien lo esta disfrazando, y
 * ese es el defecto que este checkpoint cierra.
 */
class MessagePersistenceAndAckTest {

    private val ed25519 = Ed25519Impl()
    private val aliceKp = ed25519.generateKeyPair()
    private val bobKp = ed25519.generateKeyPair()
    private val aliceId = IdentityId("11".repeat(32))
    private val bobId = IdentityId("22".repeat(32))
    private val decoder = JsonDecoder()
    private val encoder = JsonEncoder()

    // ------------------------------------------------------------------
    // Dobles
    // ------------------------------------------------------------------

    /**
     * `MessageStore` que cuenta las escrituras y delega en uno real.
     *
     * Delega porque un `insert` duplicado tiene que poder escribirse: si el doble
     * descartara la segunda llamada, el defecto seria invisible aqui y solo
     * aparecia en el store de verdad.
     */
    private class CountingMessageStore : MessageStore {
        val inner = InMemoryMessageStore()

        /** Llamadas a [insert], una por invocacion. */
        var insertCalls = 0
            private set

        /** Fallos de [insert] que este doble haprovocado. */
        var insertFailures = 0
            private set

        /** Cuando es `true`, el proximo [insert] falla. */
        var failNextInsert = false

        override fun insert(message: Message): Result<Unit> {
            insertCalls++
            if (failNextInsert) {
                insertFailures++
                failNextInsert = false
                return Result.failure(IllegalStateException("disco lleno"))
            }
            return inner.insert(message)
        }

        override fun updateState(messageId: MessageId, state: MessageState, failureCode: FailureCode?) =
            inner.updateState(messageId, state, failureCode)

        override fun get(messageId: MessageId): Result<Message?> = inner.get(messageId)

        override fun listByConversation(localId: IdentityId, peerId: IdentityId) =
            inner.listByConversation(localId, peerId)

        override fun delete(messageId: MessageId) = inner.delete(messageId)
    }

    /**
     * Transporte con dos canales contados por separado.
     *
     * Guarda tambien los manejadores que el nucleo registra en su `init`, para
     * poder inyectar frames desde el test sin un rele de verdad.
     */
    private class CountingTransport : Transport {
        /** Cargas que salieron por el canal `MESSAGE`. */
        val messages = mutableListOf<ByteArray>()

        /** `ACK` que salieron por el canal `ACK`. */
        val acks = mutableListOf<ByteArray>()

        var online = true

        var messageHandler: ((ByteArray) -> Unit)? = null
            private set
        var ackHandler: ((ByteArray) -> Unit)? = null
            private set

        override fun connect(): Result<Unit> {
            online = true
            return Result.success(Unit)
        }

        override fun disconnect() {
            online = false
        }

        override fun send(data: ByteArray, to: IdentityId): Result<Unit> {
            messages.add(data)
            return Result.success(Unit)
        }

        override fun sendAck(ackBytes: ByteArray): Result<Unit> {
            acks.add(ackBytes)
            return Result.success(Unit)
        }

        override fun setMessageHandler(handler: (ByteArray) -> Unit) {
            messageHandler = handler
        }

        override fun setAckHandler(handler: (ByteArray) -> Unit) {
            ackHandler = handler
        }

        override fun isOnline(): Boolean = online

        override fun connectionState(): ConnectionState =
            if (online) ConnectionState.ONLINE else ConnectionState.DISCONNECTED

        override fun onStateChange(handler: (ConnectionState) -> Unit) {}
        override fun onPeerOnline(handler: (IdentityId) -> Unit) {}
        override fun onPeerOffline(handler: (IdentityId) -> Unit) {}
    }

    /** Nodo de Alice: envia y recibe, y autentica a Bob. */
    private fun alice(
        store: MessageStore = CountingMessageStore(),
        transport: Transport = CountingTransport(),
        offlineQueue: OfflineQueue = InMemoryOfflineQueue(),
        duplicateStore: DuplicateStore = InMemoryDuplicateStore(),
        knowsBob: Boolean = true,
        encoderOverride: Encoder = encoder,
    ) = KeyMessageCoreImpl(
        localIdentity = aliceId,
        localPrivateKey = aliceKp.privateKey,
        transport = transport,
        ed25519 = ed25519,
        messageStore = store,
        offlineQueue = offlineQueue,
        duplicateStore = duplicateStore,
        encoder = encoderOverride,
        decoder = decoder,
        peerPublicKeyResolver = { peer ->
            // Conoce la clave de Bob solo si el bundle se le dio.
            if (peer == bobId && knowsBob) bobKp.publicKey else null
        },
    )

    /** Nodo de Bob: el que recibe mensajes y firma ACKs. */
    private fun bob(
        store: MessageStore = CountingMessageStore(),
        transport: Transport = CountingTransport(),
        duplicateStore: DuplicateStore = InMemoryDuplicateStore(),
    ) = KeyMessageCoreImpl(
        localIdentity = bobId,
        localPrivateKey = bobKp.privateKey,
        transport = transport,
        ed25519 = ed25519,
        messageStore = store,
        duplicateStore = duplicateStore,
        encoder = encoder,
        decoder = decoder,
    )

    /** Bytes de un `MESSAGE` entrante, en el vocabulario de `km-core`. */
    private fun wireMessage(
        from: IdentityId,
        to: IdentityId,
        payload: ByteArray,
        id: MessageId = MessageId(UUID.randomUUID()),
    ): ByteArray = encoder.encodeMessage(Message(id, from, to, payload, 1_790_000_000_000)).getOrThrow()

    /** Un `ACK` firmado con la clave de Bob. */
    private fun signedAck(
        from: IdentityId,
        to: IdentityId,
        originalMessageId: MessageId,
        status: AckStatus = AckStatus.DELIVERED,
        timestamp: Long = 1_790_000_000_000,
        privateKey: ByteArray = bobKp.privateKey,
    ): ByteArray {
        val data = AckTranscript.build(from, to, originalMessageId, status, timestamp)
        val ack = Ack(
            from = from,
            to = to,
            originalMessageId = originalMessageId,
            status = status,
            timestamp = timestamp,
            signature = ed25519.sign(privateKey, data).bytes,
        )
        return encoder.encodeAck(ack).getOrThrow()
    }

    // ==================================================================
    // F1 — createMessage() persiste exactamente una vez
    // ==================================================================

    @Test
    fun `S1_1 createMessage inserta UNA vez y devuelve el mensaje persistido`() {
        val store = CountingMessageStore()
        val core = alice(store = store)

        val message = core.createMessage(bobId, "hola".toByteArray()).getOrThrow()

        assertEquals(1, store.insertCalls, "createMessage debe insertar exactamente una vez")
        // Y el mensaje devuelto es el que esta en el store, no una copia: si
        // divergieran, `send` leeria un registro y el llamaria con otro.
        val stored = store.get(message.messageId).getOrThrow()
        assertNotNull(stored, "el mensaje devuelto tiene que estar en el store")
        assertEquals(message, stored)
        assertEquals(MessageState.CREATED, stored!!.state)
    }

    @Test
    fun `S1_1 createMessage no inserta mas de una vez por mensaje`() {
        val store = CountingMessageStore()
        val core = alice(store = store)

        repeat(5) { core.createMessage(bobId, "carga $it".toByteArray()).getOrThrow() }

        assertEquals(5, store.insertCalls, "cinco mensajes, cinco escrituras: ni una mas")
    }

    @Test
    fun `S1_2 un insert fallido hace fallar createMessage y no emite nada`() {
        val store = CountingMessageStore()
        val wire = CountingTransport()
        val core = alice(store = store, transport = wire)
        store.failNextInsert = true

        val result = core.createMessage(bobId, "hola".toByteArray())

        assertTrue(result.isFailure, "createMessage debe propagar el fallo del store")
        assertEquals(1, store.insertFailures)
        // R1.2: sin mensaje persistido no hay nada que enviar. Enviarlo sin
        // persistir seria peor: un reintento no tendria de donde volver.
        assertTrue(wire.messages.isEmpty(), "un create fallido no puede transmitir")
        assertTrue(wire.acks.isEmpty())
    }

    @Test
    fun `R1_3 send NO vuelve a insertar`() {
        val store = CountingMessageStore()
        val core = alice(store = store)

        val message = core.createMessage(bobId, "hola".toByteArray()).getOrThrow()
        assertEquals(1, store.insertCalls, "createMessage inserta una vez")

        core.sendMessage(message).getOrThrow()

        assertEquals(1, store.insertCalls, "send no inserta: el registro ya existe")
    }

    @Test
    fun `R1_3 el envio en cola y su reintento NO vuelven a insertar`() {
        val store = CountingMessageStore()
        val wire = CountingTransport()
        val core = alice(store = store, transport = wire)

        val message = core.createMessage(bobId, "hola".toByteArray()).getOrThrow()

        // Sin conexion: `send` encola en vez de salir. El registro sigue siendo
        // el de `createMessage`.
        wire.online = false
        core.sendMessage(message).getOrThrow()
        assertEquals(MessageState.QUEUED, store.get(message.messageId).getOrThrow()!!.state)

        // Vuelve la conexion y se reintenta: sigue sin escribirse nada nuevo.
        wire.online = true
        core.sendMessage(message).getOrThrow()

        assertEquals(1, store.insertCalls, "ni la cola ni el reintento insertan")
    }

    @Test
    fun `R1_3 un mensaje sin persistir NO se envia`() {
        val store = CountingMessageStore()
        val wire = CountingTransport()
        val core = alice(store = store, transport = wire)

        // Fabricado a mano y nunca creado por `createMessage`: no hay registro.
        val orphan = Message(
            messageId = MessageId(UUID.randomUUID()),
            from = aliceId,
            to = bobId,
            payload = "huerfano".toByteArray(),
            timestamp = 1L,
            state = MessageState.CREATED,
        )

        val result = core.sendMessage(orphan).getOrThrow()

        assertEquals(MessageState.FAILED, result.state, "sin registro no hay envio que reintentar")
        assertTrue(wire.messages.isEmpty(), "un mensaje sin registro no sale")
        assertEquals(0, store.insertCalls, "y enviarlo tampoco lo persiste por las espaldas")
    }

    // ==================================================================
    // F2 — un MESSAGE, un ACK, cero amplificacion
    // ==================================================================

    @Test
    fun `S2_1 un MESSAGE recibido produce UN ACK con su originalMessageId`() {
        val bobStore = CountingMessageStore()
        val bobWire = CountingTransport()
        val bob = bob(store = bobStore, transport = bobWire)

        val inbound = wireMessage(aliceId, bobId, "hola".toByteArray())
        bobWire.messageHandler!!.invoke(inbound)

        // Cero `MESSAGE`: el ACK no puede salir disfrazado.
        assertTrue(bobWire.messages.isEmpty(), "el ACK no sale por el canal de MESSAGE")
        assertEquals(1, bobWire.acks.size, "un MESSAGE produce un ACK")

        val ack = decoder.decodeAck(bobWire.acks.single()).getOrThrow()
        assertEquals(
            decoder.decodeMessage(inbound).getOrThrow()!!.messageId,
            ack.originalMessageId,
            "el ACK confirma ESE mensaje, con su identificador",
        )
        assertEquals(AckStatus.DELIVERED, ack.status)
        assertEquals(bobId, ack.from)
        assertEquals(aliceId, ack.to)
    }

    @Test
    fun `S2_4 un MESSAGE duplicado confirma el MISMO mensaje, sin persistir dos veces`() {
        val bobStore = CountingMessageStore()
        val bobWire = CountingTransport()
        val bob = bob(store = bobStore, transport = bobWire)

        val inbound = wireMessage(aliceId, bobId, "hola".toByteArray())
        bobWire.messageHandler!!.invoke(inbound)
        bobWire.messageHandler!!.invoke(inbound)

        // Cada entrega se confirma: es lo que evita que el emisor retransmita.
        assertEquals(2, bobWire.acks.size, "un reintento tambien se confirma")

        val first = decoder.decodeAck(bobWire.acks[0]).getOrThrow()
        val second = decoder.decodeAck(bobWire.acks[1]).getOrThrow()
        // Mismo `originalMessageId`: la deduplicacion es contra el mensaje
        // CONFIRMADO. Un identificador nuevo por ACK haria que cada reintento
        // pareciese un mensaje distinto.
        assertEquals(first.originalMessageId, second.originalMessageId)
        assertEquals(decoder.decodeMessage(inbound).getOrThrow()!!.messageId, first.originalMessageId)

        // Y el duplicado no se persiste dos veces.
        assertEquals(1, bobStore.insertCalls, "el receptor deduplica por messageId")
    }

    /**
     * El escenario de aceptacion entero: 1 MESSAGE -> 1 ACK -> 0 ACKs extra ->
     * 0 MESSAGE desde el ACK.
     *
     * Es el unico test que mira el ciclo completo, y el que distingue "no hay
     * amplificacion" de "la amplificacion todavia no ha ocurrido".
     */
    @Test
    fun `aceptacion 1 MESSAGE 1 ACK 0 ACKs extra 0 MESSAGE desde el ACK`() {
        val aliceStore = CountingMessageStore()
        val aliceWire = CountingTransport()
        val sender = alice(store = aliceStore, transport = aliceWire)

        val message = sender.createMessage(bobId, "hola".toByteArray()).getOrThrow()
        sender.sendMessage(message).getOrThrow()

        // --- 1 MESSAGE sale del emisor, y ningun ACK todavia.
        assertEquals(1, aliceWire.messages.size, "createMessage + send = un MESSAGE")
        assertTrue(aliceWire.acks.isEmpty(), "un mensaje saliente no genera ningun ACK")
        val messageBytes = aliceWire.messages.single()

        // --- Bob lo recibe y responde.
        val bobStore = CountingMessageStore()
        val bobWire = CountingTransport()
        val receiver = bob(store = bobStore, transport = bobWire)
        val deliveries = mutableListOf<Message>()
        receiver.registerMessageHandler { deliveries.add(it) }

        bobWire.messageHandler!!.invoke(messageBytes)

        assertEquals(1, deliveries.size, "el mensaje llega una vez")
        assertEquals(1, bobStore.insertCalls, "y se persiste una vez")
        assertEquals(1, bobWire.acks.size, "1 MESSAGE -> 1 ACK")
        assertTrue(bobWire.messages.isEmpty(), "el ACK no genera un MESSAGE")
        val ackBytes = bobWire.acks.single()

        // --- El ACK vuelve al emisor, que lo autentica con la clave de Bob.
        val acks = mutableListOf<Ack>()
        sender.registerAckHandler { acks.add(it) }
        aliceWire.ackHandler!!.invoke(ackBytes)

        assertEquals(1, acks.size, "el emisor recibe su ACK y lo procesa")
        assertEquals(message.messageId, acks.single().originalMessageId)
        assertEquals(
            MessageState.DELIVERED,
            aliceStore.get(message.messageId).getOrThrow()!!.state,
            "el ACK autentica y mueve el estado a DELIVERED",
        )

        // --- Y por el camino no ha aparecido nada mas.
        assertTrue(aliceWire.acks.isEmpty(), "0 ACKs extra: un ACK no genera un ACK")
        assertEquals(1, aliceStore.insertCalls, "y el emisor no inserta nada nuevo")
    }

    @Test
    fun `R2_7 un ACK se autentica con la clave del emisor y procesa`() {
        val store = CountingMessageStore()
        val wire = CountingTransport()
        val core = alice(store = store, transport = wire, knowsBob = true)

        val message = core.createMessage(bobId, "hola".toByteArray()).getOrThrow()
        core.sendMessage(message).getOrThrow()

        val seen = mutableListOf<Ack>()
        core.registerAckHandler { seen.add(it) }
        wire.ackHandler!!.invoke(signedAck(bobId, aliceId, message.messageId))

        assertEquals(1, seen.size, "con la clave del emisor el ACK se procesa")
        assertEquals(MessageState.DELIVERED, store.get(message.messageId).getOrThrow()!!.state)
    }

    @Test
    fun `R2_7 un ACK firmado con OTRA clave NO se procesa`() {
        val store = CountingMessageStore()
        val wire = CountingTransport()
        // Mallory firma con su clave, pero el resolver declara la de Bob.
        val malloryKp = ed25519.generateKeyPair()
        val core = alice(store = store, transport = wire, knowsBob = true)

        val message = core.createMessage(bobId, "hola".toByteArray()).getOrThrow()
        core.sendMessage(message).getOrThrow()

        val seen = mutableListOf<Ack>()
        core.registerAckHandler { seen.add(it) }
        wire.ackHandler!!.invoke(
            signedAck(bobId, aliceId, message.messageId, privateKey = malloryKp.privateKey)
        )

        // El `messageId` es legitimo y la firma no. Sin esta comprobacion,
        // cualquiera podria dar por entregado un mensaje que no llego, y el
        // emisor dejaria de reintentar.
        assertTrue(seen.isEmpty(), "un ACK con firma ajena no se procesa")
        assertEquals(
            MessageState.SENT,
            store.get(message.messageId).getOrThrow()!!.state,
            "el estado no avanza con una firma que no verifica",
        )
    }

    @Test
    fun `R2_7 sin clave del emisor el ACK no se procesa, y no es un fallo`() {
        val store = CountingMessageStore()
        val wire = CountingTransport()
        // Sin bundle, el resolver no sabe de nadie: `onAckReceived` no puede
        // autenticar, y eso es un resultado legitimo, no una excepcion.
        val core = alice(store = store, transport = wire, knowsBob = false)

        val message = core.createMessage(bobId, "hola".toByteArray()).getOrThrow()
        core.sendMessage(message).getOrThrow()

        val seen = mutableListOf<Ack>()
        core.registerAckHandler { seen.add(it) }
        wire.ackHandler!!.invoke(signedAck(bobId, aliceId, message.messageId))

        assertTrue(seen.isEmpty())
        assertEquals(MessageState.SENT, store.get(message.messageId).getOrThrow()!!.state)
    }

    @Test
    fun `R2_5 el ACK se entrega al manejador de ACK, no al de mensaje`() {
        // Los dos canales son de entrada y de salida. Este test mira el de
        // ENTRADA, que es donde un enrutado equivocado reabre el ciclo: si el
        // `ACK` llegara al manejador de mensaje, el receptor lo ingeria como un
        // mensaje mas y responderia con otro `ACK`.
        val store = CountingMessageStore()
        val wire = CountingTransport()
        val core = alice(store = store, transport = wire)

        val message = core.createMessage(bobId, "hola".toByteArray()).getOrThrow()
        core.sendMessage(message).getOrThrow()

        val deliveries = mutableListOf<Message>()
        val acks = mutableListOf<Ack>()
        core.registerMessageHandler { deliveries.add(it) }
        core.registerAckHandler { acks.add(it) }
        val acksOutBefore = wire.acks.size

        val ackBytes = signedAck(bobId, aliceId, message.messageId)
        wire.ackHandler!!.invoke(ackBytes)

        // Al manejador de ACK, y solo a el.
        assertEquals(1, acks.size, "un frame ACK va al manejador de ACK")
        assertEquals(message.messageId, acks.single().originalMessageId)
        assertTrue(deliveries.isEmpty(), "y NO al manejador de mensaje")
        assertEquals(MessageState.DELIVERED, store.get(message.messageId).getOrThrow()!!.state)

        // Y no genera trafico de salida: un `ACK` no es un `MESSAGE`.
        assertEquals(acksOutBefore, wire.acks.size, "un ACK recibido no produce un ACK")
        assertEquals(1, wire.messages.size, "ni un MESSAGE: sigue habiendo solo el de salida")
    }

    @Test
    fun `R2_5 un frame MESSAGE no se puede leer como ACK`() {
        // La mitad contraria del enrutado: un `MESSAGE` que se colara por el
        // canal de `ACK` haria que `AckManager` intentara verificar una firma
        // sobre un mensaje, y fallaria por forma. Que falle es correcto; que
        // delivered something seria el defecto.
        val messageBytes = wireMessage(bobId, aliceId, "hola".toByteArray())
        assertTrue(
            decoder.decodeAck(messageBytes).isFailure,
            "un MESSAGE no decodifica como Ack: le faltan originalMessageId y status",
        )
    }
}