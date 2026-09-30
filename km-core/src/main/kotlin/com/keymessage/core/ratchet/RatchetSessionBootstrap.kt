package com.keymessage.core.ratchet

import com.keymessage.core.crypto.AgreementKeyGuard
import com.keymessage.core.crypto.Kdf
import com.keymessage.core.crypto.X25519
import com.keymessage.core.crypto.X25519KeyPair
import com.keymessage.core.sf.BinarySecureFrameCodec
import com.keymessage.core.sf.SecureFrameProtector
import com.keymessage.core.sf.SecureRatchetProtocol

/**
 * KM-0006 §7.4 — Establishment: de `F` al estado inicial del Double Ratchet.
 *
 * SEGUNDO DE LOS TRES ESTADOS (no los mezcla con los otros dos):
 *
 * <pre>
 *   1. Bootstrap X3DH   : ContactBundle -> X3DH -> SK, K_prekey, F
 *   2. Establishment    : F -> RootKey -> estado inicial del ratchet   [AQUI]
 *   3. Mensajeria       : plaintext -> ratchet -> SecureFrame -> transporte
 * </pre>
 *
 * RAZON DE `F`: el `RootKey` se deriva de `F`, no de `SK`. Asi `SK` (secreto de
 * X3DH), `K_prekey` (transporte del bootstrap) y `RootKey` (estado del ratchet)
 * son tres capas separadas con dominios separados.
 *
 * COSTE DE CADENA INICIAL:
 * <pre>
 *   RootKey    = HKDF(0^32, F, "KM-0006/V1/ROOT-KEY", 32)
 *   (RK, CKs)  = KDF_RK(RootKey, DH(EK_A, SPK_B))
 * </pre>
 * El emisor puede calcularlo porque posee `EK_A` y `SPK_B.public`. El
 * receptor calcula exactamente lo mismo al recibir el frame PREKEY, porque
 * X25519 es conmutativo: `DH(SPK_B.priv, EK_A.pub) == DH(EK_A, SPK_B)`.
 */
object RatchetSessionBootstrap {

    /** Dominio de derivacion del RootKey a partir de F. */
    const val INFO_ROOT_KEY: String = "KM-0006/V1/ROOT-KEY"

    const val KEY_LENGTH: Int = 32

    /**
     * Deriva el `RootKey` inicial a partir del bootstrap value `F`.
     *
     * @param bootstrapValue `F` de 32 bytes.
     */
    fun rootKeyFrom(bootstrapValue: ByteArray, kdf: Kdf): ByteArray {
        require(bootstrapValue.size == KEY_LENGTH) {
            "F debe tener $KEY_LENGTH bytes, tiene ${bootstrapValue.size}"
        }
        return kdf.hkdf(
            salt = ByteArray(KEY_LENGTH),
            ikm = bootstrapValue,
            info = INFO_ROOT_KEY.toByteArray(),
            length = KEY_LENGTH,
        )
    }

    /**
     * Prepara el protocolo del EMISOR.
     *
     * @param bootstrapValue `F`.
     * @param ephemeral par X25519 efimero generado por X3DH; pasa a ser el
     *   `DHs` inicial del ratchet (no se genera material redundante).
     * @param remoteSignedPreKey `SPK_B.public`, el `DHr` inicial.
     * @param signedPreKeyPrivate clave privada del receptor, NO disponible
     *   para el emisor: solo se usa para derivar la cadena inicial.
     *
     * FRONTERA DE MATERIAL REMOTO: [remoteSignedPreKey] es `SPK_B.public` de
     * un `ContactBundle` remoto, es decir, lo elige el otro extremo. Se
     * comprueba ANTES de derivar el `RootKey` y antes del acuerdo, para que un
     * rechazo no deje ni una derivacion a medias ni un `RootKey` consumido por
     * una sesion que no llega a construirse. El tipo de excepcion es
     * [com.keymessage.core.crypto.AllZeroSharedSecretException], el mismo que
     * produce la primitiva: no hay canal de `Result` en este motor y no se
     * inventa uno.
     */
    fun initiatorProtocol(
        bootstrapValue: ByteArray,
        ephemeral: X25519KeyPair,
        remoteSignedPreKey: ByteArray,
        signedPreKeyPrivate: ByteArray,
        x25519: X25519,
        kdf: Kdf,
        protector: SecureFrameProtector,
    ): SecureRatchetProtocol {
        // El orden de las dos comprobaciones NO es arbitrario: se mantiene el
        // de antes —primero la longitud de `F`, que es material LOCAL y no
        // estaba en juego— y despues la clave remota. Un llamante que envie
        // las dos cosas invalidas sigue viendo el mismo
        // `IllegalArgumentException` que antes.
        require(bootstrapValue.size == KEY_LENGTH) {
            "F debe tener $KEY_LENGTH bytes, tiene ${bootstrapValue.size}"
        }
        AgreementKeyGuard.requireUsableAgreementKey(remoteSignedPreKey, "SPK_B", KEY_LENGTH)

        val rootKey = rootKeyFrom(bootstrapValue, kdf)


        // (RK, CKs) = KDF_RK(rootKey, DH(EK_A, SPK_B)).
        // El emisor posee EK_A y SPK_B.public, asi que puede calcularlo.
        val dhOut = x25519.agree(ephemeral.privateKey, remoteSignedPreKey)
        val derivation = KdfRk(x25519, kdf).kdfRk(rootKey, dhOut)

        val session = DoubleRatchetSession(
            rootKey = derivation.newRootKey,
            dhSelf = ephemeral,
            dhRemote = remoteSignedPreKey,
            sendChainKey = derivation.newChainKey,
            // La cadena de recepcion se inicializa en el primer receive.
            receiveChainKey = ByteArray(KEY_LENGTH),
            x25519 = x25519,
            kdf = kdf,
        )
        return SecureRatchetProtocol(session, protector, BinarySecureFrameCodec)
    }
}

/**
 * KDF_RK aislado, para que el bootstrap no dependa de la sesion.
 */
class KdfRk(
    private val x25519: X25519,
    private val kdf: Kdf,
) {
    fun kdfRk(rootKey: ByteArray, dhOutput: ByteArray): RootDerivation {
        val newRoot = kdf.hkdf(
            salt = rootKey,
            ikm = dhOutput,
            info = RootRatchetSpec.INFO_ROOT.toByteArray(),
            length = RootRatchetSpec.ROOT_KEY_LENGTH,
        )
        val newChain = kdf.hkdf(
            salt = rootKey,
            ikm = dhOutput,
            info = RootRatchetSpec.INFO_CHAIN.toByteArray(),
            length = RootRatchetSpec.CHAIN_KEY_LENGTH,
        )
        return RootDerivation(newRoot, newChain)
    }
}
