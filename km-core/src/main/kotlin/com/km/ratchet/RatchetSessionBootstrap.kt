package com.km.ratchet

import com.km.crypto.AgreementKeyGuard
import com.km.crypto.Kdf
import com.km.crypto.X25519
import com.km.crypto.X25519KeyPair
import com.km.frame.BinarySecureFrameCodec
import com.km.frame.SecureFrameProtector
import com.km.frame.SecureRatchetProtocol

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
     * Prepara el protocolo del EMISOR, con la firma historica.
     *
     * @param signedPreKeyPrivate clave privada del receptor, que NO disponible
     *   para el emisor: se conserva en la firma pero NO se usa.
     *
     * FRONTERA DE MATERIAL REMOTO: [remoteSignedPreKey] es `SPK_B.public` de
     * un `ContactBundle` remoto, es decir, lo elige el otro extremo. Se
     * comprueba ANTES de derivar el `RootKey` y antes del acuerdo, para que un
     * rechazo no deje ni una derivacion a medias ni un `RootKey` consumido por
     * una sesion que no llega a construirse. El tipo de excepcion es
     * [com.km.crypto.AllZeroSharedSecretException], el mismo que
     * produce la primitiva: no hay canal de `Result` en este motor y no se
     * inventa uno.
     *
     * [signedPreKeyPrivate] NO aparece en el calculo: el emisor obtiene
     * `DH(EK_A, SPK_B)` de `EK_A` y de `SPK_B.public`, y la clave privada del
     * receptor no le existe. Que el parametro estuviera en la firma obligaba al
     * llamante a inventar una clave ajena —los tests pasaban la verdadera,
     * que un emisor real no tiene—.
     *
     * Se conserva delegando en la sobrecarga de abajo para no romper los
     * llamantes existentes; un consumidor NUEVO debe usar la de abajo, que no
     * pide material que el emisor no tiene.
     */
    fun initiatorProtocol(
        bootstrapValue: ByteArray,
        ephemeral: X25519KeyPair,
        remoteSignedPreKey: ByteArray,
        signedPreKeyPrivate: ByteArray,
        x25519: X25519,
        kdf: Kdf,
        protector: SecureFrameProtector,
    ): SecureRatchetProtocol =
        initiatorProtocol(
            bootstrapValue = bootstrapValue,
            ephemeral = ephemeral,
            remoteSignedPreKey = remoteSignedPreKey,
            x25519 = x25519,
            kdf = kdf,
            protector = protector,
        )

    /**
     * Prepara el protocolo del EMISOR. Esta es la firma que debe usar el
     * codigo NUEVO: no pide material que el emisor no tiene.
     *
     * @param bootstrapValue `F`.
     * @param ephemeral par X25519 efimero generado por X3DH; pasa a ser el
     *   `DHs` inicial del ratchet (no se genera material redundante).
     * @param remoteSignedPreKey `SPK_B.public`, el `DHr` inicial.
     *
     * FRONTERA DE MATERIAL REMOTO: ver la sobrecarga de arriba. El rechazo
     * sigue siendo [com.km.crypto.AllZeroSharedSecretException] y sigue
     * ocurre ANTES de derivar el `RootKey`, en el mismo orden.
     */
    fun initiatorProtocol(
        bootstrapValue: ByteArray,
        ephemeral: X25519KeyPair,
        remoteSignedPreKey: ByteArray,
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

    /**
     * Prepara el protocolo del RECEPTOR: el estado inicial del ratchet en el
     * lado que respondo, derivado del MISMO `F` que uso el emisor.
     *
     * POR QUE ES SIMETRICO Y NO UNA COPIA
     *
     * <pre>
     *   emisor  : RootKey = HKDF(0^32, F, ROOT-KEY, 32)
     *              (RK, CKs) = KDF_RK(RootKey, DH(EK_A, SPK_B.public))
     *   receptor: RootKey = HKDF(0^32, F, ROOT-KEY, 32)     <- el MISMO F
     *              la primera recepcion ejecuta DH(SPK_B.priv, EK_A.pub)
     * </pre>
     *
     * El receptor NO ejecuta `KDF_RK` aqui, y esa asimetria es intentional: al
     * receptor todavia no le ha llegado ningun frame, asi que no puede saber si
     * va a abrir una epoch nueva o si esta continuando una. Dejar el calculo
     * para el primer [DoubleRatchetSession.previewReceive] es lo que hace que
     * `dhRemote` empiece en `null`: si se rellenara aqui con `EK_A`, el ratchet
     * creeria que la epoch ya existe y se saltaria su propio DH ratchet, que
     * es justamente el paso que aporta el secreto de la cadena.
     *
     * [sendChainKey] y [receiveChainKey] arrancan a cero porque no son claves
     * de derivacion sino MARCADORES: las cadenas reales las produce el primer
     * envio y la primera recepcion respectivamente.
     *
     * @param signedPreKey el par SPK del receptor. Es su `DHs` inicial: tiene
     *   que ser EXACTAMENTE el par que el emisor vio como `SPK_B.public`, o
     *   `DH(SPK_B.priv, EK_A.pub)` no coincidiria con `DH(EK_A, SPK_B.public)`.
     * @throws IllegalArgumentException si `F` no tiene [KEY_LENGTH] bytes.
     */
    fun responderProtocol(
        bootstrapValue: ByteArray,
        signedPreKey: X25519KeyPair,
        x25519: X25519,
        kdf: Kdf,
        protector: SecureFrameProtector,
    ): SecureRatchetProtocol {
        require(bootstrapValue.size == KEY_LENGTH) {
            "F debe tener $KEY_LENGTH bytes, tiene ${bootstrapValue.size}"
        }
        val session = DoubleRatchetSession(
            rootKey = rootKeyFrom(bootstrapValue, kdf),
            dhSelf = signedPreKey,
            // `null`, y no `EK_A`: la epoch se abre en la primera recepcion.
            dhRemote = null,
            sendChainKey = ByteArray(KEY_LENGTH),
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
