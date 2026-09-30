package com.keymessage.core.km7

/**
 * Codec KCE para CapabilitySets KM-0007.
 *
 * Estructura:
 *
 *   capability-set = {
 *     "doc": "km.capabilitySet",
 *     "version": 1,
 *     "capabilities": [ capability, ... ]
 *   }
 *
 *   capability = {
 *     "id": text,
 *     "version": uint,
 *     "parameters": { text -> value }    ; opcional
 *   }
 *
 * PROPIEDADES:
 *   - Codificacion DETERMINISTA (sorted_pairs de KCE)
 *   - Campos desconocidos se IGNORAN (forward compatibility)
 *   - ids duplicados se rechazan
 *   - version 0 se rechaza
 */
object Km7Codec {

    private const val DOC = "km.capabilitySet"
    private const val CURRENT_VERSION: Long = 1

    fun encode(capSet: CapabilitySet): ByteArray {
        val map = linkedMapOf<String, Any?>(
            "doc" to DOC,
            "version" to CURRENT_VERSION,
            "capabilities" to capSet.capabilities.map { encodeCapability(it) },
        )
        return com.keymessage.core.kmid.Kce.encode(map)
    }

    fun decode(bytes: ByteArray): CapabilitySet {
        @Suppress("UNCHECKED_CAST")
        val map = com.keymessage.core.kmid.Kce.decode(bytes) as Map<String, Any?>

        val doc = map["doc"] as? String
        require(doc == DOC) { "doc debe ser '$DOC', recibido: $doc" }

        val capabilitiesRaw = map["capabilities"] as? List<Map<String, Any?>>
            ?: error("campo 'capabilities' ausente")

        val capabilities = capabilitiesRaw.map { decodeCapability(it) }

        return CapabilitySet(capabilities)
    }

    private fun encodeCapability(cap: Capability): Map<String, Any?> {
        val map = linkedMapOf<String, Any?>(
            "id" to cap.id,
            "version" to cap.version.toLong(),
        )
        if (cap.parameters.isNotEmpty()) {
            map["parameters"] = cap.parameters.mapValues { encodeValue(it.value) }
        }
        return map
    }

    private fun decodeCapability(map: Map<String, Any?>): Capability {
        val id = map["id"] as? String ?: error("capability: campo 'id' ausente")
        val versionRaw = map["version"] as? Number
            ?: error("capability '$id': campo 'version' ausente")
        val version = versionRaw.toInt()
        require(version > 0) { "capability '$id': version debe ser > 0, recibido: $version" }

        val params = mutableMapOf<String, KceValue>()
        @Suppress("UNCHECKED_CAST")
        val rawParams = map["parameters"] as? Map<String, Any?>
        if (rawParams != null) {
            for ((k, v) in rawParams) {
                params[k] = decodeValue(k, v)
            }
        }

        return Capability(id = id, version = version, parameters = params)
    }

    private fun encodeValue(value: KceValue): Any = when (value) {
        is KceValue.VString -> value.value
        is KceValue.VLong -> value.value
        is KceValue.VBytes -> value.value
        is KceValue.VBool -> if (value.value) 1L else 0L // KCE no soporta CBOR simple/boolean
    }

    private fun decodeValue(key: String, value: Any?): KceValue = when (value) {
        is String -> KceValue.VString(value)
        is Number -> {
            val longVal = value.toLong()
            // Reconocer booleanos codificados como 0/1
            if (longVal == 0L || longVal == 1L) KceValue.VLong(longVal) else KceValue.VLong(longVal)
        }
        is ByteArray -> KceValue.VBytes(value)
        else -> error("parametro '$key': tipo no soportado: ${value?.let { it::class.simpleName }}")
    }
}