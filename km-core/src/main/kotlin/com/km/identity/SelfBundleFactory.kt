package com.km.identity

import com.km.model.IdentityId

/**
 * El bundle DE ESTE DISPOSITIVO: lo que se publica para que otro nodo nos agregue.
 *
 * ## QUE HACE Y QUE NO
 *
 * Elige QUE claves entran —las que ya tiene guardadas [LocalDeviceKeys]— y deja
 * que el framework haga el resto: [DeviceRoster] construye y firma el roster,
 * [ContactBundle] construye y firma el sobre, [BundlePruner] lo poda y
 * [CarrierCodec] lo comprime. Aqui no se deriva ninguna clave ni se firma nada a
 * mano: la firma la emite la clave de la RAIZ, y este tipo solo la pasa.
 *
 * ## LA FIRMA DE LA RAIZ ES TAMBIEN LA DEL DISPOSITIVO
 *
 * El `deviceId` se deriva de la clave de FIRMA de la raiz ([LocalDeviceKeyFactory]
 * lo hace asi), de modo que la `DeviceEntry` del roster declara como `signingKey`
 * la clave publica de la raiz. Es coherente con el resto del sistema —`deviceId`,
 * `subjectSigningKey` e `identityId` salen del mismo material— y se declara como
 * limitacion: un despliegue multi-dispositivo querria dos niveles.
 *
 * ## CERO OPK PARA EL QR
 *
 * [QR_ONE_TIME_PREKEYS] es 0: cada OPK anade ~125 B y un QR de pantalla de movil
 * no lee bien por encima de ~1500 caracteres. X3DH sigue funcionando con el SPK
 * (`DH1-DH3`); solo se pierde `DH4` en el primer mensaje. Los OPK siguen
 * disponibles para portadores de mas capacidad.
 *
 * ## SE VERIFICA LO QUE SE PUBLICA
 *
 * Antes de devolver el texto se lee por el MISMO camino que el receptor
 * ([CarrierCodec.decode]) con la raiz propia como esperada: no se publica un
 * bundle que el otro lado vaya a rechazar.
 */
object SelfBundleFactory {

    /** Cuantos OPK viajan en el portador de un QR. */
    const val QR_ONE_TIME_PREKEYS: Int = 0

    /** `keyId` del signed prekey que se publica. */
    const val SIGNED_PREKEY_ID: Long = 1L

    /** Etiqueta del dispositivo en el roster. No es identidad. */
    const val DEFAULT_DEVICE_NAME: String = "km"

    /** El portador propio, ya en el texto que se comparte. */
    data class Exported(
        /** El `.km`: bytes deflados leidos como ISO-8859-1. Va al QR y al fichero. */
        val text: String,
        /** La identidad que FIRMA: la que vera el otro nodo. */
        val identityId: IdentityId,
        /** `deviceId` del dispositivo local, en hex. */
        val deviceId: String,
        /** Cuantos OPK viajan. */
        val oneTimePreKeyCount: Int,
        /** Tamano REAL del portador (ISO-8859-1), el que decide si cabe en un QR. */
        val byteLength: Int,
    )

    /**
     * Firma el bundle propio.
     *
     * @param signingPublicKey clave publica de la raiz (Ed25519).
     * @param signingPrivateKey clave privada de la raiz: es la que firma roster y bundle.
     * @param localKeys material del dispositivo; su `deviceId` TIENE que derivar
     *   de [signingPublicKey], o el bundle declararia un sujeto que no es este.
     * @param maxOneTimePreKeys cuantos OPK entran. `0` produce el bundle mas
     *   pequeno que sigue siendo valido.
     * @throws IllegalArgumentException si el material no corresponde a la
     *   identidad o [maxOneTimePreKeys] es negativo.
     * @throws KmIdVerificationException si el bundle propio no verifica.
     */
    fun export(
        signingPublicKey: ByteArray,
        signingPrivateKey: ByteArray,
        localKeys: LocalDeviceKeys,
        maxOneTimePreKeys: Int = QR_ONE_TIME_PREKEYS,
        deviceName: String = DEFAULT_DEVICE_NAME,
    ): Exported {
        require(maxOneTimePreKeys >= 0) {
            "maxOneTimePreKeys no puede ser negativo: $maxOneTimePreKeys"
        }
        require(localKeys.deviceId.contentEquals(KmIds.deviceId(signingPublicKey))) {
            "el deviceId guardado no deriva de la clave de firma de la identidad: " +
                "el material local y la identidad son de dispositivos distintos"
        }

        val rosterUnsigned = DeviceRoster.build(
            identityRootPublicKey = signingPublicKey,
            devices = listOf(
                DeviceRoster.makeDeviceEntry(
                    signingPublicKey = signingPublicKey,
                    agreementPublicKey = localKeys.agreementKey.publicKey,
                    name = deviceName,
                ),
            ),
            sequence = 1L,
        )
        val roster = DeviceRoster.sign(
            BundlePruner.pruneRoster(rosterUnsigned),
            signingPrivateKey,
        )

        val signedPrekeyUnsigned = ContactBundle.buildSignedPrekey(
            deviceSigningPublicKey = signingPublicKey,
            publicKey = localKeys.signedPreKey.publicKey,
            keyId = SIGNED_PREKEY_ID,
            deviceIdBytes = localKeys.deviceId,
        )
        val signedPrekey = ContactBundle.signSignedPrekey(
            BundlePruner.pruneSignedPrekey(signedPrekeyUnsigned),
            signingPrivateKey,
        )

        // `toSortedMap` y no `toList`: dos exportaciones del mismo material tienen
        // que dar el MISMO texto, y el orden de un `Map` de OPK no lo garantiza.
        val oneTimePrekeys = localKeys.oneTimePreKeys.toSortedMap()
            .entries
            .take(maxOneTimePreKeys)
            .map { (keyId, key) ->
                ContactBundle.buildOnetimePrekey(
                    publicKey = key.publicKey,
                    keyId = keyId,
                    deviceIdBytes = localKeys.deviceId,
                )
            }

        val bundleUnsigned = ContactBundle.buildBundle(
            identityRootPublicKey = signingPublicKey,
            signedRoster = roster,
            subjectDeviceId = localKeys.deviceId,
            signedPrekey = signedPrekey,
            kind = KmIdConstants.KIND_CONTACT,
            oneTimePrekeys = oneTimePrekeys,
        )
        val bundle = ContactBundle.signBundle(
            BundlePruner.pruneBundle(bundleUnsigned),
            signingPrivateKey,
        )

        val text = CarrierCodec.encode(Kce.encode(bundle))
        val verificado = CarrierCodec.decode(text, signingPublicKey)

        return Exported(
            text = text,
            identityId = IdentityId(KmIds.identityId(signingPublicKey).toHex()),
            deviceId = localKeys.deviceId.toHex(),
            oneTimePreKeyCount = verificado.oneTimePrekeyCount(),
            byteLength = CarrierCodec.bytes(text),
        )
    }

    /** Como [export], pero devuelve el fallo en el `Result` en vez de lanzar. */
    fun exportResult(
        signingPublicKey: ByteArray,
        signingPrivateKey: ByteArray,
        localKeys: LocalDeviceKeys,
        maxOneTimePreKeys: Int = QR_ONE_TIME_PREKEYS,
        deviceName: String = DEFAULT_DEVICE_NAME,
    ): Result<Exported> = runCatching {
        export(signingPublicKey, signingPrivateKey, localKeys, maxOneTimePreKeys, deviceName)
    }

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
}
