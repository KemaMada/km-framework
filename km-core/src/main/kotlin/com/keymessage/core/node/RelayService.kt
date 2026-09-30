package com.keymessage.core.node

import com.keymessage.core.model.Ack
import com.keymessage.core.model.Message
import com.keymessage.core.model.RelayLimits
import com.keymessage.core.model.StoredMessage
import com.keymessage.core.storage.RelayStore

interface RelayService {
    val relayStore: RelayStore
    val limits: RelayLimits

    fun handleMessage(message: Message, recipientOnline: Boolean): Result<RelayDecision>

    fun handleAck(ack: Ack): Result<Unit>

    fun expire(now: Long): Result<List<StoredMessage>>

    fun registerStoredHandler(handler: (StoredMessage) -> Unit)
    fun registerAckForwardedHandler(handler: (Ack) -> Unit)
}