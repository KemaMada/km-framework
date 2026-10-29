package com.example.keymessage.contact

import com.example.keymessage.identity.IdentityMaterial
import com.example.keymessage.identity.LocalKeyMaterial
import com.example.keymessage.util.KMLog
import com.km.identity.SelfBundleFactory
import com.km.model.IdentityId

/**
 * El `.km` DE ESTE DISPOSITIVO: delega en [SelfBundleFactory] y conserva la API.
 *
 * ## QUE SE MUDO AL FRAMEWORK
 *
 * La construccion del roster firmado, del signed prekey, del bundle y de su
 * portador comprimido. Estaba aqui y el escritorio habria tenido que copiarla:
 * dos fabricas producen dos documentos para el mismo material. km-core la tiene
 * desde G1 y esta app solo elige QUE claves entran.
 *
 * ## LO QUE ESTA CLASE CONSERVA
 *
 * - [Exported] con los mismos campos que mira la UI.
 * - [QR_ONE_TIME_PREKEYS] = 0, [SIGNED_PREKEY_ID] y [DEFAULT_DEVICE_NAME].
 * - [ExportFailure] con sus dos formas, para no cambiar el diagnostico.
 *
 * ## LA FIRMA DE LA RAIZ ES TAMBIEN LA DEL DISPOSITIVO
 *
 * `deviceId` se deriva de la clave de FIRMA de la raiz, de modo que la
 * `DeviceEntry` del roster declara como `signingKey` la clave publica de la
 * raiz. Es coherente con el resto del sistema y se declara como limitacion: un
 * despliegue multi-dispositivo querria dos niveles.
 */
object SelfBundleExporter {

    /** Cero OPK en el QR: cada uno son ~125 B que un movil no lee con fiabilidad. */
    const val QR_ONE_TIME_PREKEYS: Int = SelfBundleFactory.QR_ONE_TIME_PREKEYS

    /** `keyId` del `signedPrekey` que publica la app. */
    const val SIGNED_PREKEY_ID: Long = SelfBundleFactory.SIGNED_PREKEY_ID

    /** Nombre del dispositivo en el roster. No es identidad: es una etiqueta. */
    const val DEFAULT_DEVICE_NAME: String = SelfBundleFactory.DEFAULT_DEVICE_NAME

    /** El portador propio, en el vocabulario de la app. */
    data class Exported(
        val text: String,
        val identityId: IdentityId,
        val deviceId: String,
        val oneTimePreKeyCount: Int,
        val byteLength: Int,
    )

    /**
     * Firma el bundle de [identity] para el dispositivo de [localKeys].
     *
     * @throws IllegalArgumentException si el `deviceId` guardado no deriva de la
     *   clave de firma de [identity], o si [maxOneTimePreKeys] es negativo.
     * @throws com.km.identity.KmIdVerificationException si el bundle propio no
     *   verifica. No se degrada: mejor no publicar que publicar algo que el otro
     *   nodo va a rechazar.
     */
    fun export(
        identity: IdentityMaterial,
        localKeys: LocalKeyMaterial,
        maxOneTimePreKeys: Int = QR_ONE_TIME_PREKEYS,
        deviceName: String = DEFAULT_DEVICE_NAME,
    ): Exported {
        val exportado = SelfBundleFactory.export(
            signingPublicKey = identity.keyPair.publicKey,
            signingPrivateKey = identity.keyPair.privateKey,
            localKeys = localKeys,
            maxOneTimePreKeys = maxOneTimePreKeys,
            deviceName = deviceName,
        )
        // La linea que la pantalla usa para comprobar que el numero que ensena
        // es el MISMO que el portador: si el log y la etiqueta discrepan, la
        // cifra que ve el usuario no mide lo que cree.
        KMLog.event(
            "[CONTACT]",
            "bundle propio emitido bytes=${exportado.byteLength} opk=${exportado.oneTimePreKeyCount}",
        )
        return Exported(
            text = exportado.text,
            identityId = exportado.identityId,
            deviceId = exportado.deviceId,
            oneTimePreKeyCount = exportado.oneTimePreKeyCount,
            byteLength = exportado.byteLength,
        )
    }

    /**
     * Excepciones del emisor, en las DOS formas que la UI necesita distinguir.
     *
     * Un rechazo con codigo (`IDENTITY_MISMATCH`, `SUBJECT_REVOKED`, ...) se
     * muestra; un material local incoherente es un bug de la app y se dice como
     * tal.
     */
    sealed class ExportFailure(message: String, cause: Throwable? = null) : Exception(message, cause) {
        /** El material local NO encaja con la identidad. Es un bug de la app. */
        class MaterialIncoherente(message: String) : ExportFailure(message)

        /** El bundle propio no verifica. No se publica. */
        class Rechazado(message: String, cause: Throwable?) : ExportFailure(message, cause)
    }

    /** Como [export], pero devuelve el fallo en vez de lanzar. */
    fun exportResult(
        identity: IdentityMaterial,
        localKeys: LocalKeyMaterial,
        maxOneTimePreKeys: Int = QR_ONE_TIME_PREKEYS,
        deviceName: String = DEFAULT_DEVICE_NAME,
    ): Result<Exported> = runCatching {
        export(identity, localKeys, maxOneTimePreKeys, deviceName)
    }.recoverCatching { failure ->
        throw when (failure) {
            is IllegalArgumentException ->
                ExportFailure.MaterialIncoherente(failure.message ?: "material incoherente")
            else -> ExportFailure.Rechazado(failure.message ?: "el bundle propio no verifica", failure)
        }
    }
}
