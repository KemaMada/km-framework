package com.km.crypto.provider

import com.km.crypto.Kdf
import org.bouncycastle.crypto.digests.SHA256Digest
import org.bouncycastle.crypto.macs.HMac
import org.bouncycastle.crypto.params.KeyParameter

/**
 * Provider HKDF-SHA-256 (RFC 5869) basado en Bouncy Castle.
 *
 * Implementa HKDF-Extract y HKDF-Expand sobre HMAC-SHA-256.
 *
 * ESTA CLASE es del package `provider`: es uno de los pocos lugares del
 * proyecto donde se permite importar `org.bouncycastle.*`.
 */
class BcHkdfSha256 : Kdf {

    private val hashLength = 32

    override fun hkdfExtract(salt: ByteArray, ikm: ByteArray): ByteArray {
        // RFC 5869: si el salt no se provee, se usa HashLen ceros.
        val actualSalt = if (salt.isEmpty()) ByteArray(hashLength) else salt
        val hmac = HMac(SHA256Digest())
        hmac.init(KeyParameter(actualSalt))
        hmac.update(ikm, 0, ikm.size)
        return ByteArray(hashLength).also { hmac.doFinal(it, 0) }
    }

    override fun hkdfExpand(prk: ByteArray, info: ByteArray, length: Int): ByteArray {
        require(length > 0) { "length debe ser > 0, es $length" }
        require(length <= 255 * hashLength) {
            "length no puede superar ${255 * hashLength} bytes, es $length"
        }
        val hmac = HMac(SHA256Digest())
        hmac.init(KeyParameter(prk))

        val out = ByteArray(length)
        val t = ByteArray(hashLength)
        var tLength = 0
        var pos = 0
        var counter = 1

        while (pos < length) {
            // T(i) = HMAC(PRK, T(i-1) || info || i)
            if (tLength > 0) hmac.update(t, 0, tLength)
            hmac.update(info, 0, info.size)
            hmac.update(byteArrayOf(counter.toByte()), 0, 1)
            hmac.doFinal(t, 0)
            tLength = hashLength

            val toCopy = minOf(hashLength, length - pos)
            System.arraycopy(t, 0, out, pos, toCopy)
            pos += toCopy
            counter++
        }
        return out
    }
}
