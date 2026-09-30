package com.keymessage.core.codec

import com.keymessage.core.model.RelayErrorNotice
import com.keymessage.core.model.RelayExpiredNotice
import com.keymessage.core.model.StoredReceipt

interface RelayControlCodec {
    fun encodeStored(receipt: StoredReceipt): Result<ByteArray>
    fun decodeStored(data: ByteArray): Result<StoredReceipt>
    fun encodeRelayExpired(notice: RelayExpiredNotice): Result<ByteArray>
    fun decodeRelayExpired(data: ByteArray): Result<RelayExpiredNotice>
    fun encodeError(error: RelayErrorNotice): Result<ByteArray>
    fun decodeError(data: ByteArray): Result<RelayErrorNotice>
}
