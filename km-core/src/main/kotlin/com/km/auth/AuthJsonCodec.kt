package com.km.auth

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode

/**
 * Codecs JSON de los mensajes wire de autenticacion (KM-0002 seccion 10).
 *
 * 3F.4 -- PRINCIPIO DE LOS BYTES FIRMADOS
 *
 * JSON NO es la unidad canonicamente firmada. La firma se calcula siempre
 * sobre el transcript de bytes (TranscriptBuilder), construido a partir de
 * los campos binarios ya parseados (nonce/publicKey/signature en crudo y
 * los ids hex en ASCII). Reserializar el JSON e intentar firmarlo seria una
 * canonicalizacion no definida por el RFC y esta prohibido por diseno:
 * reordenar claves o formatear el JSON NO puede alterar la firma.
 *
 * Politica de parseo:
 * - Campos MUST presentes y con el tipo correcto -> si no, IllegalArgumentException.
 * - Desconocidos: se ignoran (extensions MAY), pero jamas participan de
 *   la firma.
 * - Los bytes van en Base64URL estricto (Base64Url) o hex ASCII (ids).
 */
object AuthJsonCodec {

    private val mapper = ObjectMapper()

    // --- Challenge ---------------------------------------------------------

    fun challengeWire(challenge: AuthChallenge): String {
        val node = mapper.createObjectNode()
        node.put("nonce", Base64Url.encode(challenge.nonce))
        node.put("timestamp", challenge.timestampMillis)
        node.put("version", challenge.version)
        node.put("responderIdentityId", challenge.responderIdentityId)
        return mapper.writeValueAsString(node)
    }

    fun parseChallenge(wire: String): AuthChallenge {
        val n = requireObject(wire)
        return AuthChallenge(
            nonce = bytes(n, "nonce"),
            timestampMillis = longField(n, "timestamp"),
            version = intField(n, "version"),
            responderIdentityId = textField(n, "responderIdentityId"),
        )
    }

    // --- Response ----------------------------------------------------------

    fun responseWire(response: AuthResponse): String {
        val node = mapper.createObjectNode()
        node.put("identityId", response.identityId)
        node.put("publicKey", Base64Url.encode(response.publicKey))
        node.put("signature", Base64Url.encode(response.signature))
        node.put("protocolVersion", response.protocolVersion)
        return mapper.writeValueAsString(node)
    }

    fun parseResponse(wire: String): AuthResponse {
        val n = requireObject(wire)
        return AuthResponse(
            identityId = textField(n, "identityId"),
            publicKey = bytes(n, "publicKey"),
            signature = bytes(n, "signature"),
            protocolVersion = intField(n, "protocolVersion"),
        )
    }

    // --- AUTH_OK -----------------------------------------------------------

    fun authOkWire(authOk: AuthOk): String {
        val node = mapper.createObjectNode()
        node.put("sessionId", authOk.sessionId)
        node.put("serverNonce", Base64Url.encode(authOk.serverNonce))
        node.put("publicKey", Base64Url.encode(authOk.responderPublicKey))
        node.put("signature", Base64Url.encode(authOk.signature))
        return mapper.writeValueAsString(node)
    }

    fun parseAuthOk(wire: String): AuthOk {
        val n = requireObject(wire)
        return AuthOk(
            sessionId = textField(n, "sessionId"),
            serverNonce = bytes(n, "serverNonce"),
            responderPublicKey = bytes(n, "publicKey"),
            signature = bytes(n, "signature"),
        )
    }

    // --- helpers ----------------------------------------------------------

    private fun requireObject(wire: String): JsonNode {
        val node = mapper.readTree(wire)
        require(node != null && node.isObject) { "mensaje de autenticacion debe ser un objeto JSON" }
        return node
    }

    private fun removePaddingIfAny(text: String): String = text

    private fun bytes(n: JsonNode, field: String): ByteArray {
        val value = textField(n, field)
        val decoded = Base64Url.decode(value)
        return decoded
    }

    private fun textField(n: JsonNode, field: String): String {
        val v = n.get(field)
        require(v != null && v.isTextual) { "campo '$field' debe ser un string presente" }
        return v.asText()
    }

    private fun longField(n: JsonNode, field: String): Long {
        val v = n.get(field)
        require(v != null && v.isIntegralNumber) { "campo '$field' debe ser un entero presente" }
        return v.asLong()
    }

    private fun intField(n: JsonNode, field: String): Int {
        val v = n.get(field)
        require(v != null && v.isIntegralNumber) { "campo '$field' debe ser un entero presente" }
        return v.asInt()
    }

    /** Solo para tests: un objeto JSON con el mismo contenido reordenado. */
    fun reorder(wire: String): String {
        val req = requireObject(wire)
        val out: ObjectNode = mapper.createObjectNode()
        val names = req.fieldNames().asSequence().toList().reversed()
        for (name in names) out.set<JsonNode>(name, req.get(name))
        return mapper.writeValueAsString(out)
    }
}