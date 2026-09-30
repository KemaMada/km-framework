package com.example.keymessage.crypto

import android.util.Base64
import java.security.SecureRandom
import java.security.KeyFactory
import java.security.spec.X509EncodedKeySpec
import java.security.spec.PKCS8EncodedKeySpec
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

object Crypto {

    private const val AES_KEY_SIZE = 256
    private const val GCM_IV_LEN = 12
    private const val GCM_TAG_LEN = 128
    private val rng = SecureRandom()

    fun encryptMessage(plaintext: String, recipientPublicKeyB64: String): String {
        val aesKey = generateAesKey()
        val iv = ByteArray(GCM_IV_LEN).also { rng.nextBytes(it) }
        val ciphertext = aesGcmEncrypt(plaintext.toByteArray(Charsets.UTF_8), aesKey, iv)
        val encryptedAesKey = rsaEncrypt(aesKey, recipientPublicKeyB64)
        return "${Base64.encodeToString(encryptedAesKey, Base64.NO_WRAP)}.${Base64.encodeToString(iv, Base64.NO_WRAP)}.${Base64.encodeToString(ciphertext, Base64.NO_WRAP)}"
    }

    fun decryptMessage(payload: String, privateKeyB64: String): String {
        val parts = payload.split(".")
        if (parts.size != 3) throw IllegalArgumentException("Invalid payload")
        val encryptedAesKey = Base64.decode(parts[0], Base64.NO_WRAP)
        val iv = Base64.decode(parts[1], Base64.NO_WRAP)
        val ciphertext = Base64.decode(parts[2], Base64.NO_WRAP)
        val aesKey = rsaDecrypt(encryptedAesKey, privateKeyB64)
        return String(aesGcmDecrypt(ciphertext, aesKey, iv), Charsets.UTF_8)
    }

    private fun generateAesKey(): ByteArray {
        val kg = KeyGenerator.getInstance("AES")
        kg.init(AES_KEY_SIZE, rng)
        return kg.generateKey().encoded
    }

    private fun aesGcmEncrypt(plaintext: ByteArray, key: ByteArray, iv: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(GCM_TAG_LEN, iv))
        return cipher.doFinal(plaintext)
    }

    private fun aesGcmDecrypt(ciphertext: ByteArray, key: ByteArray, iv: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(GCM_TAG_LEN, iv))
        return cipher.doFinal(ciphertext)
    }

    private fun rsaEncrypt(data: ByteArray, publicKeyB64: String): ByteArray {
        val factory = KeyFactory.getInstance("RSA")
        val pubKey = factory.generatePublic(X509EncodedKeySpec(Base64.decode(publicKeyB64, Base64.NO_WRAP)))
        val cipher = Cipher.getInstance("RSA/ECB/OAEPWithSHA-256AndMGF1Padding")
        cipher.init(Cipher.ENCRYPT_MODE, pubKey)
        return cipher.doFinal(data)
    }

    private fun rsaDecrypt(data: ByteArray, privateKeyB64: String): ByteArray {
        val factory = KeyFactory.getInstance("RSA")
        val privKey = factory.generatePrivate(PKCS8EncodedKeySpec(Base64.decode(privateKeyB64, Base64.NO_WRAP)))
        val cipher = Cipher.getInstance("RSA/ECB/OAEPWithSHA-256AndMGF1Padding")
        cipher.init(Cipher.DECRYPT_MODE, privKey)
        return cipher.doFinal(data)
    }
}
