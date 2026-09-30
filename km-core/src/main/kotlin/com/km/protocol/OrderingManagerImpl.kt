package com.km.protocol

import com.km.model.Message
import com.km.model.MessageId
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentSkipListSet

class OrderingManagerImpl : OrderingManager {

    private val receivedMessages = ConcurrentSkipListSet<Message>(
        compareBy<Message> { it.timestamp }.thenBy { it.messageId }
    )
    private val gaps = ConcurrentHashMap<MessageId, GapInfo>()
    private val presentationHandlers = mutableListOf<(List<Message>) -> Unit>()

    data class GapInfo(
        val expectedId: MessageId,
        val missingIds: MutableSet<MessageId> = mutableSetOf()
    )

    override fun onMessageDelivered(message: Message) {
        receivedMessages.add(message)
        checkGaps(message)
        presentationHandlers.forEach { it(getOrderedMessages()) }
    }

    override fun getOrderedMessages(): List<Message> {
        return receivedMessages.toList()
    }

    override fun getMissingIds(): List<MessageId> {
        return gaps.values.flatMap { it.missingIds }.toList()
    }

    fun registerPresentationHandler(handler: (List<Message>) -> Unit) {
        presentationHandlers.add(handler)
    }

    private fun checkGaps(message: Message) {
        val prevMessage = receivedMessages.lower(message)
        if (prevMessage != null && prevMessage.timestamp < message.timestamp - 1) {
            gaps[message.messageId] = GapInfo(message.messageId)
        }
    }
}
