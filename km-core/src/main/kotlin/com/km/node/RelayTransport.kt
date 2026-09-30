package com.km.node

import com.km.codec.Encoder
import com.km.model.Ack
import com.km.model.AckStatus
import com.km.model.IdentityId
import com.km.model.Message
import com.km.model.StoredReceipt
import com.km.model.StoredMessage
import com.km.protocol.Transport

interface RelayTransport : Transport {
    fun setStoredHandler(handler: (StoredReceipt) -> Unit)
    fun setRelayExpiredHandler(handler: (com.km.model.RelayExpiredNotice) -> Unit)
}