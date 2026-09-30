package com.km.crypto

/**
 * HKDF-SHA-256 (RFC 5869): derivacion de claves basada en HMAC-SHA-256.
 *
 * KeyMessage define esta API; la implementacion concreta vive en un
 * provider. El Double Ratchet depende SOLO de esta interfaz.
 *
 * HKDF tiene dos pasos:
 *   extract(salt, ikm)    → PRK  (pseudo-random key)
 *   expand(prk, info, L)  → OKM  (output keying material, L bytes)
 */
interface Kdf {

    /** Longitud del PRK producido por extract, en bytes (32 para SHA-256). */
    val prkLength: Int get() = 32

    /**
     * HKDF-Extract: extrae un PRK a partir de un IKM y un salt opcional.
     *
     * @param salt sal. Si esta vacio, se usa una cadena de HashLen ceros.
     * @param ikm input keying material.
     */
    fun hkdfExtract(salt: ByteArray, ikm: ByteArray): ByteArray

    /**
     * HKDF-Expand: expande un PRK en `length` bytes de material.
     *
     * @param prk pseudo-random key (de [hkdfExtract], 32 bytes para SHA-256).
     * @param info contexto de derivacion, ligado al proposito de la clave.
     * @param length numero de bytes a derivar (1..255*HashLen).
     *
     * @throws IllegalArgumentException si length <= 0 o length > 255*32.
     */
    fun hkdfExpand(prk: ByteArray, info: ByteArray, length: Int): ByteArray

    /**
     * HKDF de un solo paso: extract seguido de expand.
     *
     * @param info cadena de dominio; debe distinguir usos distintos de la clave.
     */
    fun hkdf(salt: ByteArray, ikm: ByteArray, info: ByteArray, length: Int): ByteArray =
        hkdfExpand(hkdfExtract(salt, ikm), info, length)
}
