package com.km.x3dh

import com.km.crypto.Kdf
import com.km.crypto.X25519
import com.km.crypto.X25519KeyPair
import com.km.identity.VerifiedContactBundle

/**
 * KM-0006 — Constantes del bootstrap X3DH.
 *
 * CONGELADO en docs/rfc/KM-0006-session-bootstrap.md v0.2.
 */
object X3dhSpec {
    /**
     * Dominios de HKDF, uno por posicion.
     *
     * Separar por posicion es lo que impide que una salida de HKDF tenga dos
     * significados criptograficos distintos: DH1..DH4 salen de pares de claves
     * diferentes y tienen propiedades diferentes.
     */
    const val INFO_DH1: String = "KM-0006/X3DH/V1/DH1"
    const val INFO_DH2: String = "KM-0006/X3DH/V1/DH2"
    const val INFO_DH3: String = "KM-0006/X3DH/V1/DH3"
    const val INFO_DH4: String = "KM-0006/X3DH/V1/DH4"
    const val INFO_SK: String = "KM-0006/X3DH/V1/SK"

    /**
     * Dominio de la clave que protege F en el primer SecureFrame.
     *
     * Deliberadamente distinto de `/SK`: `SK` no se usa nunca como clave AEAD.
     */
    const val INFO_PREKEY: String = "KM-0006/X3DH/V1/PREKEY"

    /** Longitudes de todas las claves derivadas. */
    const val KEY_LENGTH: Int = 32

    /**
     * Longitud de la clave de bootstrap F.
     *
     * Invariante 7: F tiene exactamente 32 bytes. F es la mitad del secreto
     * que Alice conoce de antemano; sin ella, dos sesiones contra el mismo SPK
     * derivarian el mismo SK.
     */
    const val F_LENGTH: Int = 32
}

/**
 * Material publico de un dispositivo, tal y como aparece en un
 * `ContactBundle` ya verificado.
 *
 * Solo contiene claves PUBLICAS. Ninguna clase de este archivo acepta ni
 * almacena una clave de firma Ed25519 en un papel de acuerdo.
 */
data class BootstrapPrekeys(
    /** `deviceId` del dispositivo destinatario (derivado de su signingKey). */
    val deviceId: ByteArray,
    /** IK del dispositivo: su `agreementKey` X25519 (§13, device-scoped). */
    val identityAgreementKey: ByteArray,
    /** SPK: clave publica X25519 firmada por el dispositivo. */
    val signedPreKey: ByteArray,
    /** `keyId` del SPK, para consumed-state local. */
    val signedPreKeyId: Long,
    /** OPK opcional: clave publica X25519 de un solo uso. */
    val oneTimePreKey: ByteArray? = null,
    /** `keyId` del OPK. */
    val oneTimePreKeyId: Long? = null,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is BootstrapPrekeys) return false
        return deviceId.contentEquals(other.deviceId) &&
            identityAgreementKey.contentEquals(other.identityAgreementKey) &&
            signedPreKey.contentEquals(other.signedPreKey) &&
            signedPreKeyId == other.signedPreKeyId &&
            (oneTimePreKey?.contentEquals(other.oneTimePreKey) ?: (other.oneTimePreKey == null)) &&
            oneTimePreKeyId == other.oneTimePreKeyId
    }

    override fun hashCode(): Int {
        var r = deviceId.contentHashCode()
        r = 31 * r + identityAgreementKey.contentHashCode()
        r = 31 * r + signedPreKey.contentHashCode()
        r = 31 * r + signedPreKeyId.hashCode()
        r = 31 * r + (oneTimePreKey?.contentHashCode() ?: 0)
        r = 31 * r + (oneTimePreKeyId?.hashCode() ?: 0)
        return r
    }

    companion object {
        /**
         * DERIVA LAS PREKEYS DE UN BUNDLE YA VERIFICADO. El UNICO punto de
         * entrada que hoy existe: hasta este slice, [BootstrapPrekeys] solo se
         * construia a mano en ficheros de test, y no habia ninguna ruta de
         * produccion que la derivase de un `ContactBundle` real.
         *
         * ## POR QUE VIVE AQUI Y NO EN `VerifiedContactBundle`
         *
         * La dependencia va en UN sentido: la capa de protocolo (`x3dh`) conoce
         * el documento de identidad (`identity`); el documento de identidad no
         * sabe que es un bootstrap de protocolo. Poner la fabrica al lado del
         * `ContactBundle` obligaria a `com.km.identity` a saber que existe una
         * `IK`, un `SPK` y un `OPK`, es decir, a invadir la capa de arriba.
         *
         * ## QUE GARANTIZA EL TIPO DE ENTRADA
         *
         * El parametro es [VerifiedContactBundle], no `Map<String, Any?>`: su
         * constructor es privado y la unica fabrica que lo crea verifica antes.
         * No existe ninguna forma de llegar aqui con material sin verificar, ni
         * dentro del modulo ni fuera. Esa es la razon de que este slice exista
         * y la de que el slice 1 hiciera el tipo.
         *
         * ## COPIAS DEFENSIVAS
         *
         * Los accesores de [VerifiedContactBundle] devuelven el array que vive
         * dentro del documento. Un [BootstrapPrekeys] sobrevive mucho mas que
         * el bundle que lo produjo —llega hasta el ratchet—, asi que aqui se
         * copian: que un consumidor mutara el array no puede dejar de ser
         * cierto lo que el bundle dice.
         *
         * ## EL ONE-TIME PREKEY: CONTRATO
         *
         * X3DH define `DH4` como OPCIONAL ([X3dh.initiate] lo omite cuando
         * `oneTimePreKey` es `null`, y [X3dh.respond] hace lo propio con el
         * suyo). Por tanto hay dos caminos legitimos y ninguno puede ser el
         * default del otro:
         *
         *  - `oneTimePrekeyIndex = null` -> NO se usa ningun OPK, aunque el
         *    bundle los lleve. Es una decision explicita del llamante.
         *  - `oneTimePrekeyIndex = i` -> se usa el OPK de la posicion `i`. Por
         *    defecto `i = 0`: es la decision que mejora el secreto y la que un
         *    bundle recien exportado casi siempre puede satisfacer.
         *
         * Si `i` no existe, esta LANZA en vez de degradar en silencio. Caer de
         * `i = 0` a "sin OPK" es perder `DH4` —y con el, el salto de clave que
         * aporta— sin que nada lo anuncie: el `SK` derivado seria distinto y
         * el receptor tendria que saberlo por otra via. Un silencio aqui es un
         * fallo de intercompatibilidad disfrazado de decision.
         *
         * Que el `keyId` viaje es lo que hace el contrato utilizable: es el
         * valor que el RECEPTOR necesita para saber que OPK tiene que meter en
         * su [ResponderKeyMaterial] para que los dos `SK` coincidan.
         *
         * @throws IllegalArgumentException si `oneTimePrekeyIndex` es negativo o
         *   no existe en el bundle.
         */
        fun fromVerified(
            bundle: VerifiedContactBundle,
            oneTimePrekeyIndex: Int? = 0,
        ): BootstrapPrekeys {
            val base = BootstrapPrekeys(
                deviceId = bundle.subjectDeviceId.copyOf(),
                // La IK de X3DH es la clave de ACUERDO del dispositivo, jamas
                // su clave de firma: `verifyBundle` ya garantiza que
                // `subjectAgreementKey` es la `agreementKey` del roster.
                identityAgreementKey = bundle.subjectAgreementKey.copyOf(),
                signedPreKey = bundle.signedPrekeyPublicKey.copyOf(),
                signedPreKeyId = bundle.signedPrekeyId,
            )
            if (oneTimePrekeyIndex == null) return base

            require(oneTimePrekeyIndex >= 0) {
                "el indice de one-time prekey no puede ser negativo: $oneTimePrekeyIndex"
            }
            val disponibles = bundle.oneTimePrekeyCount()
            require(oneTimePrekeyIndex < disponibles) {
                "el bundle lleva $disponibles one-time prekeys y se pidio el indice " +
                    "$oneTimePrekeyIndex; pasa null para una sesion sin DH4"
            }
            // `oneTimePrekeyAt` / `oneTimePrekeyIdAt` devuelven null fuera de
            // rango. Aqui el rango ya esta comprobado, asi que un null en una
            // posicion valida es un documento MALFORMADO, no una ausencia: que
            // se note en vez de propagarse como un OPK sin id.
            val opk = bundle.oneTimePrekeyAt(oneTimePrekeyIndex)
            val opkId = bundle.oneTimePrekeyIdAt(oneTimePrekeyIndex)
            require(opk != null && opkId != null) {
                "el one-time prekey #$oneTimePrekeyIndex del bundle no trae publicKey y keyId"
            }
            return base.copy(oneTimePreKey = opk.copyOf(), oneTimePreKeyId = opkId)
        }
    }
}

/**
 * Material PRIVADO de un dispositivo, necesario para INICIAR una sesion.
 *
 * @param identityAgreementKey par X25519 de acuerdo del dispositivo emisor
 *   (su `IK`). NUNCA una clave de firma Ed25519.
 */
data class InitiatorKeyMaterial(
    val deviceId: ByteArray,
    val identityAgreementKey: X25519KeyPair,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is InitiatorKeyMaterial) return false
        return deviceId.contentEquals(other.deviceId) &&
            identityAgreementKey.privateKey.contentEquals(other.identityAgreementKey.privateKey)
    }

    override fun hashCode(): Int =
        31 * deviceId.contentHashCode() + identityAgreementKey.privateKey.contentHashCode()
}

/**
 * Material PRIVADO de un dispositivo, necesario para RESPONDER una sesion.
 *
 * Incluye las claves privadas de SPK y OPK, que solo existen en el dispositivo
 * receptor.
 */
data class ResponderKeyMaterial(
    val deviceId: ByteArray,
    val identityAgreementKey: X25519KeyPair,
    val signedPreKey: X25519KeyPair,
    val oneTimePreKey: X25519KeyPair?,
    /** `keyId` del OPK, para registrar su consumo local. */
    val oneTimePreKeyId: Long? = null,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ResponderKeyMaterial) return false
        return deviceId.contentEquals(other.deviceId) &&
            signedPreKey.privateKey.contentEquals(other.signedPreKey.privateKey) &&
            (oneTimePreKey?.privateKey?.contentEquals(other.oneTimePreKey?.privateKey)
                ?: (other.oneTimePreKey == null))
    }

    override fun hashCode(): Int {
        var r = deviceId.contentHashCode()
        r = 31 * r + signedPreKey.privateKey.contentHashCode()
        r = 31 * r + (oneTimePreKey?.privateKey?.contentHashCode() ?: 0)
        return r
    }
}

/**
 * Resultado del bootstrap del emisor: el secreto derivado y lo que debe
 * transmitirse.
 *
 * @param sharedKey `SK`. Secreto de X3DH. NO es una clave AEAD.
 * @param preKeyKey `K_prekey`, derivada de SK con su propio dominio.
 * @param bootstrapValue `F`, 32 bytes aleatorios.
 * @param ephemeralPublic clave publica efimera del emisor (se envia en el
 *   `SecureFrame` de tipo PREKEY como `dhPublicKey`).
 */
class X3dhInitiatorResult internal constructor(
    val sharedKey: ByteArray,
    val preKeyKey: ByteArray,
    val bootstrapValue: ByteArray,
    /**
     * Par efimero generado por X3DH.
     *
     * Se devuelve COMPLETO, incluida la clave privada, porque el llamante es
     * el propio iniciador: necesita `EK_A` para continuar la sesion con el
     * Double Ratchet (misma invariante que en `InitiatorKeyMaterial`).
     * No es una fuga: es la clave del llamante, que ya poseia de facto.
     */
    val ephemeral: com.km.crypto.X25519KeyPair,
    val usedOneTimePreKeyId: Long?,
) {
    /** Parte publica, que es la que viaja en el header del frame PREKEY. */
    val ephemeralPublic: ByteArray get() = ephemeral.publicKey

    override fun toString(): String =
        "X3dhInitiatorResult(sharedKey=<${sharedKey.size}B>, preKeyKey=<${preKeyKey.size}B>, " +
            "F=<${bootstrapValue.size}B>, opkId=$usedOneTimePreKeyId)"
}

/**
 * Resultado del bootstrap del receptor.
 *
 * @param sharedKey `SK` recalculado; debe coincidir con el del emisor.
 * @param preKeyKey `K_prekey` recalculada.
 * @param bootstrapValue `F` descifrada.
 */
class X3dhResponderResult internal constructor(
    val sharedKey: ByteArray,
    val preKeyKey: ByteArray,
    val bootstrapValue: ByteArray,
    val usedOneTimePreKeyId: Long?,
) {
    override fun toString(): String =
        "X3dhResponderResult(sharedKey=<${sharedKey.size}B>, F=<${bootstrapValue.size}B>, " +
            "opkId=$usedOneTimePreKeyId)"
}
