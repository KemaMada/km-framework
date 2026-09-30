package com.keymessage.core.crypto

/**
 * Par de claves X25519 cuya mitad publica es SIEMPRE la derivacion de la
 * mitad privada.
 *
 * ## POR QUE EXISTE ESTE TIPO (y no basta con `ByteArray`)
 *
 * El proyecto ya tiene una representacion de par X25519: [X25519KeyPair], que
 * guarda las dos mitades por separado. El problema no es que falte una
 * estructura: es que en esa estructura las dos mitades son **independientes**,
 * y nada obliga a que sean coherentes entre si.
 *
 * Cuando ese par hay que escribirlo, recuperarlo y volver a usarlo, la
 * incoherencia deja de ser teorica. Un estado con `publicKey` distinta de
 * `X25519(sk, privateKey)` no es "un poco raro": produce un secreto DH
 * distinto, y por tanto una clave de cadena distinta, y el receptor nunca
 * podrra descifrar. Es un fallo de seguridad que se manifestaria tarde y de
 * forma opaca: "no descifra nada" en lugar de "el estado guardado estaba mal".
 *
 * Este tipo convierte esa invariante en algo **estructural**:
 *
 *  1. El constructor es PRIVADO. No existe forma de construir un par cuya
 *     publica no sea la derivacion de su privada.
 *  2. Las dos unicas factorias ([from] y [derive]) derivan la publica con
 *     [X25519.publicKey] y, en el caso de [from], la COMPARAN con la que trae
 *     el par de origen.
 *  3. Las dos mitades se devuelven por copia: el estado no se puede alterar
 *     desde fuera sin pasar por las factorias.
 *
 * La fuente de verdad es **el escalar privado**. Es lo unico que hay que
 * proteger, escribir y autenticar (3Q.5.2b): la publica sale sola.
 *
 * ## QUE NO ES
 *
 * No es un formato de persistencia. No sabe de bytes en disco, de versiones ni
 * de nada que no sea la relacion entre las dos mitades de una clave. Quien
 * tenga que escribirla en un almacen decide el formato; esta clase solo
 * garantiza que lo que escriba sea coherente por construccion.
 *
 * ## RELACION CON `X25519KeyPair`
 *
 * Es un envoltorio, no un sustituto: [from] consume un `X25519KeyPair` y
 * [toKeyPair] devuelve uno. El resto de km-core sigue usando `X25519KeyPair`
 * igual que hasta ahora (X3DH, bootstrap, tests). Este tipo solo interviene en
 * el punto donde una clave tiene que sobrevivir a un round-trip.
 */
class DerivedX25519KeyPair private constructor(
    private val privateKey: ByteArray,
    private val publicKey: ByteArray,
) {

    /**
     * Escalar privado, [LENGTH] bytes. Copia defensiva.
     *
     * Es el unico material que hay que proteger: sin el, la publica no se
     * puede recuperar.
     */
    fun privateKeyBytes(): ByteArray = privateKey.copyOf()

    /** Clave publica derivada, [LENGTH] bytes. Copia defensiva. */
    fun publicKeyBytes(): ByteArray = publicKey.copyOf()

    /**
     * Devuelve el par del proyecto, con copias propias de ambas mitades.
     *
     * A partir de aqui la coherencia deja de estar garantizada por el tipo:
     * el llamante recibe dos `ByteArray` que puede modificar. Quien necesite
     * garantizarla de nuevo debe volver a pasar por [from].
     */
    fun toKeyPair(): X25519KeyPair = X25519KeyPair(privateKey.copyOf(), publicKey.copyOf())

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is DerivedX25519KeyPair) return false
        // Contenido, no referencia: `ByteArray` en Kotlin compara por identidad.
        return privateKey.contentEquals(other.privateKey) &&
            publicKey.contentEquals(other.publicKey)
    }

    override fun hashCode(): Int = 31 * privateKey.contentHashCode() + publicKey.contentHashCode()

    /**
     * Solo imprime la mitad publica.
     *
     * Un `toString` que imprimiera el escalar pondria material secreto en
     * logs, excepciones y volcados de depuracion. La publica no es secreta y
     * basta para diagnosticar que dos estados son el mismo.
     */
    override fun toString(): String =
        "DerivedX25519KeyPair(public=" + publicKey.joinToString("") { "%02x".format(it) } + ")"

    companion object {

        /** Longitud de ambas mitades en bytes (RFC 7748). */
        const val LENGTH: Int = 32

        /**
         * Captura un par EXISTENTE y verifica que sea coherente.
         *
         * @param x25519 la MISMA implementacion que produjo el par. Importa:
         *   la derivacion es la de la primitiva, no una convencion de este
         *   paquete. Cambiar de provider cambia el resultado de
         *   [X25519.publicKey] y por tanto la clave que se obtiene.
         * @throws IllegalArgumentException si la publica del par no es la
         *   derivacion de su privada. Se prefiere fallar aqui, al leer, y no
         *   al descifrar: un estado incoherente detectado tarde es
         *   indistinguible de un fallo de red.
         */
        fun from(pair: X25519KeyPair, x25519: X25519): DerivedX25519KeyPair {
            val derived = x25519.publicKey(pair.privateKey)
            require(derived.contentEquals(pair.publicKey)) {
                "par X25519 incoherente: su clave publica no es la derivacion de su clave privada"
            }
            return DerivedX25519KeyPair(pair.privateKey.copyOf(), pair.publicKey.copyOf())
        }

        /**
         * Captura un escalar y deriva su publica.
         *
         * Es la via para material RECUPERADO de un almacen, donde la publica
         * no viene: se recalcula. Recalcular es preferible a leer, porque
         * una publica leida de un sitio que no sea la derivacion exacta del
         * escalar es indistinguible de una clave ajena.
         *
         * @throws IllegalArgumentException si el escalar no tiene [LENGTH]
         *   bytes, o si la primitiva lo rechaza.
         */
        fun derive(privateKey: ByteArray, x25519: X25519): DerivedX25519KeyPair {
            require(privateKey.size == LENGTH) {
                "el escalar privado debe tener $LENGTH bytes, tiene ${privateKey.size}"
            }
            return DerivedX25519KeyPair(privateKey.copyOf(), x25519.publicKey(privateKey))
        }
    }
}
