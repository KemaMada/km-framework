package com.keymessage.core.node

import com.keymessage.core.model.FailureCode
import com.keymessage.core.model.Message
import com.keymessage.core.model.StoredMessage

sealed class RelayDecision {
    data class Forwarded(val message: Message) : RelayDecision()
    data class Stored(val stored: StoredMessage) : RelayDecision()
    data object Duplicate : RelayDecision()
    data class Rejected(val code: FailureCode) : RelayDecision()
}