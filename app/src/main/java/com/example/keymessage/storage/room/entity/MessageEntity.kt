package com.example.keymessage.storage.room.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey
import com.keymessage.core.model.*

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
    val failureCode: String? = null
) {
    fun toMessage(): Message = Message(
        messageId = MessageId.fromString(messageId),
        from = IdentityId(from),
        to = IdentityId(to),
        payload = payload,
        timestamp = timestamp,
        state = MessageState.valueOf(state),
        failureCode = failureCode?.let { FailureCode.valueOf(it) }
    )

    companion object {
        fun fromMessage(msg: Message): MessageEntity = MessageEntity(
            messageId = msg.messageId.value.toString(),
            from = msg.from.value,
            to = msg.to.value,
            payload = msg.payload,
            timestamp = msg.timestamp,
            state = msg.state.name,
            failureCode = msg.failureCode?.name
        )
    }
}
