package com.example.keymessage.storage.room.database

import androidx.room.Database
import androidx.room.RoomDatabase
import com.example.keymessage.storage.room.dao.DuplicateDao
import com.example.keymessage.storage.room.dao.MessageDao
import com.example.keymessage.storage.room.dao.OfflineQueueDao
import com.example.keymessage.storage.room.entity.DuplicateEntity
import com.example.keymessage.storage.room.entity.MessageEntity
import com.example.keymessage.storage.room.entity.OfflineMessageEntity

/**
 * 5.1 — La base de la app.
 *
 * `version = 1` y `exportSchema = false` siguen como estaban: el esquema lo
 * genera `ksp` en `$projectDir/schemas` por lo que declara `app/build.gradle.kts`,
 * y esas decisiones no son de esta fase.
 */
@Database(
    entities = [
        MessageEntity::class,
        OfflineMessageEntity::class,
        DuplicateEntity::class,
    ],
    version = 1,
    exportSchema = false,
)
abstract class KeyMessageDatabase : RoomDatabase() {
    abstract fun messageDao(): MessageDao
    abstract fun offlineQueueDao(): OfflineQueueDao
    abstract fun duplicateDao(): DuplicateDao
}