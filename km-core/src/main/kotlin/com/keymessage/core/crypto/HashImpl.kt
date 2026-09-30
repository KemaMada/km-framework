package com.keymessage.core.crypto

import java.security.MessageDigest

class HashImpl : Hash {
    override fun sha256(data: ByteArray): ByteArray {
        val digest = MessageDigest.getInstance("SHA-256")
        return digest.digest(data)
    }
}
