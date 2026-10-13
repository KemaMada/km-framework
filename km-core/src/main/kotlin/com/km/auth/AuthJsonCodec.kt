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
        // El dialecto SIEMPRE viaja como string (R34). El `version` numerico de
        // KM-0002 no se emite aqui.
        node.put("protocolVersion", response.protocolVersion.wire)
        return mapper.writeValueAsString(node)
    }

    fun parseResponse(wire: String): AuthResponse {
        val n = requireObject(wire)
        return AuthResponse(
            identityId = textField(n, "identityId"),
            publicKey = bytes(n, "publicKey"),
            signature = bytes(n, "signature"),
            protocolVersion = dialectField(n, "protocolVersion"),
        )
    }

    // --- AUTH_OK -----------------------------------------------------------

    /**
     * Campos canonicos del `AUTH_OK` (R15/R16), con las MISMAS claves que el
     * codec del daemon `JsonWireCodec.authOk`: `initiatorIdentityId`,
     * `responderIdentityId`, `sessionId`, `serverNonce`, `responderPublicKey`,
     * `serverSignature`. Sin `identityId`, `publicKey` ni `signature` (nombres
     * retirados).
     *
     * R15: los dos ids canonicos son REQUERIDOS. Aqui se exige que no vengan
     * VACIOS (un id "" se serializaba antes); la forma canonica (64 hex) NO se
     * valida en este codec: la garantiza el emisor del cable.
     */
    fun authOkWire(authOk: AuthOk): String {
        require(authOk.initiatorIdentityId.isNotEmpty()) {
            "campo 'initiatorIdentityId' no puede ser vacio al serializar el AUTH_OK (R15)"
        }
        require(authOk.responderIdentityId.isNotEmpty()) {
            "campo 'responderIdentityId' no puede ser vacio al serializar el AUTH_OK (R15)"
        }
        val node = mapper.createObjectNode()
        node.put("initiatorIdentityId", authOk.initiatorIdentityId)
        node.put("responderIdentityId", authOk.responderIdentityId)
        node.put("sessionId", authOk.sessionId)
        node.put("serverNonce", Base64Url.encode(authOk.serverNonce))
        node.put("responderPublicKey", Base64Url.encode(authOk.responderPublicKey))
        node.put("serverSignature", Base64Url.encode(authOk.signature))
        return mapper.writeValueAsString(node)
    }

    /**
     * Parsea un `AUTH_OK` con la tabla canonica de campos (R15/R16).
     *
     * Los dos ids canonicos (`initiatorIdentityId`, `responderIdentityId`) son
     * OBLIGATORIOS y NO VACIOS: ausentes, de tipo no-string o vacios lanzan
     * [IllegalArgumentException]. La forma canonica (64 hex) no se comprueba
     * aqui.
     *
     * Este metodo es ESTRICTO en el nombre del iniciador: lee solo
     * `initiatorIdentityId` y no admite el alias legacy. La lectura que SI
     * aplica las reglas del dialecto (aceptar el alias en `2.0` y retirarlo
     * —en cualquier forma— en `2.1`) es [parseAuthOkForDialect].
     */
    fun parseAuthOk(wire: String): AuthOk {
        val n = requireObject(wire)
        return AuthOk(
            sessionId = textField(n, "sessionId"),
            serverNonce = bytes(n, "serverNonce"),
            responderPublicKey = bytes(n, "responderPublicKey"),
            signature = bytes(n, "serverSignature"),
            initiatorIdentityId = requiredTextField(n, "initiatorIdentityId"),
            responderIdentityId = requiredTextField(n, "responderIdentityId"),
        )
    }

    /**
     * Parsea un `AUTH_OK` aplicando la transicion del alias `identityId`
     * (task 1.3, R2/R3) para el [dialect] del handshake.
     *
     * A diferencia de [parseAuthOk], esta lectura:
     *  - acepta `identityId` como ALIAS del iniciador SOLO en dialecto `2.0`
     *    (en solitario, o junto al canonico si ambos son IGUALES);
     *  - en `2.1` rechaza el alias en CUALQUIER forma (solo, o junto al
     *    canonico, con valor igual o distinto) con `UNKNOWN_PROTOCOL`: el
     *    nombre esta retirado en ese dialecto (ruling del owner);
     *  - en `2.0`, si los dos campos conviven y DIFIEREN, rechaza con
     *    `MALFORMED_MESSAGE`.
     *
     * Un rechazo de campo (alias retirado o conflicto) se devuelve como
     * [AuthOkFields.Rejected] con su codigo, no como excepcion: es una decision
     * del dialecto, no un JSON ilegible. Un JSON que no sea objeto, o un campo
     * base mal formado (sessionId/serverNonce/responderPublicKey/serverSignature
     * ausentes o del tipo equivocado), sigue lanzando [IllegalArgumentException].
     */
    fun parseAuthOkForDialect(wire: String, dialect: Dialect): AuthOkFields {
        val n = requireObject(wire)
        val canonical = optionalTextField(n, "initiatorIdentityId")
        val legacy = optionalTextField(n, "identityId")
        val initiator = when (val resolved = AuthOkAliasRules.resolve(dialect, canonical, legacy)) {
            is AuthOkInitiator.Rejected -> return AuthOkFields.Rejected(resolved.error, resolved.detail)
            is AuthOkInitiator.Accepted -> resolved.identityId
        }
        return AuthOkFields.Accepted(
            AuthOk(
                sessionId = textField(n, "sessionId"),
                serverNonce = bytes(n, "serverNonce"),
                responderPublicKey = bytes(n, "responderPublicKey"),
                signature = bytes(n, "serverSignature"),
                initiatorIdentityId = initiator,
                responderIdentityId = requiredTextField(n, "responderIdentityId"),
            )
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

    /**
     * Como [textField] pero devuelve `null` si el campo NO esta. Un campo
     * presente pero de tipo no-string sigue siendo un fallo estructural.
     *
     * Lo usa la lectura dialectal del iniciador ([parseAuthOkForDialect]): el
     * alias legacy puede faltar o estar, y la regla del dialecto decide.
     */
    private fun optionalTextField(n: JsonNode, field: String): String? {
        val v = n.get(field) ?: return null
        require(v.isTextual) { "campo '$field', si esta presente, debe ser un string" }
        return v.asText()
    }

    /**
     * Como [textField], pero ademas rechaza la cadena VACIA.
     *
     * Lo usan los ids canonicos del `AUTH_OK`, que R15 declara requeridos: un
     * id "" no es un id valido y antes se colaba. No valida 64 hex (forma
     * canonica): eso es responsabilidad del emisor del cable.
     */
    private fun requiredTextField(n: JsonNode, field: String): String {
        val value = textField(n, field)
        require(value.isNotEmpty()) { "campo '$field' debe ser un string no vacio" }
        return value
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

    /**
     * Campo dialecto: un string JSON que mapea a un [Dialect] conocido.
     *
     * Un valor ausente, de tipo no-string o que no corresponde a ningun
     * dialecto implementado falla cerrado (R34): el dialecto nunca es un numero.
     */
    private fun dialectField(n: JsonNode, field: String): Dialect {
        val v = n.get(field)
        require(v != null && v.isTextual) { "campo '$field' debe ser un string dialecto presente" }
        return Dialect.fromWireOrNull(v.asText())
            ?: throw IllegalArgumentException("campo '$field' no es un dialecto conocido: '${v.asText()}'")
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