package com.keymessage.core.crypto.provider

import com.keymessage.core.crypto.Aead
import com.keymessage.core.crypto.AeadAuthenticationException
import org.bouncycastle.crypto.InvalidCipherTextException
import org.bouncycastle.crypto.modes.ChaCha20Poly1305
import org.bouncycastle.crypto.params.AEADParameters
import org.bouncycastle.crypto.params.KeyParameter

/**
 * Provider ChaCha20-Poly1305 (RFC 8439) basado en Bouncy Castle.
 *
 * Parametros congelados para SecureFrame v1:
 *   clave   32 bytes
 *   nonce   12 bytes
 *   tag     16 bytes
 *   AAD     soportado
 *
 * ESTA CLASE es del package `provider`: uno de los pocos lugares del
 * proyecto donde se permite importar `org.bouncycastle.*`.
 *
 * NOTA SOBRE EL NONCE:
 * Esta clase NO genera nonces y NO aplica ninguna politica de reutilizacion.
 * El llamador entrega el nonce y es responsable de no repetirlo bajo la
 * misma clave (contrato de [Aead]).
 *
 * NOTA SOBRE EL USO DE LA API DE BOUNCY CASTLE:
 * El cifrador AEAD entrega la salida en bloques: `processBytes` puede
 * escribir menos bytes de los entregados y `doFinal` vuelca el resto
 * (mas el tag al cifrar, o la verificacion del tag al descifrar). Por eso
 * el buffer se dimensiona con `getOutputSize` y se acumulan los dos
 * retornos, en lugar de asumir que `processBytes` lo escribe todo.
 */
class BcChaCha20Poly1305 : Aead {

    override val algorithm: String = "ChaCha20-Poly1305"
    override val keyLength: Int = 32
    override val nonceLength: Int = 12
    override val tagLength: Int = 16

    override fun encrypt(
        key: ByteArray,
        nonce: ByteArray,
        plaintext: ByteArray,
        associatedData: ByteArray,
    ): ByteArray {
        validateKey(key)
        validateNonce(nonce)

        val engine = newEngine(key, nonce, associatedData, forEncryption = true)
        val out = ByteArray(engine.getOutputSize(plaintext.size))
        var len = engine.processBytes(plaintext, 0, plaintext.size, out, 0)
        len += engine.doFinal(out, len)
        return out.copyOf(len)
    }

    override fun decrypt(
        key: ByteArray,
        nonce: ByteArray,
        ciphertext: ByteArray,
        associatedData: ByteArray,
    ): ByteArray {
        validateKey(key)
        validateNonce(nonce)
        require(ciphertext.size >= tagLength) {
            "ciphertext debe incluir el tag ($tagLength bytes), tiene ${ciphertext.size}"
        }

        val engine = newEngine(key, nonce, associatedData, forEncryption = false)
        // El buffer se dimensiona con el tamano del CIPHERTEXT (no del texto
        // plano) porque doFinal necesita escribir el tag para verificarlo.
        val out = ByteArray(engine.getOutputSize(ciphertext.size))
        val total = try {
            var len = engine.processBytes(ciphertext, 0, ciphertext.size, out, 0)
            len += engine.doFinal(out, len)
            len
        } catch (e: InvalidCipherTextException) {
            // El texto plano NUNCA se entrega si la autenticacion falla.
            throw AeadAuthenticationException(
                "fallo la autenticacion ChaCha20-Poly1305: ${e.message}"
            )
        } catch (e: org.bouncycastle.crypto.RuntimeCryptoException) {
            // Un ciphertext truncado (tag incompleto) hace que Bouncy Castle
            // lance DataLengthException. Para el llamador es un fallo de
            // integridad, no un error de programacion: se traduce al mismo
            // fallo de autenticacion.
            throw AeadAuthenticationException(
                "ciphertext invalido o truncado en ChaCha20-Poly1305: ${e.message}"
            )
        }
        // Al descifrar, doFinal verifica el tag y NO lo cuenta en el
        // retorno: `total` es ya la longitud del texto plano.
        return out.copyOf(total)
    }

    /**
     * Bouncy Castle toma el nonce de 12 bytes directamente y el contador
     * inicial es 0, que es exactamente RFC 8439 §2.4.
     */
    private fun newEngine(
        key: ByteArray,
        nonce: ByteArray,
        associatedData: ByteArray,
        forEncryption: Boolean,
    ): ChaCha20Poly1305 {
        val params = AEADParameters(
            KeyParameter(key),
            tagLength * 8,
            nonce,
            associatedData,
        )
        val engine = ChaCha20Poly1305()
        engine.init(forEncryption, params)
        return engine
    }

    private fun validateKey(key: ByteArray) {
        require(key.size == keyLength) { "key debe tener $keyLength bytes, tiene ${key.size}" }
    }

    private fun validateNonce(nonce: ByteArray) {
        require(nonce.size == nonceLength) { "nonce debe tener $nonceLength bytes, tiene ${nonce.size}" }
    }
}
