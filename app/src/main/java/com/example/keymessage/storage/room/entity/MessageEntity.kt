package com.example.keymessage.storage.room.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey
import com.km.model.FailureCode
import com.km.model.IdentityId
import com.km.model.Message
import com.km.model.MessageId
import com.km.model.MessageState

/**
 * 5.1 — La fila de un mensaje, y el mapa de ida y vuelta al dominio.
 *
 * ## POR QUE LA CONVERSION VIVE AQUI Y NO EN UN `TypeConverter`
 *
 * Porque un `TypeConverter` de Room es un `IdentityId <-> String` invisible: el
 * error de mapear `messageId` a una constante no se ve en ninguna parte, y el
 * unico sintoma es que todos los mensajes comparten clave primaria y uno borra
 * al otro. Con los dos sentidos en funciones NOMBRADAS —[toMessage] y
 * [fromMessage]—, esa conversion es codigo que se lee y que
 * `MessageMappingTest` puede atacar con una constante.
 *
 * ## QUE NO SE PERSISTE
 *
 * Nada del material criptografico: ni claves de sesion, ni el estado del
 * ratchet. El registro de sesiones es del proceso, en memoria, por decision
 * documentada de km-core; lo que sobrevive a un reinicio es el mensaje ya
 * descifrado, y eso es una decision de producto, no un olvido.
 */
@Entity(tableName = "messages")
data class MessageEntity(
    @PrimaryKey
    @ColumnInfo(name = "message_id")
    val messageId: String,

    @ColumnInfo(name = "from_identity")
    val from: String,

    @ColumnInfo(name = "to_identity")
    val to: String,

    val payload: ByteArray,

    val timestamp: Long,

    val state: String,

    @ColumnInfo(name = "failure_code")
    val failureCode: String? = null,
) {
    fun toMessage(): Message = Message(
        messageId = MessageId.fromString(messageId),
        from = IdentityId(from),
        to = IdentityId(to),
        payload = payload,
        timestamp = timestamp,
        state = MessageState.valueOf(state),
        failureCode = failureCode?.let { FailureCode.valueOf(it) },
    )

    companion object {
        fun fromMessage(message: Message): MessageEntity = MessageEntity(
            messageId = message.messageId.value.toString(),
            from = message.from.value,
            to = message.to.value,
            payload = message.payload,
            timestamp = message.timestamp,
            state = message.state.name,
            failureCode = message.failureCode?.name,
        )
    }
}

/**
 * El mensaje en la cola offline.
 *
 * `state` NO se guarda: una fila de la cola esta `QUEUED` por definicion, y
 * persistirlo abriria la puerta a una fila con `state = SENT` que nadie puede
 * explicar.
 */
@Entity(tableName = "offline_queue")
data class OfflineMessageEntity(
    @PrimaryKey
    @ColumnInfo(name = "message_id")
    val messageId: String,

    @ColumnInfo(name = "from_identity")
    val from: String,

    @ColumnInfo(name = "to_identity")
    val to: String,

    val payload: ByteArray,

    val timestamp: Long,
) {
    fun toMessage(): Message = Message(
        messageId = MessageId.fromString(messageId),
        from = IdentityId(from),
        to = IdentityId(to),
        payload = payload,
        timestamp = timestamp,
        state = MessageState.QUEUED,
    )

    companion object {
        fun fromMessage(message: Message): OfflineMessageEntity = OfflineMessageEntity(
            messageId = message.messageId.value.toString(),
            from = message.from.value,
            to = message.to.value,
            payload = message.payload,
            timestamp = message.timestamp,
        )
    }
}

/**
 * Lo ya visto, por `(messageId, from)`.
 *
 * La clave primaria es COMPUESTA y en ese orden porque la deduplicacion es de
 * un par concreto: el mismo `messageId` recibido de dos emisores distintos son
 * dos mensajes, y con clave simple uno se tragaria al otro.
 */
@Entity(
    tableName = "duplicates",
    primaryKeys = ["message_id", "from_identity"],
)
data class DuplicateEntity(
    @ColumnInfo(name = "message_id")
    val messageId: String,

    @ColumnInfo(name = "from_identity")
    val from: String,

    @ColumnInfo(name = "seen_at")
    val seenAt: Long,
)
