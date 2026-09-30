package com.keymessage.core.crypto.provider

import com.keymessage.core.crypto.AllZeroSharedSecretException
import com.keymessage.core.crypto.X25519
import com.keymessage.core.crypto.X25519KeyPair
import org.bouncycastle.crypto.agreement.X25519Agreement
import org.bouncycastle.crypto.params.X25519PrivateKeyParameters
import org.bouncycastle.crypto.params.X25519PublicKeyParameters
import java.security.SecureRandom

/**
 * Provider X25519 basado en Bouncy Castle (RFC 7748).
 *
 * ESTA CLASE (y las de este paquete) es el UNICO lugar del proyecto donde
 * se permite importar `org.bouncycastle.*`. El resto de km-core depende
 * de las interfaces de `com.keymessage.core.crypto`.
 *
 * NOTA SOBRE CLAMPING:
 * Bouncy Castle aplica el clamping de RFC 7748 §5 dentro de
 * `X25519PrivateKeyParameters`. Por eso [generateKeyPair] entrega ya un
 * escalar valido y `agree` no debe clampar de nuevo.
 */
class BcX25519(
    private val random: SecureRandom = SecureRandom(),
) : X25519 {

    override fun generateKeyPair(): X25519KeyPair {
        val privateKey = ByteArray(32).also { random.nextBytes(it) }
        val publicKey = publicKey(privateKey)
        return X25519KeyPair(privateKey, publicKey)
    }

    override fun publicKey(privateKey: ByteArray): ByteArray {
        require(privateKey.size == keyLength) {
            "privateKey debe tener $keyLength bytes, tiene ${privateKey.size}"
        }
        val priv = X25519PrivateKeyParameters(privateKey, 0)
        val pub = priv.generatePublicKey()
        return pub.encoded.copyOf()
    }

    override fun agree(privateKey: ByteArray, publicKey: ByteArray): ByteArray {
        require(privateKey.size == keyLength) {
            "privateKey debe tener $keyLength bytes, tiene ${privateKey.size}"
        }
        require(publicKey.size == keyLength) {
            "publicKey debe tener $keyLength bytes, tiene ${publicKey.size}"
        }
        val agreement = X25519Agreement()
        agreement.init(X25519PrivateKeyParameters(privateKey, 0))
        val out = ByteArray(sharedSecretLength)

        // Bouncy Castle detecta internamente los puntos de bajo orden de
        // RFC 7748 §6.1 y lanza IllegalStateException. Se traduce a la
        // excepcion de dominio para que el contrato de [X25519] se cumpla
        // con independencia de la implementacion subyacente.
        try {
            // calculateAgreement devuelve void en BC 1.86; escribe en `out`.
            agreement.calculateAgreement(X25519PublicKeyParameters(publicKey, 0), out, 0)
        } catch (e: IllegalStateException) {
            throw AllZeroSharedSecretException()
        }

        // Segunda linea de defensa: si el secreto es todo cero, la clave
        // publica remota tiene orden pequeno. RFC 7748 §6.1: abortar.
        if (out.all { it == 0.toByte() }) {
            throw AllZeroSharedSecretException()
        }
        return out
    }
}
