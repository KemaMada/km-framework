package com.km.daemon.server

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.km.auth.AuthJsonCodec
import com.km.auth.Dialect
import com.km.auth.DialectCheck
import com.km.auth.DialectNegotiation
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.security.MessageDigest

/**
 * CONFORMANCE de los vectores P6 en el DAEMON (task 1.4).
 *
 * POR QUE EXISTE
 * --------------
 * La condicion 4 del owner exige que los CUATRO componentes consuman los
 * MISMOS BYTES del vector. Aqui el daemon prueba dos cosas:
 *
 *  1. BYTE-IDENTITY: carga cada artefacto P6 del CLASSPATH (viaja dentro del
 *     jar de km-core, que el daemon consume como dependencia) y comprueba que
 *     su sha256 coincide con el declarado en `AUTH_MANIFEST.json`. Los mismos
 *     bytes, no una copia ni una ruta relativa.
 *  2. El codec DEL DAEMON ([JsonWireCodec.parseAuthOk]) LEE cada `AUTH_OK`
 *     canonico y valida su forma, y el dialecto se resuelve con la misma regla
 *     compartida que los demas ([DialectNegotiation]).
 *
 * ALCANCE: demuestra que el daemon puede leer estos bytes. La verificacion
 * criptografica de referencia se ejerce en km-core (P6VectorConformanceTest);
 * que Android/escritorio la corran en produccion es 4.1/5.2.
 */
class P6VectorConformanceTest {

    private val codec = JsonWireCodec()
    private val mapper = ObjectMapper()

    private fun resourceBytes(path: String): ByteArray =
        checkNotNull(javaClass.getResourceAsStream(path)) {
            "falta el recurso de clase '$path' (¿km-core empaqueta km-id-reference/vectors?)"
        }.use { it.readBytes() }

    private fun resourceText(path: String): String = resourceBytes(path).toString(Charsets.UTF_8)

    private fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private val inventoryIds: List<String> = resourceText("/p6-vectors/P6_VECTOR_INVENTORY.txt")
        .lines().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }

    private val manifest: JsonNode by lazy { mapper.readTree(resourceText("/p6-vectors/AUTH_MANIFEST.json")) }

    private fun vector(id: String): JsonNode =
        manifest.path("vectors").first { it.path("id").asText() == id }

    @Test
    fun `el daemon lee exactamente los mismos bytes que declara el manifiesto`() {
        assertEquals(20, inventoryIds.size)
        for (id in inventoryIds) {
            val artifacts = vector(id).path("artifacts")
            for (entry in artifacts.properties()) {
                val name = entry.key
                val real = resourceBytes("/p6-vectors/$id/$name")
                assertEquals(
                    entry.value.path("length").asInt(),
                    real.size,
                    "$id/$name: longitud distinta de la declarada",
                )
                assertEquals(
                    entry.value.path("sha256").asText(),
                    sha256Hex(real),
                    "$id/$name: sha256 distinto del declarado (el daemon no lee los mismos bytes)",
                )
            }
        }
    }

    @Test
    fun `el codec del daemon lee el AUTH_OK canonico de cada vector`() {
        for (id in inventoryIds) {
            val expectations = vector(id).path("expectations")
            for (exp in expectations) {
                val artifact = exp.path("artifact").asText()
                val stage = exp.path("stage").asText()
                val text = resourceText("/p6-vectors/$id/$artifact")

                // El codec del daemon debe reconocer el frame como objeto JSON.
                val nodeOrNull = codec.readObject(text)
                assertNotNull(nodeOrNull, "$id/$artifact: el codec del daemon no reconoce el frame")
                val node = nodeOrNull!!

                val hasCanonical = node.get("initiatorIdentityId") != null
                val parsed = codec.parseAuthOk(node)

                if (stage == "STRUCTURE" || !hasCanonical) {
                    assertNull(parsed, "$id/$artifact: el codec del daemon no deberia completar el parseo")
                } else {
                    assertNotNull(parsed, "$id/$artifact: el codec del daemon deberia leer el AUTH_OK")
                    val fields = parsed!!
                    assertEquals(64, fields.initiatorIdentityId.length, "$id/$artifact")
                    assertEquals(64, fields.responderIdentityId.length, "$id/$artifact")
                    assertEquals(40, fields.sessionId.length, "$id/$artifact")
                    assertEquals(16, fields.serverNonce.size, "$id/$artifact")
                    assertEquals(32, fields.responderPublicKey.size, "$id/$artifact")
                    assertEquals(64, fields.serverSignature.size, "$id/$artifact")
                    // El dialecto se lee como string, nunca como numero.
                    val rawPv = node.get("protocolVersion")
                    if (rawPv != null) {
                        assertTrue(rawPv.isTextual, "$id/$artifact: protocolVersion debe ser string (R34)")
                        assertEquals(rawPv.asText(), fields.protocolVersion, "$id/$artifact")
                    }
                }
            }
        }
    }

    @Test
    fun `el dialecto de los vectores se resuelve con la regla compartida`() {
        for (id in inventoryIds) {
            val inputs = vector(id).path("inputs")
            val client = Dialect.fromWireOrNull(inputs.path("dialect").asText()) ?: continue
            val challengeWire = mapper.readTree(resourceBytes("/p6-vectors/$id/challenge.json"))
                .get("protocolVersion")?.takeIf { it.isTextual }?.asText()
            for (exp in vector(id).path("expectations")) {
                if (exp.path("stage").asText() != "DIALECT") continue
                val challengeCheck = DialectNegotiation.checkChallenge(client, challengeWire)
                val actual = when (challengeCheck) {
                    is DialectCheck.Rejected -> challengeCheck.error.wireCode
                    is DialectCheck.Accepted -> {
                        val authOk = resourceText("/p6-vectors/$id/${exp.path("artifact").asText()}")
                        val authOkDialect = mapper.readTree(authOk).get("protocolVersion")
                            ?.takeIf { it.isTextual }?.asText()
                        val okCheck = DialectNegotiation.checkAuthOk(
                            client, challengeCheck.dialect, authOkDialect,
                        )
                        assertTrue(okCheck is DialectCheck.Rejected) { "$id: se esperaba rechazo de dialecto" }
                        (okCheck as DialectCheck.Rejected).error.wireCode
                    }
                }
                assertEquals(exp.path("errorCode").asText(), actual, "$id: codigo de dialecto")
            }
        }
    }

    @Test
    fun `el writer del daemon declara el nombre canonico initiatorIdentityId y serverSignature`() {
        val fields = codec.parseAuthOk(
            codec.readObject(resourceText("/p6-vectors/P6-A/auth_ok.json"))!!,
        )!!
        val emitted = codec.authOk(
            sessionId = fields.sessionId,
            serverSignature = fields.serverSignature,
            initiatorIdentityId = fields.initiatorIdentityId,
            responderIdentityId = fields.responderIdentityId,
            dialect = Dialect.V2_0.wire,
            serverNonce = fields.serverNonce,
            responderPublicKey = fields.responderPublicKey,
        )
        val node = codec.readObject(emitted)!!
        assertTrue(node.get("initiatorIdentityId") != null, "el daemon debe emitir el nombre canonico")
        assertTrue(node.get("serverSignature") != null, "el daemon debe emitir serverSignature")
        assertNull(node.get("identityId"), "el daemon NUNCA emite el alias legacy")
        assertNull(node.get("signature"), "el daemon NUNCA emite el nombre legacy 'signature'")
        assertEquals(Dialect.V2_0.wire, node.path("protocolVersion").asText())
        // Round-trip: el codec vuelve a leer su propio frame.
        assertEquals(fields, codec.parseAuthOk(node))
    }

    @Test
    fun `el codec del daemon rechaza el esquema legacy`() {
        val legacy = """
            {"sessionId":"b2aeff36f46a697d3287794e39ac64ed1dd3e7d0",
             "serverNonce":"IYoenlGkVa12ihR9aDTHiA",
             "responderPublicKey":"mDo4IQ6bRA2ahHRpeGPf-7-9T1XenN8hJXE82oZumvM",
             "signature":"ZCH9OweNslm5IR8swOgaGPlca_QbLPgi8WG092Z0iIJpKImfKOd6jWZ9UObGovbYvhNRfRPzS4a4N8UzXTrmDw"}
        """.trimIndent()
        val node = codec.readObject(legacy)!!
        assertNull(codec.parseAuthOk(node), "el codec del daemon no debe aceptar el esquema legacy")
    }
}
