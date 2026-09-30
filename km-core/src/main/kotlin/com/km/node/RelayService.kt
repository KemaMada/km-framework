package com.km.node

import com.km.model.Ack
import com.km.model.Message
import com.km.model.RelayLimits
import com.km.model.StoredMessage
import com.km.storage.RelayStore

interface RelayService {
    val relayStore: RelayStore
    val limits: RelayLimits

    fun handleMessage(message: Message, recipientOnline: Boolean): Result<RelayDecision>

    fun handleAck(ack: Ack): Result<Unit>

    fun expire(now: Long): Result<List<StoredMessage>>

    fun registerStoredHandler(handler: (StoredMessage) -> Unit)
    fun registerAckForwardedHandler(handler: (Ack) -> Unit)
}