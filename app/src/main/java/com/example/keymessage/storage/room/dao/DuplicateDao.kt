package com.example.keymessage.storage.room.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.example.keymessage.storage.room.entity.DuplicateEntity

/**
 * 5.1 — Deduplicacion de entradas.
 *
 * `mark` es IGNORE y no REPLACE a proposito: si el mismo par se marca dos veces,
 * la segunda no debe reponer `seen_at` y alargar la vida de una entrada que el
 * llamante acaba de marcar. Marcar es "he visto esto", y "he visto esto" tiene
 * una hora.
 */
@Dao
interface DuplicateDao {

    @Query("SELECT COUNT(*) FROM duplicates WHERE message_id = :messageId AND from_identity = :fromIdentity")
    suspend fun count(messageId: String, fromIdentity: String): Int

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(duplicate: DuplicateEntity)

    @Query("DELETE FROM duplicates WHERE seen_at < :beforeTimestamp")
    suspend fun cleanup(beforeTimestamp: Long): Int
}