package com.example.keymessage.storage.room.dao

import androidx.room.*
import com.example.keymessage.storage.room.entity.MessageEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface MessageDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(message: MessageEntity)

    @Query("UPDATE messages SET state = :state, failure_code = :failureCode WHERE message_id = :messageId")
    suspend fun updateState(messageId: String, state: String, failureCode: String?)

    @Query("SELECT * FROM messages WHERE message_id = :messageId")
    suspend fun get(messageId: String): MessageEntity?

    @Query("SELECT * FROM messages WHERE (from_identity = :id1 AND to_identity = :id2) OR (from_identity = :id2 AND to_identity = :id1) ORDER BY timestamp ASC")
    suspend fun listConversation(id1: String, id2: String): List<MessageEntity>

    @Query("SELECT * FROM messages WHERE (from_identity = :id1 AND to_identity = :id2) OR (from_identity = :id2 AND to_identity = :id1) ORDER BY timestamp ASC")
    fun observeConversation(id1: String, id2: String): Flow<List<MessageEntity>>

    @Query("DELETE FROM messages WHERE message_id = :messageId")
    suspend fun delete(messageId: String)
}
