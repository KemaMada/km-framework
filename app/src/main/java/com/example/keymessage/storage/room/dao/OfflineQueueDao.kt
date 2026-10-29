package com.example.keymessage.storage.room.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.example.keymessage.storage.room.entity.OfflineMessageEntity

/**
 * 5.1 — Cola offline.
 *
 * `dequeue` saca el MAS ANTIGUO de un par (`ORDER BY timestamp ASC LIMIT 1`): la
 * cola de un chat es FIFO y sacar el mas nuevo seria saltarse mensajes sin
 * dejar rastro.
 */
@Dao
interface OfflineQueueDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun enqueue(message: OfflineMessageEntity)

    @Query("SELECT * FROM offline_queue WHERE to_identity = :peerId ORDER BY timestamp ASC, message_id ASC LIMIT 1")
    suspend fun dequeue(peerId: String): OfflineMessageEntity?

    @Query("SELECT * FROM offline_queue ORDER BY timestamp ASC, message_id ASC")
    suspend fun peekAll(): List<OfflineMessageEntity>

    @Query("DELETE FROM offline_queue WHERE message_id = :messageId")
    suspend fun remove(messageId: String)

    @Query("DELETE FROM offline_queue WHERE timestamp < :beforeTimestamp")
    suspend fun expire(beforeTimestamp: Long): Int
}