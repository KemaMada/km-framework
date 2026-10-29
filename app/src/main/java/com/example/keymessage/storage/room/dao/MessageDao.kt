package com.example.keymessage.storage.room.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.example.keymessage.storage.room.entity.MessageEntity
import kotlinx.coroutines.flow.Flow

/**
 * 5.1 — Consultas del almacen de mensajes.
 *
 * `listByConversation` ordena por tiempo ASC: la pantalla de conversacion es una
 * cronologia y el orden de llegada (descendente) obliga a invertirla en la UI
 * para que el mas nuevo quede abajo.
 */
@Dao
interface MessageDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(message: MessageEntity)

    @Query("UPDATE messages SET state = :state, failure_code = :failureCode WHERE message_id = :messageId")
    suspend fun updateState(messageId: String, state: String, failureCode: String?)

    @Query("SELECT * FROM messages WHERE message_id = :messageId")
    suspend fun get(messageId: String): MessageEntity?

    @Query(
        "SELECT * FROM messages WHERE (from_identity = :id1 AND to_identity = :id2) " +
            "OR (from_identity = :id2 AND to_identity = :id1) ORDER BY timestamp ASC, message_id ASC"
    )
    suspend fun listConversation(id1: String, id2: String): List<MessageEntity>

    @Query(
        "SELECT * FROM messages WHERE (from_identity = :id1 AND to_identity = :id2) " +
            "OR (from_identity = :id2 AND to_identity = :id1) ORDER BY timestamp ASC, message_id ASC"
    )
    fun observeConversation(id1: String, id2: String): Flow<List<MessageEntity>>

    /**
     * F2 — El ULTIMO mensaje por par, en UNA consulta (spec R2).
     *
     * ## POR QUE UNA SUBCONSULTA CORRELACIONADA Y NO WINDOW FUNCTIONS
     *
     * `ROW_NUMBER() OVER (...)` no existe en el SQLite de API 28 (llego en 3.25,
     * Android 11 / API 30). La subconsulta `= (SELECT ... ORDER BY ... LIMIT 1)`
     * es SQL estandar y corre en el motor antiguo, asi que la app no se cae en
     * un telefono viejo por una funcion de ventana que ese motor no conoce.
     *
     * ## EL DESEMPATE ES TOTAL Y DETERMINISTA
     *
     * `ORDER BY timestamp DESC, message_id DESC LIMIT 1`: con dos mensajes en el
     * MISMO milisegundo, decide el `message_id` mayor. Sin ese desempate el
     * motor devolveria el que le apeteciera (rowid) y el resumen cambiaria entre
     * dos consultas identicas.
     *
     * ## LA CLAVE DEL PAR ES EL OTRO EXTREMO
     *
     * `CASE WHEN from_identity = :local THEN to_identity ELSE from_identity END`
     * devuelve el peer, sea el mensaje mio o suyo: asi el mapa que sale de aqui
     * se indexa por el par y no por "quien escribio".
     *
     * @param local la identidad LOCAL, para saber que extremo es el par.
     */
    @Query(
        "SELECT * FROM messages AS m WHERE m.message_id = (" +
            "SELECT s.message_id FROM messages AS s " +
            "WHERE (s.from_identity = :local AND s.to_identity = (CASE WHEN m.from_identity = :local " +
            "THEN m.to_identity ELSE m.from_identity END)) " +
            "OR (s.to_identity = :local AND s.from_identity = (CASE WHEN m.from_identity = :local " +
            "THEN m.to_identity ELSE m.from_identity END)) " +
            "ORDER BY s.timestamp DESC, s.message_id DESC LIMIT 1)"
    )
    fun observeUltimos(local: String): Flow<List<MessageEntity>>

    @Query("DELETE FROM messages WHERE message_id = :messageId")
    suspend fun delete(messageId: String)
}