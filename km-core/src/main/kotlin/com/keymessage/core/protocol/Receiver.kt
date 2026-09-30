package com.keymessage.core.protocol

import com.keymessage.core.model.Message

interface Receiver {
    fun onMessageReceived(message: Message): Result<Unit>
}
