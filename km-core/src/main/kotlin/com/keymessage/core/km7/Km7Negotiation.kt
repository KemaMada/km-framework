package com.keymessage.core.km7

/**
 * Logica de negociacion KM-0007.
 *
 * ALGORITMO
 *
 *   Para cada categoria de capacidad (serialization, compression, crypto),
 *   se realiza la interseccion de los valores soportados por ambas partes.
 *   De la interseccion, el lado LOCAL selecciona segun su orden de preferencia.
 *
 *   La seleccion es DETERMINISTA: dados dos CapabilitySets identicos y el
 *   mismo orden de preferencia local, el resultado es identico.
 */
object Km7Negotiation {

    /**
     * Negocia capacidades entre local y peer.
     *
     * @param local capacidades soportadas localmente (ordenadas por preferencia).
     * @param peer capacidades anunciadas por el peer.
     * @param requiredCategories conjunto de IDs de capacidad que son obligatorias.
     *        Si la interseccion esta vacia para alguna categoria obligatoria,
     *        el resultado es failure.
     * @return NegotiationResult con las capacidades seleccionadas.
     */
    fun negotiate(
        local: CapabilitySet,
        peer: CapabilitySet,
        requiredCategories: Set<String> = emptySet(),
    ): NegotiationResult {
        val result = mutableMapOf<String, NegotiatedCapability>()

        for (localCap in local.capabilities) {
            val peerCap = peer.find(localCap.id) ?: continue

            // Interseccion de versiones: seleccionar la version mas alta
            // que ambas partes soporten
            val selectedVersion = minOf(localCap.version, peerCap.version)

            // Fusion de parametros: locales primero, del peer complementan
            val mergedParams = LinkedHashMap(localCap.parameters)
            for ((k, v) in peerCap.parameters) {
                if (k !in mergedParams) {
                    mergedParams[k] = v
                }
            }

            result[localCap.id] = NegotiatedCapability(
                id = localCap.id,
                version = selectedVersion,
                parameters = mergedParams,
            )
        }

        // Verificar categorias obligatorias
        for (required in requiredCategories) {
            if (required !in result) {
                return NegotiationResult.failure(
                    "categoria obligatoria '$required' no negociable: " +
                        "local=${local.has(required)}, peer=${peer.has(required)}"
                )
            }
        }

        return NegotiationResult(result)
    }

    /**
     * Version simplificada para negociacion de un unico valor dentro de
     * una categoria (ej: algoritmo de compression).
     *
     * @param localValues valores locales ordenados por preferencia (primero = mas preferido).
     * @param peerValues valores soportados por el peer.
     * @return el primer valor local que el peer tambien soporta, o null.
     */
    fun selectFirstCommon(
        localValues: List<String>,
        peerValues: Set<String>,
    ): String? = localValues.firstOrNull { it in peerValues }

    /**
     * Interseccion completa: devuelve los valores comunes preservando
     * el orden local.
     */
    fun intersection(
        localValues: List<String>,
        peerValues: Set<String>,
    ): List<String> = localValues.filter { it in peerValues }

    /**
     * Genera un hash de la negociacion para binding post-autenticacion.
     * Esto previene downgrade: si un atacante manipula la negociacion,
     * el hash no coincidira con el esperado.
     */
    fun negotiationHash(result: NegotiationResult): ByteArray {
        val sb = StringBuilder()
        result.capabilities.entries.sortedBy { it.key }.forEach { (id, cap) ->
            sb.append("$id:${cap.version}")
        }
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        digest.update(sb.toString().encodeToByteArray())
        return digest.digest()
    }
}