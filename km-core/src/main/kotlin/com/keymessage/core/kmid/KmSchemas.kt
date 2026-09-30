package com.keymessage.core.kmid

/**
 * Esquemas de KM-ID-0001 -- validadores ejecutables de mundo cerrado.
 *
 * Port directo de km-id-reference/schemas.py. El CDDL del RFC es la
 * especificacion sintactica, pero CDDL (RFC 8610) es un lenguaje ABIERTO:
 * no puede expresar "un campo desconocido es un error" ni acotar la longitud
 * de un array. Esa politica es NORMATIVA y vive aqui, no en el CDDL.
 *
 * Un puerto NO debe sustituir esto por kotlinx.serialization con
 * `ignoreUnknownKeys = true` ni por un CBOR configurado: se necesitan
 *.unknown prohibidos, sin tags, sin floats, sin null, sin longitudes
 * indefinidas, sin claves duplicadas, NFC obligatorio y errores
 * diagnosticos especificos. KCE cubre la capa de bytes; esto cubre la forma.
 */
object KmSchemas {

    /**
     * Una declaracion de campo.
     *
     * [requireIf] expresa dependencias del tipo "este campo es obligatorio si
     * `otro` vale `esperado`". Se comprueba en una pasada SEPARADA, despues de
     * la comprobacion de tipos, para que el error de dependencia no tape un
     * error de tipo mas primario.
     */
    class Field(
        val key: String,
        val kind: String,
        val required: Boolean = true,
        val enum: Any? = null,
        val size: Int? = null,
        val maxLen: Int? = null,
        val requireIf: Pair<String, Any?>? = null,
        val maxValue: Long = KmIdConstants.MAX_UINT64,
    )

    class Schema(
        val name: String,
        val docValue: String?,
        val fields: List<Field>,
        val signatureFields: List<String>,
    ) {
        val byKey: Map<String, Field> = fields.associateBy { it.key }
        val allowedKeys: Set<String> = byKey.keys

        /**
         * Payload firmable: la estructura menos TODOS sus campos de firma.
         *
         * Es una RESTRICCION sobre las claves, no una copia. Si la firma
         * estuviera dentro del payload, firmarse a si misma; por eso el
         * payload se construye siempre por exclusion de [signatureFields].
         */
        fun signable(value: Map<String, Any?>): Map<String, Any?> =
            value.filterKeys { it !in signatureFields }
    }

    // ===================================================================
    // Comprobacion de valor
    // ===================================================================

    private fun checkValue(field: Field, value: Any?, where: String) {
        when (field.kind) {
            "bstr32" ->
                if (value !is ByteArray || value.size != KmIdConstants.PUBKEY_BYTES) {
                    throw KmIdSchemaException("FIELD_SIZE", "$where debe ser bstr .size 32")
                }
            "bstr64" ->
                if (value !is ByteArray || value.size != KmIdConstants.SIG_BYTES) {
                    throw KmIdSchemaException("FIELD_SIZE", "$where debe ser bstr .size 64")
                }
            "text" -> {
                if (value !is String || value.isEmpty()) {
                    throw KmIdSchemaException("FIELD_TYPE", "$where debe ser tstr no vacio")
                }
                if (field.size != null && value.toByteArray(Charsets.UTF_8).size > field.size) {
                    throw KmIdSchemaException("FIELD_SIZE", "$where excede ${field.size} bytes")
                }
            }
            "uint" -> {
                // Boolean NO cuenta como uint: en la JVM `Boolean` no es
                // Number, pero la separacion se hace explicita para que
                // anadir un Int no rompa el esquema en silencio.
                if (value !is Long) {
                    throw KmIdSchemaException("FIELD_TYPE", "$where debe ser uint")
                }
                if (value < 0 || value > field.maxValue) {
                    throw KmIdSchemaException("FIELD_TYPE", "$where fuera de rango uint")
                }
            }
            "array" -> {
                if (value !is List<*>) {
                    throw KmIdSchemaException("FIELD_TYPE", "$where debe ser array")
                }
                if (field.maxLen != null && value.size > field.maxLen) {
                    throw KmIdSchemaException("ARRAY_TOO_LONG", "$where excede ${field.maxLen} elementos")
                }
            }
            "map" ->
                if (value !is Map<*, *>) {
                    throw KmIdSchemaException("FIELD_TYPE", "$where debe ser map")
                }
            "doc" ->
                if (value != field.enum) {
                    throw KmIdSchemaException("FIELD_TYPE", "$where debe ser ${field.enum}")
                }
            else -> throw KmIdSchemaException("FIELD_TYPE", "$where tipo interno desconocido ${field.kind}")
        }

        // Se captura en un local porque una propiedad de extension no hace
        // smart-cast: la comprobacion de nulidad no se traslada al segundo uso.
        val enumerado = field.enumSet
        if (enumerado != null && field.kind == "uint" && value !in enumerado) {
            throw KmIdSchemaException("ENUM_VALUE", "$where debe ser uno de ${enumerado.sorted()}")
        }
    }

    /** Los valores admitidos de un enumerado, o null si el campo no es enumerado. */
    private val Field.enumSet: Set<Long>?
        get() = when (val e = enum) {
            is Set<*> -> e.map { (it as Long) }.toSet()
            is Map<*, *> -> e.keys.map { (it as Long) }.toSet()
            else -> null
        }

    // ===================================================================
    // Validacion
    // ===================================================================

    /**
     * Valida una estructura contra su esquema, aplicando mundo cerrado.
     *
     * ORDEN: mapa -> `doc` -> campos desconocidos -> campos requeridos ->
     * tipo y tamano -> dependencias (`requireIf`).
     *
     * Los campos desconocidos se rechazan ANTES de comprobar los requeridos,
     * para que el error que se reporta sea el primario del documento. Sin ese
     * orden, un documento con `version` ausente y un campo desconocido
     * reportaria MISSING_FIELD, que es menos accionable.
     *
     * El orden importa ademas por reproducibilidad: el MANIFEST declara el
     * codigo de error esperado de cada fixture, y este orden es el que lo
     * produce. Alterarlo rompe los vectores negativos.
     */
    fun validate(schema: Schema, value: Any?, path: String = "") {
        if (value !is Map<*, *>) {
            throw KmIdSchemaException("FIELD_TYPE", "${path.ifEmpty { schema.name }} debe ser map")
        }
        val m = value as Map<String, Any?>

        if (schema.docValue != null && m["doc"] != schema.docValue) {
            throw KmIdSchemaException("FIELD_TYPE", "${path}doc debe ser ${schema.docValue}")
        }

        for (key in m.keys) {
            if (key !in schema.allowedKeys) {
                throw KmIdSchemaException("UNKNOWN_FIELD", "$path$key no esta definido en ${schema.name}")
            }
        }

        for (field in schema.fields) {
            if (!m.containsKey(field.key)) {
                if (field.required && field.requireIf == null) {
                    throw KmIdSchemaException("MISSING_FIELD", "$path${field.key} es obligatorio")
                }
                continue
            }
            checkValue(field, m[field.key], "$path.")
        }

        for (field in schema.fields) {
            val dep = field.requireIf ?: continue
            val (otherKey, expected) = dep
            if (m[otherKey] == expected && !m.containsKey(field.key)) {
                throw KmIdSchemaException(
                    "MISSING_FIELD",
                    "$path${field.key} es obligatorio si $otherKey == $expected",
                )
            }
        }
    }

    // ===================================================================
    // Definicion de esquemas
    // ===================================================================

    val DEVICE_ENTRY = Schema(
        "DeviceEntry",
        null,
        listOf(
            Field("deviceId", "bstr32"),
            Field("signingKey", "bstr32"),
            Field("agreementKey", "bstr32"),
            Field("status", "uint", enum = KmIdConstants.DEVICE_STATUS_VALUES),
            Field("addedAt", "uint"),
            Field("name", "text", required = false, size = KmIdConstants.MAX_NAME_BYTES),
            Field("revokedAt", "uint", required = false),
        ),
        signatureFields = emptyList(),
    )

    val DEVICE_ROSTER = Schema(
        "DeviceRoster",
        KmIdConstants.DOC_ROSTER,
        listOf(
            Field("doc", "doc", enum = KmIdConstants.DOC_ROSTER),
            Field("version", "uint"),
            Field("identityRoot", "bstr32"),
            Field("identityId", "bstr32"),
            Field("sequence", "uint"),
            Field("previous", "bstr32"),
            Field("createdAt", "uint"),
            Field("devices", "array", maxLen = KmIdConstants.MAX_DEVICES),
            Field("signature", "bstr64"),
        ),
        signatureFields = listOf("signature"),
    )

    val SIGNED_PREKEY = Schema(
        "SignedPrekey",
        KmIdConstants.DOC_SIGNED_PREKEY,
        listOf(
            Field("doc", "doc", enum = KmIdConstants.DOC_SIGNED_PREKEY),
            Field("version", "uint"),
            Field("deviceId", "bstr32"),
            Field("keyId", "uint"),
            Field("publicKey", "bstr32"),
            Field("createdAt", "uint"),
            Field("expiresAt", "uint"),
            Field("signature", "bstr64"),
        ),
        signatureFields = listOf("signature"),
    )

    val ONETIME_PREKEY = Schema(
        "OneTimePrekey",
        KmIdConstants.DOC_ONETIME_PREKEY,
        listOf(
            Field("doc", "doc", enum = KmIdConstants.DOC_ONETIME_PREKEY),
            Field("version", "uint"),
            Field("deviceId", "bstr32"),
            Field("keyId", "uint"),
            Field("publicKey", "bstr32"),
        ),
        signatureFields = emptyList(),
    )

    val ENDPOINT_HINT = Schema(
        "EndpointHint",
        null,
        listOf(
            Field("type", "uint", enum = KmIdConstants.ENDPOINT_TYPE_VALUES),
            Field("value", "text", size = KmIdConstants.MAX_ENDPOINT_VALUE_BYTES),
            Field("observedAt", "uint"),
            Field("relayId", "bstr32", required = false),
        ),
        signatureFields = emptyList(),
    )

    val CONTACT_BUNDLE = Schema(
        "ContactBundle",
        KmIdConstants.DOC_CONTACT_BUNDLE,
        listOf(
            Field("doc", "doc", enum = KmIdConstants.DOC_CONTACT_BUNDLE),
            Field("version", "uint"),
            Field("kind", "uint", enum = KmIdConstants.BUNDLE_KIND_VALUES),
            Field("createdAt", "uint"),
            Field("identityRoot", "bstr32"),
            Field("identityId", "bstr32"),
            Field("roster", "map"),
            Field("subjectDeviceId", "bstr32"),
            Field("subjectSigningKey", "bstr32"),
            Field("subjectAgreementKey", "bstr32"),
            Field("signedPrekey", "map"),
            Field("oneTimePrekeys", "array", maxLen = KmIdConstants.MAX_ONE_TIME_PREKEYS),
            Field("capabilities", "uint", maxValue = KmIdConstants.MAX_CAPABILITIES_UINT),
            Field("signature", "bstr64"),
            Field("expiresAt", "uint", required = false),
            Field("endpoints", "array", required = false, maxLen = KmIdConstants.MAX_ENDPOINTS),
            Field(
                "linkSecret", "bstr32", required = false,
                requireIf = Pair("kind", KmIdConstants.KIND_DEVICE_LINK),
            ),
        ),
        signatureFields = listOf("signature"),
    )
}
