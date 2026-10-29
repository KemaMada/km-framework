package com.example.keymessage.data

import com.example.keymessage.model.ChatMessage
import com.example.keymessage.model.MessageStatus
import com.example.keymessage.storage.room.dao.MessageDao
import com.km.api.KeyMessageCore
import com.km.model.IdentityId
import com.km.model.Message
import com.km.model.MessageId
import com.km.messaging.SecureSendRejectedException
import com.km.protocol.SendResult
import com.example.keymessage.core.SendOutcome
import com.example.keymessage.core.rejectedReasonOf
import com.example.keymessage.util.KMLog
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * 5.2 — La conversacion de un par: lo que se LEE y lo que se ENVIA.
 *
 * ## POR QUE NO USA `AppState` CON JSON
 *
 * El `AppState` legacy guardaba los mensajes y los contactos en un
 * `JSONArray` dentro de `SharedPreferences`: dos fuentes de verdad (esa y el
 * almacen de `km-core`), una migracion que nadie hacia y un `messageId` que se
 * generaba en la app en vez de venir del dominio. Aqui hay una sola: la tabla
 * `messages`, que es la que escribe el `MessageStore` del framework.
 *
 * ## POR QUE RECIBE UN PROVIDER Y NO UN NUCLEO (R14)
 *
 * Antes recibia el `KeyMessageCore` de la conexion. `AppContainer` recrea el
 * nucleo en cada reconexion, asi que el repositorio y el `ViewModel` que lo
 * capturaban seguian enviando por un nucleo CERRADO: el mensaje moria por
 * `EXPIRED` y la pantalla no tenia como saberlo. Un provider (`() -> KeyMessageCore?`)
 * resuelve SIEMPRE el nucleo vigente; el repositorio y el VM pueden sobrevivir a
 * un reemplazo sin quedar pegados a un muerto.
 *
 * ## LO QUE ESTA CLASE PROMETE AL USUARIO
 *
 * Que un mensaje `FAILED`/`EXPIRED` se pinte como lo que es, con su motivo, y
 * que solo una accion explicita lo reintente o lo descarte. Y que `SENT` —bytes
 * escritos— no se presente como entrega: eso lo decide [MessageStatus], no el
 * repositorio.
 */
class ConversationRepository(
    private val coreProvider: () -> KeyMessageCore?,
    private val messageDao: MessageDao,
    private val localIdentity: IdentityId,
) {

    /** El nucleo VIGENTE, o `null` si la conexion todavia no existe. */
    private fun coreOrNull(): KeyMessageCore? = coreProvider()

    /** La conversacion observada, ya proyectada a pantalla. */
    fun observeConversation(peerId: IdentityId): Flow<List<ChatMessage>> =
        messageDao.observeConversation(localIdentity.value, peerId.value)
            .map { entities -> entities.map { it.toMessage().toChatMessage() } }

    /** La conversacion, una vez. */
    fun conversation(peerId: IdentityId): Result<List<ChatMessage>> {
        val core = coreOrNull()
            ?: return Result.failure(IllegalStateException("sin nucleo: no hay conversacion que leer"))
        return core.getConversation(peerId).map { list -> list.map { it.toChatMessage() } }
    }

    /**
     * Envia [payload] a [to].
     *
     * @return lo que la pantalla tiene que pintar. Un rechazo por falta de sesion
     *   NO es una excepcion para el usuario: es un `Envio` con `sent=false` y un
     *   motivo. La excepcion se registra en `[SEND]` y se propaga al que quiera
     *   distinguirla por tipo.
     */
    fun send(to: IdentityId, payload: ByteArray): Envio {
        val core = coreOrNull()
            ?: return Envio(
                sent = false,
                motivo = "sin nucleo: mensaje NO enviado (sin conexion)",
                messageId = null,
            )
        val creado = core.createMessage(to, payload).getOrElse { failure ->
            KMLog.event("[SEND]", "no se pudo crear el mensaje: ${failure.message}", KMLog.Level.ERROR)
            return Envio(sent = false, motivo = rejectedReasonOf(failure), messageId = null)
        }

        val resultado: Result<SendResult> = core.sendMessage(creado)
        return resultado.fold(
            onSuccess = { sr ->
                val ui = SendOutcome.of(sr)
                KMLog.messageSent(
                    messageId = creado.messageId.value.toString(),
                    peerId = to.value,
                    resultado = ui.deliveredLabel,
                    failureCode = sr.failureCode?.name,
                )
                Envio(sent = !ui.failed, motivo = ui.rejectionReason, messageId = creado.messageId.value.toString())
            },
            onFailure = { failure ->
                KMLog.messageSent(
                    messageId = creado.messageId.value.toString(),
                    peerId = to.value,
                    resultado = "REJECTED",
                    failureCode = (failure as? SecureSendRejectedException)?.reason?.name,
                )
                Envio(sent = false, motivo = rejectedReasonOf(failure), messageId = creado.messageId.value.toString())
            },
        )
    }

    /**
     * R13 — reintento EXPLICITO de un mensaje terminal.
     *
     * Conserva `messageId` y `timestamp`: el receptor lo deduplica por
     * `(messageId, from)` y re-emite su ACK. No se crea un mensaje nuevo ni se
     * toca ninguna clave de establecimiento.
     */
    fun resend(messageId: String): Envio = accionTerminal(messageId, "RESEND") { core, id ->
        core.resend(id)
    }

    /** R13 — descarte explicito de un mensaje terminal (borra la fila). */
    fun discard(messageId: String): Envio = accionTerminal(messageId, "DISCARD") { core, id ->
        core.discard(id)
    }

    private fun accionTerminal(
        messageId: String,
        etiqueta: String,
        accion: (KeyMessageCore, MessageId) -> Result<Unit>,
    ): Envio {
        val core = coreOrNull()
            ?: return Envio(sent = false, motivo = "sin nucleo: accion NO aplicada", messageId = messageId)
        val id = runCatching { MessageId.fromString(messageId) }.getOrElse { failure ->
            return Envio(sent = false, motivo = "messageId invalido: ${failure.message}", messageId = messageId)
        }
        return accion(core, id).fold(
            onSuccess = {
                KMLog.event("[SEND]", "$etiqueta aplicado a ${KMLog.truncar(messageId, 12)}")
                Envio(sent = false, motivo = null, messageId = messageId)
            },
            onFailure = { failure ->
                KMLog.event(
                    "[SEND]",
                    "$etiqueta rechazado para ${KMLog.truncar(messageId, 12)}: ${failure.message}",
                    KMLog.Level.WARN,
                )
                Envio(sent = false, motivo = rejectedReasonOf(failure), messageId = messageId)
            },
        )
    }

    private fun Message.toChatMessage(): ChatMessage = ChatMessage.from(this, localIdentity.value)
}

/**
 * El resultado de un envio, ya traducido para la UI.
 *
 * [sent] es la unica casilla que la pantalla consulta para poner un tick: no hay
 * forma de que aparezca un tick sin que el mensaje saliera. Ojo: `sent=true`
 * significa "salio", NO "entrego". La unica evidencia de entrega es el estado
 * `DELIVERED` (ACK firmado), que se pinta desde la FILA, no desde este objeto.
 */
data class Envio(
    val sent: Boolean,
    val motivo: String?,
    val messageId: String?,
)
