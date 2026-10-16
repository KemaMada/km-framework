package com.km.protocol

import com.km.codec.Encoder
import com.km.codec.Validator
import com.km.crypto.Ed25519
import com.km.messaging.SecureOutbound
import com.km.messaging.SecureSendOutcome
import com.km.messaging.SecureSendReject
import com.km.messaging.SecureSendRejectedException
import com.km.model.*
import com.km.storage.MessageStore
import com.km.storage.OfflineQueue

/**
 * Emisor de km-core, y R1: EL PUNTO DE DECISION entre salir cifrado y salir en
 * claro.
 *
 * ## EL ORDEN DE LAS COMPROBACIONES, Y POR QUE ES ESTE
 *
 * 1. Hay canal? `Transport.isOnline()` responde primero. Si no lo hay NO se
 *    emite nada, y por eso no se decide nada de cifrado: el mensaje se encola
 *    como haria el camino legado. Encolar no es una forma de enviar en claro,
 *    porque lo que sale de la cola vuelve a pasar por [send], y ahi decide la
 *    sesion.
 * 2. Hay camino cifrado? Si el consumidor inyecto un `SecureOutbound`, el
 *    sobre va a la sesion del destinatario. O sale un `SecureFrame`, o se
 *    RECHAZA de forma observable. Nunca se cae a `Transport.send`.
 * 3. No hay camino cifrado: se conserva el camino legado. Y aqui esta la parte
 *    que no hay que maquillar: ESE MENSAJE SALE EN CLARO. Es compatibilidad, no
 *    cifrado, y ningun consumidor debe leerlo de otra manera.
 *
 * R3 (DISPONIBILIDAD, y solo disponibilidad): el paso 1 pregunta al `Transport`
 * legado y el paso 2 al `PeerTransportManager`. Son dos nociones distintas de "hay
 * quien me escuche" y aqui se mezclan a proposito, porque el emisor ya recibia
 * las dos: se conserva la primera y se deja que la segunda decida por si sola.
 *
 * ## LO QUE PASA CON UN DESTINO OFFLINE, DICHO SIN MAQUILLAR
 *
 * Con `SecureOutbound` inyectado, un destino offline se ENCOLA en el paso 1 y se
 * vacia por el PASO 2, es decir **CIFRADO**, o **NO se vacia**: la sesion exige
 * que el binding este CONNECTED, y si no lo esta el envio se RECHAZA de forma
 * observable y el mensaje sigue en la cola. Lo que NO puede pasar —y por eso no
 * hay que escribirlo al reves— es que se vacie por el paso 3: el paso 3 es el
 * camino legado EN CLARO y solo existe cuando `SecureOutbound == null`.
 *
 * La brecha que R3 deja abierta es, por tanto, de DISPONIBILIDAD y no de
 * confidencialidad: lo que dispara el vaciado es la presencia que declara el
 * `Transport` legado, que no es la misma nocion que el estado del binding de la
 * pila de par. Un mensaje puede esperar en la cola con la pila de par lista, y
 * eso es un retraso, no una fuga.
 *
 * ## POR QUE EL RECHAZO NO ES UNA EXCEPCION QUE SUBE
 *
 * Porque quien llama necesita distinguir "no salio" de "salio", y para eso hace
 * falta un estado, no un fallo desatendido: el mensaje queda `FAILED` con su
 * codigo de fallo en el registro, que es lo unico que sobrevive al proceso. Un
 * reintento en claro como plan B seria peor que el fallo: el usuario creeria
 * que ha enviado algo y lo que sale por la red no es lo que cree.
 */
class SenderImpl(
    private val messageStore: MessageStore,
    private val offlineQueue: OfflineQueue,
    private val encoder: Encoder,
    private val validator: Validator,
    private val ed25519: Ed25519,
    private val transport: Transport,
    private val ackManager: AckManagerImpl,
    private val retryManager: RetryManagerImpl,
    private val localIdentity: IdentityId,
    private val localPrivateKey: ByteArray,
    /**
     * Camino cifrado, o `null` si el consumidor no lo inyecto.
     *
     * `null` = el envio sale EN CLARO por `Encoder` + `Transport.send`. Ver el
     * KDoc de la clase y el de `SecureOutbound`.
     */
    private val secureOutbound: SecureOutbound? = null,
) : Sender {

    private val stateHandlers = mutableListOf<(MessageId, MessageState, FailureCode?) -> Unit>()

    /**
     * Ultimo wire cifrado por mensaje. Es lo que hace idempotente el reintento.
     *
     * Solo se escribe cuando el envio sale cifrado y con exito, y se borra en
     * cuanto ese wire deja de servir: un rechazo posterior, un `cancel`. Un
     * mensaje sin entrada aqui NO se re-cifra. Ver [retrySecure].
     */
    private val lastWireByMessageId = mutableMapOf<MessageId, ByteArray>()

    override fun send(message: Message): Result<Unit> {
        validator.validateMessage(message).getOrThrow()
        // F1: el registro lo escribio `createMessage`, y aqui SOLO se lee.
        //
        // Insertar aqui seria un segundo dono del mismo mensaje: el reintento y
        // `onPeerOnline` pasan por este mismo metodo, asi que un `insert`
        // recrearia el registro en cada intento y el `messageId` -- que es la
        // clave contra la que `AckManager` empareja -- dejaria de ser estable.
        // Ademas, un envio de un mensaje que nadie persistio no es un envio
        // recuperable: si la conexion se cae a mitad, no hay de donde reintentar.
        val stored = messageStore.get(message.messageId).getOrNull()
            ?: return Result.failure(
                NoSuchElementException("Message ${message.messageId} not found")
            )
        messageStore.updateState(stored.messageId, MessageState.SENDING, null).getOrThrow()
        stateHandlers.forEach { it(stored.messageId, MessageState.SENDING, null) }

        // (1) Sin canal no hay emision, luego no hay decision de cifrado que
        //     tomar: el mensaje espera.
        if (!transport.isOnline()) {
            return queueOffline(stored)
        }

        // (2) Camino cifrado.
        val secure = secureOutbound
        if (secure != null) {
            return sendSecure(stored, secure)
        }

        // (3) Camino legado: EN CLARO.
        val encoded = encoder.encodeMessage(stored).getOrThrow()

        val sendResult = transport.send(encoded, stored.to)
        return if (sendResult.isSuccess) {
            messageStore.updateState(stored.messageId, MessageState.SENT, null).getOrThrow()
            stateHandlers.forEach { it(stored.messageId, MessageState.SENT, null) }
            ackManager.expectAck(stored.messageId)
            retryManager.scheduleRetry(stored.messageId, 1)
            Result.success(Unit)
        } else {
            queueOffline(stored)
        }
    }

    /**
     * Entrega cifrada, o rechazo observable.
     *
     * El estado de exito es el MISMO que el del camino legado (`SENT`, con
     * `expectAck` y reintento programado) para que `AckManager` y
     * `RetryManager` no tengan que saber por donde salio el mensaje.
     */
    private fun sendSecure(stored: Message, secure: SecureOutbound): Result<Unit> {
        return when (val outcome = secure.send(stored)) {
            is SecureSendOutcome.Encrypted -> {
                lastWireByMessageId[stored.messageId] = outcome.wire
                messageStore.updateState(stored.messageId, MessageState.SENT, null).getOrThrow()
                stateHandlers.forEach { it(stored.messageId, MessageState.SENT, null) }
                ackManager.expectAck(stored.messageId)
                retryManager.scheduleRetry(stored.messageId, 1)
                Result.success(Unit)
            }
            is SecureSendOutcome.Rejected -> failSecure(stored.messageId, outcome.reason, outcome.failureCode)
        }
    }

    /**
     * Rechazo: estado `FAILED`, sin encolar, sin `Transport.send`.
     *
     * Devuelve `Result.failure` a proposito, para que `KeyMessageCoreImpl` no
     * lo reporte como enviado. El estado del store es la verdad observable y ya
     * queda escrito antes de que este metodo devuelva.
     */
    private fun failSecure(messageId: MessageId, reason: SecureSendReject, failureCode: FailureCode): Result<Unit> {
        // El wire cacheado era de un intento que ya no vale: si el mensaje
        // llegara a salir por el camino legado, ese seria el frame que el
        // receptor veria, y no el de este intento.
        lastWireByMessageId.remove(messageId)
        messageStore.updateState(messageId, MessageState.FAILED, failureCode).getOrThrow()
        stateHandlers.forEach { it(messageId, MessageState.FAILED, failureCode) }
        return Result.failure(SecureSendRejectedException(reason))
    }

    override fun retry(messageId: MessageId): Result<Unit> {
        // Idem `send`: el reintento LEE el registro. `RetryManagerImpl` solo
        // programa el temporizador; no tiene `MessageStore` y no escribe nada.
        val message = messageStore.get(messageId).getOrNull()
            ?: return Result.failure(NoSuchElementException("Message $messageId not found"))

        if (message.state != MessageState.SENT && message.state != MessageState.QUEUED) {
            return Result.success(Unit)
        }

        // Camino cifrado: NUNCA se re-cifra. Se reenvia el MISMO wire.
        val secure = secureOutbound
        if (secure != null) {
            return retrySecure(message, secure)
        }

        val encoded = encoder.encodeMessage(message).getOrThrow()

        return if (transport.isOnline()) {
            val sendResult = transport.send(encoded, message.to)
            if (sendResult.isSuccess) {
                messageStore.updateState(message.messageId, MessageState.SENT, null)
                stateHandlers.forEach { it(message.messageId, MessageState.SENT, null) }
                Result.success(Unit)
            } else {
                Result.failure(Exception("Send failed"))
            }
        } else {
            Result.failure(Exception("Offline"))
        }
    }

    /**
     * Reintento cifrado: el MISMO wire, o un fallo explicito.
     *
     * `MAX_RESEND` es un checkpoint cerrado, y la razon de que esto no cifre de
     * nuevo es la misma: un frame distinto, con otro `N` y otra clave de
     * mensaje, es un mensaje NUEVO. El receptor lo descifraria dos veces y el
     * emisor quemaria dos claves de la cadena por un solo reintento.
     *
     * Si no hay wire cacheado, no hay reintento posible. La alternativa
     * --re-cifrar el sobre con el mismo `messageId`-- parece inocua y no lo es:
     * produce un frame que el receptor no puede distinguir de un mensaje
     * legitimo nuevo y, de paso, avanza el ratchet. Ante la duda el mensaje se
     * queda como esta y se avisa con un fallo, que es informacion; fingir que
     * salio no lo es.
     */
    private fun retrySecure(message: Message, secure: SecureOutbound): Result<Unit> {
        val wire = lastWireByMessageId[message.messageId]
            ?: return Result.failure(
                SecureSendRejectedException(SecureSendReject.NO_SESSION),
            )
        return when (val outcome = secure.retransmit(message, wire)) {
            is SecureSendOutcome.Encrypted -> {
                messageStore.updateState(message.messageId, MessageState.SENT, null)
                stateHandlers.forEach { it(message.messageId, MessageState.SENT, null) }
                Result.success(Unit)
            }
            is SecureSendOutcome.Rejected ->
                failSecure(message.messageId, outcome.reason, outcome.failureCode)
        }
    }

    override fun cancel(messageId: MessageId): Result<Unit> {
        retryManager.cancelRetry(messageId)
        lastWireByMessageId.remove(messageId)
        messageStore.updateState(messageId, MessageState.FAILED, FailureCode.INTERNAL_ERROR)
        stateHandlers.forEach { it(messageId, MessageState.FAILED, FailureCode.INTERNAL_ERROR) }
        return Result.success(Unit)
    }

    fun onRetryExpired(messageId: MessageId, attempt: Int) {
        val nextAttempt = attempt + 1
        if (nextAttempt > retryManager.maxRetries()) {
            messageStore.updateState(messageId, MessageState.EXPIRED, FailureCode.TIMEOUT)
            stateHandlers.forEach { it(messageId, MessageState.EXPIRED, FailureCode.TIMEOUT) }
            return
        }
        val result = retry(messageId)
        if (result.isSuccess) {
            retryManager.scheduleRetry(messageId, nextAttempt)
        } else {
            retryManager.scheduleRetry(messageId, nextAttempt)
        }
    }

    fun onPeerOnline(peerId: IdentityId) {
        // El vaciado de la cola vuelve a pasar por `send`, que ya lee el
        // registro. Por eso un mensaje que se encolo sin estar persistido
        // NO se envia: `send` lo rechaza y el mensaje se queda en la cola en vez
        // de perder el `messageId` que le permitiria reintentarse.
        //
        // Y que vuelva a pasar por `send` es lo que hace que la rama CIFRADA
        // cubra tambien el vaciado de la cola: al vaciar no se decide nada de
        // transporte, se llama al mismo metodo que decide de cifrado.
        val queued = offlineQueue.peekAll().getOrNull().orEmpty()
        queued.filter { it.to == peerId }.forEach { message ->
            val sendResult = send(message)
            if (sendResult.isSuccess) {
                offlineQueue.remove(message.messageId)
            }
        }
    }

    fun registerStateHandler(handler: (MessageId, MessageState, FailureCode?) -> Unit) {
        stateHandlers.add(handler)
    }

    private fun queueOffline(message: Message): Result<Unit> {
        offlineQueue.enqueue(message)
        messageStore.updateState(message.messageId, MessageState.QUEUED, null)
        stateHandlers.forEach { it(message.messageId, MessageState.QUEUED, null) }
        return Result.success(Unit)
    }
}
