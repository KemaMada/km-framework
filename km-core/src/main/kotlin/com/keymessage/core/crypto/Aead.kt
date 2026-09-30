package com.keymessage.core.crypto

/**
 * AEAD: cifrado autenticado con datos asociados (RFC 8439 ChaCha20-Poly1305).
 *
 * KeyMessage define esta API; la implementacion concreta vive en un
 * provider. El Double Ratchet y `SecureFrame` dependen SOLO de esta interfaz.
 *
 * CONTRATO DE NONCE (invariante):
 * - Esta interfaz NO genera nonces. El llamador los provee.
 * - El llamador es responsable de que un nonce NUNCA se repita bajo la
 *   misma clave AEAD. Reutilizarlo rompe la confidencialidad.
 * - No existe ninguna politica de nonce implicita en esta capa.
 *
 * CONTRATO DE FALLOS:
 * - [decrypt] NUNCA devuelve texto plano parcial cuando la autenticacion
 *   falla: lanza [AeadAuthenticationException]. Un descifrado no
 *   autenticado no es un descifrado.
 */
interface Aead {

    /** Nombre canonico del algoritmo (para registro y vectores). */
    val algorithm: String

    /** Longitud de clave requerida en bytes (32 para ChaCha20-Poly1305). */
    val keyLength: Int

    /** Longitud de nonce requerida en bytes (12 para ChaCha20-Poly1305). */
    val nonceLength: Int

    /** Longitud de la etiqueta de autenticacion en bytes (16). */
    val tagLength: Int

    /**
     * Cifra y autentica.
     *
     * @param key clave simetrica de [keyLength] bytes.
     * @param nonce nonce unico de [nonceLength] bytes.
     * @param plaintext texto plano.
     * @param associatedData datos autenticados pero NO cifrados.
     * @return ciphertext con la etiqueta de autenticacion anadida.
     *
     * @throws IllegalArgumentException si key o nonce no tienen el tamano exacto.
     */
    fun encrypt(
        key: ByteArray,
        nonce: ByteArray,
        plaintext: ByteArray,
        associatedData: ByteArray,
    ): ByteArray

    /**
     * Descifra y verifica la autenticacion.
     *
     * @return texto plano SOLO si la etiqueta verifica correctamente.
     *
     * @throws AeadAuthenticationException si la verificacion falla
     *   (clave, nonce, ciphertext, AAD alterados, o etiqueta truncada).
     * @throws IllegalArgumentException si key o nonce no tienen el tamano exacto.
     */
    fun decrypt(
        key: ByteArray,
        nonce: ByteArray,
        ciphertext: ByteArray,
        associatedData: ByteArray,
    ): ByteArray
}

/**
 * Verificacion de autenticacion AEAD fallida.
 *
 * El texto plano no se entrega en ningun caso: no existe descifrado
 * parcial ni "best effort" para un frame no autenticado.
 */
class AeadAuthenticationException(
    message: String = "fallo la autenticacion AEAD",
) : RuntimeException(message)
