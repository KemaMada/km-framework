package com.keymessage.core.x3dh

import com.keymessage.core.crypto.Kdf
import com.keymessage.core.crypto.X25519
import com.keymessage.core.crypto.X25519KeyPair

/**
 * KM-0006 — Constantes del bootstrap X3DH.
 *
 * CONGELADO en docs/rfc/KM-0006-session-bootstrap.md v0.2.
 */
object X3dhSpec {
    /**
     * Dominios de HKDF, uno por posicion.
     *
     * Separar por posicion es lo que impide que una salida de HKDF tenga dos
     * significados criptograficos distintos: DH1..DH4 salen de pares de claves
     * diferentes y tienen propiedades diferentes.
     */
    const val INFO_DH1: String = "KM-0006/X3DH/V1/DH1"
    const val INFO_DH2: String = "KM-0006/X3DH/V1/DH2"
    const val INFO_DH3: String = "KM-0006/X3DH/V1/DH3"
    const val INFO_DH4: String = "KM-0006/X3DH/V1/DH4"
    const val INFO_SK: String = "KM-0006/X3DH/V1/SK"

    /**
     * Dominio de la clave que protege F en el primer SecureFrame.
     *
     * Deliberadamente distinto de `/SK`: `SK` no se usa nunca como clave AEAD.
     */
    const val INFO_PREKEY: String = "KM-0006/X3DH/V1/PREKEY"

    /** Longitudes de todas las claves derivadas. */
    const val KEY_LENGTH: Int = 32

    /**
     * Longitud de la clave de bootstrap F.
     *
     * Invariante 7: F tiene exactamente 32 bytes. F es la mitad del secreto
     * que Alice conoce de antemano; sin ella, dos sesiones contra el mismo SPK
     * derivarian el mismo SK.
     */
    const val F_LENGTH: Int = 32
}

/**
 * Material publico de un dispositivo, tal y como aparece en un
 * `ContactBundle` ya verificado.
 *
 * Solo contiene claves PUBLICAS. Ninguna clase de este archivo acepta ni
 * almacena una clave de firma Ed25519 en un papel de acuerdo.
 */
data class BootstrapPrekeys(
    /** `deviceId` del dispositivo destinatario (derivado de su signingKey). */
    val deviceId: ByteArray,
    /** IK del dispositivo: su `agreementKey` X25519 (§13, device-scoped). */
    val identityAgreementKey: ByteArray,
    /** SPK: clave publica X25519 firmada por el dispositivo. */
    val signedPreKey: ByteArray,
    /** `keyId` del SPK, para consumed-state local. */
    val signedPreKeyId: Long,
    /** OPK opcional: clave publica X25519 de un solo uso. */
    val oneTimePreKey: ByteArray? = null,
    /** `keyId` del OPK. */
    val oneTimePreKeyId: Long? = null,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is BootstrapPrekeys) return false
        return deviceId.contentEquals(other.deviceId) &&
            identityAgreementKey.contentEquals(other.identityAgreementKey) &&
            signedPreKey.contentEquals(other.signedPreKey) &&
            signedPreKeyId == other.signedPreKeyId &&
            (oneTimePreKey?.contentEquals(other.oneTimePreKey) ?: (other.oneTimePreKey == null)) &&
            oneTimePreKeyId == other.oneTimePreKeyId
    }

    override fun hashCode(): Int {
        var r = deviceId.contentHashCode()
        r = 31 * r + identityAgreementKey.contentHashCode()
        r = 31 * r + signedPreKey.contentHashCode()
        r = 31 * r + signedPreKeyId.hashCode()
        r = 31 * r + (oneTimePreKey?.contentHashCode() ?: 0)
        r = 31 * r + (oneTimePreKeyId?.hashCode() ?: 0)
        return r
    }
}

/**
 * Material PRIVADO de un dispositivo, necesario para INICIAR una sesion.
 *
 * @param identityAgreementKey par X25519 de acuerdo del dispositivo emisor
 *   (su `IK`). NUNCA una clave de firma Ed25519.
 */
data class InitiatorKeyMaterial(
    val deviceId: ByteArray,
    val identityAgreementKey: X25519KeyPair,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is InitiatorKeyMaterial) return false
        return deviceId.contentEquals(other.deviceId) &&
            identityAgreementKey.privateKey.contentEquals(other.identityAgreementKey.privateKey)
    }

    override fun hashCode(): Int =
        31 * deviceId.contentHashCode() + identityAgreementKey.privateKey.contentHashCode()
}

/**
 * Material PRIVADO de un dispositivo, necesario para RESPONDER una sesion.
 *
 * Incluye las claves privadas de SPK y OPK, que solo existen en el dispositivo
 * receptor.
 */
data class ResponderKeyMaterial(
    val deviceId: ByteArray,
    val identityAgreementKey: X25519KeyPair,
    val signedPreKey: X25519KeyPair,
    val oneTimePreKey: X25519KeyPair?,
    /** `keyId` del OPK, para registrar su consumo local. */
    val oneTimePreKeyId: Long? = null,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ResponderKeyMaterial) return false
        return deviceId.contentEquals(other.deviceId) &&
            signedPreKey.privateKey.contentEquals(other.signedPreKey.privateKey) &&
            (oneTimePreKey?.privateKey?.contentEquals(other.oneTimePreKey?.privateKey)
                ?: (other.oneTimePreKey == null))
    }

    override fun hashCode(): Int {
        var r = deviceId.contentHashCode()
        r = 31 * r + signedPreKey.privateKey.contentHashCode()
        r = 31 * r + (oneTimePreKey?.privateKey?.contentHashCode() ?: 0)
        return r
    }
}

/**
 * Resultado del bootstrap del emisor: el secreto derivado y lo que debe
 * transmitirse.
 *
 * @param sharedKey `SK`. Secreto de X3DH. NO es una clave AEAD.
 * @param preKeyKey `K_prekey`, derivada de SK con su propio dominio.
 * @param bootstrapValue `F`, 32 bytes aleatorios.
 * @param ephemeralPublic clave publica efimera del emisor (se envia en el
 *   `SecureFrame` de tipo PREKEY como `dhPublicKey`).
 */
class X3dhInitiatorResult internal constructor(
    val sharedKey: ByteArray,
    val preKeyKey: ByteArray,
    val bootstrapValue: ByteArray,
    /**
     * Par efimero generado por X3DH.
     *
     * Se devuelve COMPLETO, incluida la clave privada, porque el llamante es
     * el propio iniciador: necesita `EK_A` para continuar la sesion con el
     * Double Ratchet (misma invariante que en `InitiatorKeyMaterial`).
     * No es una fuga: es la clave del llamante, que ya poseia de facto.
     */
    val ephemeral: com.keymessage.core.crypto.X25519KeyPair,
    val usedOneTimePreKeyId: Long?,
) {
    /** Parte publica, que es la que viaja en el header del frame PREKEY. */
    val ephemeralPublic: ByteArray get() = ephemeral.publicKey

    override fun toString(): String =
        "X3dhInitiatorResult(sharedKey=<${sharedKey.size}B>, preKeyKey=<${preKeyKey.size}B>, " +
            "F=<${bootstrapValue.size}B>, opkId=$usedOneTimePreKeyId)"
}

/**
 * Resultado del bootstrap del receptor.
 *
 * @param sharedKey `SK` recalculado; debe coincidir con el del emisor.
 * @param preKeyKey `K_prekey` recalculada.
 * @param bootstrapValue `F` descifrada.
 */
class X3dhResponderResult internal constructor(
    val sharedKey: ByteArray,
    val preKeyKey: ByteArray,
    val bootstrapValue: ByteArray,
    val usedOneTimePreKeyId: Long?,
) {
    override fun toString(): String =
        "X3dhResponderResult(sharedKey=<${sharedKey.size}B>, F=<${bootstrapValue.size}B>, " +
            "opkId=$usedOneTimePreKeyId)"
}
