package com.example.keymessage.storage.room.dao

import androidx.room.*
import com.example.keymessage.storage.room.entity.OfflineMessageEntity

@Dao
interface OfflineQueueDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun enqueue(message: OfflineMessageEntity)

    @Query("SELECT * FROM offline_queue WHERE to_identity = :peerId ORDER BY timestamp ASC LIMIT 1")
    suspend fun dequeue(peerId: String): OfflineMessageEntity?

    @Query("SELECT * FROM offline_queue ORDER BY timestamp ASC")
    suspend fun peekAll(): List<OfflineMessageEntity>

    @Query("DELETE FROM offline_queue WHERE message_id = :messageId")
    suspend fun remove(messageId: String)

    @Query("DELETE FROM offline_queue WHERE timestamp < :beforeTimestamp")
    suspend fun expire(beforeTimestamp: Long): Int
}
