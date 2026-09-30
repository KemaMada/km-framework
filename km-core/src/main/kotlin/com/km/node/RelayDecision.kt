package com.km.node

import com.km.model.FailureCode
import com.km.model.Message
import com.km.model.StoredMessage

sealed class RelayDecision {
    data class Forwarded(val message: Message) : RelayDecision()
    data class Stored(val stored: StoredMessage) : RelayDecision()
    data object Duplicate : RelayDecision()
    data class Rejected(val code: FailureCode) : RelayDecision()
}