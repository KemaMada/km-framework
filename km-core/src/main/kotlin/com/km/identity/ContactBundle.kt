package com.km.identity

import com.km.crypto.Ed25519Impl
import com.km.crypto.Signature

/**
 * ContactBundle, SignedPrekey, OneTimePrekey -- KM-ID-0001 secciones 13, 14
 *
 * El ContactBundle es el artefacto que viaja como `.kmc` o como QR. El
 * contenido criptografico subyacente es el MISMO en ambos casos: no existen
 * "protocolo QR" y "protocolo .kmc" separados.
 *
 * DOS RESPONSABILIDADES DISTINTAS
 *
 *     DeviceRoster  ->  QUE dispositivos estan autorizados
 *     ContactBundle ->  COMO abro sesion con subjectDevice
 *
 * BOOTSTRAP, NO ESTADO PERMANENTE
 *
 * El bundle lleva un snapshot firmado del roster y prekeys vigentes, de modo
 * que es autonomo: permite iniciar una relacion sin ningun servicio. Pero es
 * un bootstrap, no una fuente de verdad.
 *
 * INVARIANTE DE BINDING
 *
 * El bundle no puede afirmar que pertenece al dispositivo X mientras contiene
 * las claves de Y. El verificador DEBE comprobar que:
 *
 *     subjectSigningKey   ==  DeviceEntry(subjectDeviceId).signingKey
 *     subjectAgreementKey ==  DeviceEntry(subjectDeviceId).agreementKey
 *     deviceId(subjectSigningKey) == subjectDeviceId
 *     deviceId == deviceId de cada SignedPrekey y OneTimePrekey
 *
 * Port directo de km-id-reference/contact.py.
 */
object ContactBundle {

    private val ed25519 = Ed25519Impl()

    // -----------------------------------------------------------------
    // Prekeys
    // -----------------------------------------------------------------

    /**
     * Construye un SignedPrekey SIN firmar.
     *
     * La firma la emite la clave de FIRMA DEL DISPOSITIVO, no la raiz: la
     * cadena de autoridad es
     *     IdentityRoot -> DeviceRoster -> DeviceSigningKey -> SignedPrekey
     */
    fun buildSignedPrekey(
        deviceSigningPublicKey: ByteArray,
        publicKey: ByteArray,
        keyId: Long,
        deviceIdBytes: ByteArray,
        createdAt: Long = KmIdConstants.SAMPLE_CREATED_AT,
        expiresAt: Long = KmIdConstants.SAMPLE_CREATED_AT + KmIdConstants.SAMPLE_SPK_VALIDITY_MS,
    ): Map<String, Any?> {
        val prekey = LinkedHashMap<String, Any?>()
        prekey["doc"] = KmIdConstants.DOC_SIGNED_PREKEY
        prekey["version"] = KmIdConstants.CURRENT_VERSION
        prekey["deviceId"] = deviceIdBytes
        prekey["keyId"] = keyId
        prekey["publicKey"] = publicKey
        prekey["createdAt"] = createdAt
        prekey["expiresAt"] = expiresAt
        KmSchemas.validate(
            KmSchemas.SIGNED_PREKEY,
            prekey + mapOf("signature" to ByteArray(KmIdConstants.SIG_BYTES)),
            "SignedPrekey.",
        )
        return prekey
    }

    fun signSignedPrekey(
        prekey: Map<String, Any?>,
        deviceSigningPrivateKey: ByteArray,
    ): Map<String, Any?> {
        val signature = ed25519.sign(
            deviceSigningPrivateKey,
            Kce.encode(KmSchemas.SIGNED_PREKEY.signable(prekey)),
        )
        return prekey + mapOf("signature" to signature.bytes)
    }

    fun signablePrekeyBytes(prekey: Map<String, Any?>): ByteArray =
        Kce.encode(KmSchemas.SIGNED_PREKEY.signable(prekey))

    fun buildOnetimePrekey(
        publicKey: ByteArray,
        keyId: Long,
        deviceIdBytes: ByteArray,
    ): Map<String, Any?> {
        val opk = LinkedHashMap<String, Any?>()
        opk["doc"] = KmIdConstants.DOC_ONETIME_PREKEY
        opk["version"] = KmIdConstants.CURRENT_VERSION
        opk["deviceId"] = deviceIdBytes
        opk["keyId"] = keyId
        opk["publicKey"] = publicKey
        KmSchemas.validate(KmSchemas.ONETIME_PREKEY, opk, "OneTimePrekey.")
        return opk
    }

    fun buildEndpointHint(
        type: Long,
        value: String,
        observedAt: Long = KmIdConstants.SAMPLE_CREATED_AT,
        relayId: ByteArray? = null,
    ): Map<String, Any?> {
        if (type == KmIdConstants.ENDPOINT_RELAY && relayId == null) {
            throw KmIdVerificationException("MISSING_FIELD", "relayId es obligatorio si type == RELAY")
        }
        val hint = LinkedHashMap<String, Any?>()
        hint["type"] = type
        hint["value"] = value
        hint["observedAt"] = observedAt
        if (relayId != null) hint["relayId"] = relayId
        KmSchemas.validate(KmSchemas.ENDPOINT_HINT, hint, "EndpointHint.")
        return hint
    }

    // -----------------------------------------------------------------
    // ContactBundle
    // -----------------------------------------------------------------

    /**
     * Construye un ContactBundle SIN firmar.
     *
     * `subjectDeviceId` DEBE existir en `signedRoster`; sus claves se toman
     * del roster, no se aceptan por separado. Esto hace imposible por
     * construccion el bundle que dice una cosa y contiene otra.
     */
    @Suppress("UNCHECKED_CAST")
    fun buildBundle(
        identityRootPublicKey: ByteArray,
        signedRoster: Map<String, Any?>,
        subjectDeviceId: ByteArray,
        signedPrekey: Map<String, Any?>,
        kind: Long = KmIdConstants.KIND_CONTACT,
        oneTimePrekeys: List<Map<String, Any?>> = emptyList(),
        endpoints: List<Map<String, Any?>> = emptyList(),
        capabilities: Long = 0,
        createdAt: Long = KmIdConstants.SAMPLE_CREATED_AT,
        expiresAt: Long? = null,
        linkSecret: ByteArray? = null,
    ): Map<String, Any?> {
        val entry = DeviceRoster.findDevice(signedRoster, subjectDeviceId)
        if (entry == null) {
            throw KmIdVerificationException("SUBJECT_NOT_IN_ROSTER", "subjectDeviceId no figura en el roster")
        }
        if (entry["status"] != KmIdConstants.STATUS_ACTIVE) {
            throw KmIdVerificationException("SUBJECT_REVOKED", "subjectDeviceId esta revocado")
        }

        if (oneTimePrekeys.size > KmIdConstants.MAX_ONE_TIME_PREKEYS) {
            throw KmIdVerificationException(
                "TOO_MANY_PREKEYS", "excede maximo ${KmIdConstants.MAX_ONE_TIME_PREKEYS} one-time prekeys"
            )
        }
        if (endpoints.size > KmIdConstants.MAX_ENDPOINTS) {
            throw KmIdVerificationException(
                "TOO_MANY_ENDPOINTS", "excede maximo ${KmIdConstants.MAX_ENDPOINTS} endpoints"
            )
        }

        if (kind == KmIdConstants.KIND_DEVICE_LINK && linkSecret == null) {
            throw KmIdVerificationException("MISSING_FIELD", "linkSecret es obligatorio si kind == DEVICE_LINK")
        }
        if (kind == KmIdConstants.KIND_CONTACT && linkSecret != null) {
            throw KmIdVerificationException("UNEXPECTED_FIELD", "linkSecret solo aplica a DEVICE_LINK")
        }

        val bundle = LinkedHashMap<String, Any?>()
        bundle["doc"] = KmIdConstants.DOC_CONTACT_BUNDLE
        bundle["version"] = KmIdConstants.CURRENT_VERSION
        bundle["kind"] = kind
        bundle["createdAt"] = createdAt
        bundle["identityRoot"] = identityRootPublicKey
        bundle["identityId"] = KmIds.identityId(identityRootPublicKey)
        bundle["roster"] = signedRoster
        bundle["subjectDeviceId"] = subjectDeviceId
        bundle["subjectSigningKey"] = entry["signingKey"]
        bundle["subjectAgreementKey"] = entry["agreementKey"]
        bundle["signedPrekey"] = signedPrekey
        bundle["oneTimePrekeys"] = oneTimePrekeys
        bundle["endpoints"] = endpoints
        bundle["capabilities"] = capabilities
        if (expiresAt != null) bundle["expiresAt"] = expiresAt
        if (linkSecret != null) bundle["linkSecret"] = linkSecret

        KmSchemas.validate(
            KmSchemas.CONTACT_BUNDLE,
            bundle + mapOf("signature" to ByteArray(KmIdConstants.SIG_BYTES)),
            "ContactBundle.",
        )
        return bundle
    }

    fun signBundle(
        bundle: Map<String, Any?>,
        identityRootPrivateKey: ByteArray,
    ): Map<String, Any?> {
        val signature = ed25519.sign(
            identityRootPrivateKey,
            signableBytes(bundle),
        )
        return bundle + mapOf("signature" to signature.bytes)
    }

    fun signableBytes(bundle: Map<String, Any?>): ByteArray =
        Kce.encode(KmSchemas.CONTACT_BUNDLE.signable(bundle))

    fun canonicalBytes(bundle: Map<String, Any?>): ByteArray = Kce.encode(bundle)

    // -----------------------------------------------------------------
    // Verificacion
    // -----------------------------------------------------------------

    /**
     * Verifica un ContactBundle ya decodificado y con esquema validado.
     *
     * Orden de comprobacion:
     *   1. binding del subject contra la DeviceEntry del roster
     *   2. el roster embebido verifica (incluida su propia cadena y firma)
     *   3. binding de prekeys al subject
     *   4. firma de la raiz sobre el bundle
     */
    @Suppress("UNCHECKED_CAST")
    fun verifyBundle(
        bundle: Map<String, Any?>,
        expectedIdentityRoot: ByteArray? = null,
    ) {
        if (expectedIdentityRoot != null &&
            !(bundle["identityRoot"] as ByteArray).contentEquals(expectedIdentityRoot)
        ) {
            throw KmIdVerificationException("IDENTITY_MISMATCH", "identityRoot inesperada")
        }

        if (!(bundle["identityId"] as ByteArray).contentEquals(
                KmIds.identityId(bundle["identityRoot"] as ByteArray)
            )
        ) {
            throw KmIdVerificationException("IDENTITY_ID_MISMATCH", "identityId no coincide con su derivacion")
        }

        // 1. El roster embebido debe verificar por si mismo.
        DeviceRoster.verify(
            bundle["roster"] as Map<String, Any?>,
            requireChain = false,
        )

        if (!(bundle["roster"] as Map<String, Any?>)["identityRoot"].let { r ->
                if (r is ByteArray) r.contentEquals(bundle["identityRoot"] as ByteArray)
                else false
            }
        ) {
            throw KmIdVerificationException("IDENTITY_MISMATCH", "el roster embebido es de otra identidad")
        }

        // 2. Binding del subject contra la DeviceEntry.
        val entry = DeviceRoster.findDevice(
            bundle["roster"] as Map<String, Any?>,
            bundle["subjectDeviceId"] as ByteArray,
        )
        if (entry == null) {
            throw KmIdVerificationException("SUBJECT_NOT_IN_ROSTER", "subjectDeviceId no figura en el roster")
        }
        if (!(bundle["subjectSigningKey"] as ByteArray).contentEquals(entry["signingKey"] as ByteArray)) {
            throw KmIdVerificationException(
                "SUBJECT_BINDING_MISMATCH", "subjectSigningKey no coincide con el roster"
            )
        }
        if (!(bundle["subjectAgreementKey"] as ByteArray).contentEquals(entry["agreementKey"] as ByteArray)) {
            throw KmIdVerificationException(
                "SUBJECT_BINDING_MISMATCH", "subjectAgreementKey no coincide con el roster"
            )
        }
        if (!KmIds.deviceId(bundle["subjectSigningKey"] as ByteArray)
                .contentEquals(bundle["subjectDeviceId"] as ByteArray)
        ) {
            throw KmIdVerificationException(
                "SUBJECT_BINDING_MISMATCH", "subjectDeviceId no deriva de su signingKey"
            )
        }

        // 3. Binding de prekeys al subject.
        val spk = bundle["signedPrekey"] as Map<String, Any?>
        KmSchemas.validate(KmSchemas.SIGNED_PREKEY, spk, "ContactBundle.signedPrekey.")
        if (!(spk["deviceId"] as ByteArray).contentEquals(bundle["subjectDeviceId"] as ByteArray)) {
            throw KmIdVerificationException(
                "PREKEY_BINDING_MISMATCH", "signedPrekey.deviceId != subjectDeviceId"
            )
        }
        val prekeySignature = Signature(spk["signature"] as ByteArray)
        val deviceSigningKey = entry["signingKey"] as ByteArray
        if (!ed25519.verify(deviceSigningKey, signablePrekeyBytes(spk), prekeySignature)) {
            throw KmIdVerificationException("INVALID_SIGNATURE", "la firma del signed prekey no verifica")
        }

        for (opk in bundle["oneTimePrekeys"] as List<Map<String, Any?>>) {
            KmSchemas.validate(KmSchemas.ONETIME_PREKEY, opk, "ContactBundle.oneTimePrekeys[].")
            if (!(opk["deviceId"] as ByteArray).contentEquals(bundle["subjectDeviceId"] as ByteArray)) {
                throw KmIdVerificationException(
                    "PREKEY_BINDING_MISMATCH", "oneTimePrekey.deviceId != subjectDeviceId"
                )
            }
        }

        for (hint in (bundle["endpoints"] as? List<Map<String, Any?>>) ?: emptyList()) {
            KmSchemas.validate(KmSchemas.ENDPOINT_HINT, hint, "ContactBundle.endpoints[].")
            if (hint["type"] == KmIdConstants.ENDPOINT_RELAY && !hint.containsKey("relayId")) {
                throw KmIdVerificationException("MISSING_FIELD", "relayId es obligatorio si type == RELAY")
            }
        }

        // 4. Autenticidad del bundle por la raiz.
        val bundleSignature = Signature(bundle["signature"] as ByteArray)
        if (!ed25519.verify(
                bundle["identityRoot"] as ByteArray,
                signableBytes(bundle),
                bundleSignature,
            )
        ) {
            throw KmIdVerificationException("INVALID_SIGNATURE", "la firma de la raiz sobre el bundle no verifica")
        }
    }
}