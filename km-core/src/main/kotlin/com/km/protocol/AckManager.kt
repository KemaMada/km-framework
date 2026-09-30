package com.km.protocol

import com.km.model.Ack
import com.km.model.MessageId

interface AckManager {
    fun onAckReceived(ack: Ack): Result<Unit>
    fun waitForAck(messageId: MessageId): Result<Ack>
}
