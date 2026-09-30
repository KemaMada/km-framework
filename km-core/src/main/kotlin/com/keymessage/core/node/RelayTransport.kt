package com.keymessage.core.node

import com.keymessage.core.codec.Encoder
import com.keymessage.core.model.Ack
import com.keymessage.core.model.AckStatus
import com.keymessage.core.model.IdentityId
import com.keymessage.core.model.Message
import com.keymessage.core.model.StoredReceipt
import com.keymessage.core.model.StoredMessage
import com.keymessage.core.protocol.Transport

interface RelayTransport : Transport {
    fun setStoredHandler(handler: (StoredReceipt) -> Unit)
    fun setRelayExpiredHandler(handler: (com.keymessage.core.model.RelayExpiredNotice) -> Unit)
}