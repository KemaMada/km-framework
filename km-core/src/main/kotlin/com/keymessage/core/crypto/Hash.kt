package com.keymessage.core.crypto

interface Hash {
    fun sha256(data: ByteArray): ByteArray
}
