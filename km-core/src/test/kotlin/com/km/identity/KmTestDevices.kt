package com.km.identity

import com.km.auth.Base64Url
import com.km.crypto.Ed25519
import com.km.crypto.Ed25519Impl
import com.km.crypto.X25519
import com.km.crypto.X25519KeyPair
import com.km.crypto.provider.BcX25519
import com.km.model.IdentityId

/**
 * Dispositivos y raices REALES para las pruebas de establecimiento.
 *
 * TODO lo que sale de aqui esta construido con las primitivas del propio
 * framework —`DeviceRoster`, `ContactBundle`, `Ed25519Impl`, `BcX25519`— y
 * firmado de verdad. No hay ningun byte escrito a mano: un material fabricado
 * para que una comprobacion pase no la demuestra.
 *
 * Los vectores congelados de `km-id-reference` sirven para lo que sirven: para
 * contrastar CONTRA EL DOCUMENTO CRUDO. Sus claves son semillas opacas, no
 * claves X25519 reales, asi que de ahi no sale material con el que hacer un
 * acuerdo de verdad.
 */
class KmTestDevice(
    val name: String,
    private val ed25519: Ed25519 = Ed25519Impl(),
    private val x25519: X25519 = BcX25519(),
    val signedPreKeyId: Long = 42L,
) {
    val signingKeyPair = ed25519.generateKeyPair()

    /** `deviceId` DERIVADO de la clave de firma, como exige KM-ID-0001. */
    val deviceId: ByteArray = KmIds.deviceId(signingKeyPair.publicKey)

    /** La `IK` de X3DH: clave de ACUERDO, jamas la de firma. */
    val agreementKey: X25519KeyPair = x25519.generateKeyPair()

    /** El `SPK`: se firma con la clave de FIRMA del dispositivo. */
    val signedPreKey: X25519KeyPair = x25519.generateKeyPair()

    private val oneTimePreKeys = LinkedHashMap<Long, X25519KeyPair>()

    val identityId: IdentityId = IdentityId(KmIds.identityId(signingKeyPair.publicKey).toHex())

    /** Emite (o devuelve) el OPK [keyId] de este dispositivo. */
    fun issueOneTimePreKey(keyId: Long): X25519KeyPair =
        oneTimePreKeys.getOrPut(keyId) { x25519.generateKeyPair() }

    /** El OPK [keyId] si existe. */
    fun oneTimePreKey(keyId: Long): X25519KeyPair? = oneTimePreKeys[keyId]

    /** Los `keyId` emitidos, en orden de emision. */
    fun issuedOneTimePreKeyIds(): List<Long> = oneTimePreKeys.keys.toList()

    fun signWith(data: ByteArray): ByteArray =
        ed25519.sign(signingKeyPair.privateKey, data).bytes
}

/**
 * Raiz de identidad que firma los bundles de los dispositivos que publica.
 */
class KmTestIdentityRoot(
    private val ed25519: Ed25519 = Ed25519Impl(),
) {
    val keyPair = ed25519.generateKeyPair()

    val identityId: IdentityId = IdentityId(KmIds.identityId(keyPair.publicKey).toHex())

    /**
     * Bundle CONTACT firmado y PUBLICADO para [subject].
     *
     * @param devices dispositivos que aparecen en el roster firmado.
     * @param subject dispositivo al que describe el bundle.
     * @param oneTimePreKeyIds `keyId` de los OPK que el bundle ofrece. Los
     *   publicos viajan; las claves privadas se quedan en el dispositivo, que
     *   es lo que hace un emisor real con las suyas.
     */
    fun bundleFor(
        devices: List<KmTestDevice>,
        subject: KmTestDevice,
        oneTimePreKeyIds: List<Long>,
    ): Map<String, Any?> {
        val roster = DeviceRoster.sign(
            DeviceRoster.build(
                identityRootPublicKey = keyPair.publicKey,
                devices = devices.map {
                    DeviceRoster.makeDeviceEntry(
                        signingPublicKey = it.signingKeyPair.publicKey,
                        agreementPublicKey = it.agreementKey.publicKey,
                        name = it.name,
                    )
                },
                sequence = 1,
            ),
            keyPair.privateKey,
        )

        val spk = ContactBundle.signSignedPrekey(
            ContactBundle.buildSignedPrekey(
                deviceSigningPublicKey = subject.signingKeyPair.publicKey,
                publicKey = subject.signedPreKey.publicKey,
                keyId = subject.signedPreKeyId,
                deviceIdBytes = subject.deviceId,
            ),
            subject.signingKeyPair.privateKey,
        )

        val opks = oneTimePreKeyIds.map { keyId ->
            ContactBundle.buildOnetimePrekey(
                publicKey = requireNotNull(subject.oneTimePreKey(keyId)) {
                    "el dispositivo ${subject.name} no ha emitido el OPK $keyId"
                }.publicKey,
                keyId = keyId,
                deviceIdBytes = subject.deviceId,
            )
        }

        return ContactBundle.signBundle(
            ContactBundle.buildBundle(
                identityRootPublicKey = keyPair.publicKey,
                signedRoster = roster,
                subjectDeviceId = subject.deviceId,
                signedPrekey = spk,
                oneTimePrekeys = opks,
            ),
            keyPair.privateKey,
        )
    }

    /** El mismo bundle, pasando por el sobre base64url(KCE) de un QR o `.kmc`. */
    fun envelopeFor(
        devices: List<KmTestDevice>,
        subject: KmTestDevice,
        oneTimePreKeyIds: List<Long>,
    ): String = Base64Url.encode(
        ContactBundle.canonicalBytes(bundleFor(devices, subject, oneTimePreKeyIds)),
    )
}

private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }