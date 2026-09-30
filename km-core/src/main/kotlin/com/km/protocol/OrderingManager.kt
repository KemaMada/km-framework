package com.km.protocol

import com.km.model.Message
import com.km.model.MessageId

interface OrderingManager {
    fun onMessageDelivered(message: Message)
    fun getOrderedMessages(): List<Message>
    fun getMissingIds(): List<MessageId>
}
