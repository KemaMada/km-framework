package com.example.keymessage.storage.room.impl

import com.example.keymessage.storage.room.dao.DuplicateDao
import com.example.keymessage.storage.room.entity.DuplicateEntity
import com.km.model.IdentityId
import com.km.model.MessageId
import com.km.storage.DuplicateStore
import kotlinx.coroutines.runBlocking

/**
 * 5.1 — `com.km.storage.DuplicateStore` sobre Room.
 *
 * ## POR QUE `isDuplicate` NO ES UN `Result`
 *
 * Porque la interfaz no lo declara asi, y porque un `false` cuando la base de
 * datos falla seria un "no es duplicado" dicho con la misma seguridad que un
 * "no es duplicado" de verdad. Se declara: un fallo de lectura de la base se
 * registra y se trata como "no visto", y el nucleo deduplica por su cuenta.
 * El coste de esa decision esta escrito aqui para que nadie lo descubra por un
 * mensaje duplicado en pantalla.
 */
class RoomDuplicateStore(
    private val duplicateDao: DuplicateDao,
    private val onError: (Throwable) -> Unit = {},
) : DuplicateStore {

    override fun isDuplicate(messageId: MessageId, from: IdentityId): Boolean = runBlocking {
        runCatching { duplicateDao.count(messageId.value.toString(), from.value) > 0 }
            .onFailure { onError(it) }
            .getOrDefault(false)
    }

    override fun mark(messageId: MessageId, from: IdentityId) {
        runBlocking {
            runCatching {
                duplicateDao.insert(
                    DuplicateEntity(
                        messageId = messageId.value.toString(),
                        from = from.value,
                        seenAt = System.currentTimeMillis(),
                    ),
                )
            }.onFailure { onError(it) }
        }
    }

    override fun cleanup(beforeTimestamp: Long): Result<Int> = runBlocking {
        runCatching { duplicateDao.cleanup(beforeTimestamp) }.onFailure { onError(it) }
    }
}