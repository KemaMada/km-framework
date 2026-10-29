package com.example.keymessage.model

import com.km.model.Message
import com.km.model.MessageState

/**
 * 5.2 — El mensaje tal como lo PINTA la pantalla.
 *
 * ## POR QUE UN MODELO DE UI Y NO EL DE `km-core` DIRECTO
 *
 * Porque un `@Composable` no debería recibir un `ByteArray` y decidir si lo
 * muestra como texto. El payload se decodifica UNA vez, aqui, y el resultado
 * lleva el estado y el motivo del rechazo ya resueltos: `NOT SENT` con su razon
 * no es lo mismo que `SENT`, y una pantalla con un solo campo `status: String`
 * acabaria pintando los dos igual.
 *
 * El mapeo es UNA FUNCION PURA: eso es lo que permite probarlo sin Compose y
 * sin dispositivo.
 */
data class ChatMessage(
    val id: String,
    val senderId: String,
    val receiverId: String,
    val text: String,
    val timestamp: Long,
    val isMine: Boolean,
    val status: MessageStatus,
    /** Motivo del rechazo, o `null` si no lo hay. Nunca se inventa. */
    val statusDetail: String? = null,
) {
    companion object {
        /**
         * Proyecta un mensaje de `km-core` a la pantalla.
         *
         * @param payloadDecodificador como se saca el texto del payload. Es un
         *   parametro y no un `String(bytes)` fijo porque el formato del payload
         *   es de la APLICACION, no del framework: si manana es CBOR, cambia
         *   aqui y no en cada pantalla.
         */
        fun from(
            message: Message,
            localIdentity: String,
            payloadDecodificador: (ByteArray) -> String = { String(it) },
        ): ChatMessage {
            val status = MessageStatus.from(message.state, message.failureCode?.name)
            return ChatMessage(
                id = message.messageId.value.toString(),
                senderId = message.from.value,
                receiverId = message.to.value,
                text = runCatching { payloadDecodificador(message.payload) }.getOrElse { "<ilegible>" },
                timestamp = message.timestamp,
                isMine = message.from.value == localIdentity,
                status = status,
                // R16: el motivo SOLO se rellena cuando hay algo que decir. Un
                // `SENT` no tiene motivo porque no hay confirmacion, no porque
                // se haya perdido uno por el camino.
                statusDetail = when (status) {
                    MessageStatus.FAILED -> message.failureCode?.name ?: "sin motivo declarado"
                    MessageStatus.EXPIRED -> message.failureCode?.name ?: "sin confirmacion"
                    else -> null
                },
            )
        }
    }
}

/**
 * Los estados de la pantalla, y su relacion con los del framework.
 *
 * ## QUE DICE CADA ETIQUETA, Y POR QUE `SENT` YA NO DICE "ENVIADO"
 *
 * `MessageState.SENT` significa una cosa muy concreta: el socket LOCAL acepto
 * los bytes. El rele y el par no han confirmado nada. Llamarlo "ENVIADO" en
 * pantalla era la mentira que el usuario veia cuando el primer mensaje parecia
 * entregado mientras los siguientes esperaban.
 *
 * La proyeccion es honesta por construccion:
 *
 * - [QUEUED] / [SENDING]: en cola / escribiendo. No son entrega.
 * - [SENT]: **OUT (sin confirmar)**. Solo hay evidencia local.
 * - [DELIVERED]: la UNICA con `confirmed = true`, y solo se alcanza con un
 *   `ACK` firmado que `AckManagerImpl` verifico.
 * - [EXPIRED] / [FAILED]: terminales con `needsAction = true`; su motivo se
 *   pinta y la pantalla ofrece Reintentar / Descartar.
 *
 * Los valores persistidos del framework NO cambian (`SENT` sigue siendo el
 * string en la fila): sin migracion y sin tocar el wire.
 */
enum class MessageStatus(
    val label: String,
    /** Evidencia de ENTREGA (ACK firmado del par). Solo DELIVERED. */
    val confirmed: Boolean,
    /** El usuario tiene una accion explicita que puede tomar. */
    val needsAction: Boolean,
) {
    CREATED("CREAD", confirmed = false, needsAction = false),
    QUEUED("ENCOLADO", confirmed = false, needsAction = false),
    SENDING("ENVIANDO", confirmed = false, needsAction = false),
    SENT("OUT (sin confirmar)", confirmed = false, needsAction = false),
    DELIVERED("ENTREGADO", confirmed = true, needsAction = false),
    FAILED("FALLIDO", confirmed = false, needsAction = true),
    EXPIRED("EXPIRADO", confirmed = false, needsAction = true);

    /**
     * La etiqueta que se pinta, con el motivo cuando existe.
     *
     * Una funcion PURA a proposito: la decision de que texto corresponde a cada
     * estado se prueba en JVM, sin Compose y sin dispositivo.
     */
    fun display(detail: String? = null): String = when (this) {
        EXPIRED -> when {
            detail.isNullOrBlank() || detail == "sin confirmacion" -> "$label: sin confirmacion"
            else -> "$label: sin confirmacion ($detail)"
        }
        FAILED -> if (detail.isNullOrBlank()) label else "$label: $detail"
        else -> label
    }

    companion object {
        /**
         * Proyecta un estado del framework.
         *
         * Un estado que la app no conhece se degrada a [CREATED], que es el
         * estado INICIAL de un `Message` de km-core:_unknown_ en pantalla es
         * exactamente lo que el dominio dice de un mensaje recien creado.
         */
        fun from(state: MessageState, failureCode: String? = null): MessageStatus =
            runCatching { valueOf(state.name) }.getOrDefault(CREATED)
    }
}