package com.keymessage.core.api

import com.keymessage.core.model.*
import com.keymessage.core.protocol.SendResult

interface KeyMessageCore {
    fun start(): Result<Unit>
    fun stop()
    fun createMessage(to: IdentityId, payload: ByteArray): Result<Message>
    fun sendMessage(message: Message): Result<SendResult>
    fun getMessageState(messageId: MessageId): Result<MessageState>
    fun getConversation(peerId: IdentityId): Result<List<Message>>
    fun registerAckHandler(handler: (Ack) -> Unit)
    fun registerMessageHandler(handler: (Message) -> Unit)
    fun registerStateChangeHandler(handler: (MessageId, MessageState) -> Unit)
}
