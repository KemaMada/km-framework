package com.keymessage.core.protocol

import com.keymessage.core.model.Ack
import com.keymessage.core.model.MessageId

interface AckManager {
    fun onAckReceived(ack: Ack): Result<Unit>
    fun waitForAck(messageId: MessageId): Result<Ack>
}
