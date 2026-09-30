package com.example.keymessage.storage.room.entity

import androidx.room.ColumnInfo
import androidx.room.Entity

@Entity(
    tableName = "duplicates",
    primaryKeys = ["message_id", "from_identity"]
)
data class DuplicateEntity(
    @ColumnInfo(name = "message_id")
    val messageId: String,

    @ColumnInfo(name = "from_identity")
    val from: String,

    @ColumnInfo(name = "seen_at")
    val seenAt: Long
)
