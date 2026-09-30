package com.example.keymessage.storage.room.database

import androidx.room.Database
import androidx.room.RoomDatabase
import com.example.keymessage.storage.room.dao.DuplicateDao
import com.example.keymessage.storage.room.dao.MessageDao
import com.example.keymessage.storage.room.dao.OfflineQueueDao
import com.example.keymessage.storage.room.entity.DuplicateEntity
import com.example.keymessage.storage.room.entity.MessageEntity
import com.example.keymessage.storage.room.entity.OfflineMessageEntity

@Database(
    entities = [
        MessageEntity::class,
        OfflineMessageEntity::class,
        DuplicateEntity::class
    ],
    version = 1,
    exportSchema = false
)
abstract class KeyMessageDatabase : RoomDatabase() {
    abstract fun messageDao(): MessageDao
    abstract fun offlineQueueDao(): OfflineQueueDao
    abstract fun duplicateDao(): DuplicateDao
}
