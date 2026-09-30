package com.km.model

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ArrayNode
import com.fasterxml.jackson.databind.node.ObjectNode
import com.km.identity.KmIds
import java.util.UUID

/**
 * Codec JSON deterministico para NodeAnnouncement (KM-0005 §9.4).
 *
 * PRINCIPIOS
 *
 *   1. La serializacion es DETERMINISTA: campos en orden fijo, sin
 *      whitespace, formato canonico de numeros.
 *
 *   2. La firma se verifica CONTRA LOS BYTES DE [signableJson], que es
 *      el JSON sin el campo `signature`. Esto garantiza que el creador
 *      y el verificador usan los mismos bytes firmados.
 *
 *   3. [encode] incluye signature. [signableJson] NO.
 *
 *   4. El decode es tolerante al orden de campos (JSON no es canonico
 *      por si mismo), pero la serializacion es estricta.
 *
 *   5. `nodeId` y `publicKey` se verifican mutuamente: publicKey se
 *      incluye en el JSON firmado, y nodeId debe derivar de ella.
 *
 * ARCHITECTURE: identityId != publicKey
 *
 *   - `nodeId` es un IdentityId (64 hex chars, 32 bytes)
 *   - `publicKey` es una clave Ed25519 de 32 bytes
 *   - nodeId = SHA-256("KM-ID-IDENTITY" || publicKey)
 *   - publicKey NUNCA se usa como nodeId, y viceversa
 */
object NodeAnnouncementJsonCodec {

    private val mapper = ObjectMapper()

    // Orden canonico de campos (sorted)
    private val FIELD_ORDER = listOf(
        "messageId",
        "timestamp",
        "protocolVersion",
        "nodeId",
        "publicKey",
        "nodeName",
        "endpoints",
        "capabilities",
        "limits",
    )

    private const val FIELD_SIGNATURE = "signature"

    // ===================================================================
    // API publica
    // ===================================================================

    /**
     * Codifica un NodeAnnouncement a JSON bytes.
     * Incluye el campo signature.
     */
    fun encode(ann: NodeAnnouncement): ByteArray {
        val root = mapper.createObjectNode()
        root.put("messageId", ann.messageId.value.toString())
        root.put("timestamp", ann.timestamp)
        root.put("protocolVersion", ann.protocolVersion)
        root.put("nodeId", ann.identity.nodeId.value)
        root.put("publicKey", bytesToBase64Url(ann.identity.publicKey))
        if (ann.identity.nodeName != null) {
            root.put("nodeName", ann.identity.nodeName)
        }

        // Endpoints
        val endpoints = root.putArray("endpoints")
        for (ep in ann.endpoints) {
            val eo = mapper.createObjectNode()
            eo.put("transport", ep.transport)
            eo.put("address", ep.address)
            endpoints.add(eo)
        }

        // Capabilities
        val caps = root.putArray("capabilities")
        for (cap in ann.capabilities) {
            caps.add(cap.name)
        }

        // Limits (optional)
        if (ann.limits != null) {
            val lim = mapper.createObjectNode()
            lim.put("maxMessageSize", ann.limits.maxMessageSize)
            lim.put("maxStoredMessages", ann.limits.maxStoredMessages)
            lim.put("maxStorageBytes", ann.limits.maxStorageBytes)
            lim.put("maxMessageAgeMs", ann.limits.maxMessageAgeMs)
            lim.put("maxConnections", ann.limits.maxConnections)
            root.replace("limits", lim)
        }

        // Signature SIEMPRE al final
        root.put(FIELD_SIGNATURE, bytesToBase64Url(ann.signature))

        return serializeWithCanonicalOrder(root)
    }

    /**
     * Decodifica un NodeAnnouncement desde JSON bytes.
     * Tolerante al orden de campos.
     *
     * @throws NodeAnnouncementCodecException si el JSON es invalido.
     */
    fun decode(jsonBytes: ByteArray): NodeAnnouncement {
        val root = try {
            mapper.readTree(jsonBytes)
        } catch (e: Exception) {
            throw NodeAnnouncementCodecException("JSON_MALFORMED", "JSON malformado: ${e.message}")
        }

        if (root !is ObjectNode) {
            throw NodeAnnouncementCodecException("INVALID_TYPE", "la raiz debe ser un objeto JSON")
        }

        // Campos obligatorios
        val messageId = getTextField(root, "messageId")
        val timestamp = getLongField(root, "timestamp")
        val protocolVersion = getTextField(root, "protocolVersion")
        val nodeIdHex = getTextField(root, "nodeId")
        val publicKeyB64 = getTextField(root, "publicKey")
        val signatureB64 = getTextField(root, FIELD_SIGNATURE)

        // Campos opcionales
        val nodeName = root.get("nodeName")?.asText()

        // Validar IDs y claves
        validateNodeId(nodeIdHex)
        val publicKey = try {
            base64UrlToBytes(publicKeyB64, 32, "publicKey")
        } catch (e: IllegalArgumentException) {
            throw NodeAnnouncementCodecException("INVALID_PUBLIC_KEY", "publicKey invalida: ${e.message}")
        }
        validateSignature(signatureB64)
        val signature = try {
            base64UrlToBytes(signatureB64, 64, "signature")
        } catch (e: IllegalArgumentException) {
            throw NodeAnnouncementCodecException("INVALID_SIGNATURE", "signature invalida: ${e.message}")
        }

        // Verificar que nodeId deriva de publicKey
        val expectedId = KmIds.identityId(publicKey).toHexString()
        require(nodeIdHex == expectedId) {
            "nodeId '$nodeIdHex' no coincide con SHA-256(\"KM-ID-IDENTITY\" || publicKey): esperado '$expectedId'"
        }

        // Endpoints
        val endpoints = mutableListOf<NodeEndpoint>()
        val endpointsNode = root.get("endpoints")
        if (endpointsNode != null) {
            for (en in endpointsNode) {
                val transport = en["transport"]?.asText() ?: throw NodeAnnouncementCodecException("MISSING_FIELD", "endpoint.transport ausente")
                val address = en["address"]?.asText() ?: throw NodeAnnouncementCodecException("MISSING_FIELD", "endpoint.address ausente")
                endpoints.add(NodeEndpoint(transport, address))
            }
        }

        // Capabilities
        val capabilities = mutableSetOf<NodeCapability>()
        val capsNode = root.get("capabilities")
        if (capsNode != null) {
            for (cap in capsNode) {
                val capName = cap.asText()
                val capability = try {
                    NodeCapability.valueOf(capName)
                } catch (_: IllegalArgumentException) {
                    throw NodeAnnouncementCodecException("UNKNOWN_CAPABILITY", "capacidad desconocida: '$capName'")
                }
                capabilities.add(capability)
            }
        }

        // Limits (optional)
        val limits: RelayLimits? = if (root.has("limits")) {
            val lim = root["limits"]
            RelayLimits(
                maxMessageSize = lim["maxMessageSize"]?.asLong() ?: RelayLimits().maxMessageSize,
                maxStoredMessages = lim["maxStoredMessages"]?.asInt() ?: RelayLimits().maxStoredMessages,
                maxStorageBytes = lim["maxStorageBytes"]?.asLong() ?: RelayLimits().maxStorageBytes,
                maxMessageAgeMs = lim["maxMessageAgeMs"]?.asLong() ?: RelayLimits().maxMessageAgeMs,
                maxConnections = lim["maxConnections"]?.asInt() ?: RelayLimits().maxConnections,
            )
        } else null

        val identity = NodeIdentity(
            nodeId = IdentityId(nodeIdHex),
            publicKey = publicKey,
            nodeName = nodeName,
        )

        return NodeAnnouncement(
            messageId = MessageId(try {
                UUID.fromString(messageId)
            } catch (_: IllegalArgumentException) {
                throw NodeAnnouncementCodecException("INVALID_MESSAGE_ID", "messageId invalido: '$messageId'")
            }),
            timestamp = timestamp,
            protocolVersion = protocolVersion,
            identity = identity,
            endpoints = endpoints,
            capabilities = capabilities,
            limits = limits,
            signature = signature,
        )
    }

    /**
     * Produce el JSON firmable (sin signature) en formato canonico.
     * El creador firma estos bytes; el verificador los reconstruye.
     */
    fun signableJson(ann: NodeAnnouncement): ByteArray {
        val root = mapper.createObjectNode()
        root.put("messageId", ann.messageId.value.toString())
        root.put("timestamp", ann.timestamp)
        root.put("protocolVersion", ann.protocolVersion)
        root.put("nodeId", ann.identity.nodeId.value)
        root.put("publicKey", bytesToBase64Url(ann.identity.publicKey))
        if (ann.identity.nodeName != null) {
            root.put("nodeName", ann.identity.nodeName)
        }

        val endpoints = root.putArray("endpoints")
        for (ep in ann.endpoints) {
            val eo = mapper.createObjectNode()
            eo.put("transport", ep.transport)
            eo.put("address", ep.address)
            endpoints.add(eo)
        }

        val caps = root.putArray("capabilities")
        for (cap in ann.capabilities) {
            caps.add(cap.name)
        }

        if (ann.limits != null) {
            val lim = mapper.createObjectNode()
            lim.put("maxMessageSize", ann.limits.maxMessageSize)
            lim.put("maxStoredMessages", ann.limits.maxStoredMessages)
            lim.put("maxStorageBytes", ann.limits.maxStorageBytes)
            lim.put("maxMessageAgeMs", ann.limits.maxMessageAgeMs)
            lim.put("maxConnections", ann.limits.maxConnections)
            root.replace("limits", lim)
        }

        return serializeWithCanonicalOrder(root)
    }

    // ===================================================================
    // Internal
    // ===================================================================

    /**
     * Serializa en orden canonico: FIELD_ORDER primero, sin whitespace,
     * y EXCLUYE el campo signature (para que los firmantes y verificadores
     * usen exactamente los mismos bytes).
     */
    private fun serializeWithCanonicalOrder(root: ObjectNode): ByteArray {
        val sb = StringBuilder()
        sb.append('{')

        var first = true
        // Primero los campos en orden canonico
        for (field in FIELD_ORDER) {
            val value = root.get(field) ?: continue
            if (!first) sb.append(',')
            first = false
            sb.append('"')
            sb.append(field)
            sb.append('"')
            sb.append(':')
            sb.append(value.toString()) // Jackson serializa sin whitespace
        }

        // Luego el campo signature (si existe y no esta en FIELD_ORDER)
        val sig = root.get(FIELD_SIGNATURE)
        if (sig != null && FIELD_SIGNATURE !in FIELD_ORDER) {
            if (!first) sb.append(',')
            sb.append('"')
            sb.append(FIELD_SIGNATURE)
            sb.append('"')
            sb.append(':')
            sb.append(sig.toString())
        }

        sb.append('}')
        return sb.toString().encodeToByteArray()
    }

    private fun getTextField(root: ObjectNode, field: String): String {
        val node = root.get(field)
            ?: throw NodeAnnouncementCodecException("MISSING_FIELD", "campo obligatorio '$field' ausente")
        if (!node.isTextual) {
            throw NodeAnnouncementCodecException("FIELD_TYPE", "campo '$field' debe ser texto")
        }
        return node.asText()
    }

    private fun getLongField(root: ObjectNode, field: String): Long {
        val node = root.get(field)
            ?: throw NodeAnnouncementCodecException("MISSING_FIELD", "campo obligatorio '$field' ausente")
        if (!node.isNumber) {
            throw NodeAnnouncementCodecException("FIELD_TYPE", "campo '$field' debe ser numero")
        }
        return node.asLong()
    }

    private fun validateNodeId(hex: String) {
        require(hex.length == 64) { "nodeId debe tener 64 caracteres hex, tiene ${hex.length}" }
        require(hex.all { it in Constants.HEX_CHARS }) { "nodeId contiene caracteres no hex" }
    }

    private fun validateSignature(b64: String) {
        // Validar que es base64url sin padding
        require(b64.isNotEmpty()) { "signature no puede estar vacia" }
        require(b64.all { it in Constants.B64URL_CHARS }) { "signature contiene caracteres no base64url" }
        // 64 bytes → 86 caracteres base64url sin padding (ceil(64*4/3) = 86)
        require(b64.length == 86) { "signature debe tener 86 caracteres base64url, tiene ${b64.length}" }
    }

    private object Constants {
        val HEX_CHARS = "0123456789abcdef".toSet()
        val B64URL_CHARS = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_".toSet()
    }

    private fun bytesToBase64Url(bytes: ByteArray): String =
        java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)

    private fun base64UrlToBytes(b64: String, expectedBytes: Int, label: String): ByteArray {
        val bytes = try {
            java.util.Base64.getUrlDecoder().decode(b64)
        } catch (_: IllegalArgumentException) {
            throw NodeAnnouncementCodecException("INVALID_BASE64URL", "$label: base64url invalido")
        }
        require(bytes.size == expectedBytes) {
            "$label debe tener $expectedBytes bytes, tiene ${bytes.size}"
        }
        return bytes
    }
}

class NodeAnnouncementCodecException(
    val code: String,
    override val message: String,
) : Exception("$code: $message")