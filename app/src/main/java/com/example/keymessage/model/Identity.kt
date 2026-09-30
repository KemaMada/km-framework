package com.example.keymessage.model

import android.util.Base64
import java.security.*

data class Identity(
    val seedPhrase: String,
    val publicKey: String,
    val privateKey: String,
    val publicId: String,
    val displayName: String = ""
) {
    companion object {
        private const val ALGORITHM = "RSA"
        private const val KEY_SIZE = 2048

        fun generate(): Identity {
            val kg = KeyPairGenerator.getInstance(ALGORITHM)
            kg.initialize(KEY_SIZE)
            val kp = kg.generateKeyPair()
            val pubRaw = kp.public.encoded
            val privRaw = kp.private.encoded
            val pubB64 = Base64.encodeToString(pubRaw, Base64.NO_WRAP)
            val privB64 = Base64.encodeToString(privRaw, Base64.NO_WRAP)
            val id = hashToId(pubRaw)
            val seed = generateSeedPhrase()
            return Identity(
                seedPhrase = seed,
                publicKey = pubB64,
                privateKey = privB64,
                publicId = id
            )
        }

        fun hashToId(data: ByteArray): String {
            val md = MessageDigest.getInstance("SHA-256")
            val hash = md.digest(data)
            // Take first 12 bytes → 24 hex chars as display ID
            return hash.take(12).joinToString("") { "%02x".format(it) }
        }

        private fun generateSeedPhrase(): String {
            val words = listOf(
                "abandonar", "gato", "mesa", "silla", "perro", "casa",
                "arbol", "sol", "luna", "agua", "fuego", "tierra",
                "viento", "mar", "cielo", "roca", "flor", "rio",
                "bosque", "nube", "lluvia", "nieve", "piedra", "lago"
            )
            val random = SecureRandom()
            val idx = List(12) { random.nextInt(words.size) }
            return idx.joinToString(" ") { words[it] }
        }
    }
}
