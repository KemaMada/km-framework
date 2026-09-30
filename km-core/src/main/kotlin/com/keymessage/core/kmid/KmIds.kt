package com.keymessage.core.kmid

import java.security.MessageDigest

/**
 * Identificadores derivados de clave -- KM-ID-0001 seccion 6.
 *
 *     identityId = SHA-256("KM-ID-IDENTITY" || identityRootPublicKey)
 *     deviceId   = SHA-256("KM-ID-DEVICE"   || deviceSigningPublicKey)
 *     nodeId     = SHA-256("KM-NODE-NODE"  || nodeIdentityPublicKey)
 *
 * INVARIANTE: identidad, dispositivo y nodo son objetos DISTINTOS. Ningun
 * identificador es un alias de otro, y los tres prefijos de dominio son
 * distintos precisamente para que no puedan colisionar.
 *
 * La clave de acuerdo X25519 NO participa en la derivacion de deviceId:
 * deviceId se ancla a la clave de FIRMA, que es la que autoriza al
 * dispositivo.
 *
 * Port directo de km-id-reference/identity.py.
 */
object KmIds {

    /**
     * El dominio se concatena como bytes UTF-8, SIN separador ni prefijo de
     * longitud. El propio prefijo de dominio hace la separacion, y anadir un
     * delimitador produciria identificadores distintos de los de la
     * referencia.
     */
    private fun derive(domain: String, publicKey: ByteArray): ByteArray {
        require(publicKey.size == KmIdConstants.PUBKEY_BYTES) {
            "la clave publica debe tener ${KmIdConstants.PUBKEY_BYTES} bytes, " +
                "se recibieron ${publicKey.size}"
        }
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(domain.toByteArray(Charsets.UTF_8))
        digest.update(publicKey)
        return digest.digest()
    }

    /** Deriva el identityId de una clave de identidad de usuario (Ed25519). */
    fun identityId(identityRootPublicKey: ByteArray): ByteArray =
        derive(KmIdConstants.DS_IDENTITY, identityRootPublicKey)

    /** Deriva el deviceId de una clave de firma de dispositivo (Ed25519). */
    fun deviceId(deviceSigningPublicKey: ByteArray): ByteArray =
        derive(KmIdConstants.DS_DEVICE, deviceSigningPublicKey)

    /** Deriva el nodeId de una clave de identidad de nodo (Ed25519). */
    fun nodeId(nodeIdentityPublicKey: ByteArray): ByteArray =
        derive(KmIdConstants.DS_NODE, nodeIdentityPublicKey)
}
