package com.keymessage.core.protocol

import com.keymessage.core.model.Message
import com.keymessage.core.model.MessageId

interface OrderingManager {
    fun onMessageDelivered(message: Message)
    fun getOrderedMessages(): List<Message>
    fun getMissingIds(): List<MessageId>
}
