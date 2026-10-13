package com.km.desktop

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.km.auth.AuthJsonCodec
import com.km.auth.AuthOkFields
import com.km.auth.Dialect
import com.km.auth.DialectCheck
import com.km.auth.DialectNegotiation
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.security.MessageDigest

/**
 * CONFORMANCE de los vectores P6 en ESCRITORIO (task 1.4).
 *
 * km-desktop consume `com.km:km-core` POR ARTEFACTO (desde Maven local, no como
 * `project(":km-core")`). Este test demuestra que los vectores llegan DENTRO de
 * ese artefacto y que este componente lee LOS MISMOS BYTES que los demas: los
 * carga del claspath (`/p6-vectors/...`) y comprueba su sha256 contra el
 * `AUTH_MANIFEST.json` empaquetado. Ademas pasa cada `AUTH_OK` por el codec de
 * produccion y afirma el resultado declarado.
 *
 * ALCANCE: prueba que escritorio puede consumir los bytes. La verificacion
 * criptografica de referencia se ejerce en km-core; que escritorio la corra en
 * produccion es 5.2.
 */
class P6VectorConformanceTest {

    private val mapper = ObjectMapper()

    private fun resourceBytes(path: String): ByteArray =
        checkNotNull(javaClass.getResourceAsStream(path)) {
            "falta el recurso '$path' (¿km-core publicado incluye p6-vectors/?)"
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
    fun `escritorio lee exactamente los mismos bytes del artefacto km-core`() {
        assertEquals(20, inventoryIds.size)
        for (id in inventoryIds) {
            val artifacts = vector(id).path("artifacts")
            for (entry in artifacts.properties()) {
                val real = resourceBytes("/p6-vectors/$id/${entry.key}")
                assertEquals(
                    entry.value.path("sha256").asText(),
                    sha256Hex(real),
                    "$id/${entry.key}: sha256 distinto (escritorio no lee los mismos bytes)",
                )
            }
        }
    }

    @Test
    fun `cada vector se evalua con el codec de produccion y da el resultado declarado`() {
        for (id in inventoryIds) {
            val inputs = vector(id).path("inputs")
            val client = Dialect.fromWireOrNull(inputs.path("dialect").asText()) ?: continue
            val challengeWire = mapper.readTree(resourceBytes("/p6-vectors/$id/challenge.json"))
                .get("protocolVersion")?.takeIf { it.isTextual }?.asText()

            for (exp in vector(id).path("expectations")) {
                val artifact = exp.path("artifact").asText()
                val stage = exp.path("stage").asText()
                val wire = resourceText("/p6-vectors/$id/$artifact")
                val authOkDialect = mapper.readTree(wire).get("protocolVersion")
                    ?.takeIf { it.isTextual }?.asText()

                val challengeCheck = DialectNegotiation.checkChallenge(client, challengeWire)
                if (stage == "DIALECT") {
                    val actual = when (challengeCheck) {
                        is DialectCheck.Rejected -> challengeCheck.error.wireCode
                        is DialectCheck.Accepted -> {
                            val okCheck = DialectNegotiation.checkAuthOk(
                                client, challengeCheck.dialect, authOkDialect,
                            )
                            assertTrue(okCheck is DialectCheck.Rejected) { "$id/$artifact" }
                            (okCheck as DialectCheck.Rejected).error.wireCode
                        }
                    }
                    assertEquals(exp.path("errorCode").asText(), actual, "$id/$artifact")
                    continue
                }

                assertTrue(challengeCheck is DialectCheck.Accepted) { "$id/$artifact: challenge" }
                val effective = (challengeCheck as DialectCheck.Accepted).dialect
                val okCheck = DialectNegotiation.checkAuthOk(client, effective, authOkDialect)
                assertTrue(okCheck is DialectCheck.Accepted) { "$id/$artifact: authOk" }
                val fieldDialect = (okCheck as DialectCheck.Accepted).dialect

                when (stage) {
                    "STRUCTURE" -> assertThrows<IllegalArgumentException> {
                        AuthJsonCodec.parseAuthOkForDialect(wire, fieldDialect)
                    }
                    "FIELDS_REJECT" -> {
                        val fields = AuthJsonCodec.parseAuthOkForDialect(wire, fieldDialect)
                        assertTrue(fields is AuthOkFields.Rejected) { "$id/$artifact" }
                        assertEquals(
                            exp.path("errorCode").asText(),
                            (fields as AuthOkFields.Rejected).error.wireCode,
                            "$id/$artifact",
                        )
                    }
                    "FIELDS_ACCEPT", "CRYPTO_REJECT", "VERIFIED" -> {
                        val fields = AuthJsonCodec.parseAuthOkForDialect(wire, fieldDialect)
                        assertTrue(fields is AuthOkFields.Accepted) { "$id/$artifact" }
                        assertNotNull((fields as AuthOkFields.Accepted).authOk.initiatorIdentityId)
                    }
                    else -> error("etapa desconocida '$stage' en $id/$artifact")
                }
            }
        }
    }
}
