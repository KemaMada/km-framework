package com.keymessage.core.crypto

data class KeyPair(val publicKey: ByteArray, val privateKey: ByteArray)
data class Signature(val bytes: ByteArray)

interface Ed25519 {
    fun generateKeyPair(): KeyPair

    /**
     * Deriva el par de claves de forma DETERMINISTA a partir de una semilla
     * de 32 bytes, conforme a RFC 8032.
     *
     * `privateKey` devuelto ES la semilla, no el escalar expandido. En
     * Ed25519 la clave privada es la semilla y el escalar se deriva como
     * SHA-512(semilla) en cada operacion. Guardar el valor expandido seria
     * incorrecto, porque firmar volveria a aplicar la derivacion y romperia
     * la cadena.
     *
     * Es lo que permite que los vectores de KM-ID-0001 sean reproducibles:
     * la misma semilla produce el mismo par en cualquier implementacion
     * conforme a RFC 8032.
     */
    fun keyPairFromSeed(seed: ByteArray): KeyPair

    /**
     * Firma [data] con [privateKey].
     *
     * ATENCION AL ORDEN DE LOS PARAMETROS.
     *
     * Aqui es `sign(privateKey, data)`. La referencia Python de km-id usa
     * `ed25519.sign(message, private_key)`, o sea, el orden INVERSO. Como
     * ambos parametros son `ByteArray`, el compilador acepta el cruce sin
     * quejarse y el fallo solo aparece en ejecucion, como "seed length is
     * wrong", sin senalar la llamada.
     *
     * Cualquier puerto nuevo debe leer este aviso antes de llamar.
     */
    fun sign(privateKey: ByteArray, data: ByteArray): Signature

    /**
     * Verifica [signature] sobre [data] con [publicKey].
     *
     * Mismo aviso de orden que en [sign]: aqui es
     * `verify(publicKey, data, signature)`, no el `(signature, data,
     * publicKey)` de la referencia.
     */
    fun verify(publicKey: ByteArray, data: ByteArray, signature: Signature): Boolean
}
