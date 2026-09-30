package com.km.codec

import com.km.model.RelayErrorNotice
import com.km.model.RelayExpiredNotice
import com.km.model.StoredReceipt

interface RelayControlCodec {
    fun encodeStored(receipt: StoredReceipt): Result<ByteArray>
    fun decodeStored(data: ByteArray): Result<StoredReceipt>
    fun encodeRelayExpired(notice: RelayExpiredNotice): Result<ByteArray>
    fun decodeRelayExpired(data: ByteArray): Result<RelayExpiredNotice>
    fun encodeError(error: RelayErrorNotice): Result<ByteArray>
    fun decodeError(data: ByteArray): Result<RelayErrorNotice>
}
