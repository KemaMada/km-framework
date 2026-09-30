package com.keymessage.core.km7

/**
 * KM-0007 — Negotiation of capabilities, serialization, compression, and crypto parameters.
 *
 * SEPARACION DE CONCEPTOS
 *
 *   SUPPORTED  ≠  ENABLED  ≠  NEGOTIATED
 *
 *   Una capacidad anunciada como soportada NO implica que este habilitada
 *   para la sesion actual, y una capacidad habilitada en local NO implica
 *   que se negocie automaticamente.
 *
 * RESPONSABILIDAD UNICA
 *
 *   KM-0007 selecciona PARAMETROS. No los ejecuta. No inicia cifrado.
 *   No comprime datos. No serializa mensajes de aplicacion.
 *
 * INVARIANTES
 *
 *   1. La seleccion es DETERMINISTA: dados dos CapabilitySets identicos,
 *      el resultado es identico.
 *   2. La interseccion NUNCA incluye capacidades no soportadas por ambas
 *      partes.
 *   3. La preferencia es del lado LOCAL: si A y B soportan X e Y, A
 *      selecciona segun su orden de preferencia, no el de B.
 *   4. Una capacidad desconocida se IGNORA, no se rechaza.
 *   5. La ausencia de interseccion produce NegotiationResult vacio
 *      (isFailure = true).
 */

/** Una capacidad individual. */
data class Capability(
    val id: String,
    val version: Int,
    val parameters: Map<String, KceValue> = emptyMap(),
)

/** Valor representable en KCE para los parametros de capacidad. */
sealed interface KceValue {
    data class VString(val value: String) : KceValue
    data class VLong(val value: Long) : KceValue
    data class VBytes(val value: ByteArray) : KceValue
    data class VBool(val value: Boolean) : KceValue
}

/** Conjunto de capacidades ofrecidas por una parte. */
data class CapabilitySet(
    val capabilities: List<Capability>,
) {
    init {
        require(capabilities.distinctBy { it.id }.size == capabilities.size) {
            "ids duplicados en CapabilitySet: ${capabilities.groupBy { it.id }.filter { it.value.size > 1 }.keys}"
        }
    }

    fun find(id: String): Capability? = capabilities.find { it.id == id }

    fun has(id: String): Boolean = capabilities.any { it.id == id }
}

/** Algoritmos conocidos por KM-0007. */
object KnownCapabilities {
    // Serialization
    const val SERIALIZATION = "serialization"
    const val SER_CBOR = "kce-cbor"

    // Compression
    const val COMPRESSION = "compression"
    const val CMP_NONE = "none"

    // Identity algorithm
    const val IDENTITY = "identity"
    const val ID_ED25519 = "ed25519"

    // Signature algorithm
    const val SIGNATURE = "signature"
    const val SIG_ED25519 = "ed25519"

    // Key agreement
    const val KEY_AGREEMENT = "key-agreement"
    const val KA_X25519 = "x25519"

    // Symmetric encryption
    const val ENCRYPTION = "encryption"
    const val ENC_XCHACHA20_POLY1305 = "xchacha20-poly1305"

    // Hash
    const val HASH = "hash"
    const val HASH_SHA256 = "sha-256"

    // KDF
    const val KDF = "kdf"
    const val KDF_HKDF_SHA256 = "hkdf-sha-256"

    // Binding (previene downgrade despues de autenticacion)
    const val BINDING = "binding"
    const val BIND_NEGOTIATION = "negotiation-hash"
}

/**
 * Resultado de la negociacion entre dos CapabilitySets.
 *
 * @property capabilities capacidades negociadas (interseccion con preferencia local).
 * @property isFailure true si la interseccion esta vacia para capacidades obligatorias.
 * @property failureReason descripcion del fallo si isFailure es true.
 */
data class NegotiationResult(
    val capabilities: Map<String, NegotiatedCapability>,
    val isFailure: Boolean = false,
    val failureReason: String? = null,
) {
    companion object {
        fun failure(reason: String): NegotiationResult =
            NegotiationResult(emptyMap(), isFailure = true, failureReason = reason)
    }
}

/** Una capacidad negociada (seleccionada por interseccion + preferencia local). */
data class NegotiatedCapability(
    val id: String,
    val version: Int,
    val parameters: Map<String, KceValue> = emptyMap(),
    val selectedValue: String? = null,
)