package com.example.keymessage.storage.room.dao

import androidx.room.*
import com.example.keymessage.storage.room.entity.DuplicateEntity

@Dao
interface DuplicateDao {
    @Query("SELECT COUNT(*) FROM duplicates WHERE message_id = :messageId AND from_identity = :fromIdentity")
    suspend fun count(messageId: String, fromIdentity: String): Int

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(duplicate: DuplicateEntity)

    @Query("DELETE FROM duplicates WHERE seen_at < :beforeTimestamp")
    suspend fun cleanup(beforeTimestamp: Long): Int
}
