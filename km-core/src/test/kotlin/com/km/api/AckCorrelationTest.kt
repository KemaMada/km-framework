package com.km.api

import com.km.codec.JsonDecoder
import com.km.codec.JsonEncoder
import com.km.crypto.Ed25519Impl
import com.km.model.Ack
import com.km.model.AckStatus
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
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * Correlacion del `ACK`: `MESSAGE(id=M)` -> `ACK(originalMessageId=M)` -> el
 * emisor confirma `M`.
 *
 * QUE COMPRUEBA Y POR QUE AQUI
 *
 * La correccion esta en el codec del consumidor (`RelayDataCodec`,
 * `RelayDataCodec` de Android), que es donde se acuñaba un `messageId` nuevo al
 * reconstruir el mensaje recibido. Pero lo que ese codec hace es algo que
 * `km-core` puede comprobar por si mismo, sin sockets: que el `messageId` de la
 * carga es el MISMO que el que el emisor guardo, y que el `ACK` vuelve citando
 * ese identificador y no otro.
 *
 * Aqui el "salto" lo hace [hop], que decodifica y vuelve a codificar la carga:
 * exactamente lo que hace un transporte correcto, y exactamente lo que haria uno
 * con el defecto. Sin ese paso intermedio el test no probaria nada, porque en un
 * proceso unico los bytes pasan de mano sin que nadie los vuelva a mirar.
 *
 * EL CONTROL NEGATIVO DE ABAJO ES LA PRUEBA DE QUE ESTO DETECTA EL DEFECTO
 *
 * `el salto que acuña un identificador nuevo rompe la correlacion` no documenta
 * un caso raro: es el detector del defecto, ejecutado y con resultado. Un test
 * que solo dice "la correlacion funciona" pasa igual con la correccion puesta y
 * sin ella.
 */
class AckCorrelationTest {

    private val ed25519 = Ed25519Impl()
    private val aliceKp = ed25519.generateKeyPair()
    private val bobKp = ed25519.generateKeyPair()
    private val aliceId = IdentityId("11".repeat(32))
    private val bobId = IdentityId("22".repeat(32))
    private val decoder = JsonDecoder()
    private val encoder = JsonEncoder()

    // ------------------------------------------------------------------
    // Transporte de recuento: dos canales separados
    // ------------------------------------------------------------------

    private class RecordingTransport : Transport {
        /** Cargas que salieron por el canal `MESSAGE`. */
        val messages = mutableListOf<ByteArray>()

        /** `ACK` que salieron por el canal `ACK`. */
        val acks = mutableListOf<ByteArray>()

        var online = true

        private var messageHandler: ((ByteArray) -> Unit)? = null
        private var ackHandler: ((ByteArray) -> Unit)? = null

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

        /** Entrega una carga por el canal `MESSAGE`, como haria el rele. */
        fun deliverMessage(bytes: ByteArray) {
            checkNotNull(messageHandler) { "el nucleo no registro un manejador de mensajes" }.invoke(bytes)
        }

        /** Entrega un `ACK` por el canal `ACK`, como haria el rele. */
        fun deliverAck(bytes: ByteArray) {
            checkNotNull(ackHandler) { "el nucleo no registro un manejador de ACK" }.invoke(bytes)
        }
    }

    private fun alice(
        store: MessageStore = InMemoryMessageStore(),
        transport: Transport = RecordingTransport(),
        knowsBob: Boolean = true,
    ) = KeyMessageCoreImpl(
        localIdentity = aliceId,
        localPrivateKey = aliceKp.privateKey,
        transport = transport,
        ed25519 = ed25519,
        messageStore = store,
        offlineQueue = InMemoryOfflineQueue(),
        duplicateStore = InMemoryDuplicateStore(),
        encoder = encoder,
        decoder = decoder,
        peerPublicKeyResolver = { peer -> if (peer == bobId && knowsBob) bobKp.publicKey else null },
    )

    private fun bob(
        store: MessageStore = InMemoryMessageStore(),
        transport: Transport = RecordingTransport(),
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

    /**
     * El salto de transporte.
     *
     * Decodifica la carga y la vuelve a codificar, que es lo que hace un
     * transporte que entrega a `km-core` el `Message` que el emisor escribio.
     * `mintFresh` reproduce el defecto: acuñar un identificador nuevo al
     * reconstruir.
     */
    private fun hop(wire: ByteArray, mintFresh: Boolean = false): ByteArray {
        val message = decoder.decodeMessage(wire).getOrThrow()
        val carried = if (mintFresh) message.copy(messageId = MessageId(UUID.randomUUID())) else message
        return encoder.encodeMessage(carried).getOrThrow()
    }

    /** `ACK` saliente de Bob, ya decodificado. */
    private fun acksOf(transport: RecordingTransport): List<Ack> =
        transport.acks.map { decoder.decodeAck(it).getOrThrow() }

    // ==================================================================
    // S1 — el `messageId` de la carga es el que creo `createMessage`
    // ==================================================================

    @Test
    fun `S1 el MESSAGE que sale lleva el messageId que creo createMessage`() {
        val wire = RecordingTransport()
        val core = alice(transport = wire)

        val created = core.createMessage(bobId, "hola".toByteArray()).getOrThrow()
        core.sendMessage(created).getOrThrow()

        val onTheWire = decoder.decodeMessage(wire.messages.single()).getOrThrow()
        assertEquals(
            created.messageId, onTheWire.messageId,
            "la carga tiene que conservar el identificador que el emisor guardo",
        )
    }

    // ==================================================================
    // S2 + S3 — ida, ACK, y vuelta con el identificador correcto
    // ==================================================================

    @Test
    fun `S2 el ACK de B cita el messageId del MESSAGE que recibio`() {
        val bobWire = RecordingTransport()
        val aliceWire = RecordingTransport()
        val bobStore = InMemoryMessageStore()
        val bobCore = bob(store = bobStore, transport = bobWire)

        // Sin `sendMessage` no hay nada en el cable: `createMessage` persiste y
        // punto. Por eso el envio va explicito y no encadenado.
        val aliceCore = alice(transport = aliceWire)
        val sent = aliceCore.createMessage(bobId, "hola".toByteArray()).getOrThrow()
        aliceCore.sendMessage(sent).getOrThrow()

        // S2: el `MESSAGE` sale de Alice, pasa por el salto y llega a Bob.
        bobWire.deliverMessage(hop(aliceWire.messages.single()))

        val ack = acksOf(bobWire).single()
        assertEquals(sent.messageId, ack.originalMessageId, "S2: el ACK cita M")
        assertEquals(bobId, ack.from)
        assertEquals(aliceId, ack.to)

        // Y el registro de Bob existe bajo ESE identificador: no es una copia del
        // mensaje con otro id que el ACK no podria encontrar.
        val stored = bobStore.get(ack.originalMessageId).getOrThrow()
        assertNotNull(stored, "el receptor guardo el mensaje bajo el id que cita el ACK")
        assertEquals(sent.messageId, stored!!.messageId)
        assertEquals(sent.payload.toList(), stored.payload.toList())
    }

    @Test
    fun `S3 el emisor correlaciona M y su estado pasa a DELIVERED`() {
        val aliceStore = InMemoryMessageStore()
        val aliceWire = RecordingTransport()
        val bobWire = RecordingTransport()
        val aliceCore = alice(store = aliceStore, transport = aliceWire)
        val bobCore = bob(transport = bobWire)

        val created = aliceCore.createMessage(bobId, "hola".toByteArray()).getOrThrow()
        aliceCore.sendMessage(created).getOrThrow()
        assertEquals(MessageState.SENT, aliceStore.get(created.messageId).getOrThrow()!!.state)

        // Ida -> ACK -> vuelta, por los dos canales y con el salto en el medio.
        bobWire.deliverMessage(hop(aliceWire.messages.single()))
        aliceWire.deliverAck(bobWire.acks.single())

        assertEquals(
            MessageState.DELIVERED,
            aliceCore.getMessageState(created.messageId).getOrThrow(),
            "S3: el ACK tiene que confirmar el registro M",
        )
        assertEquals(MessageState.DELIVERED, aliceStore.get(created.messageId).getOrThrow()!!.state)
    }

    @Test
    fun `S3 el handler de ACK del emisor recibe el ACK con M`() {
        val aliceWire = RecordingTransport()
        val bobWire = RecordingTransport()
        val aliceCore = alice(transport = aliceWire)
        val bobCore = bob(transport = bobWire)
        val seen = mutableListOf<Ack>()
        aliceCore.registerAckHandler { seen.add(it) }

        val created = aliceCore.createMessage(bobId, "hola".toByteArray()).getOrThrow()
        aliceCore.sendMessage(created).getOrThrow()
        bobWire.deliverMessage(hop(aliceWire.messages.single()))
        aliceWire.deliverAck(bobWire.acks.single())

        assertEquals(1, seen.size, "un ACK, un aviso: no cero")
        assertEquals(created.messageId, seen.single().originalMessageId)
        assertEquals(AckStatus.DELIVERED, seen.single().status)
    }

    // ==================================================================
    // R1.4 / S4 — la retransmision no genera una confirmacion distinta
    // ==================================================================

    @Test
    fun `S4 un MESSAGE duplicado produce el MISMO ACK y no duplica la entrega`() {
        val bobWire = RecordingTransport()
        val bobStore = InMemoryMessageStore()
        val delivered = mutableListOf<Message>()
        val bobCore = bob(store = bobStore, transport = bobWire)
        bobCore.registerMessageHandler { delivered.add(it) }

        val created = alice().createMessage(bobId, "hola".toByteArray()).getOrThrow()
        val outbound = encoder.encodeMessage(
            Message(created.messageId, aliceId, bobId, created.payload, created.timestamp)
        ).getOrThrow()

        bobWire.deliverMessage(hop(outbound))
        bobWire.deliverMessage(hop(outbound))

        val acks = acksOf(bobWire)
        assertEquals(2, acks.size, "un ACK por cada entrega, incluso la repetida")
        assertTrue(
            acks.all { it.originalMessageId == created.messageId },
            "los dos ACK citan el mismo M: ${acks.map { it.originalMessageId }}",
        )
        assertEquals(1, delivered.size, "el duplicado se deduplica por messageId")
    }

    @Test
    fun `S4 el emisor no cambia de estado cuando el ACK repetido llega dos veces`() {
        val aliceStore = InMemoryMessageStore()
        val aliceWire = RecordingTransport()
        val bobWire = RecordingTransport()
        val aliceCore = alice(store = aliceStore, transport = aliceWire)
        val bobCore = bob(transport = bobWire)

        val created = aliceCore.createMessage(bobId, "hola".toByteArray()).getOrThrow()
        aliceCore.sendMessage(created).getOrThrow()
        val wire = aliceWire.messages.single()

        bobWire.deliverMessage(hop(wire))
        aliceWire.deliverAck(bobWire.acks.last())
        assertEquals(MessageState.DELIVERED, aliceStore.get(created.messageId).getOrThrow()!!.state)

        // El `ACK` vuelve a llegar. `AckManagerImpl` corta en `DELIVERED`, y el
        // estado no se mueve: una confirmacion repetida no es un estado nuevo.
        bobWire.deliverMessage(hop(wire))
        aliceWire.deliverAck(bobWire.acks.last())

        assertEquals(MessageState.DELIVERED, aliceStore.get(created.messageId).getOrThrow()!!.state)
    }

    // ==================================================================
    // CONTROL NEGATIVO — el detector, ejecutado
    // ==================================================================

    @Test
    fun `CONTROL el salto que acuña un identificador nuevo ROMPE la correlacion`() {
        val aliceStore = InMemoryMessageStore()
        val aliceWire = RecordingTransport()
        val bobWire = RecordingTransport()
        val aliceCore = alice(store = aliceStore, transport = aliceWire)
        val bobCore = bob(transport = bobWire)

        val created = aliceCore.createMessage(bobId, "hola".toByteArray()).getOrThrow()
        aliceCore.sendMessage(created).getOrThrow()

        // El salto con el defecto puesto: el mensaje llega a Bob con OTRO id.
        bobWire.deliverMessage(hop(aliceWire.messages.single(), mintFresh = true))

        // El ACK cita el id equivocado, que es justo el defecto que se corrigio.
        val ack = acksOf(bobWire).single()
        assertNotEquals(created.messageId, ack.originalMessageId, "el salto con defecto cambia el id")

        aliceWire.deliverAck(bobWire.acks.single())

        // Y aqui esta la prueba de que el test de arriba detecta algo: con el
        // identificador cambiado, el emisor NO confirma. Si este test falla,
        // los tests S3 y S5 no estan probando la correlacion.
        assertNotEquals(
            MessageState.DELIVERED,
            aliceCore.getMessageState(created.messageId).getOrThrow(),
            "con el id cambiado en el salto, el emisor no puede correlacionar",
        )
        assertEquals(
            MessageState.SENT,
            aliceStore.get(created.messageId).getOrThrow()!!.state,
            "y el registro sigue en SENT: el ACK no encontro nada que confirmar",
        )
    }

    @Test
    fun `CONTROL un ACK sin la clave del peer NO confirma el mensaje`() {
        // El otro extremo del mismo fallo silencioso: `AckManagerImpl` devuelve
        // `success` tanto si verifica como si no, asi que un resolver vacio
        // tambien deja al emisor esperando para siempre. Se comprueba para que
        // `getMessageState == SENT` no se lea como "el ACK llego y no procuro".
        val aliceStore = InMemoryMessageStore()
        val aliceWire = RecordingTransport()
        val bobWire = RecordingTransport()
        // Alice NO conoce la clave de Bob: el resolver por defecto del core.
        val aliceCore = alice(store = aliceStore, transport = aliceWire, knowsBob = false)
        val bobCore = bob(transport = bobWire)

        val created = aliceCore.createMessage(bobId, "hola".toByteArray()).getOrThrow()
        aliceCore.sendMessage(created).getOrThrow()
        bobWire.deliverMessage(hop(aliceWire.messages.single()))
        aliceWire.deliverAck(bobWire.acks.single())

        assertEquals(MessageState.SENT, aliceCore.getMessageState(created.messageId).getOrThrow())
    }
}