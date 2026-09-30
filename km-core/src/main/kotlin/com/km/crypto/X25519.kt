package com.km.crypto

/**
 * Acuerdo de claves X25519 (RFC 7748).
 *
 * KeyMessage define esta API; la implementacion concreta vive en un
 * provider. El Double Ratchet depende SOLO de esta interfaz.
 *
 * DISTINCIÓN OBLIGATORIA (KM-ID-0001):
 * X25519 NO es Ed25519. Son primitivas distintas con claves distintas.
 *
 *   Ed25519 → firma      (Ed25519Impl)
 *   X25519  → acuerdo    (esta interfaz)
 *
 * Reutilizar una clave de firma para acuerdo de claves esta prohibido.
 */
interface X25519 {

    /** Longitud de clave privada y publica en bytes (32). */
    val keyLength: Int get() = 32

    /** Longitud del secreto compartido en bytes (32). */
    val sharedSecretLength: Int get() = 32

    /**
     * Genera un par de claves X25519.
     *
     * La clave privada se genera con una fuente criptograficamente segura.
     * El bigint se reduce modulo 2^255-19 y se clarea segun RFC 7748
     * (los bits low 3 y el bit high se ajustan).
     */
    fun generateKeyPair(): X25519KeyPair

    /**
     * Deriva la clave publica a partir de la privada.
     *
     * @throws IllegalArgumentException si la clave privada no tiene [keyLength].
     */
    fun publicKey(privateKey: ByteArray): ByteArray

    /**
     * Calcula el secreto compartido X25519(sk, pk).
     *
     * Simetrico: agree(a, b) == agree(b, a).
     *
     * @throws IllegalArgumentException si alguna clave no tiene [keyLength].
     * @throws AllZeroSharedSecretException si el resultado es todo cero
     *   (indicador de clave publica de orden pequeno; RFC 7748 recomienda abortar).
     */
    fun agree(privateKey: ByteArray, publicKey: ByteArray): ByteArray
}

/**
 * Par de claves X25519.
 *
 * @param privateKey 32 bytes.
 * @param publicKey 32 bytes.
 */
data class X25519KeyPair(
    val privateKey: ByteArray,
    val publicKey: ByteArray,
) {
    init {
        require(privateKey.size == 32) { "privateKey debe tener 32 bytes, tiene ${privateKey.size}" }
        require(publicKey.size == 32) { "publicKey debe tener 32 bytes, tiene ${publicKey.size}" }
    }
}

/**
 * Secreto compartido X25519 con valor low-order (todo ceros).
 *
 * RFC 7748 §6.1: si la salida es todo cero, la clave publica remota
 * tiene orden pequeno y el protocolo DEBE abortar la negociacion.
 *
 * SE LANZA TAMBIEN, Y CON EL MISMO TIPO, POR UNA FRONTERA TEMPRANA:
 * [AgreementKeyGuard] aborta con esta misma excepcion cuando detecta el
 * material de orden pequeno ANTES de cifrar, para que el rechazo no deje
 * material derivado ni sesion consumida a medias. No es un tipo nuevo: el
 * llamante que ya tiene que manejar el abort por secreto cero lo sigue
 * manejando igual, y el que no lo hacia tiene ahora un unico motivo que
 * capturar. El mensaje por defecto es el de la primitiva; el constructor con
 * mensaje lo usa la frontera para decir QUE clave era, que es la unica
 * informacion que el llamante necesita para decidir que hacer.
 */
class AllZeroSharedSecretException(
    message: String = "X25519 produjo un secreto compartido de todo cero: " +
        "clave publica de orden pequeno",
) : RuntimeException(message)

