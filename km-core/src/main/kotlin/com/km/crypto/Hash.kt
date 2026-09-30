package com.km.crypto

interface Hash {
    fun sha256(data: ByteArray): ByteArray
}
