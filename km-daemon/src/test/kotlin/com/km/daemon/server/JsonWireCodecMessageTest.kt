package com.km.daemon.server

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import com.km.auth.AuthFieldError
import com.km.auth.AuthJsonCodec
import com.km.auth.AuthOk
import com.km.auth.AuthOkFields
import com.km.auth.Base64Url
import com.km.auth.Dialect
import com.km.daemon.util.Base64Standard
import com.km.model.IdentityId
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.security.SecureRandom

/**
 * La propiedad central del spec, y la unica que este modulo decide.
 *
 * KM-WIRE-RELAY §6.2: **el frame que entra es byte a byte el frame que
 * sale**. Es verificable sobre el codigo, no es una intencion: si la
 * transcodificacion tocara un solo bit, este test cae.
 *
 * Los bytes de los casos son ADVERSOS a proposito, no un `byteArrayOf(1,2,3)`:
 * cubren los 256 valores posibles y las tres longitudes de residuo modulo 3
 * (las que deciden cuanto padding lleva el Base64 de salida). Un test con tres
 * numeros pequenos pasaria con casi cualquier codificacion y no probaria
 * nada: en UTF-8, todos los bytes ASCII sobreviven a un `String(bytes)`, que
 * es exactamente el error que documented el cliente Android.
 *
 * El segundo caso cubre la otra mitad de "opaco" (§14.1): un Base64 invalido
 * se descarta ENTERO. No solo no se altera lo que llega: tampoco se inventa
 * lo que no llega.
 */
class JsonWireCodecMessageTest {

    private val mapper = ObjectMapper()
    private val codec = JsonWireCodec()
    private val from = IdentityId("a".repeat(64))
    private val to = IdentityId("b".repeat(64))

    @Test
    fun `la carga de MESSAGE sobrevive al viaje de ida y vuelta byte a byte`() {
        val random = SecureRandom()
        for (size in listOf(0, 1, 2, 3, 44, 255, 256, 4096, 65535)) {
            val original = ByteArray(size).also { random.nextBytes(it) }
            // Los 256 valores, para que ningun byte sobreviva por ser ASCII
            // imprimible y por tanto idempotente en UTF-8.
            if (size >= 256) for (i in 0 until 256) original[i] = i.toByte()

            // Salida: ByteArray -> Base64 estandar -> JSON MESSAGE.
            val wire = codec.message(from, to, original)
            val frame = codec.readObject(wire)
            assertNotNull(frame, "el frame emitido debe ser un objeto JSON (tamano $size)")
            assertEquals("MESSAGE", codec.typeOf(frame!!))
            assertEquals(Base64Standard.encode(original), frame.get("data").asText())

            // Entrada: el mismo JSON vuelve a entrar por el codec.
            val parsed = codec.parseMessage(frame)
            assertNotNull(parsed, "MESSAGE debe poder releerse (tamano $size)")
            assertEquals(from, parsed!!.from)
            assertEquals(to, parsed.to)
            assertEquals(original.size, parsed.data.size)
            assertArrayEquals(
                original,
                parsed.data,
                "la carga debe salir identica byte a byte (tamano $size, §6.2)",
            )
        }
    }

    @Test
    fun `un Base64 invalido se descarta entero, nunca parcialmente - seccion 14 punto 1`() {
        val invalidos = listOf(
            "AQ==A",  // caracteres fuera del alfabeto estandar
            "AQ D=",  // whitespace: NO se tolera en silencio
            "AQ-_",   // alfabeto URL-safe donde va el estandar
            "A",      // longitud imposible: len % 4 == 1
        )
        for (invalido in invalidos) {
            assertNull(
                codec.parseMessage(messageWithData(invalido)),
                "'$invalido' no es Base64 estandar valido: el frame se descarta entero",
            )
        }

        // El borde positivo que un decoder con las manos torpes tritura: un
        // unico byte, que es el que lleva el padding maximo ("/w==" para 0xFF).
        val unByte = byteArrayOf(0xFF.toByte())
        assertArrayEquals(unByte, codec.parseMessage(messageWithData("/w=="))?.data)
    }

    /** `MESSAGE` con un campo `data` arbitrario, sin pasar por el encoder. */
    private fun messageWithData(data: String): ObjectNode {
        val node = mapper.createObjectNode()
        node.put("type", "MESSAGE")
        node.put("from", from.value)
        node.put("to", to.value)
        node.put("data", data)
        return node
    }

    // =====================================================================
    // Task 1.1 — el dialecto en el cable: siempre string, en challenge y OK
    // =====================================================================

    @Test
    fun `el AUTH_CHALLENGE lleva protocolVersion como string`() {
        val frame = mapper.readTree(
            codec.authChallenge(
                nonce = ByteArray(16) { it.toByte() },
                timestamp = 1_790_000_000_000L,
                responderIdentityId = from.value,
                dialect = Dialect.V2_0.wire,
            )
        )
        assertTrue(
            frame.get("protocolVersion").isTextual,
            "protocolVersion debe ser string, no numero (R34)",
        )
        assertEquals("2.0", frame.get("protocolVersion").asText())
    }

    @Test
    fun `el AUTH_OK confirma el dialecto como string`() {
        val frame = mapper.readTree(
            codec.authOk(
                sessionId = "c".repeat(40),
                serverSignature = ByteArray(64),
                initiatorIdentityId = from.value,
                responderIdentityId = to.value,
                dialect = Dialect.V2_0.wire,
                serverNonce = ByteArray(16) { it.toByte() },
                responderPublicKey = ByteArray(32) { (it + 1).toByte() },
            )
        )
        assertTrue(frame.get("protocolVersion").isTextual)
        assertEquals("2.0", frame.get("protocolVersion").asText())
    }

    // =====================================================================
    // Task 1.2 — la tabla canonica de AUTH_OK y la conformidad entre codecs
    // =====================================================================

    private val serverNonce16 = ByteArray(16) { (it + 16).toByte() }
    private val responderPub32 = ByteArray(32) { (it + 7).toByte() }

    private fun emitCanonicalAuthOk(): String = codec.authOk(
        sessionId = "c".repeat(40),
        serverSignature = ByteArray(64) { (it * 3).toByte() },
        initiatorIdentityId = from.value,
        responderIdentityId = to.value,
        dialect = Dialect.V2_0.wire,
        serverNonce = serverNonce16,
        responderPublicKey = responderPub32,
    )

    /**
     * R15: el `AUTH_OK` incorpora la tabla canonica con las codificaciones y
     * longitudes exactas. Los nombres retirados no se emiten.
     */
    @Test
    fun `el AUTH_OK lleva la tabla canonica con codificaciones exactas`() {
        val frame = mapper.readTree(emitCanonicalAuthOk())

        for (f in listOf(
            "protocolVersion", "initiatorIdentityId", "responderIdentityId",
            "sessionId", "serverNonce", "responderPublicKey", "serverSignature",
        )) {
            assertTrue(frame.has(f), "falta el campo canonico $f")
        }
        for (retired in listOf("identityId", "publicKey", "signature")) {
            assertFalse(frame.has(retired), "el nombre retirado $retired no debe emitirse")
        }

        assertEquals(40, frame.get("sessionId").asText().length)
        assertEquals(from.value, frame.get("initiatorIdentityId").asText())
        assertEquals(to.value, frame.get("responderIdentityId").asText())

        // Base64URL sin padding, con las longitudes del spec.
        val serverNonce = frame.get("serverNonce").asText()
        val responderPublicKey = frame.get("responderPublicKey").asText()
        val serverSignature = frame.get("serverSignature").asText()
        assertFalse(serverNonce.contains('='), "serverNonce sin padding")
        assertFalse(responderPublicKey.contains('='), "responderPublicKey sin padding")
        assertFalse(serverSignature.contains('='), "serverSignature sin padding")
        assertArrayEquals(serverNonce16, Base64Url.decode(serverNonce))
        assertArrayEquals(responderPub32, Base64Url.decode(responderPublicKey))
        assertEquals(64, Base64Url.decode(serverSignature).size)
    }

    /**
     * R16: los dos codecs hablan UN dialecto. El frame del `JsonWireCodec`
     * (daemon) es parseable por el `AuthJsonCodec` (km-core) y viceversa: las
     * seis claves canonicas coinciden, byte a byte en los campos.
     */
    @Test
    fun `JsonWireCodec y AuthJsonCodec comparten las mismas claves canonicas`() {
        val jsonWireFrame = mapper.readTree(emitCanonicalAuthOk())

        // Direccion daemon -> core: el parser de km-core entiende el frame.
        val parsed = AuthJsonCodec.parseAuthOk(jsonWireFrame.toString())
        assertEquals("c".repeat(40), parsed.sessionId)
        assertEquals(from.value, parsed.initiatorIdentityId)
        assertEquals(to.value, parsed.responderIdentityId)
        assertArrayEquals(serverNonce16, parsed.serverNonce)
        assertArrayEquals(responderPub32, parsed.responderPublicKey)
        assertEquals(64, parsed.signature.size)

        // Direccion core -> daemon: mismas claves, mismos valores.
        val coreFrame = mapper.readTree(
            AuthJsonCodec.authOkWire(
                AuthOk(
                    sessionId = parsed.sessionId,
                    serverNonce = parsed.serverNonce,
                    responderPublicKey = parsed.responderPublicKey,
                    signature = parsed.signature,
                    initiatorIdentityId = parsed.initiatorIdentityId,
                    responderIdentityId = parsed.responderIdentityId,
                )
            )
        )
        for (f in listOf(
            "initiatorIdentityId", "responderIdentityId", "sessionId",
            "serverNonce", "responderPublicKey", "serverSignature",
        )) {
            assertTrue(coreFrame.has(f), "AuthJsonCodec debe emitir la clave canonica $f")
            assertEquals(
                jsonWireFrame.get(f).asText(),
                coreFrame.get(f).asText(),
                "el valor de $f debe coincidir entre ambos codecs",
            )
        }
    }

    // =====================================================================
    // Task 1.3 — la transicion del alias identityId (R2/R3) en el daemon
    // =====================================================================

    /**
     * R16: el frame que emite el daemon (con el nombre canonico
     * `initiatorIdentityId`) es leido por `AuthJsonCodec.parseAuthOkForDialect`
     * tanto en `2.0` como en `2.1`. El rele nunca emite el alias.
     */
    @Test
    fun `el AUTH_OK del daemon se lee en 2_0 y en 2_1 sin alias`() {
        for (dialect in listOf(Dialect.V2_0, Dialect.V2_1)) {
            val wire = codec.authOk(
                sessionId = "c".repeat(40),
                serverSignature = ByteArray(64) { (it * 3).toByte() },
                initiatorIdentityId = from.value,
                responderIdentityId = to.value,
                dialect = dialect.wire,
                serverNonce = serverNonce16,
                responderPublicKey = responderPub32,
            )
            val parsed = AuthJsonCodec.parseAuthOkForDialect(wire, dialect)
            val accepted = assertInstanceOf(AuthOkFields.Accepted::class.java, parsed)
            assertEquals(from.value, accepted.authOk.initiatorIdentityId)
        }
    }

    private fun authOkAliasFrame(initiatorFields: String): String {
        val node = mapper.createObjectNode()
        node.put("sessionId", "c".repeat(40))
        node.put("serverNonce", Base64Url.encode(serverNonce16))
        node.put("responderPublicKey", Base64Url.encode(responderPub32))
        node.put("serverSignature", Base64Url.encode(ByteArray(64) { (it * 3).toByte() }))
        node.put("responderIdentityId", to.value)
        // Campos del iniciador inyectados: canonico y/o alias legacy.
        val extra = mapper.readTree("{$initiatorFields}")
        extra.fields().forEachRemaining { (k, v) -> node.set<com.fasterxml.jackson.databind.JsonNode>(k, v) }
        return node.toString()
    }

    /** R2/S15: en 2.1 el alias en solitario se rechaza con UNKNOWN_PROTOCOL. */
    @Test
    fun `en 2_1 el alias identityId en solitario es UNKNOWN_PROTOCOL`() {
        val wire = authOkAliasFrame(""""identityId":"${from.value}"""")
        val parsed = AuthJsonCodec.parseAuthOkForDialect(wire, Dialect.V2_1)
        val rejected = assertInstanceOf(AuthOkFields.Rejected::class.java, parsed)
        assertEquals(AuthFieldError.UNKNOWN_PROTOCOL, rejected.error)
    }

    /** R2/S13: en 2.0 el mismo frame se acepta por el alias. */
    @Test
    fun `en 2_0 el alias identityId en solitario se acepta`() {
        val wire = authOkAliasFrame(""""identityId":"${from.value}"""")
        val parsed = AuthJsonCodec.parseAuthOkForDialect(wire, Dialect.V2_0)
        val accepted = assertInstanceOf(AuthOkFields.Accepted::class.java, parsed)
        assertEquals(from.value, accepted.authOk.initiatorIdentityId)
    }

    /** R3/S14/P6-T: ambos presentes y distintos -> MALFORMED_MESSAGE. */
    @Test
    fun `ambos campos distintos son MALFORMED_MESSAGE`() {
        val wire = authOkAliasFrame(
            """"initiatorIdentityId":"${from.value}","identityId":"${to.value}"""",
        )
        for (dialect in listOf(Dialect.V2_0, Dialect.V2_1)) {
            val rejected = assertInstanceOf(
                AuthOkFields.Rejected::class.java,
                AuthJsonCodec.parseAuthOkForDialect(wire, dialect),
            )
            assertEquals(AuthFieldError.MALFORMED_MESSAGE, rejected.error)
        }
    }

    @Test
    fun `el AUTH_FAIL solo lleva protocolVersion como diagnostico`() {
        val withEcho = mapper.readTree(
            codec.authFail(
                AuthAdapter.ErrorCode.UNSUPPORTED_PROTOCOL_VERSION,
                "no soportado",
                dialect = "9.9",
            )
        )
        assertEquals("9.9", withEcho.get("protocolVersion").asText())

        val without = mapper.readTree(codec.authFail(AuthAdapter.ErrorCode.AUTH_FAILED, "firma mala"))
        assertFalse(without.has("protocolVersion"), "el eco es opcional (N6)")
    }
}
