package com.km.protocol

import com.km.model.*
import com.km.crypto.Signature

interface Sender {
    fun send(message: Message): Result<Unit>
    fun retry(messageId: MessageId): Result<Unit>
    fun cancel(messageId: MessageId): Result<Unit>

    /**
     * Reintento EXPLICITO de un mensaje terminal (`EXPIRED`/`FAILED`).
     *
     * Conserva `messageId` y `timestamp` originales, y vuelve a cifrar una vez.
     * Rechaza cualquier otro estado: no es una resurreccion automatica.
     */
    fun resend(messageId: MessageId): Result<Unit>

    /** Borra un mensaje terminal por decision explicita del usuario. */
    fun discard(messageId: MessageId): Result<Unit>
}

data class SendResult(
    val messageId: MessageId,
    val state: MessageState,
    val failureCode: FailureCode? = null
)
