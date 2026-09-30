package com.km.frame

import com.km.crypto.Aead
import com.km.crypto.AeadAuthenticationException
import com.km.crypto.Kdf

/**
 * SecureFrameProtector: la PARTE CRIPTOGRAFICA de KM-0004.
 *
 * SEPARACION DE RESPONSABILIDADES (frozen en 3O.1):
 * - [SecureFrameCodec] hace SOLO serializacion. No cifra ni descifra.
 * - [SecureFrameProtector] hace SOLO criptografia: deriva clave y nonce,
 *   construye el AAD y aplica el AEAD. No serializa.
 *
 * DERIVACION (todo desde la `messageKey`, con dominios separados):
 * <pre>
 *   aeadKey = HKDF-SHA-256(salt = 0^32, ikm = messageKey,
 *                          info = "KM-0004/SECURE-FRAME/V1/AEAD-KEY", len = 32)
 *   nonce   = HKDF-SHA-256(salt = 0^32, ikm = messageKey,
 *                          info = "KM-0004/SECURE-FRAME/V1/NONCE",     len = 12)
 * </pre>
 *
 * El nonce NO viaja por wire. Como `messageKey` es unica por mensaje
 * (invariante del Double Ratchet), el nonce tambien lo es.
 *
 * El AAD son los 44 bytes del header, exactamente los que escribe el codec.
 * Modificar `version`, `type`, `length`, `DH`, `PN` o `N` invalida el tag.
 */
class SecureFrameProtector(
    private val aead: Aead,
    private val kdf: Kdf,
) {
    /**
     * Deriva la clave AEAD de una messageKey.
     *
     * @throws IllegalArgumentException si la messageKey no tiene 32 bytes.
     */
    fun deriveAeadKey(messageKey: ByteArray): ByteArray {
        require(messageKey.size == MESSAGE_KEY_LENGTH) {
            "messageKey debe tener $MESSAGE_KEY_LENGTH bytes, tiene ${messageKey.size}"
        }
        return kdf.hkdf(
            salt = ByteArray(32),
            ikm = messageKey,
            info = SecureFrameSpec.INFO_AEAD_KEY.toByteArray(),
            length = aead.keyLength,
        )
    }

    /**
     * Deriva el nonce de una messageKey. El nonce nunca se transmite.
     */
    fun deriveNonce(messageKey: ByteArray): ByteArray {
        require(messageKey.size == MESSAGE_KEY_LENGTH) {
            "messageKey debe tener $MESSAGE_KEY_LENGTH bytes, tiene ${messageKey.size}"
        }
        return kdf.hkdf(
            salt = ByteArray(32),
            ikm = messageKey,
            info = SecureFrameSpec.INFO_NONCE.toByteArray(),
            length = SecureFrameSpec.NONCE_LENGTH,
        )
    }

    /**
     * Protege un plaintext: cifra y construye el SecureFrame completo.
     *
     * @param type tipo de frame.
     * @param ratchetHeader header del ratchet (entra en el AAD).
     * @param messageKey clave de UN SOLO mensaje.
     * @param plaintext contenido a cifrar.
     */
    fun protect(
        type: FrameType,
        ratchetHeader: RatchetHeader,
        messageKey: ByteArray,
        plaintext: ByteArray,
    ): SecureFrame {
        val aeadKey = deriveAeadKey(messageKey)
        val nonce = deriveNonce(messageKey)

        // El AAD se construye desde los campos del header y la longitud FINAL
        // del ciphertext (plaintext + tag), que se conoce antes de cifrar.
        // `length` forma parte del AAD, asi que debe ser el definitivo.
        val finalCiphertextLength = plaintext.size + aead.tagLength
        val aad = BinarySecureFrameCodec.authenticatedHeader(
            type, ratchetHeader, finalCiphertextLength,
        )

        val ciphertext = aead.encrypt(aeadKey, nonce, plaintext, aad)
        return SecureFrame(SecureFrameSpec.VERSION, type, ratchetHeader, ciphertext)
    }

    /**
     * Desprotege un SecureFrame y devuelve el plaintext.
     *
     * @param messageKey clave correspondiente al `messageNumber` del frame.
     * @throws AeadAuthenticationException si cualquier campo del header,
     *   el ciphertext o el tag fueron alterados.
     */
    fun unprotect(frame: SecureFrame, messageKey: ByteArray): ByteArray {
        val aeadKey = deriveAeadKey(messageKey)
        val nonce = deriveNonce(messageKey)
        val aad = BinarySecureFrameCodec.authenticatedHeader(frame)
        return aead.decrypt(aeadKey, nonce, frame.ciphertext, aad)
    }

    companion object {
        /** Longitud de la messageKey: igual que la clave AEAD (32 bytes). */
        const val MESSAGE_KEY_LENGTH: Int = 32
    }
}
