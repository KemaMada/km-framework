package com.keymessage.core.x3dh

import com.keymessage.core.crypto.AgreementKeyGuard
import com.keymessage.core.crypto.Kdf
import com.keymessage.core.crypto.X25519

/**
 * Motor X3DH v1 (KM-0006 v0.3).
 *
 * Implementa `docs/rfc/KM-0006-session-bootstrap.md`:
 *
 * <pre>
 *   DH1 = X25519(IK_A.priv, SPK_B.pub)
 *   DH2 = X25519(EK_A.priv,  IK_B.pub)
 *   DH3 = X25519(EK_A.priv,  SPK_B.pub)
 *   DH4 = X25519(EK_A.priv,  OPK_B.pub)      // opcional
 *
 *   Kn       = HKDF(0^32, DHn, "KM-0006/X3DH/V1/DHn", 32)
 *   SK       = HKDF(0^32, K1||K2||K3[||K4], "KM-0006/X3DH/V1/SK", 32)
 *   K_prekey = HKDF(0^32, SK,  "KM-0006/X3DH/V1/PREKEY", 32)
 * </pre>
 *
 * SEPARACION DE CLAVES (normativa, §5.1):
 * Solo entran claves X25519 de acuerdo. Este motor NO acepta claves Ed25519
 * y no existe conversion Ed25519 -> X25519 en su API.
 *
 * REQUISITO DEL RECEPTOR (§7.3):
 * Calcular `DH1` requiere `IK_A.public`, que NO viaja en el `SecureFrame`
 * (header fijo de 44 bytes, solo lleva `EK_A`). Por eso [respond] exige
 * tambien el material publico del emisor, obtenido via ContactExchange.
 *
 * TRANSACCIONALIDAD: ni [initiate] ni [respond] mutan estado. El receptor NO
 * consume ningun OPK; eso ocurre en [ResponderSession.commit].
 *
 * FRONTERA DE MATERIAL REMOTO (§3.4 de [AgreementKeyGuard]): las tres claves
 * publicas que entran por [BootstrapPrekeys] y la efimera que entra por
 * [respond] las elige el otro extremo. Todas pasan por [requireAgreementKey]
 * ANTES de generar material o de cifrar, de modo que un rechazo no deja
 * claves derivadas a medias ni un par efimero gastado en una sesion que no
 * llega a existir.
 */
class X3dh(
    private val x25519: X25519,
    private val kdf: Kdf,
) {
    // ------------------------------------------------------------------
    // Derivaciones
    // ------------------------------------------------------------------

    private fun derive(secret: ByteArray, info: String): ByteArray = kdf.hkdf(
        salt = ByteArray(X3dhSpec.KEY_LENGTH),
        ikm = secret,
        info = info.toByteArray(),
        length = X3dhSpec.KEY_LENGTH,
    )

    /** Un DH de X25519. */
    private fun dh(privateKey: ByteArray, publicKey: ByteArray): ByteArray =
        x25519.agree(privateKey, publicKey)

    /** Deriva `SK` y `K_prekey` desde el material `K1||K2||K3[||K4]`. */
    private fun deriveSharedKeys(material: ByteArray): Pair<ByteArray, ByteArray> {
        val sk = derive(material, X3dhSpec.INFO_SK)
        return sk to derive(sk, X3dhSpec.INFO_PREKEY)
    }

    // ------------------------------------------------------------------
    // Lado emisor
    // ------------------------------------------------------------------

    /**
     * Inicia una sesion: Alice contra el `ContactBundle` verificado de Bob.
     *
     * No muta estado. Genera `EK_A` y usa el `F` provisto por el llamante.
     *
     * @param initiator claves privadas del dispositivo emisor (su `IK`).
     * @param prekeys material publico del dispositivo receptor.
     * @param bootstrapValue `F` de 32 bytes.
     */
    fun initiate(
        initiator: InitiatorKeyMaterial,
        prekeys: BootstrapPrekeys,
        bootstrapValue: ByteArray,
    ): X3dhInitiatorResult {
        require(bootstrapValue.size == X3dhSpec.F_LENGTH) {
            "F debe tener ${X3dhSpec.F_LENGTH} bytes, tiene ${bootstrapValue.size}"
        }
        requireAgreementKey(prekeys.identityAgreementKey, "IK remoto")
        requireAgreementKey(prekeys.signedPreKey, "SPK")
        prekeys.oneTimePreKey?.let { requireAgreementKey(it, "OPK") }

        val ek = x25519.generateKeyPair()

        val k1 = derive(
            dh(initiator.identityAgreementKey.privateKey, prekeys.signedPreKey),
            X3dhSpec.INFO_DH1,
        )
        val k2 = derive(dh(ek.privateKey, prekeys.identityAgreementKey), X3dhSpec.INFO_DH2)
        val k3 = derive(dh(ek.privateKey, prekeys.signedPreKey), X3dhSpec.INFO_DH3)

        val material = if (prekeys.oneTimePreKey != null) {
            val k4 = derive(dh(ek.privateKey, prekeys.oneTimePreKey), X3dhSpec.INFO_DH4)
            k1 + k2 + k3 + k4
        } else {
            k1 + k2 + k3
        }

        val (sk, kPrekey) = deriveSharedKeys(material)
        return X3dhInitiatorResult(
            sharedKey = sk,
            preKeyKey = kPrekey,
            bootstrapValue = bootstrapValue.copyOf(),
            ephemeral = ek,
            usedOneTimePreKeyId = if (prekeys.oneTimePreKey != null) prekeys.oneTimePreKeyId else null,
        )
    }

    // ------------------------------------------------------------------
    // Lado receptor
    // ------------------------------------------------------------------

    /**
     * Prepara la respuesta a un `SecureFrame` de tipo PREKEY.
     *
     * NO consume ningun estado. El OPK permanece disponible hasta que el
     * llamante confirme con [ResponderSession.commit].
     *
     * @param responder claves privadas del dispositivo receptor.
     * @param remotePrekeys material publico del EMISOR (`IK_A`), obtenido
     *   via ContactExchange y verificado. Obligatorio por §7.3.
     * @param ephemeralPublic `EK_A` recibida en el header del frame.
     */
    fun respond(
        responder: ResponderKeyMaterial,
        remotePrekeys: BootstrapPrekeys,
        ephemeralPublic: ByteArray,
    ): X3dhSessionPreparation {
        requireAgreementKey(ephemeralPublic, "clave efimera recibida")
        requireAgreementKey(remotePrekeys.identityAgreementKey, "IK remoto")

        // X25519 es conmutativo: el receptor usa SU clave privada en el papel
        // que el emisor uso con la publica del receptor.
        //
        //   DH1 = DH(IK_A.priv, SPK_B.pub) = DH(SPK_B.priv, IK_A.pub)
        //   DH2 = DH(EK_A.priv, IK_B.pub) = DH(IK_B.priv, EK_A.pub)
        //   DH3 = DH(EK_A.priv, SPK_B.pub) = DH(SPK_B.priv, EK_A.pub)
        //   DH4 = DH(EK_A.priv, OPK_B.pub) = DH(OPK_B.priv, EK_A.pub)
        val k1 = derive(
            dh(responder.signedPreKey.privateKey, remotePrekeys.identityAgreementKey),
            X3dhSpec.INFO_DH1,
        )
        val k2 = derive(
            dh(responder.identityAgreementKey.privateKey, ephemeralPublic),
            X3dhSpec.INFO_DH2,
        )
        val k3 = derive(
            dh(responder.signedPreKey.privateKey, ephemeralPublic),
            X3dhSpec.INFO_DH3,
        )

        val material = if (responder.oneTimePreKey != null) {
            val k4 = derive(
                dh(responder.oneTimePreKey.privateKey, ephemeralPublic),
                X3dhSpec.INFO_DH4,
            )
            k1 + k2 + k3 + k4
        } else {
            k1 + k2 + k3
        }

        val (sk, kPrekey) = deriveSharedKeys(material)
        return X3dhSessionPreparation(
            sharedKey = sk,
            preKeyKey = kPrekey,
            usedOneTimePreKeyId = if (responder.oneTimePreKey != null) {
                responder.oneTimePreKeyId
            } else {
                null
            },
        )
    }

    /**
     * Unico punto por el que entra TODA clave publica de acuerdo remota.
     *
     * Se llama a las tres de [initiate] y a las dos de [respond], y siempre
     * antes de la primera operacion criptografica. Ver
     * [com.keymessage.core.crypto.AgreementKeyGuard] para por que el rechazo
     * va aqui y no desperiodo de `agree`.
     *
     * El mensaje de longitud es el de siempre: la longitud NO es un motivo
     * nuevo, y quien fija el comportamiento de ese rechazo sigue siendo el
     * mismo codigo de antes.
     */
    private fun requireAgreementKey(key: ByteArray, label: String) =
        AgreementKeyGuard.requireUsableAgreementKey(key, label, X3dhSpec.KEY_LENGTH)
}

/**
 * Preparacion de la sesion en el receptor, todavia sin confirmar.
 *
 * El `OPK` NO se ha consumido: esa decision la toma la aplicacion tras
 * descifrar correctamente el frame PREKEY.
 */
class X3dhSessionPreparation internal constructor(
    val sharedKey: ByteArray,
    val preKeyKey: ByteArray,
    val usedOneTimePreKeyId: Long?,
) {
    /**
     * Confirma la sesion: el OPK se marca consumido y no puede reutilizarse.
     */
    fun commit(): ResponderSession = ResponderSession(
        sharedKey = sharedKey,
        preKeyKey = preKeyKey,
        consumedOneTimePreKeyId = usedOneTimePreKeyId,
    )
}

/** Sesion del receptor ya confirmada; su OPK queda consumido. */
class ResponderSession internal constructor(
    val sharedKey: ByteArray,
    val preKeyKey: ByteArray,
    val consumedOneTimePreKeyId: Long?,
)
