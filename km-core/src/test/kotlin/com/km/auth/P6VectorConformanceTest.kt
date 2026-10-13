package com.km.auth

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.km.crypto.Ed25519
import com.km.crypto.Ed25519Impl
import com.km.crypto.Signature
import com.km.identity.KmIds
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/**
 * CONFORMANCE de ESQUEMA y RESULTADO de los 20 vectores P6 (task 1.4).
 *
 * POR QUE EXISTE (anti-falso-verde)
 * ---------------------------------
 * El gate anterior ([P6VectorInventoryGateTest]) solo contaba IDs: **nunca
 * abria un vector**. Los 20 vectores de la primera tentativa de 1.4 se
 * generaron con el esquema KM-0002 legacy (`signature` en vez de
 * `serverSignature`, sin `initiatorIdentityId`, `protocolVersion` numerico) y
 * el gate paso en VERDE igualmente, porque no miraba los bytes. Este test
 * cierra ese agujero: abre cada `auth_ok.json` empaquetado en el artefacto y
 * lo pasa por el CODEC DE PRODUCCION
 * ([AuthJsonCodec.parseAuthOkForDialect]) y por [DialectNegotiation], y afirma
 * el resultado declarado en `vector.json`. Un vector con el esquema equivocado
 * hace FALLAR la suite.
 *
 * ALCANCE HONESTO
 * ---------------
 * Aqui se ejerce la verificacion criptografica de REFERENCIA (Ed25519 sobre los
 * transcripts de 120/152 B) para poder afirmar los resultados de los vectores
 * criptograficos. Eso demuestra que la criptografia funciona EN EL CODIGO DE
 * REFERENCIA. NO demuestra que Android ni escritorio la ejecuten en produccion:
 * eso es 4.1/5.2. Ningun nombre de test afirma lo contrario.
 *
 * Los bytes se cargan del CLASSPATH (`/p6-vectors/...`), no subiendo rutas
 * relativas: es el mismo artefacto que consumen los otros tres componentes.
 */
class P6VectorConformanceTest {

    private val mapper = ObjectMapper()
    private val ed25519: Ed25519 = Ed25519Impl()

    /** "Ahora" fijo para la capa de timestamp: el timestamp canonico de P6-A. */
    private val nowCanonical = 1_721_827_200_000L

    private fun resourceBytes(path: String): ByteArray =
        checkNotNull(javaClass.getResourceAsStream(path)) { "falta el recurso de clase '$path'" }
            .use { it.readBytes() }

    private fun resourceText(path: String): String = resourceBytes(path).toString(Charsets.UTF_8)

    private fun json(path: String): JsonNode = mapper.readTree(resourceBytes(path))

    private val inventoryIds: List<String> = resourceText("/p6-vectors/P6_VECTOR_INVENTORY.txt")
        .lines().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }

    private fun lowHex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it) }

    private fun dialectOrNull(wire: String?): Dialect? = Dialect.fromWireOrNull(wire)

    // =====================================================================
    // El gate que de verdad abre los vectores.
    // =====================================================================

    @Test
    fun `cada vector declara expectativas y todas se cumplen con el codec de produccion`() {
        assertEquals(20, inventoryIds.size, "deben existir los 20 vectores P6-A..P6-T")

        for (id in inventoryIds) {
            val vector = json("/p6-vectors/$id/vector.json")
            val inputs = vector.path("inputs")
            val expectations = vector.path("expectations")
            assertTrue(expectations.isArray && expectations.size() > 0) {
                "$id no declara 'expectations' en vector.json"
            }

            val client = dialectOrNull(inputs.path("dialect").asText())
                ?: error("$id: 'inputs.dialect' no es un dialecto conocido")

            val challenge = json("/p6-vectors/$id/challenge.json")
            val challengeWire = challenge.get("protocolVersion")?.takeIf { it.isTextual }?.asText()

            for (exp in expectations) {
                val artifact = exp.path("artifact").asText()
                val stage = exp.path("stage").asText()
                val outcome = exp.path("outcome").asText()
                val errorCode = exp.path("errorCode").takeIf { it.isTextual }?.asText()

                val wire = resourceText("/p6-vectors/$id/$artifact")
                val authOkWireDialect = mapper.readTree(wire).get("protocolVersion")
                    ?.takeIf { it.isTextual }?.asText()

                val challengeCheck = DialectNegotiation.checkChallenge(client, challengeWire)

                when (stage) {
                    "DIALECT" -> {
                        val actual = when (challengeCheck) {
                            is DialectCheck.Rejected -> challengeCheck.error.wireCode
                            is DialectCheck.Accepted -> {
                                val okCheck = DialectNegotiation.checkAuthOk(
                                    client, challengeCheck.dialect, authOkWireDialect,
                                )
                                assertTrue(okCheck is DialectCheck.Rejected) {
                                    "$id/$artifact: se esperaba un rechazo de dialecto en $stage"
                                }
                                (okCheck as DialectCheck.Rejected).error.wireCode
                            }
                        }
                        assertEquals("REJECT", outcome, "$id/$artifact")
                        assertEquals(errorCode, actual, "$id/$artifact: codigo de dialecto")
                        // Condicion 5: aunque el rechazo sea de DIALECTO, el
                        // wire del AUTH_OK debe ser valido. El codec de
                        // produccion lo parsea en el dialecto del cliente
                        // (los campos canonicales estan bien; lo que falla es
                        // el dialecto, no el esquema). Un esquema legacy aqui
                        // haria fallar esta asercion.
                        assertTrue(
                            AuthJsonCodec.parseAuthOkForDialect(wire, client) is AuthOkFields.Accepted,
                        ) {
                            "$id/$artifact: el wire del AUTH_OK debe ser estructuralmente valido " +
                                "aunque el dialecto se rechace"
                        }
                    }

                    else -> {
                        val effective = effectiveDialect(id, artifact, challengeCheck, client, authOkWireDialect)
                        val fields = parseForStage(stage, wire, effective, id, artifact)
                        when (stage) {
                            "STRUCTURE" -> {
                                assertEquals("REJECT", outcome, "$id/$artifact")
                            }
                            "FIELDS_REJECT" -> {
                                assertEquals("REJECT", outcome, "$id/$artifact")
                                assertEquals(
                                    errorCode,
                                    (fields as AuthOkFields.Rejected).error.wireCode,
                                    "$id/$artifact: codigo de campos",
                                )
                            }
                            "FIELDS_ACCEPT" -> {
                                assertEquals("ACCEPT", outcome, "$id/$artifact")
                            }
                            "CRYPTO_REJECT", "VERIFIED" -> {
                                val accepted = fields as AuthOkFields.Accepted
                                val actual = referenceCryptoOutcome(
                                    id, artifact, exp.path("crypto").asText(), accepted.authOk, challenge,
                                )
                                if (stage == "VERIFIED") {
                                    assertEquals("ACCEPT", outcome, "$id/$artifact")
                                    assertNull(actual, "$id/$artifact: la criptografia de referencia debe pasar")
                                } else {
                                    assertEquals("REJECT", outcome, "$id/$artifact")
                                    assertEquals(errorCode, actual, "$id/$artifact: criptografia de referencia")
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    /** Resuelve el dialecto efectivo del handshake o falla si el dialecto no pasa. */
    private fun effectiveDialect(
        id: String,
        artifact: String,
        challengeCheck: DialectCheck,
        client: Dialect,
        authOkWireDialect: String?,
    ): Dialect {
        assertTrue(challengeCheck is DialectCheck.Accepted) {
            "$id/$artifact: el dialecto del challenge deberia aceptarse en esta etapa"
        }
        val challengeEffective = (challengeCheck as DialectCheck.Accepted).dialect
        val okCheck = DialectNegotiation.checkAuthOk(client, challengeEffective, authOkWireDialect)
        assertTrue(okCheck is DialectCheck.Accepted) {
            "$id/$artifact: el dialecto del AUTH_OK deberia aceptarse en esta etapa"
        }
        return (okCheck as DialectCheck.Accepted).dialect
    }

    /** Aplica la capa de campos y afirma el resultado de la expectativa. */
    private fun parseForStage(
        stage: String,
        wire: String,
        effective: Dialect,
        id: String,
        artifact: String,
    ): AuthOkFields = when (stage) {
        "STRUCTURE" -> {
            assertThrows<IllegalArgumentException>("$id/$artifact: el parseo debe lanzar") {
                AuthJsonCodec.parseAuthOkForDialect(wire, effective)
            }
            // Devolvemos un centinela solo para la firma del `when`; no se usa.
            AuthOkFields.Rejected(AuthFieldError.MALFORMED_MESSAGE, "estructura invalida")
        }

        "FIELDS_REJECT" -> {
            val fields = AuthJsonCodec.parseAuthOkForDialect(wire, effective)
            assertTrue(fields is AuthOkFields.Rejected) { "$id/$artifact: se esperaba un rechazo de campos" }
            fields
        }

        "FIELDS_ACCEPT", "CRYPTO_REJECT", "VERIFIED" -> {
            val fields = AuthJsonCodec.parseAuthOkForDialect(wire, effective)
            assertTrue(fields is AuthOkFields.Accepted) { "$id/$artifact: se esperaba aceptacion de campos" }
            fields
        }

        else -> error("etapa desconocida '$stage' en $id/$artifact")
    }

    /**
     * Verificacion criptografica de REFERENCIA. Devuelve `null` si pasa, o el
     * codigo de error de la capa que falla.
     */
    private fun referenceCryptoOutcome(
        id: String,
        artifact: String,
        crypto: String,
        authOk: AuthOk,
        challenge: JsonNode,
    ): String? {
        // Las expectativas de FIELDS_* no declaran crypto; nunca llegan aqui.
        if (crypto.isEmpty()) return null

        return when (crypto) {
            "SERVER_SIGNATURE_VALID" -> {
                val verifier = AuthVerifier(ed25519, Clock { nowCanonical }, NonceReplayGuard { true })
                assertNull(verifier.verifyAuthOk(authOk, authOk.initiatorIdentityId)) {
                    "$id/$artifact: verifyAuthOk deberia pasar"
                }
                // Cross-check con los bytes congelados del server transcript.
                val serverTr = resourceBytes("/p6-vectors/$id/server_transcript.bin")
                assertEquals(
                    TranscriptBuilder.SERVER_AUTH_TRANSCRIPT_LEN, serverTr.size,
                    "$id: el server transcript debe medir 120 B",
                )
                assertTrue(ed25519.verify(authOk.responderPublicKey, serverTr, Signature(authOk.signature))) {
                    "$id/$artifact: la firma no verifica contra server_transcript.bin"
                }
                null
            }

            "SERVER_SIGNATURE" -> {
                val verifier = AuthVerifier(ed25519, Clock { nowCanonical }, NonceReplayGuard { true })
                val err = verifier.verifyAuthOk(authOk, authOk.initiatorIdentityId)
                if (err == null) error("$id/$artifact: la firma invalida no deberia verificar")
                err.name
            }

            "IDENTITY_ANCHOR" -> {
                val pin = challenge.path("responderIdentityId").asText()
                val derived = lowHex(KmIds.identityId(authOk.responderPublicKey))
                assertNotEquals(pin, derived, "$id/$artifact: la clave deberia anclar a una identidad distinta")
                "PIN_MISMATCH"
            }

            "CHALLENGE_CLAIM" -> {
                val vector = json("/p6-vectors/$id/vector.json")
                val pin = vector.path("inputs").path("responderIdentityId").asText()
                assertNotEquals(pin, challenge.path("responderIdentityId").asText()) {
                    "$id/$artifact: el claim del challenge deberia diferir del pin"
                }
                "PIN_MISMATCH"
            }

            "CHALLENGE_TIMESTAMP" -> {
                val verifier = AuthVerifier(ed25519, Clock { nowCanonical }, NonceReplayGuard { true })
                val err = verifier.verifyChallengeResponse(
                    challengeFrom(id, challenge),
                    responseFrom(id),
                )
                assertEquals(AuthError.INVALID_TIMESTAMP, err, "$id/$artifact")
                "INVALID_TIMESTAMP"
            }

            "INITIATOR_SIGNATURE" -> {
                val verifier = AuthVerifier(ed25519, Clock { nowCanonical }, NonceReplayGuard { true })
                val err = verifier.verifyChallengeResponse(
                    challengeFrom(id, challenge),
                    responseFrom(id),
                )
                assertEquals(AuthError.INVALID_SIGNATURE, err, "$id/$artifact")
                "INVALID_SIGNATURE"
            }

            else -> error("crypto desconocido '$crypto' en $id/$artifact")
        }
    }

    private fun challengeFrom(id: String, challenge: JsonNode): AuthChallenge = AuthChallenge(
        nonce = Base64Url.decode(challenge.path("nonce").asText()),
        timestampMillis = challenge.path("timestamp").asLong(),
        version = challenge.path("version").asInt(),
        responderIdentityId = challenge.path("responderIdentityId").asText(),
    )

    private fun responseFrom(id: String): AuthResponse {
        val resp = json("/p6-vectors/$id/response.json")
        return AuthResponse(
            identityId = resp.path("identityId").asText(),
            publicKey = Base64Url.decode(resp.path("publicKey").asText()),
            signature = Base64Url.decode(resp.path("signature").asText()),
            protocolVersion = Dialect.V2_0,
        )
    }

    // =====================================================================
    // Prueba explicita del arreglo: esquema legacy FALLA, esquema reparado PASA.
    // =====================================================================

    @Test
    fun `el esquema legacy pre-reparacion es RECHAZADO por el codec de produccion`() {
        // Forma EXACTA que emitio la primera tentativa de 1.4 (medida sobre los
        // 20 auth_ok.json): `signature` en vez de `serverSignature` y SIN
        // `initiatorIdentityId` ni `responderIdentityId`.
        val legacyShape = """
            {
              "sessionId": "b2aeff36f46a697d3287794e39ac64ed1dd3e7d0",
              "serverNonce": "IYoenlGkVa12ihR9aDTHiA",
              "responderPublicKey": "mDo4IQ6bRA2ahHRpeGPf-7-9T1XenN8hJXE82oZumvM",
              "signature": "ZCH9OweNslm5IR8swOgaGPlca_QbLPgi8WG092Z0iIJpKImfKOd6jWZ9UObGovbYvhNRfRPzS4a4N8UzXTrmDw"
            }
        """.trimIndent()

        // parseAuthOkForDialect: sin canonico ni alias -> Rejected MALFORMED_MESSAGE.
        val fields = AuthJsonCodec.parseAuthOkForDialect(legacyShape, Dialect.V2_0)
        assertTrue(fields is AuthOkFields.Rejected) {
            "el esquema legacy se acepto: el gate seria un falso verde"
        }
        assertEquals(AuthFieldError.MALFORMED_MESSAGE, (fields as AuthOkFields.Rejected).error)

        // El parseo estricto ademas LANZA: falta `initiatorIdentityId`.
        assertThrows<IllegalArgumentException> { AuthJsonCodec.parseAuthOk(legacyShape) }

        // Y un frame que solo renombra la firma (con initiator presente) tambien
        // se rechaza: `serverSignature` ausente no es negociable.
        val wrongSignatureName = resourceText("/p6-vectors/P6-A/auth_ok.json")
            .replace("\"serverSignature\"", "\"signature\"")
        assertThrows<IllegalArgumentException> {
            AuthJsonCodec.parseAuthOkForDialect(wrongSignatureName, Dialect.V2_0)
        }
    }

    @Test
    fun `el esquema reparado es ACEPTADO por el codec de produccion`() {
        val repaired = resourceText("/p6-vectors/P6-A/auth_ok.json")
        val fields = AuthJsonCodec.parseAuthOkForDialect(repaired, Dialect.V2_0)
        assertTrue(fields is AuthOkFields.Accepted, "el esquema reparado debe aceptarse")
        val accepted = fields as AuthOkFields.Accepted
        assertEquals(
            "7add69bd30b028ce2c7838680066069d2709b47a01cc480034a8f9677c22237f",
            accepted.authOk.initiatorIdentityId,
        )
        assertEquals(32, accepted.authOk.responderPublicKey.size)
        assertEquals(16, accepted.authOk.serverNonce.size)
        assertEquals(64, accepted.authOk.signature.size)
    }
}
