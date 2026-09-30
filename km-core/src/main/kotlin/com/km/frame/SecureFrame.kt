package com.km.frame

import com.km.model.IdentityId

/**
 * KM-0004 — SecureFrame v1: contenedor cifrado del camino caliente.
 *
 * SecureFrame es un protocolo de TRANSPORTE SEGURO de mensajes, no algo
 * especifico de "chat". Cualquier aplicacion P2P (chat, archivos, llamadas,
 * sincronizacion, publicaciones) puede usarlo sobre el DataChannel.
 *
 * WIRE FORMAT v1 (todos los enteros unsigned big-endian):
 * <pre>
 *  offset  size  campo
 *  0       1     version            = 1
 *  1       1     type               (1-based; 0 reservado)
 *  2       2     length             longitud del ciphertext en bytes
 *  4       32    dhPublicKey        clave publica X25519 del ratchet
 *  36      4     previousChainLength  PN
 *  40      4     messageNumber        N
 *  44      N     ciphertext          plaintext cifrado + tag de 16 bytes
 * </pre>
 *
 * El header del ratchet tiene longitud FIJA en v1: la version determina
 * el layout, por lo que no hace falta un campo de longitud de header.
 * ANYAD de longitud variable requeriria subir la version.
 *
 * AAD (datos autenticados) = los primeros 44 bytes, es decir
 * `version || type || length || dhPublicKey || PN || N`.
 * Modificar cualquiera de esos campos invalida la autenticacion AEAD.
 *
 * INVARIANTES:
 * 1. El nonce NO viaja por wire. Se deriva por HKDF de la `messageKey`
 *    (ver [SecureFrameProtector]).
 * 2. Una `messageKey` pertenece a exactamente un SecureFrame.
 * 3. `identityId` NUNCA aparece en un SecureFrame ni se usa como clave.
 * 4. Version desconocida → rechazo total, sin derivar clave ni nonce.
 * 5. `length` acota el frame a 65535 bytes de ciphertext. Cargas mayores
 *    requieren fragmentacion (incremento posterior).
 */
object SecureFrameSpec {
    /** Version actual del wire format. */
    const val VERSION: UByte = 1u

    /** Longitudes fijas del header en v1. */
    const val HEADER_LENGTH: Int = 44
    const val VERSION_OFFSET: Int = 0
    const val TYPE_OFFSET: Int = 1
    const val LENGTH_OFFSET: Int = 2
    const val DH_PUBLIC_KEY_OFFSET: Int = 4
    const val PREVIOUS_CHAIN_LENGTH_OFFSET: Int = 36
    const val MESSAGE_NUMBER_OFFSET: Int = 40
    const val CIPHERTEXT_OFFSET: Int = 44

    /** Longitud de la clave publica X25519 (RFC 7748). */
    const val DH_PUBLIC_KEY_LENGTH: Int = 32

    /** Tamano del tag ChaCha20-Poly1305, incluido en el ciphertext. */
    const val TAG_LENGTH: Int = 16

    /** Longitud del nonce derivado (no se transmite). */
    const val NONCE_LENGTH: Int = 12

    /** Tamano maximo de ciphertext: `length` son 2 bytes unsigned. */
    const val MAX_CIPHERTEXT_LENGTH: Int = 0xFFFF

    /** Tamano minimo de ciphertext: solo el tag, con plaintext vacio. */
    const val MIN_CIPHERTEXT_LENGTH: Int = TAG_LENGTH

    /** Etiquetas de dominio HKDF. Un mismo output nunca tiene dos significados. */
    const val INFO_AEAD_KEY: String = "KM-0004/SECURE-FRAME/V1/AEAD-KEY"
    const val INFO_NONCE: String = "KM-0004/SECURE-FRAME/V1/NONCE"

    /**
     * Verifica que una identidad NO se ha usado como clave criptografica.
     *
     * Invariante 3. Una `identityId` es SHA-256 (32 bytes) y una clave
     * publica X25519 tambien son 32 bytes: confundirlos es un bug real
     * (ocurrio en 3D y 3E). Esta funcion lo hace explicito.
     */
    fun assertNotIdentityAsKey(publicKey: ByteArray, identityId: IdentityId): Boolean {
        val hex = publicKey.joinToString("") { "%02x".format(it) }
        return !hex.equals(identityId.value, ignoreCase = true)
    }
}

/**
 * Tipos de frame KM-0004 v1.
 *
 * Convencion 1-based: el valor 0 se reserva para "ausente" y NO es un
 * tipo valido. Un tipo desconocido se rechaza (no se ignora), porque
 * interpretarlo con una semantica no verificada seria peor que descartarlo.
 */
enum class FrameType(val code: UByte) {
    /** Primer mensaje de una sesion, establece las cadenas del ratchet. */
    PREKEY(1u),

    /** Mensaje normal de la sesion. */
    MESSAGE(2u),
    ;

    companion object {
        /** Resuelve un codigo de tipo, o `null` si es desconocido. */
        fun fromCode(code: UByte): FrameType? = values().firstOrNull { it.code == code }
    }
}

/**
 * Header del ratchet que accompanya a cada SecureFrame.
 *
 * Corresponde a los tres elementos del header del Double Ratchet:
 * clave publica DH, `pn` y `n`.
 *
 * @param dhPublicKey clave publica X25519 del ratchet (32 bytes).
 * @param previousChainLength longitud de la cadena de envio anterior (`PN`).
 * @param messageNumber numero de mensaje dentro de la cadena actual (`N`).
 */
data class RatchetHeader(
    val dhPublicKey: ByteArray,
    val previousChainLength: UInt,
    val messageNumber: UInt,
) {
    init {
        require(dhPublicKey.size == SecureFrameSpec.DH_PUBLIC_KEY_LENGTH) {
            "dhPublicKey debe tener ${SecureFrameSpec.DH_PUBLIC_KEY_LENGTH} bytes, " +
                "tiene ${dhPublicKey.size}"
        }
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is RatchetHeader) return false
        return dhPublicKey.contentEquals(other.dhPublicKey) &&
            previousChainLength == other.previousChainLength &&
            messageNumber == other.messageNumber
    }

    override fun hashCode(): Int {
        var result = dhPublicKey.contentHashCode()
        result = 31 * result + previousChainLength.hashCode()
        result = 31 * result + messageNumber.hashCode()
        return result
    }
}

/**
 * SecureFrame v1 descifrado: estructura, no bytes.
 *
 * El campo `ciphertext` incluye el tag de autenticacion al final.
 */
data class SecureFrame(
    val version: UByte,
    val type: FrameType,
    val ratchetHeader: RatchetHeader,
    val ciphertext: ByteArray,
) {
    init {
        require(ciphertext.size in SecureFrameSpec.MIN_CIPHERTEXT_LENGTH..SecureFrameSpec.MAX_CIPHERTEXT_LENGTH) {
            "ciphertext debe estar entre ${SecureFrameSpec.MIN_CIPHERTEXT_LENGTH} y " +
                "${SecureFrameSpec.MAX_CIPHERTEXT_LENGTH} bytes, tiene ${ciphertext.size}"
        }
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is SecureFrame) return false
        return version == other.version &&
            type == other.type &&
            ratchetHeader == other.ratchetHeader &&
            ciphertext.contentEquals(other.ciphertext)
    }

    override fun hashCode(): Int {
        var result = version.hashCode()
        result = 31 * result + type.hashCode()
        result = 31 * result + ratchetHeader.hashCode()
        result = 31 * result + ciphertext.contentHashCode()
        return result
    }
}
