package com.km.desktop.client

import com.km.api.KeyMessageCore
import com.km.model.IdentityId
import com.km.model.Message
import com.km.model.MessageId
import com.km.model.MessageState

/**
 * Los servicios de MENSAJERIA del escritorio: historial, enviar, reintentar,
 * descartar. Sin terminal y sin protocolo.
 *
 * ## QUE SIGNIFICA CADA ESTADO, Y POR QUE NO SE ADORNA
 *
 * - `SENT` es **OUT**: el socket local acepto los bytes. NO es entrega.
 * - `DELIVERED` exige un `ACK` Ed25519 VERIFICADO del par. Sin `ACK` no se pinta.
 * - `EXPIRED`/`TIMEOUT` es un TOMBSTONE: la fila se conserva y exige reintento o
 *   descarte explicitos. El rele NO guarda mensajes, y no se promete que lo haga.
 *
 * ## LA COLA VIVE EN EL CORE
 *
 * Aqui no hay cola propia: `createMessage`/`sendMessage`/`resend`/`discard` son
 * del nucleo, que ya posee el cifrado, el ratchet y el estado por mensaje.
 * Duplicar la cola seria tener dos verdades sobre que se ha entregado.
 */
class ChatService(
    private val events: ClientEvents,
    /** El nucleo, o `null` si aun no esta arrancado. */
    private val core: () -> KeyMessageCore?,
) {

    /** El historial conocido de [peer]. Vacio si no hay nucleo o no hay mensajes. */
    fun history(peer: IdentityId): List<Message> =
        core()?.getConversation(peer)?.getOrElse { emptyList() } ?: emptyList()

    /**
     * Crea y envia un mensaje de texto.
     *
     * @return el estado REAL que devolvio el nucleo. `SENT` no es entregado.
     */
    fun send(to: IdentityId, text: String): SendOutcome {
        val nucleo = core() ?: return SendOutcome.Failed("SIN_NUCLEO", "no hay nucleo arrancado")
        val creado = nucleo.createMessage(to, text.toByteArray(Charsets.UTF_8))
            .getOrElse { return SendOutcome.Failed("CREATE_FAILED", it.message ?: "no se pudo crear") }
        val resultado = nucleo.sendMessage(creado)
            .getOrElse { return SendOutcome.Failed("SEND_FAILED", it.message ?: "no se pudo enviar") }
        events.emit(ClientEvent.MessageStateChanged(resultado.messageId, resultado.state))
        return SendOutcome.Accepted(resultado.messageId, resultado.state, resultado.failureCode?.name)
    }

    /**
     * Reintenta un tombstone. CONSERVA el `messageId` y el timestamp: es el mismo
     * mensaje, no uno nuevo.
     */
    fun resend(messageId: MessageId): Result<Unit> {
        val nucleo = core() ?: return Result.failure(IllegalStateException("no hay nucleo arrancado"))
        return nucleo.resend(messageId).onFailure {
            events.emit(ClientEvent.Warn("reintento rechazado ${messageId.value}: ${it.message}"))
        }
    }

    /** Descarta un tombstone. Es la UNICA ruta de borrado. */
    fun discard(messageId: MessageId): Result<Unit> {
        val nucleo = core() ?: return Result.failure(IllegalStateException("no hay nucleo arrancado"))
        return nucleo.discard(messageId).onFailure {
            events.emit(ClientEvent.Warn("descarte rechazado ${messageId.value}: ${it.message}"))
        }
    }

    /** El estado actual de [messageId]. */
    fun stateOf(messageId: MessageId): MessageState? =
        core()?.getMessageState(messageId)?.getOrNull()

    /** El resultado de un envio, sin adornos. */
    sealed class SendOutcome {
        /** El nucleo acepto el mensaje. [state] es el estado REAL (SENT no es entrega). */
        data class Accepted(
            val messageId: MessageId,
            val state: MessageState,
            val failureCode: String?,
        ) : SendOutcome() {
            /** La etiqueta honesta que la TUI pinta. */
            val label: String get() = when (state) {
                MessageState.SENT -> "OUT"
                MessageState.DELIVERED -> "ENTREGADO"
                MessageState.EXPIRED -> "EXPIRADO"
                MessageState.FAILED -> "FALLO ${failureCode ?: "sin motivo"}"
                MessageState.CREATED -> "CREADO"
                MessageState.QUEUED -> "EN COLA"
                MessageState.SENDING -> "ENVIANDO"
            }
        }

        /** No salio. [code] es estable. */
        data class Failed(val code: String, val detail: String) : SendOutcome()
    }
}
