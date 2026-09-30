package com.km.identity

import com.km.crypto.Ed25519Impl
import com.km.crypto.Signature
import java.security.MessageDigest

/**
 * DeviceRoster -- KM-ID-0001 seccion 10
 *
 * Responde a UNA pregunta: QUE DISPOSITIVOS ESTAN AUTORIZADOS PARA ESTA
 * IDENTIDAD. No responde a "como abro sesion con este dispositivo": eso es
 * material de establecimiento de sesion y vive en el ContactBundle, para un
 * unico dispositivo. La confusion entre ambas responsabilidades es la fuente
 * clasica de errores de identidad, y por eso la separacion es normativa.
 *
 * CADENA DE HASHES
 *
 *   previous = SHA-256("KM-ID-ROSTER-GENESIS" || identityRoot)  si sequence == 1
 *   previous = SHA-256(KCE(roster previo COMPLETO))              si sequence > 1
 *
 * El genesis se ancla a la identidad en lugar de usar un campo ausente: asi
 * el primer eslabon queda criptograficamente ligado a la identidad y no
 * admite un roster #1 alternativo para la misma clave.
 *
 * El hash encadena el documento COMPLETO, incluida su firma, de modo que
 * tambien encadena la firma.
 *
 * ALCANCE DE `sequence`
 *
 * `sequence` tiene alcance en `identityRoot`. Una rotacion de identidad crea
 * un espacio de identidad NUEVO y su roster arranca en 1. Esto solo es seguro
 * porque la rotacion es bilateral: un atacante con la raiz antigua robada
 * puede firmar una rotacion, pero NO puede producir la contrafirma de la raiz
 * nueva legitima.
 *
 * Port directo de km-id-reference/roster.py.
 */
object DeviceRoster {

    private val ed25519 = Ed25519Impl()

    // -----------------------------------------------------------------
    // Despacho de version
    // -----------------------------------------------------------------

    /**
     * Comprueba que el documento declara una version soportada, ANTES de
     * validar su forma o su firma.
     *
     * El orden importa. Un documento con `version = 2` no puede interpretarse
     * con las reglas de la version 1: sus campos podrian significar otra
     * cosa. Reinterpretarlo y solo despues quejarse produciria un diagnostico
     * engañoso, del tipo "la firma no verifica" cuando el problema real es
     * que el formato es de otra generacion.
     *
     * Un resultado INDETERMINADO no es un error: significa que no se puede
     * leer la version sin interpretar el resto del documento, y entonces el
     * fallo que se reporte sera el de KCE o el del esquema, que son mas
     * precisos. Solo una version leida con certeza y distinta de la
     * soportada se considera no soportada.
     */
    fun checkVersionSupported(
        data: ByteArray,
        supportedVersion: Long = KmIdConstants.CURRENT_VERSION,
    ) {
        when (val r = Kce.peekVersion(data, supportedVersion)) {
            is Kce.VersionResult.Ok -> Unit
            is Kce.VersionResult.Unsupported ->
                throw KmIdVerificationException(
                    "UNSUPPORTED_VERSION", "version ${r.version} distinta de $supportedVersion"
                )
            // Indeterminate: se deja que la validacion normal reporte el fallo.
            is Kce.VersionResult.Indeterminate -> Unit
        }
    }

    // -----------------------------------------------------------------
    // Construccion
    // -----------------------------------------------------------------

    /** Ancla del genesis de la cadena, ligada a la identidad. */
    fun genesisPrevious(identityRootPublicKey: ByteArray): ByteArray {
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(KmIdConstants.DS_ROSTER_GENESIS.toByteArray(Charsets.UTF_8))
        digest.update(identityRootPublicKey)
        return digest.digest()
    }

    /** Enlace al roster previo. Usa el documento completo, incluida la firma. */
    fun chainPrevious(previousRoster: Map<String, Any?>): ByteArray =
        MessageDigest.getInstance("SHA-256").digest(Kce.encode(previousRoster))

    /**
     * Construye una DeviceEntry. `revokedAt` es obligatorio si se revoca.
     *
     * `deviceId` se DERIVA de la clave de firma y no se acepta como
     * parametro. Permitirlo abriria la puerta a declarar un deviceId que no
     * corresponde a la clave que autoriza al dispositivo.
     */
    fun makeDeviceEntry(
        signingPublicKey: ByteArray,
        agreementPublicKey: ByteArray,
        name: String? = null,
        status: Long = KmIdConstants.STATUS_ACTIVE,
        addedAt: Long = KmIdConstants.SAMPLE_CREATED_AT,
        revokedAt: Long? = null,
    ): Map<String, Any?> {
        if (status == KmIdConstants.STATUS_REVOKED && revokedAt == null) {
            throw KmIdVerificationException(
                "MISSING_FIELD", "revokedAt es obligatorio si status == REVOKED"
            )
        }
        if (status == KmIdConstants.STATUS_ACTIVE && revokedAt != null) {
            throw KmIdVerificationException(
                "UNEXPECTED_FIELD", "revokedAt no aplica si status == ACTIVE"
            )
        }

        val entry = LinkedHashMap<String, Any?>()
        entry["deviceId"] = KmIds.deviceId(signingPublicKey)
        entry["signingKey"] = signingPublicKey
        entry["agreementKey"] = agreementPublicKey
        entry["status"] = status
        entry["addedAt"] = addedAt
        if (name != null) entry["name"] = name
        if (revokedAt != null) entry["revokedAt"] = revokedAt

        KmSchemas.validate(KmSchemas.DEVICE_ENTRY, entry, "DeviceEntry.")
        return entry
    }

    /** Construye un DeviceRoster SIN firmar. */
    @Suppress("UNCHECKED_CAST")
    fun build(
        identityRootPublicKey: ByteArray,
        devices: List<Map<String, Any?>>,
        sequence: Long = 1,
        previous: ByteArray? = null,
        createdAt: Long = KmIdConstants.SAMPLE_CREATED_AT,
    ): Map<String, Any?> {
        if (devices.isEmpty()) {
            throw KmIdVerificationException(
                "EMPTY_ROSTER", "un roster debe contener al menos un dispositivo"
            )
        }
        if (devices.size > KmIdConstants.MAX_DEVICES) {
            throw KmIdVerificationException(
                "TOO_MANY_DEVICES", "maximo ${KmIdConstants.MAX_DEVICES}"
            )
        }

        val link = previous ?: run {
            if (sequence != 1L) {
                throw KmIdVerificationException("MISSING_PREVIOUS", "sequence > 1 exige previous")
            }
            genesisPrevious(identityRootPublicKey)
        }

        val roster = LinkedHashMap<String, Any?>()
        roster["doc"] = KmIdConstants.DOC_ROSTER
        roster["version"] = KmIdConstants.CURRENT_VERSION
        roster["identityRoot"] = identityRootPublicKey
        roster["identityId"] = KmIds.identityId(identityRootPublicKey)
        roster["sequence"] = sequence
        roster["previous"] = link
        roster["createdAt"] = createdAt
        roster["devices"] = devices

        // Se valida con una firma FICTICIA de 64 bytes: la forma del roster no
        // depende de la validez de la firma, y asi el esquema cubre tambien
        // el roster recien construido.
        KmSchemas.validate(
            KmSchemas.DEVICE_ROSTER,
            roster + mapOf("signature" to ByteArray(KmIdConstants.SIG_BYTES)),
            "DeviceRoster.",
        )
        return roster
    }

    /** Devuelve el roster con su firma, listo para canonicalizar. */
    fun sign(
        roster: Map<String, Any?>,
        identityRootPrivateKey: ByteArray,
    ): Map<String, Any?> {
        // El orden es (clave, datos) segun la interfaz Ed25519 de km-core, NO
        // (datos, clave) como en la firma de la referencia Python. Los dos son
        // (ByteArray, ByteArray), asi que el compilador no puede detectar la
        // confusion: solo falla en tiempo de ejecucion, y con un mensaje
        // ("seed length is wrong") que no senala la llamada culpable.
        val signature = ed25519.sign(identityRootPrivateKey, signableBytes(roster))
        return roster + mapOf("signature" to signature.bytes)
    }

    // -----------------------------------------------------------------
    // Canonicalizacion
    // -----------------------------------------------------------------

    /** KCE del payload firmable: la estructura sin `signature`. */
    fun signableBytes(roster: Map<String, Any?>): ByteArray =
        Kce.encode(KmSchemas.DEVICE_ROSTER.signable(roster))

    /** KCE del documento completo, incluida la firma. */
    fun canonicalBytes(roster: Map<String, Any?>): ByteArray = Kce.encode(roster)

    // -----------------------------------------------------------------
    // Verificacion
    // -----------------------------------------------------------------

    /**
     * Verifica un DeviceRoster ya decodificado.
     *
     * Asume que KCE y el esquema ya fueron validados; comprueba las
     * invariantes semanticas y la firma.
     *
     * [requireChain] desactiva la comprobacion de continuidad para el caso en
     * que el roster va incrustado dentro de un ContactBundle. Un bundle
     * embebe UN solo snapshot, de modo que si su `sequence` es > 1 el enlace
     * al anterior NO es verificable a partir del propio documento. En ese
     * contexto la autoridad es exclusivamente la FIRMA de la raiz, y el RFC
     * no debe prometer mas. Cuando `sequence == 1` el ancla genesis si es
     * comprobable sin estado externo, y se comprueba SIEMPRE.
     */
    @Suppress("UNCHECKED_CAST")
    fun verify(
        roster: Map<String, Any?>,
        previousRoster: Map<String, Any?>? = null,
        expectedIdentityRoot: ByteArray? = null,
        requireChain: Boolean = true,
    ) {
        val identityRoot = roster["identityRoot"] as ByteArray
        val devices = roster["devices"] as List<Map<String, Any?>>

        if (expectedIdentityRoot != null && !identityRoot.contentEquals(expectedIdentityRoot)) {
            throw KmIdVerificationException("IDENTITY_MISMATCH", "identityRoot inesperada")
        }

        // Invariante: identityId es DERIVADA de identityRoot, nunca declarada.
        val declaredIdentityId = roster["identityId"] as ByteArray
        if (!declaredIdentityId.contentEquals(KmIds.identityId(identityRoot))) {
            throw KmIdVerificationException(
                "IDENTITY_ID_MISMATCH", "identityId no coincide con su derivacion"
            )
        }

        if (devices.isEmpty()) {
            throw KmIdVerificationException(
                "EMPTY_ROSTER", "un roster debe contener al menos un dispositivo"
            )
        }
        if (devices.size > KmIdConstants.MAX_DEVICES) {
            throw KmIdVerificationException(
                "TOO_MANY_DEVICES", "maximo ${KmIdConstants.MAX_DEVICES}"
            )
        }

        // Invariante: cada deviceId se deriva de SU clave de firma.
        val seen = HashSet<String>()
        for (entry in devices) {
            KmSchemas.validate(KmSchemas.DEVICE_ENTRY, entry, "DeviceRoster.devices[].")
            val signingKey = entry["signingKey"] as ByteArray
            val deviceId = entry["deviceId"] as ByteArray
            if (!deviceId.contentEquals(KmIds.deviceId(signingKey))) {
                throw KmIdVerificationException(
                    "DEVICE_ID_MISMATCH", "deviceId no coincide con su derivacion"
                )
            }
            if (!seen.add(KmIdFixturesHex.of(deviceId))) {
                throw KmIdVerificationException("DUPLICATE_DEVICE", "deviceId repetido en el roster")
            }

            val isRevoked = entry["status"] == KmIdConstants.STATUS_REVOKED
            val hasRevokedAt = entry.containsKey("revokedAt")
            if (isRevoked != hasRevokedAt) {
                throw KmIdVerificationException(
                    "REVOCATION_FIELD_MISMATCH",
                    "revokedAt debe estar presente si y solo si status == REVOKED",
                )
            }
        }

        // Continuidad de la cadena.
        val sequence = roster["sequence"] as Long
        val previous = roster["previous"] as ByteArray
        if (sequence == 1L) {
            // El ancla genesis es comprobable sin estado externo: siempre.
            if (!previous.contentEquals(genesisPrevious(identityRoot))) {
                throw KmIdVerificationException("PREVIOUS_MISMATCH", "el ancla genesis no coincide")
            }
        } else if (!requireChain) {
            // Contexto de bundle: la continuidad no es verificable aqui. Solo
            // se comprueba que el enlace tenga tamano de hash.
            if (previous.size != KmIdConstants.HASH_BYTES) {
                throw KmIdVerificationException("PREVIOUS_MISMATCH", "previous debe tener 32 bytes")
            }
        } else {
            if (previousRoster == null) {
                throw KmIdVerificationException("MISSING_PREVIOUS", "se requiere el roster previo")
            }
            val prevRoot = previousRoster["identityRoot"] as ByteArray
            if (!prevRoot.contentEquals(identityRoot)) {
                throw KmIdVerificationException(
                    "IDENTITY_MISMATCH", "el roster previo es de otra identidad"
                )
            }
            if (!previous.contentEquals(chainPrevious(previousRoster))) {
                throw KmIdVerificationException("PREVIOUS_MISMATCH", "el enlace de la cadena no coincide")
            }
        }

        // Autenticidad.
        // Ojo con el orden: la interfaz Ed25519 de km-core es
        // verify(publicKey, data, signature), al reves que la firma
        // ed25519.verify(signature, data, publicKey) de la referencia. Son
        // los mismos tres valores en otro orden, y cruzarlos compila igual.
        val signature = Signature(roster["signature"] as ByteArray)
        if (!ed25519.verify(identityRoot, signableBytes(roster), signature)) {
            throw KmIdVerificationException("INVALID_SIGNATURE", "la firma de la raiz no verifica")
        }
    }

    /** Localiza una DeviceEntry por deviceId, o null. */
    fun findDevice(roster: Map<String, Any?>, targetDeviceId: ByteArray): Map<String, Any?>? {
        @Suppress("UNCHECKED_CAST")
        val devices = roster["devices"] as List<Map<String, Any?>>
        for (entry in devices) {
            val deviceId = entry["deviceId"] as ByteArray
            if (deviceId.contentEquals(targetDeviceId)) return entry
        }
        return null
    }
}

/**
 * ByteArray no tiene equals por contenido, asi que un `Set<ByteArray>`
 * detectaria dos deviceId identicos como distintos y la comprobacion de
 * duplicados no serviria de nada. Se compara por hex.
 */
private object KmIdFixturesHex {
    fun of(b: ByteArray): String {
        val sb = StringBuilder(b.size * 2)
        for (x in b) sb.append("%02x".format(x))
        return sb.toString()
    }
}
