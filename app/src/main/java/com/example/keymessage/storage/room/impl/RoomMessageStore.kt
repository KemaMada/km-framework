package com.example.keymessage.storage.room.impl

import com.example.keymessage.storage.room.dao.MessageDao
import com.example.keymessage.storage.room.entity.MessageEntity
import com.km.model.FailureCode
import com.km.model.IdentityId
import com.km.model.Message
import com.km.model.MessageId
import com.km.model.MessageState
import com.km.storage.MessageStore
import kotlinx.coroutines.runBlocking

/**
 * 5.1 — `com.km.storage.MessageStore` sobre Room.
 *
 * ## POR QUE `runBlocking`
 *
 * La interfaz de km-core es SINCRONA y el DAO es `suspend`. El puente tiene que
 * blocking en algun sitio, y el sitio menos malo es aqui, en la ultima capa:
 * las llamadas vienen del camino de envio y de recepcion del nucleo, que ya son
 * sincronas. Poner el bloqueo en la UI seria peor.
 *
 * ## POR QUE `runCatching` Y NO `getOrThrow`
 *
 * Porque un fallo de la base de datos no puede presentedarse como excepcion en
 * el camino del nucleo sin distinguirlo de un rechazo criptografico: el `Result`
 * es la forma que la interfaz ya declara para "no se pudo". Perder un mensaje
 * por un error de disco y que el usuario vea "no hay sesion" seria mentir.
 */
class RoomMessageStore(
    private val messageDao: MessageDao,
) : MessageStore {

    override fun insert(message: Message): Result<Unit> = runBlocking {
        runCatching { messageDao.insert(MessageEntity.fromMessage(message)) }
    }

    override fun updateState(
        messageId: MessageId,
        state: MessageState,
        failureCode: FailureCode?,
    ): Result<Unit> = runBlocking {
        runCatching { messageDao.updateState(messageId.value.toString(), state.name, failureCode?.name) }
    }

    override fun get(messageId: MessageId): Result<Message?> = runBlocking {
        runCatching { messageDao.get(messageId.value.toString())?.toMessage() }
    }

    override fun listByConversation(localId: IdentityId, peerId: IdentityId): Result<List<Message>> =
        runBlocking {
            runCatching { messageDao.listConversation(localId.value, peerId.value).map { it.toMessage() } }
        }

    override fun delete(messageId: MessageId): Result<Unit> = runBlocking {
        runCatching { messageDao.delete(messageId.value.toString()) }
    }
}