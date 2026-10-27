package com.km.api

import com.km.model.*
import com.km.protocol.SendResult

interface KeyMessageCore {
    fun start(): Result<Unit>

    /**
     * Desconecta el transporte. NO apaga el nucleo: la cola y el scheduler
     * siguen vivos y un `start()` posterior reconecta.
     */
    fun stop()

    /**
     * Cierra el nucleo: desuscribe, detiene el scheduler y abandona lo que estaba
     * en vuelo como tombstone (`EXPIRED/TIMEOUT`). Idempotente.
     *
     * Es distinto de [stop]: un nucleo cerrado no debe volver a usarse. La app
     * lo cierra ANTES de construir el sucesor, para que no haya dos emisores por
     * identidad (R14).
     */
    fun close()

    fun createMessage(to: IdentityId, payload: ByteArray): Result<Message>
    fun sendMessage(message: Message): Result<SendResult>
    fun getMessageState(messageId: MessageId): Result<MessageState>
    fun getConversation(peerId: IdentityId): Result<List<Message>>

    /** Reintento EXPLICITO de un mensaje terminal. Conserva id y timestamp. */
    fun resend(messageId: MessageId): Result<Unit>

    /** Descarta un mensaje terminal por decision explicita del usuario. */
    fun discard(messageId: MessageId): Result<Unit>

    fun registerAckHandler(handler: (Ack) -> Unit)
    fun registerMessageHandler(handler: (Message) -> Unit)
    fun registerStateChangeHandler(handler: (MessageId, MessageState) -> Unit)
}
