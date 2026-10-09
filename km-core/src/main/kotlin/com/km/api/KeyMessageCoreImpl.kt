package com.km.api

import com.km.codec.*
import com.km.crypto.*
import com.km.model.*
import com.km.protocol.*
import com.km.storage.*
import java.time.Instant
import java.util.UUID

class KeyMessageCoreImpl(
    private val localIdentity: IdentityId,
    private val localPrivateKey: ByteArray,
    private val transport: Transport,
    private val ed25519: Ed25519 = Ed25519Impl(),
    private val messageStore: MessageStore = InMemoryMessageStore(),
    private val offlineQueue: OfflineQueue = InMemoryOfflineQueue(),
    private val duplicateStore: DuplicateStore = InMemoryDuplicateStore(),
    private val encoder: Encoder = JsonEncoder(),
    private val decoder: Decoder = JsonDecoder(),
    private val validator: Validator = ValidatorImpl(),
    /**
     * Clave de FIRMA Ed25519 de un peer, por identidad. F2/D3.
     *
     * Es la pieza que faltaba para que `onAckReceived` authenticara: con el
     * valor por defecto el resolver devuelve `null`, la verificacion se salta
     * y ningun `ACK` procesa nunca, aunque llegue bien formado.
     *
     * De donde sale la clave NO es de este modulo: sale del establecimiento
     * X3DH, es decir del `ContactBundle` del peer. Por eso se inyecta y no se
     * deriva. Un consumidor que tenga el bundle pasa aqui
     * `bundle.identityKey` para la identidad del Dueño del bundle; uno que no
     * lo tenga, y que por tanto no puede autenticar ningun `ACK`, recibe el
     * comportamiento documentado: `onAckReceived` no procesa nada.
     */
    private val peerPublicKeyResolver: (IdentityId) -> ByteArray? = { null }
) : KeyMessageCore {

    private val orderingManager: OrderingManagerImpl = OrderingManagerImpl()
    private val retryManager: RetryManagerImpl = RetryManagerImpl()
    private val ackManager: AckManagerImpl = AckManagerImpl(messageStore, ed25519, peerPublicKeyResolver)
    private lateinit var sender: SenderImpl
    private lateinit var receiver: ReceiverImpl

    init {
        sender = SenderImpl(
            messageStore, offlineQueue, encoder, validator, ed25519,
            transport, ackManager, retryManager, localIdentity, localPrivateKey
        )
        receiver = ReceiverImpl(
            messageStore, duplicateStore, decoder, encoder, validator, ed25519,
            transport, orderingManager, localIdentity, localPrivateKey,
            // F2/D2: la emision del `ACK` sale de aqui, no del receptor, para
            // que el `originalMessageId` que se firma y el que se envia sean el
            // mismo objeto y no dos construcciones que puedan divergir.
            ackEmitter = ::emitAck
        )

        retryManager.onRetry { messageId, attempt ->
            sender.onRetryExpired(messageId, attempt)
        }
        transport.onPeerOnline { peerId ->
            sender.onPeerOnline(peerId)
        }
        transport.setMessageHandler { data ->
            receiver.onMessageBytesReceived(data)
        }
        transport.setAckHandler { data ->
            val ack = decoder.decodeAck(data).getOrNull()
            if (ack != null) ackManager.onAckReceived(ack)
        }

        sender.registerStateHandler { id, state, _ ->
            stateChangeHandlers.forEach { it(id, state) }
        }
    }

    private val stateChangeHandlers = mutableListOf<(MessageId, MessageState) -> Unit>()

    /**
     * Emite un `ACK` firmado por el canal `ACK`, no por el de `MESSAGE`.
     *
     * F2/D1: `Transport.sendAck` y `Transport.send` son canales distintos a
     * proposito. Un `ACK` enviado por `send` seria envuelto por el transporte en
     * un envelope `MESSAGE`, llegaria al receptor contrario como un mensaje
     * entrante mas, y ese mensaje generaria OTRO `ACK`. Uno aqui, otro alla, y
     * el ciclo se cierra solo.
     *
     * El `originalMessageId` viaja en el `ACK` y es lo que empareja con
     * `AckManager.expectAck`, de modo que la deduplicacion es por mensaje
     * confirmado y no por mensaje recibido (F2/D2).
     */
    private fun emitAck(ack: Ack): Result<Unit> {
        val encoded = encoder.encodeAck(ack).getOrElse { return Result.failure(it) }
        return transport.sendAck(encoded)
    }

    override fun start(): Result<Unit> {
        if (transport.isOnline()) return Result.success(Unit)
        return transport.connect()
    }

    override fun stop() {
        if (transport.connectionState() == ConnectionState.DISCONNECTED) return
        transport.disconnect()
    }

    /**
     * Crea el mensaje Y lo persiste.
     *
     * R1.1: exactamente un `insert` por `createMessage`, aqui. R1.2: si el
     * `insert` falla, `createMessage` falla con el fallo del almacen y no
     * devuelve un `Message` que nadie ha guardado — un mensaje que existe en
     * memoria pero no en el store no se puede reintentar, consultar ni
     * conversationar.
     *
     * La insercion va ANTES del `return` y no se traga el error: `runCatching`
     * mas `getOrThrow` convierte el fallo del store en fallo de
     * `createMessage`, que es lo que R1.2 pide.
     *
     * EL `messageId` QUE SE ACUNA AQUI ES EL DE CORRELACION
     *
     * Este es el UNICO sitio donde se acuña para un mensaje saliente, y el
     * valor que sale es el que queda escrito en el store, el que `SenderImpl`
     * lee y pone en `expectAck`, el que viaja en la carga del `MESSAGE` y el
     * contra el que el receptor firmara el `originalMessageId` de su `ACK`. La
     * cadena entera depende de que nadie lo vuelva a acuñar por el camino, y
     * por eso se acuña una vez y no se reconstruye en ningun tramo posterior:
     * `SenderImpl` y `RetryManagerImpl` LEEN el registro, y el consumidor
     * (`RelayDataCodec`, `RelayClient`) lo COPIA al envelope.
     *
     * Si un tramo futuro vuelve a generar un identificador, la entrega se
     * seguiria confirmando en silencio: `AckManagerImpl.onAckReceived` busca el
     * `originalMessageId` en el store, no lo encuentra y devuelve `success`.
     * `AckCorrelationTest` es lo que detects eso.
     */
    override fun createMessage(to: IdentityId, payload: ByteArray): Result<Message> = runCatching {
        val message = Message(
            messageId = MessageId(UUID.randomUUID()),
            from = localIdentity,
            to = to,
            payload = payload,
            timestamp = Instant.now().toEpochMilli(),
            state = MessageState.CREATED
        )
        messageStore.insert(message).getOrThrow()
        message
    }

    override fun sendMessage(message: Message): Result<SendResult> = runCatching {
        val result = sender.send(message)
        if (result.isSuccess) {
            SendResult(message.messageId, MessageState.SENT)
        } else {
            SendResult(message.messageId, MessageState.FAILED, FailureCode.INTERNAL_ERROR)
        }
    }

    override fun getMessageState(messageId: MessageId): Result<MessageState> {
        return messageStore.get(messageId).map { it?.state ?: MessageState.FAILED }
    }

    override fun getConversation(peerId: IdentityId): Result<List<Message>> {
        return messageStore.listByConversation(localIdentity, peerId)
    }

    override fun registerAckHandler(handler: (Ack) -> Unit) {
        ackManager.registerAckHandler(handler)
    }

    override fun registerMessageHandler(handler: (Message) -> Unit) {
        receiver.registerMessageHandler(handler)
    }

    override fun registerStateChangeHandler(handler: (MessageId, MessageState) -> Unit) {
        stateChangeHandlers.add(handler)
    }
}
