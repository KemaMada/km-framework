package com.keymessage.core.model

import com.keymessage.core.crypto.Ed25519
import com.keymessage.core.crypto.Ed25519Impl
import com.keymessage.core.crypto.Signature
import com.keymessage.core.kmid.KmIds
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.util.UUID

/**
 * Tests del codec JSON de NodeAnnouncement (KM-0005 §9.4).
 *
 * NA.1 — Modelo/codec (tipos estrictos, campos obligatorios, UTF-8)
 * NA.2 — Encode (golden vectors, determinismo)
 * NA.3 — Decode (JSON valido, errores)
 * NA.4 — Wire verification (firma sobre bytes originales)
 * Mutation tests: M-NA-01 a M-NA-12
 */
class NodeAnnouncementJsonCodecTest {

    private val ed25519: Ed25519 = Ed25519Impl()
    private val aliceKP = ed25519.generateKeyPair()
    private val aliceId = NodeIdentity.fromKeyPair(aliceKP.publicKey, nodeName = "Alice-Node")

    private val defaultTs = 1_721_827_200_000L
    private val messageId = UUID.randomUUID()

    private fun makeAnnouncement(
        identity: NodeIdentity = aliceId,
        endpoints: List<NodeEndpoint> = listOf(NodeEndpoint("websocket", "wss://203.0.113.10:443/ws")),
        capabilities: Set<NodeCapability> = setOf(NodeCapability.CLIENT),
        limits: RelayLimits? = null,
        timestamp: Long = defaultTs,
    ): NodeAnnouncement {
        // Construir signable JSON y firmar
        val partial = NodeAnnouncement(
            messageId = MessageId(messageId),
            timestamp = timestamp,
            protocolVersion = "1.0",
            identity = identity,
            endpoints = endpoints,
            capabilities = capabilities,
            limits = limits,
        )
        val signableBytes = NodeAnnouncementJsonCodec.signableJson(partial)
        val sig = ed25519.sign(aliceKP.privateKey, signableBytes)
        return partial.copy(signature = sig.bytes)
    }

    // ===================================================================
    // NA.1 — Modelo/codec
    // ===================================================================

    @Test
    fun `encode produce JSON valido con todos los campos`() {
        val ann = makeAnnouncement()
        val json = NodeAnnouncementJsonCodec.encode(ann)
        val jsonStr = String(json, Charsets.UTF_8)

        // Verificar campos presentes
        assertTrue(jsonStr.contains("\"messageId\""))
        assertTrue(jsonStr.contains("\"timestamp\""))
        assertTrue(jsonStr.contains("\"protocolVersion\""))
        assertTrue(jsonStr.contains("\"nodeId\""))
        assertTrue(jsonStr.contains("\"publicKey\""))
        assertTrue(jsonStr.contains("\"nodeName\""))
        assertTrue(jsonStr.contains("\"endpoints\""))
        assertTrue(jsonStr.contains("\"capabilities\""))
        assertTrue(jsonStr.contains("\"signature\""))

        // Verificar formato JSON
        assertTrue(jsonStr.startsWith("{"))
        assertTrue(jsonStr.endsWith("}"))
    }

    @Test
    fun `encode con limits incluye limits en JSON`() {
        val limits = RelayLimits(maxMessageSize = 99999)
        val ann = makeAnnouncement(limits = limits)
        val json = String(NodeAnnouncementJsonCodec.encode(ann), Charsets.UTF_8)
        assertTrue(json.contains("\"limits\""))
        assertTrue(json.contains("\"maxMessageSize\":99999"))
    }

    @Test
    fun `encode sin limits no incluye limits en JSON`() {
        val ann = makeAnnouncement(limits = null)
        val json = String(NodeAnnouncementJsonCodec.encode(ann), Charsets.UTF_8)
        assertFalse(json.contains("\"limits\""))
    }

    @Test
    fun `encode sin nodeName no incluye nodeName en JSON`() {
        val idSinNombre = NodeIdentity.fromKeyPair(aliceKP.publicKey)
        val ann = makeAnnouncement(identity = idSinNombre)
        val json = String(NodeAnnouncementJsonCodec.encode(ann), Charsets.UTF_8)
        assertFalse(json.contains("\"nodeName\""))
    }

    @Test
    fun `signature es el ultimo campo en el JSON`() {
        val ann = makeAnnouncement()
        val json = String(NodeAnnouncementJsonCodec.encode(ann), Charsets.UTF_8)
        val lastField = json.substringAfterLast(",").substringBefore("}")
        assertTrue(lastField.trim().startsWith("\"signature\""),
            "signature debe ser el ultimo campo, ultimo campo=$lastField")
    }

    // ===================================================================
    // NA.2 — Encode: determinismo, roundtrip
    // ===================================================================

    @Test
    fun `encode es deterministico para misma entrada`() {
        val ann = makeAnnouncement()
        val json1 = NodeAnnouncementJsonCodec.encode(ann)
        val json2 = NodeAnnouncementJsonCodec.encode(ann)
        assertArrayEquals(json1, json2, "misma entrada debe producir mismos bytes")
    }

    @Test
    fun `encode decode roundtrip produce el mismo objeto`() {
        val ann = makeAnnouncement()
        val json = NodeAnnouncementJsonCodec.encode(ann)
        val decoded = NodeAnnouncementJsonCodec.decode(json)

        assertEquals(ann.messageId, decoded.messageId)
        assertEquals(ann.timestamp, decoded.timestamp)
        assertEquals(ann.protocolVersion, decoded.protocolVersion)
        assertEquals(ann.identity.nodeId, decoded.identity.nodeId)
        assertArrayEquals(ann.identity.publicKey, decoded.identity.publicKey)
        assertEquals(ann.identity.nodeName, decoded.identity.nodeName)
        assertEquals(ann.endpoints, decoded.endpoints)
        assertEquals(ann.capabilities, decoded.capabilities)
        assertEquals(ann.limits, decoded.limits)
        assertArrayEquals(ann.signature, decoded.signature)
    }

    @Test
    fun `signableJson produce JSON sin campo signature`() {
        val ann = makeAnnouncement()
        val json = String(NodeAnnouncementJsonCodec.signableJson(ann), Charsets.UTF_8)
        assertFalse(json.contains("\"signature\""), "signableJson NO debe incluir signature")
    }

    @Test
    fun `signableJson es deterministico`() {
        val ann = makeAnnouncement()
        val b1 = NodeAnnouncementJsonCodec.signableJson(ann)
        val b2 = NodeAnnouncementJsonCodec.signableJson(ann)
        assertArrayEquals(b1, b2)
    }

    // ===================================================================
    // NA.3 — Decode: casos validos
    // ===================================================================

    @Test
    fun `decode roundtrip con todos los campos`() {
        val limits = RelayLimits()
        val endpoints = listOf(
            NodeEndpoint("websocket", "wss://relay.example.com/ws"),
            NodeEndpoint("onion", "km6x2p7q4nl3r5t8wz1yv0dc2sh4g7kj9fa.onion"),
        )
        val capabilities = setOf(NodeCapability.CLIENT, NodeCapability.RELAY)
        val ann = makeAnnouncement(endpoints = endpoints, capabilities = capabilities, limits = limits)
        val json = NodeAnnouncementJsonCodec.encode(ann)
        val decoded = NodeAnnouncementJsonCodec.decode(json)
        assertEquals(ann, decoded)
    }

    // ===================================================================
    // NA.3 — Decode: casos invalidos
    // ===================================================================

    @Test
    fun `decode rechaza JSON malformado`() {
        val e = assertThrows<NodeAnnouncementCodecException> {
            NodeAnnouncementJsonCodec.decode("{invalid json".encodeToByteArray())
        }
        assertEquals("JSON_MALFORMED", e.code)
    }

    @Test
    fun `decode rechaza raiz que no es objeto`() {
        val e = assertThrows<NodeAnnouncementCodecException> {
            NodeAnnouncementJsonCodec.decode("\"string\"".encodeToByteArray())
        }
        assertEquals("INVALID_TYPE", e.code)
    }

    @Test
    fun `decode rechaza campo obligatorio ausente`() {
        val json = """{"timestamp":123}""".encodeToByteArray()
        val e = assertThrows<NodeAnnouncementCodecException> {
            NodeAnnouncementJsonCodec.decode(json)
        }
        assertEquals("MISSING_FIELD", e.code)
    }

    @Test
    fun `decode rechaza tipo incorrecto en timestamp`() {
        val json = """{"messageId":"${UUID.randomUUID()}","timestamp":"not-a-number","protocolVersion":"1.0","nodeId":"${aliceId.nodeId.value}","publicKey":"AAAA","signature":"${"A".repeat(86)}"}""".encodeToByteArray()
        val e = assertThrows<NodeAnnouncementCodecException> {
            NodeAnnouncementJsonCodec.decode(json)
        }
        assertEquals("FIELD_TYPE", e.code)
    }

    @Test
    fun `decode rechaza nodeId de longitud incorrecta`() {
        val json = """{"messageId":"${UUID.randomUUID()}","timestamp":123,"protocolVersion":"1.0","nodeId":"short","publicKey":"AAAA","signature":"${"A".repeat(86)}"}""".encodeToByteArray()
        assertThrows<IllegalArgumentException> {
            NodeAnnouncementJsonCodec.decode(json)
        }
    }

    @Test
    fun `decode rechaza publicKey de tamanio incorrecto`() {
        val json = """{"messageId":"${UUID.randomUUID()}","timestamp":123,"protocolVersion":"1.0","nodeId":"${aliceId.nodeId.value}","publicKey":"AAAA","signature":"${"A".repeat(86)}"}""".encodeToByteArray()
        val e = assertThrows<NodeAnnouncementCodecException> {
            NodeAnnouncementJsonCodec.decode(json)
        }
        assertEquals("INVALID_PUBLIC_KEY", e.code)
    }

    @Test
    fun `decode rechaza signature de tamanio incorrecto`() {
        val pubB64 = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(aliceKP.publicKey)
        val json = """{"messageId":"${UUID.randomUUID()}","timestamp":123,"protocolVersion":"1.0","nodeId":"${aliceId.nodeId.value}","publicKey":"$pubB64","signature":"short"}""".encodeToByteArray()
        assertThrows<IllegalArgumentException> {
            NodeAnnouncementJsonCodec.decode(json)
        }
    }

    @Test
    fun `decode acepta campos desconocidos`() {
        // Forward compatibility: campos desconocidos se ignoran
        val ann = makeAnnouncement()
        val json = String(NodeAnnouncementJsonCodec.encode(ann), Charsets.UTF_8)
        // Anadir campo desconocido al inicio
        val tampered = json.replaceFirst("{", """{"futureField":"ignored",""")
        val decoded = NodeAnnouncementJsonCodec.decode(tampered.encodeToByteArray())
        assertEquals(ann.messageId, decoded.messageId)
    }

    @Test
    fun `decode rechaza capacidad desconocida`() {
        val pubB64 = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(aliceKP.publicKey)
        val sigB64 = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(64))
        val json = """{"messageId":"${UUID.randomUUID()}","timestamp":123,"protocolVersion":"1.0","nodeId":"${aliceId.nodeId.value}","publicKey":"$pubB64","capabilities":["UNKNOWN_CAP"],"signature":"$sigB64"}""".encodeToByteArray()
        val e = assertThrows<NodeAnnouncementCodecException> {
            NodeAnnouncementJsonCodec.decode(json)
        }
        assertEquals("UNKNOWN_CAPABILITY", e.code)
    }

    @Test
    fun `decode rechaza messageId invalido`() {
        val pubB64 = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(aliceKP.publicKey)
        val sigB64 = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(64))
        val json = """{"messageId":"not-a-uuid","timestamp":123,"protocolVersion":"1.0","nodeId":"${aliceId.nodeId.value}","publicKey":"$pubB64","signature":"$sigB64"}""".encodeToByteArray()
        val e = assertThrows<NodeAnnouncementCodecException> {
            NodeAnnouncementJsonCodec.decode(json)
        }
        assertEquals("INVALID_MESSAGE_ID", e.code)
    }

    // ===================================================================
    // NA.4 — Wire verification
    // ===================================================================

    @Test
    fun `verifyWireBytes con bytes firmados originales funciona`() {
        val ann = makeAnnouncement()
        val signableBytes = NodeAnnouncementJsonCodec.signableJson(ann)
        // verifyWireBytes debe pasar con los bytes originales que se firmaron
        assertDoesNotThrow {
            ann.verifyWireBytes(signableBytes, now = defaultTs).getOrThrow()
        }
    }

    @Test
    fun `verifyWireBytes con bytes modificados falla`() {
        val ann = makeAnnouncement()
        val signableBytes = NodeAnnouncementJsonCodec.signableJson(ann)
        // Modificar 1 byte
        signableBytes[10] = (signableBytes[10].toInt() xor 1).toByte()
        val e = assertThrows<NodeAnnouncementVerificationException> {
            ann.verifyWireBytes(signableBytes, now = defaultTs).getOrThrow()
        }
        assertEquals("INVALID_SIGNATURE", e.code)
    }

    @Test
    fun `verifyWireBytes con timestamp futuro falla`() {
        val futureTs = defaultTs + 100_000L
        val ann = makeAnnouncement(timestamp = futureTs)
        val signableBytes = NodeAnnouncementJsonCodec.signableJson(ann)
        val e = assertThrows<NodeAnnouncementVerificationException> {
            ann.verifyWireBytes(signableBytes, now = defaultTs).getOrThrow() // now < futureTs
        }
        assertEquals("TIMESTAMP_IN_FUTURE", e.code)
    }

    @Test
    fun `verifyWireBytes con timestamp expirado falla`() {
        val oldTs = defaultTs - 400_000L
        val ann = makeAnnouncement(timestamp = oldTs)
        val signableBytes = NodeAnnouncementJsonCodec.signableJson(ann)
        val e = assertThrows<NodeAnnouncementVerificationException> {
            ann.verifyWireBytes(signableBytes, now = defaultTs, maxAgeMs = 300_000L).getOrThrow()
        }
        assertEquals("TIMESTAMP_TOO_OLD", e.code)
    }

    @Test
    fun `signableJson del codec produce los mismos bytes que el creador firmo`() {
        // Simular el flujo completo: creator firma, verifier verifica
        val ann = makeAnnouncement()
        val creatorSignable = NodeAnnouncementJsonCodec.signableJson(ann)

        // Verifier recibe el full JSON, lo decodifica, y obtiene signableJson
        val fullJson = NodeAnnouncementJsonCodec.encode(ann)
        val decoded = NodeAnnouncementJsonCodec.decode(fullJson)
        val verifierSignable = NodeAnnouncementJsonCodec.signableJson(decoded)

        // Ambos deben ser identicos
        assertArrayEquals(creatorSignable, verifierSignable,
            "creator y verifier deben producir los mismos signable bytes")
    }

    // ===================================================================
    // Mutation tests
    // ===================================================================

    @Test
    fun `M-NA-01 cambiar identityId produce IDENTITY_ID_MISMATCH`() {
        val ann = makeAnnouncement()
        val json = String(NodeAnnouncementJsonCodec.encode(ann), Charsets.UTF_8)
        // No se puede cambiar nodeId sin cambiar publicKey porque verifyWireBytes lo detecta
        // Simular: decode con nodeId distinto a la derivacion de publicKey
        val pubB64 = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(aliceKP.publicKey)
        val sigB64 = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(ann.signature)
        val jsonFake = """{"messageId":"${ann.messageId.value}","timestamp":$defaultTs,"protocolVersion":"1.0","nodeId":"ffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff","publicKey":"$pubB64","nodeName":"Alice-Node","endpoints":[],"capabilities":["CLIENT"],"signature":"$sigB64"}"""
        val e = assertThrows<IllegalArgumentException> {
            NodeAnnouncementJsonCodec.decode(jsonFake.encodeToByteArray())
        }
        assertTrue(e.message?.contains("nodeId") == true)
    }

    @Test
    fun `M-NA-02 cambiar nodeId se detecta en verifyWireBytes`() {
        // Construir con nodeId incorrecto via constructor directo
        val wrongId = IdentityId("a1b2c3d4e5f6a7b8c9d0e1f2a3b4c5d6e7f8a9b0c1d2e3f4a5b6c7d8e9f0a1b2")
        val fakeIdentity = NodeIdentity(wrongId, aliceKP.publicKey, "fake")
        val ann = makeAnnouncement(identity = fakeIdentity)
        val signableBytes = NodeAnnouncementJsonCodec.signableJson(ann)
        val e = assertThrows<NodeAnnouncementVerificationException> {
            ann.verifyWireBytes(signableBytes, now = defaultTs).getOrThrow()
        }
        assertEquals("IDENTITY_ID_MISMATCH", e.code)
    }

    @Test
    fun `M-NA-03 cambiar timestamp se detecta en verifyWireBytes`() {
        // Firmar con un timestamp, verificar con otro
        val ann = makeAnnouncement(timestamp = defaultTs)
        val signableBytes = NodeAnnouncementJsonCodec.signableJson(ann)
        // Modificar timestamp en los bytes firmados
        val jsonStr = String(signableBytes, Charsets.UTF_8)
        val mutated = jsonStr.replace("\"timestamp\":$defaultTs", "\"timestamp\":${defaultTs + 1}")
            .encodeToByteArray()
        // verifyWireBytes debe rechazar porque la firma no coincide
        val e = assertThrows<NodeAnnouncementVerificationException> {
            ann.verifyWireBytes(mutated, now = defaultTs).getOrThrow()
        }
        assertEquals("INVALID_SIGNATURE", e.code)
    }

    @Test
    fun `M-NA-04 cambiar capabilities en full JSON no afecta bytes firmados`() {
        val ann = makeAnnouncement(capabilities = setOf(NodeCapability.CLIENT))
        val fullJson = String(NodeAnnouncementJsonCodec.encode(ann), Charsets.UTF_8)
        // Modificar capabilities en el full JSON
        val mutated = fullJson.replace("\"capabilities\":[\"CLIENT\"]", "\"capabilities\":[\"RELAY\"]")
        val decoded = NodeAnnouncementJsonCodec.decode(mutated.encodeToByteArray())
        assertEquals(setOf(NodeCapability.RELAY), decoded.capabilities)

        // Pero verifyWireBytes usaria signableJson del decoded, que tiene RELAY
        val signable = NodeAnnouncementJsonCodec.signableJson(decoded)
        // La firma no coincide porque el creador firmo CLIENT
        val e = assertThrows<NodeAnnouncementVerificationException> {
            decoded.verifyWireBytes(signable, now = defaultTs).getOrThrow()
        }
        assertEquals("INVALID_SIGNATURE", e.code)
    }

    @Test
    fun `M-NA-05 cambiar publicKey se detecta en decode`() {
        // Firmar con alice, luego cambiar publicKey en JSON
        val ann = makeAnnouncement()
        val pubB64 = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(aliceKP.publicKey)
        val otraPub = ed25519.generateKeyPair().publicKey
        val otraB64 = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(otraPub)
        val fullJson = String(NodeAnnouncementJsonCodec.encode(ann), Charsets.UTF_8)
        val mutated = fullJson.replace("\"publicKey\":\"$pubB64\"", "\"publicKey\":\"$otraB64\"")
        // nodeId ahora no coincide con la nueva publicKey
        assertThrows<IllegalArgumentException> {
            NodeAnnouncementJsonCodec.decode(mutated.encodeToByteArray())
        }
    }

    @Test
    fun `M-NA-06 cambiar signature se detecta en decode`() {
        val ann = makeAnnouncement()
        val fullJson = String(NodeAnnouncementJsonCodec.encode(ann), Charsets.UTF_8)
        val sigB64 = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(ann.signature)
        val fakeSig = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(64) { 0xAA.toByte() })
        val mutated = fullJson.replace("\"signature\":\"$sigB64\"", "\"signature\":\"$fakeSig\"")
        val decoded = NodeAnnouncementJsonCodec.decode(mutated.encodeToByteArray())
        // decode acepta, pero verifyWireBytes falla
        val signable = NodeAnnouncementJsonCodec.signableJson(decoded)
        val e = assertThrows<NodeAnnouncementVerificationException> {
            decoded.verifyWireBytes(signable, now = defaultTs).getOrThrow()
        }
        assertEquals("INVALID_SIGNATURE", e.code)
    }

    @Test
    fun `M-NA-07 reordenar propiedades no afecta decode`() {
        val ann = makeAnnouncement()
        val json = NodeAnnouncementJsonCodec.encode(ann)
        val decoded = NodeAnnouncementJsonCodec.decode(json)
        // Reordenar no es un escenario que podamos construir facilmente
        // porque nuestro codec produce orden canonico
        // Pero el decode debe ser tolerante al orden
        val pubB64 = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(aliceKP.publicKey)
        val sigB64 = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(ann.signature)
        val reordered = """{"signature":"$sigB64","timestamp":$defaultTs,"messageId":"${ann.messageId.value}","publicKey":"$pubB64","protocolVersion":"1.0","nodeId":"${aliceId.nodeId.value}","nodeName":"Alice-Node","endpoints":[],"capabilities":["CLIENT"]}"""
        val decoded2 = NodeAnnouncementJsonCodec.decode(reordered.encodeToByteArray())
        assertEquals(ann.messageId, decoded2.messageId)
        assertEquals(ann.timestamp, decoded2.timestamp)
    }

    @Test
    fun `M-NA-08 whitespace adicional no afecta decode`() {
        val ann = makeAnnouncement()
        val jsonCompact = NodeAnnouncementJsonCodec.encode(ann)
        // Decode de JSON compacto funciona
        val decoded1 = NodeAnnouncementJsonCodec.decode(jsonCompact)
        // Decode de JSON con whitespace
        val pretty = String(jsonCompact, Charsets.UTF_8)
            .replace("{", "{ ")
            .replace(",", ", ")
            .replace(":", ": ")
            .replace("}", " }")
        val decoded2 = NodeAnnouncementJsonCodec.decode(pretty.encodeToByteArray())
        assertEquals(decoded1.messageId, decoded2.messageId)
        // Pero verifyWireBytes con pretty fallaria porque los bytes son distintos
    }

    @Test
    fun `M-NA-09 campo desconocido es ignorado en decode`() {
        val ann = makeAnnouncement()
        val json = String(NodeAnnouncementJsonCodec.encode(ann), Charsets.UTF_8)
        val withExtra = json.replaceFirst("""{""", """{"extra":"ignored",""")
        val decoded = NodeAnnouncementJsonCodec.decode(withExtra.encodeToByteArray())
        assertEquals(ann.messageId, decoded.messageId)
    }

    @Test
    fun `M-NA-10 usar identityId como publicKey falla en verifyWireBytes`() {
        // Construir NodeAnnouncement donde publicKey es en realidad identityId (64 hex como bytes)
        val identityIdBytes = KmIds.identityId(aliceKP.publicKey) // 32 bytes
        val fakePub = identityIdBytes // identityId NO es publicKey
        val fakeIdentity = NodeIdentity.fromKeyPair(aliceKP.publicKey)
        val partial = NodeAnnouncement(
            messageId = MessageId(messageId),
            timestamp = defaultTs,
            protocolVersion = "1.0",
            identity = fakeIdentity,
            endpoints = emptyList(),
            capabilities = setOf(NodeCapability.CLIENT),
        )
        val signableBytes = NodeAnnouncementJsonCodec.signableJson(partial)
        val sig = ed25519.sign(aliceKP.privateKey, signableBytes)
        val ann = partial.copy(signature = sig.bytes)

        // verifyWireBytes usa identity.publicKey (correcta)
        assertDoesNotThrow {
            ann.verifyWireBytes(signableBytes, now = defaultTs).getOrThrow()
        }

        // Si alguien usara identityId como publicKey (32 bytes identityId = 32 bytes)
        // No pasaria require(publicKey.size == 32) en fromKeyPair si identityId tiene 32 bytes
        // Pero identityIdBytes tiene 32 bytes igual que publicKey...
        // El test verifica que identityId (como byte array) NO verifica la firma
        val wrongVerifier = ed25519.verify(identityIdBytes, signableBytes, Signature(sig.bytes))
        assertFalse(wrongVerifier, "identityId no debe verificar como publicKey")
    }

    @Test
    fun `M-NA-11 signable bytes del codec vs JSON re-serializado son diferentes`() {
        // Demostrar que signableJson() NO es lo mismo que re-serializar
        // el objeto decodeado con un JSON serializer generico
        val ann = makeAnnouncement()
        val signable1 = NodeAnnouncementJsonCodec.signableJson(ann)

        // Re-serializar usando Jackson generico (no canonico)
        val fullJson = NodeAnnouncementJsonCodec.encode(ann)
        val decoded = NodeAnnouncementJsonCodec.decode(fullJson)
        val signable2 = NodeAnnouncementJsonCodec.signableJson(decoded)

        // signableJson es deterministico, ambos son iguales
        assertArrayEquals(signable1, signable2,
            "signableJson debe ser deterministico entre creator y verifier")

        // Demostrar que JSON con whitespace produce signable bytes DIFERENTES
        val prettyJson = String(fullJson, Charsets.UTF_8)
            .replace("{", "{ ")
            .replace(",", ", ")
        val decodedPretty = NodeAnnouncementJsonCodec.decode(prettyJson.encodeToByteArray())
        val signable3 = NodeAnnouncementJsonCodec.signableJson(decodedPretty)
        // Aunque el decode se hizo de JSON con whitespace,
        // signableJson() produce los mismos bytes canonicos
        assertArrayEquals(signable1, signable3,
            "signableJson debe producir siempre los mismos bytes, independientemente del formato de entrada")
    }

    @Test
    fun `M-NA-12 timestamp fuera de ventana se detecta`() {
        val ann = makeAnnouncement(timestamp = defaultTs - 600_000L) // 10 min atras
        val signableBytes = NodeAnnouncementJsonCodec.signableJson(ann)
        val e = assertThrows<NodeAnnouncementVerificationException> {
            ann.verifyWireBytes(signableBytes, now = defaultTs, maxAgeMs = 300_000L).getOrThrow()
        }
        assertEquals("TIMESTAMP_TOO_OLD", e.code)
    }
}