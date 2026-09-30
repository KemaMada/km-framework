package com.km.protocol

import com.km.model.Message

interface Receiver {
    fun onMessageReceived(message: Message): Result<Unit>
}
