package com.km.model

import java.util.UUID

@JvmInline
value class MessageId(val value: UUID) : Comparable<MessageId> {
    companion object {
        fun from(uuid: UUID): MessageId = MessageId(uuid)
        fun random(): MessageId = MessageId(UUID.randomUUID())
        fun fromString(s: String): MessageId = MessageId(UUID.fromString(s))
    }

    override fun compareTo(other: MessageId): Int = value.compareTo(other.value)
}
