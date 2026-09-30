package com.km.storage

import com.km.model.*

interface MessageStore {
    fun insert(message: Message): Result<Unit>
    fun updateState(messageId: MessageId, state: MessageState, failureCode: FailureCode?): Result<Unit>
    fun get(messageId: MessageId): Result<Message?>
    fun listByConversation(localId: IdentityId, peerId: IdentityId): Result<List<Message>>
    fun delete(messageId: MessageId): Result<Unit>
}
