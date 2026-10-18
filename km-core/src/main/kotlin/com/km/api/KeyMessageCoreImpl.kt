package com.km.api

import com.km.codec.*
import com.km.crypto.*
import com.km.messaging.LegacyInboundObservation
import com.km.messaging.SecureInbound
import com.km.messaging.SecureOutboundImpl
import com.km.messaging.SecureSessionRegistry
import com.km.model.*
import com.km.node.PeerTransportManager
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
    private val peerPublicKeyResolver: (IdentityId) -> ByteArray? = { null },
    /**
     * R1 — REGISTRO DE SESIONES, OPCIONAL Y AUSENTE POR DEFECTO.
     *
     * **Si es `null`, el envio sale EN CLARO por el camino legado.** Es una
     * decision de COMPATIBILIDAD, no una garantia de nada: ningun consumidor
     * actual construye el registro, y romperlos todos no demostraria cifrado,
     * demostraria rotura. Ese camino no puede presentarse nunca como
     * "cifrado": lo que se presenta es lo que hay, un `Message` codificado por
     * `Encoder` y entregado a `Transport.send`.
     *
     * Si es `null` pero `peerTransportManager` no lo es, el nucleo no descifra
     * nada: sin registro no hay a quien preguntar por el par, y adivinar seria
     * tratar bytes de sesion como si fueran JSON.
     *
     * Si NO es `null` y no hay sesion con el destinatario, el envio se RECHAZA
     * de forma observable (estado `FAILED` con codigo de fallo, sin encolar y
     * sin llamar a `Transport.send`). Nunca hay reintento en claro.
     *
     * En RECEPCION el mismo registro cierra el camino legado: con el registro
     * presente, un `MESSAGE` en claro no llega al handler. Ver
     * [allowLegacyCleartextReceive], que es el unico modo de reabrirlo.
     */
    private val secureSessionRegistry: SecureSessionRegistry? = null,
    /**
     * R1 — RECEPCION: EL OTRO LADO DE LA REGLA, Y EL QUE FALTABA.
     *
     * El envio ya tenia punto de decision: con el registro presente nunca se
     * degrada a `Transport.send`. La recepcion no lo tenia, y por eso con el
     * registro presente un `MESSAGE` EN CLARO del camino legado llegaba igual al
     * handler de la aplicacion que un mensaje E2E, sin ninguna marca de origen.
     * Un consumidor que hubiera inyectado el registro creyendo estar cifrado
     * no tenia ninguna forma de saberlo. Eso es lo que este parametro cierra.
     *
     * **Registro presente y este valor en `false` (el valor por defecto) —> el
     * camino legado de RECEPTION esta DESACTIVADO.** Un `MESSAGE` en claro no
     * llega al handler, y el rechazo se ve por API publica en
     * [legacyInbound].rejectedCleartext, igual que el del envio se ve en el
     * estado `FAILED` del mensaje.
     *
     * **Registro ausente -> el camino legado se conserva tal cual**, en claro y
     * sin marcar, porque es el camino de COMPATIBILIDAD y ningun consumidor
     * actual construye el registro. Es lo que hace el espejo de la regla de
     * envio, y por eso `allowLegacyCleartextReceive` no cambia NADA cuando no
     * hay registro: el opt-in solo existe para el caso con registro.
     *
     * **El valor en `true` es un opt-in EXPLICITO y NO es E2E.** Acepta claro
     * con el registro activo, y ademas lo deja marcado en
     * [legacyInbound].deliveredCleartext para que no se pueda confundir con un
     * mensaje descifrado. Se declara aqui porque un opt-in que solo esta en el
     * CHANGELOG no es un opt-in: es un rumor.
     */
    private val allowLegacyCleartextReceive: Boolean = false,
    /**
     * Gestor de transporte entre pares, opcional y ausente por defecto.
     *
     * Es la otra mitad del camino cifrado: por el van los `SecureFrame`. Sin
     * el no hay suscripcion de recepcion, porque el nucleo no tendria por donde
     * enterarse de que llego un wire.
     */
    private val peerTransportManager: PeerTransportManager? = null,
) : KeyMessageCore {

    private val orderingManager: OrderingManagerImpl = OrderingManagerImpl()
    private val retryManager: RetryManagerImpl = RetryManagerImpl()
    private val ackManager: AckManagerImpl = AckManagerImpl(messageStore, ed25519, peerPublicKeyResolver)
    private lateinit var sender: SenderImpl
    private lateinit var receiver: ReceiverImpl

    /**
     * Puente de RECEPCION cifrada, o `null` si no se inyectaron las dos piezas.
     *
     * Exponerlo es para observabilidad: los pruebas del slice 4 necesitan
     * distinguir "no llego" de "llego y se rechazo", que con la API publica
     * son el mismo silencio.
     */
    val secureInbound: SecureInbound? =
        if (secureSessionRegistry != null && peerTransportManager != null) {
            SecureInbound(localIdentity, secureSessionRegistry, peerTransportManager) { message ->
                receiver.onMessageReceived(message)
            }
        } else {
            null
        }

    /**
     * R1 — Lo que ha hecho el CAMINO LEGADO de recepcion. Observable por API
     * publica, y no un log: con el registro activo el camino legado esta
     * cerrado, y "cerrado" tiene que poder distinguirse de "nunca llego".
     *
     * [legacyReceiveEnabled] vale `false` cuando hay registro y el consumidor no
     * ha pedido el opt-in, y `true` en los otros dos casos: sin registro
     * (compatibilidad) o con el opt-in explicito.
     */
    val legacyInbound: LegacyInboundObservation = LegacyInboundObservation(
        legacyReceiveEnabled = secureSessionRegistry == null || allowLegacyCleartextReceive,
    )

    init {
        sender = SenderImpl(
            messageStore, offlineQueue, encoder, validator, ed25519,
            transport, ackManager, retryManager, localIdentity, localPrivateKey,
            secureOutbound = secureSessionRegistry?.let { SecureOutboundImpl(it) },
        )
        receiver = ReceiverImpl(
            messageStore, duplicateStore, decoder, encoder, validator, ed25519,
            transport, orderingManager, localIdentity, localPrivateKey,
            // F2/D2: la emision del `ACK` sale de aqui, no del receptor, para
            // que el `originalMessageId` que se firma y el que se envia sean el
            // mismo objeto y no dos construcciones que puedan divergir.
            ackEmitter = ::emitAck
        )
        secureInbound?.attach()

        retryManager.onRetry { messageId, attempt ->
            sender.onRetryExpired(messageId, attempt)
        }
        transport.onPeerOnline { peerId ->
            sender.onPeerOnline(peerId)
        }
        transport.setMessageHandler { data ->
            // R1 — RECEPCION. El camino legado de `MESSAGE` esta cerrado si hay
            // registro, y el cierre es OBSERVABLE: cuenta en
            // `legacyInbound.rejectedCleartext` en vez de tragarse los bytes. Un
            // silencio aqui es exactamente lo que hace que un mensaje inyectado
            // en claro sea indistinguible de uno que nunca llego.
            if (!legacyInbound.legacyReceiveEnabled) {
                legacyInbound.rejectCleartext()
                return@setMessageHandler
            }
            legacyInbound.markDeliveredCleartext()
            receiver.onMessageBytesReceived(data)
        }
        transport.setAckHandler { data ->
            val ack = decoder.decodeAck(data).getOrNull()
            if (ack != null) ackManager.onAckReceived(ack)
        }

        sender.registerStateHandler { id, state, _ ->
            stateChangeHandlers.forEach { it(id, state) }
        }
        // p4 (task 4.2): el wire cacheado de un mensaje se libera cuando llega su
        // `ACK` AUTENTICADO. `registerAckHandler` de `AckManagerImpl` dispara
        // SOLO tras verificar la firma, de modo que un `ACK` falsificado no
        // libera nada. Sin este cable, `lastWireByMessageId` crece sin limite:
        // cada mensaje cifrado dejaba su wire para siempre.
        ackManager.registerAckHandler { ack ->
            sender.onAckConfirmed(ack.originalMessageId)
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

    /**
     * Reintenta un mensaje YA enviado, por el camino que le corresponde.
     *
     * No esta en [KeyMessageCore] a proposito: el motor de reintentos ya es
     * quien decide CUANDO reintentar (programa el temporizador y acaba llamando
     * aqui), y exponerlo por el API publico invita a que un consumidor dispare
     * reintentos por su cuenta.
     *
     * Lo que si hacia falta era poder OBSERVARLA: hasta ahora no habia ninguna
     * puerta desde fuera al camino de reintento, y sin ella la unica evidencia
     * de que el reintento reenvia el MISMO wire era leer el codigo. Con esta,
     * la evidencia es el segundo frame que sale por el cable.
     */
    fun retryMessage(messageId: MessageId): Result<Unit> = sender.retry(messageId)

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
