package com.km.identity

/**
 * Un ContactBundle que ha pasado [ContactBundle.verifyBundle].
 *
 * POR QUE UN TIPO PROPIO Y NO UN MAP
 *
 *     Derivar material criptografico de un bundle NO verificado es el fallo
 *     que este tipo existe para impedir. Con `Map<String, Any?>` esa
 *     distincion es convencional —una nota en el KDoc— y por tanto
 *     convencionalmente se rompe. Aqui el constructor es privado: la unica
 *     forma de obtener una instancia es pasar por el verificador, o sea que
 *     el invariante lo sostiene el compilador y no la disciplina.
 *
 *     El mapa subyacente NO se expone. Se ofrecen accesores tipados, para que
 *     un consumidor no pueda mutar el documento y dejar de estar verificado
 *     mientras la instancia sigue diciendo que lo esta.
 *
 *     Esto tambien sustituye a `PeerContext.contactBundle`, que estaba
 *     tipado como el objeto `ContactBundle` —un singleton— y por tanto solo
 *     podia contener el propio singleton: compilaba, pero no podia llevar
 *     ningun bundle.
 *
 * LO QUE NO HACE
 *
 *     No comprueba vigencia temporal. `verifyBundle` valida estructura,
 *     invariantes de binding y firma, pero no mira el reloj; la expiracion de
 *     un bundle es una decision de protocolo que todavia no esta tomada.
 */
class VerifiedContactBundle private constructor(
    private val doc: Map<String, Any?>,
) {
    /** Bytes canonicos KCE del documento verificado. */
    fun canonicalBytes(): ByteArray = ContactBundle.canonicalBytes(doc)

    /** Raiz de identidad que firma el bundle. */
    val identityRoot: ByteArray get() = doc["identityRoot"] as ByteArray

    /** `deviceId` del dispositivo al que describe el bundle. */
    val subjectDeviceId: ByteArray get() = doc["subjectDeviceId"] as ByteArray

    /** Clave de firma del dispositivo destinatario. */
    val subjectSigningKey: ByteArray
        get() = (doc["subjectSigningKey"] as? ByteArray)
            ?: DeviceRoster.findDevice(doc["roster"] as Map<String, Any?>, subjectDeviceId)?.get("signingKey") as? ByteArray
            ?: throw IllegalStateException("subjectSigningKey ausente")

    /** Clave de acuerdo X25519 del dispositivo destinatario: la IK que X3DH necesita. */
    val subjectAgreementKey: ByteArray
        get() = (doc["subjectAgreementKey"] as? ByteArray)
            ?: DeviceRoster.findDevice(doc["roster"] as Map<String, Any?>, subjectDeviceId)?.get("agreementKey") as? ByteArray
            ?: throw IllegalStateException("subjectAgreementKey ausente")

    /** `deviceId` declarado dentro del signed prekey, si lo hay. */
    val subjectSignedPrekeyDeviceId: ByteArray?
        get() = (doc["signedPrekey"] as? Map<*, *>)?.get("deviceId") as? ByteArray

    /** Public key del signed prekey vigente. */
    val signedPrekeyPublicKey: ByteArray
        get() = (doc["signedPrekey"] as Map<*, *>).let { it["publicKey"] as ByteArray }

    /** `keyId` del signed prekey vigente. */
    val signedPrekeyId: Long
        get() = (doc["signedPrekey"] as Map<*, *>)["keyId"] as Long

    /** Cuantos one-time prekeys viaja el bundle. */
    fun oneTimePrekeyCount(): Int = oneTimePrekeys().size

    /**
     * El indice de OPK que el INICIADOR debe pedir para este bundle.
     *
     * ## POR QUE ES UNA PROPIEDAD DEL BUNDLE Y NO UNA CLASE APARTE
     *
     * `BootstrapPrekeys.fromVerified` LANZA si se le pide un OPK que el bundle no
     * lleva (`0` sobre un bundle con 0 OPK), y exige `null` para una sesion sin
     * `DH4`. Degradar de `0` a "sin OPK" en silencio cambiaria el `SK` derivado
     * sin que nada lo anuncie.
     *
     * La respuesta depende de UN SOLO dato —cuantos OPK trae el bundle— que este
     * tipo ya conoce. Tenerla en una clase de composicion aparte era una segunda
     * copia de la misma regla; aqui el llamante no puede olvidarse de mirarla.
     *
     * @return `0` si el bundle trae algun OPK (`DH4` disponible), `null` si no
     *   trae ninguno (sesion sin `DH4`, que es lo unico que km-core acepta).
     */
    val initiatorOneTimePrekeyIndex: Int?
        get() = if (oneTimePrekeyCount() == 0) null else 0

    /**
     * Public key del one-time prekey en la posicion [index], o null si no hay.
     *
     * El indice lo elige el llamante: el consumo de un OPK es estado del
     * emisor, no de este objeto, asi que aqui no se avanza ningun cursor.
     */
    fun oneTimePrekeyAt(index: Int): ByteArray? =
        oneTimePrekeys().getOrNull(index)?.get("publicKey") as? ByteArray

    /** `keyId` del one-time prekey en la posicion [index], o null si no hay. */
    fun oneTimePrekeyIdAt(index: Int): Long? =
        oneTimePrekeys().getOrNull(index)?.get("keyId") as? Long

    companion object {
        /**
         * UNICA forma de construir un [VerifiedContactBundle].
         *
         * Verifica primero y envuelve despues. Que la fabricacion viva DENTRO
         * del tipo es lo que sostiene el invariante: no hay ninguna otra ruta,
         * ni dentro del modulo ni fuera.
         *
         * @throws KmIdVerificationException si el bundle no verifica
         */
        fun verified(
            bundle: Map<String, Any?>,
            expectedIdentityRoot: ByteArray? = null,
        ): VerifiedContactBundle {
            ContactBundle.verifyBundle(bundle, expectedIdentityRoot)
            return VerifiedContactBundle(bundle)
        }
    }

    private fun oneTimePrekeys(): List<Map<*, *>> =
        (doc["oneTimePrekeys"] as? List<*>).orEmpty().mapNotNull { it as? Map<*, *> }

    /**
     * Identificador corto para trazas. NO imprime el documento: contiene
     * material criptografico y no debe acabar en un log por accidente.
     */
    override fun toString(): String = "VerifiedContactBundle(${subjectDeviceId.toHex()})"

    override fun equals(other: Any?): Boolean =
        other is VerifiedContactBundle && canonicalBytes().contentEquals(other.canonicalBytes())

    override fun hashCode(): Int = canonicalBytes().contentHashCode()

    private fun ByteArray.toHex(): String =
        joinToString("") { "%02x".format(it) }
}