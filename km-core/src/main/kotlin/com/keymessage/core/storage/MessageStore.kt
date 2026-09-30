package com.keymessage.core.storage

import com.keymessage.core.model.*

interface MessageStore {
    fun insert(message: Message): Result<Unit>
    fun updateState(messageId: MessageId, state: MessageState, failureCode: FailureCode?): Result<Unit>
    fun get(messageId: MessageId): Result<Message?>
    fun listByConversation(localId: IdentityId, peerId: IdentityId): Result<List<Message>>
    fun delete(messageId: MessageId): Result<Unit>
}
