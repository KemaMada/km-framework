package com.km.identity

import com.km.model.IdentityId

/**
 * Un contacto YA VERIFICADO, y el portador del que salio.
 *
 * ## POR QUE SE GUARDA EL TEXTO Y NO EL BUNDLE
 *
 * Un [VerifiedContactBundle] no se puede reconstruir desde sus accesores: solo
 * existe pasando por [ContactBundle.verified]. Guardar el sobre original —el
 * `.km`, que es lo que el usuario escanea o pega— permite REVERIFICAR al leer,
 * en vez de confiar en lo que se guardo. Un contacto que sale del disco sin
 * pasar por el verificador seria material criptografico sin verificar.
 *
 * ## LA CLAVE DE FIRMA ES LA DEL DISPOSITIVO
 *
 * `subjectSigningKey` sale del bundle verificado y es la que autentica los `ACK`
 * que ese contacto envie. Sin ella no se procesa ningun `ACK`. Se guarda en hex
 * porque viaja a ficheros de texto y se muestra, y la conversion a bytes es
 * explicita: un `runCatching` que devolviera `null` en silencio autorizaria al
 * contacto sin clave.
 *
 * ## `displayName` NO ES DEL BUNDLE
 *
 * Es el nombre que puso el usuario. No se deriva, no se firma y no se puede
 * confundir con un dato verificado.
 */
data class VerifiedContact(
    /** Identidad de la RAIZ que firma el bundle. Es la clave de ruteo. */
    val identityRoot: IdentityId,
    /** `deviceId` del dispositivo que describe el bundle, en hex. */
    val subjectDeviceId: String,
    /** Clave de FIRMA del dispositivo del contacto, en hex. */
    val subjectSigningKey: String,
    /** El portador tal cual se recibio. Se reverifica al cargarlo. */
    val bundleText: String,
    /** Nombre que el usuario le puso. No forma parte del bundle. */
    val displayName: String = "",
) {
    /**
     * Huella corta para mostrar y comparar, sin volcar el bundle.
     *
     * Los tres puntos son ASCII, como los que ya pintaba la app: el escritorio
     * no tenia una razon para cambiar lo que el usuario ve de un contacto.
     */
    val fingerprint: String get() = if (identityRoot.value.length <= 16) {
        identityRoot.value
    } else {
        identityRoot.value.take(16) + "..."
    }

    /** Lo que se guarda de un contacto recien verificado, con el nombre de [previo]. */
    internal fun withVerified(bundle: VerifiedContactBundle, root: IdentityId, previo: VerifiedContact) =
        copy(
            identityRoot = root,
            subjectDeviceId = bundle.subjectDeviceId.toHex(),
            subjectSigningKey = bundle.subjectSigningKey.toHex(),
            bundleText = previo.bundleText,
            displayName = previo.displayName,
        )

    override fun toString(): String = "VerifiedContact($fingerprint)"

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
}
