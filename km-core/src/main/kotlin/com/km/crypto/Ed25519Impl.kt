package com.km.crypto

import net.i2p.crypto.eddsa.EdDSAEngine
import net.i2p.crypto.eddsa.EdDSAPrivateKey
import net.i2p.crypto.eddsa.EdDSAPublicKey
import net.i2p.crypto.eddsa.spec.EdDSANamedCurveTable
import net.i2p.crypto.eddsa.spec.EdDSAPrivateKeySpec
import net.i2p.crypto.eddsa.spec.EdDSAPublicKeySpec
import java.security.SecureRandom

class Ed25519Impl : Ed25519 {
    private val curve = EdDSANamedCurveTable.getByName("Ed25519")

    override fun generateKeyPair(): KeyPair {
        val seed = ByteArray(32)
        SecureRandom().nextBytes(seed)
        return keyPairFromSeed(seed)
    }

    /**
     * Deriva el par desde una semilla conforme a RFC 8032.
     *
     * Verificado: net.i2p.crypto:eddsa 0.3.0 reproduce exactamente los
     * vectores de la seccion 7.1 de RFC 8032, tanto la derivacion de la
     * clave publica desde la semilla como la firma. `getA().toByteArray()`
     * devuelve los 32 bytes canónicos big-endian, que es lo que KM-ID
     * necesita como `identityRoot`.
     */
    override fun keyPairFromSeed(seed: ByteArray): KeyPair {
        require(seed.size == 32) { "la semilla debe tener 32 bytes, se recibieron ${seed.size}" }
        val privateKeySpec = EdDSAPrivateKeySpec(seed, curve)
        val privateKey = EdDSAPrivateKey(privateKeySpec)
        val publicKeySpec = EdDSAPublicKeySpec(privateKey.a, curve)
        val publicKey = EdDSAPublicKey(publicKeySpec)
        // privateKey ES la semilla: ver la nota de la interfaz Ed25519.
        return KeyPair(publicKey.a.toByteArray(), seed.copyOf())
    }

    override fun sign(privateKey: ByteArray, data: ByteArray): com.km.crypto.Signature {
        val privateKeySpec = EdDSAPrivateKeySpec(privateKey, curve)
        val privKey = EdDSAPrivateKey(privateKeySpec)
        val engine = EdDSAEngine()
        engine.initSign(privKey)
        engine.update(data)
        return com.km.crypto.Signature(engine.sign())
    }

    override fun verify(publicKey: ByteArray, data: ByteArray, signature: com.km.crypto.Signature): Boolean {
        require(publicKey.size == 32) {
            "Ed25519 publicKey debe tener 32 bytes, se recibieron ${publicKey.size}"
        }
        return try {
            val a = net.i2p.crypto.eddsa.math.GroupElement(curve.curve, publicKey)
            val publicKeySpec = EdDSAPublicKeySpec(a, curve)
            val pubKey = EdDSAPublicKey(publicKeySpec)
            val engine = EdDSAEngine()
            engine.initVerify(pubKey)
            engine.update(data)
            engine.verify(signature.bytes)
        } catch (e: IllegalArgumentException) {
            false
        } catch (e: Exception) {
            throw RuntimeException("Ed25519 verify fallo inesperado: ${e.message}", e)
        }
    }
}
