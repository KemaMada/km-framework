package com.km.auth

import com.fasterxml.jackson.databind.ObjectMapper
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.test.assertContentEquals

/**
 * Tests de AuthJsonCodec (3F.4).
 *
 * Verifica:
 * - round-trip JSON de cada tipo de mensaje
 * - campos faltantes lanzan
 * - tipo incorrecto lanza
 * - campos extra se ignoran
 * - reorden de campos no afecta el parseo
 */
class AuthJsonCodecTest {

    private val nonce16 = ByteArray(16) { it.toByte() }
    private val pubKey32 = ByteArray(32) { (it + 1).toByte() }
    private val sig64 = ByteArray(64) { (it * 2).toByte() }
    private val serverNonce16 = ByteArray(16) { (it + 16).toByte() }
    private val initiatorId = "a1b2c3d4e5f6a7b8c9d0e1f2a3b4c5d6e7f8a9b0c1d2e3f4a5b6c7d8e9f0a1b2"
    private val responderId = "f9e8d7c6b5a4f3e2d1c0b9a8f7e6d5c4b3a2f1e0d9c8b7a6958473625140f3e2d"

    @Test
    fun `challenge round-trip`() {
        val original = AuthChallenge(
            nonce = nonce16,
            timestampMillis = 1_721_827_200_000L,
            version = 3,
            responderIdentityId = "f9e8d7c6b5a4f3e2d1c0b9a8f7e6d5c4b3a2f1e0a1b2c3d4e5f6a7b8c9d0e1f2",
        )
        val wire = AuthJsonCodec.challengeWire(original)
        val parsed = AuthJsonCodec.parseChallenge(wire)
        assertContentEquals(original.nonce, parsed.nonce)
        assertEquals(original.timestampMillis, parsed.timestampMillis)
        assertEquals(original.version, parsed.version)
        assertEquals(original.responderIdentityId, parsed.responderIdentityId)
    }

    @Test
    fun `response round-trip`() {
        val original = AuthResponse(
            identityId = "a1b2c3d4e5f6a7b8c9d0e1f2a3b4c5d6e7f8a9b0c1d2e3f4a5b6c7d8e9f0a1b2",
            publicKey = pubKey32,
            signature = sig64,
            protocolVersion = Dialect.V2_0,
        )
        val wire = AuthJsonCodec.responseWire(original)
        val parsed = AuthJsonCodec.parseResponse(wire)
        assertEquals(original.identityId, parsed.identityId)
        assertContentEquals(original.publicKey, parsed.publicKey)
        assertContentEquals(original.signature, parsed.signature)
        assertEquals(original.protocolVersion, parsed.protocolVersion)
    }

    @Test
    fun `authOk round-trip con la tabla canonica de campos`() {
        val original = AuthOk(
            sessionId = "b0a9f8e7d6c5b4a3f2e1d0c9b8a7f6e5d4c3b2a1",
            serverNonce = serverNonce16,
            responderPublicKey = pubKey32,
            signature = sig64,
            initiatorIdentityId = initiatorId,
            responderIdentityId = responderId,
        )
        val wire = AuthJsonCodec.authOkWire(original)
        val parsed = AuthJsonCodec.parseAuthOk(wire)
        assertEquals(original.sessionId, parsed.sessionId)
        assertContentEquals(original.serverNonce, parsed.serverNonce)
        assertContentEquals(original.responderPublicKey, parsed.responderPublicKey)
        assertContentEquals(original.signature, parsed.signature)
        assertEquals(original.initiatorIdentityId, parsed.initiatorIdentityId)
        assertEquals(original.responderIdentityId, parsed.responderIdentityId)
    }

    /**
     * R15/R16: la clave JSON de cada campo es la canonica. Los nombres
     * retirados (`identityId`, `publicKey`, `signature`) NO aparecen.
     */
    @Test
    fun `authOk wire usa los nombres canonicos del cable`() {
        val wire = AuthJsonCodec.authOkWire(
            AuthOk(
                sessionId = "b0a9f8e7d6c5b4a3f2e1d0c9b8a7f6e5d4c3b2a1",
                serverNonce = serverNonce16,
                responderPublicKey = pubKey32,
                signature = sig64,
                initiatorIdentityId = initiatorId,
                responderIdentityId = responderId,
            )
        )
        val obj = ObjectMapper().readTree(wire)
        for (f in listOf(
            "initiatorIdentityId", "responderIdentityId", "sessionId",
            "serverNonce", "responderPublicKey", "serverSignature",
        )) {
            assertTrue(obj.has(f), "falta el campo canonico $f")
        }
        for (retired in listOf("identityId", "publicKey", "signature")) {
            assertFalse(obj.has(retired), "el nombre retirado $retired no debe emitirse")
        }
    }

    /**
     * R34: el dialecto es SIEMPRE string. Un numero (la version KM-0002) no es
     * un dialecto y falla en el parseo.
     */
    @Test
    fun `response con protocolVersion numerico lanza`() {
        assertThrows<IllegalArgumentException> {
            AuthJsonCodec.parseResponse(
                """{"identityId":"$initiatorId","publicKey":"${"A".repeat(43)}","signature":"${"A".repeat(86)}","protocolVersion":3}"""
            )
        }
    }

    /** Un string que no corresponde a ningun dialecto implementado tambien falla. */
    @Test
    fun `response con dialecto desconocido lanza`() {
        assertThrows<IllegalArgumentException> {
            AuthJsonCodec.parseResponse(
                """{"identityId":"$initiatorId","publicKey":"${"A".repeat(43)}","signature":"${"A".repeat(86)}","protocolVersion":"9.9"}"""
            )
        }
    }

    /**
     * `version` numerico de KM-0002 en el challenge NO se consume como dialecto:
     * sigue siendo un entero y un string no cuela.
     */
    @Test
    fun `challenge version sigue siendo el entero de KM-0002, no un dialecto`() {
        val original = AuthChallenge(
            nonce = nonce16,
            timestampMillis = 1,
            version = 3,
            responderIdentityId = responderId,
        )
        // El codec emite `version` (entero, RFC KM-0002); NUNCA la clave
        // `protocolVersion` del dialecto.
        val obj = ObjectMapper().readTree(AuthJsonCodec.challengeWire(original))
        assertTrue(obj.has("version"), "el challenge lleva `version` (KM-0002)")
        assertFalse(obj.has("protocolVersion"), "el challenge NO lleva `protocolVersion`")
        assertEquals(3, obj.get("version").asInt())

        val parsed = AuthJsonCodec.parseChallenge(
            """{"nonce":"${"A".repeat(22)}","timestamp":1,"version":3,"responderIdentityId":"$responderId"}"""
        )
        assertEquals(3, parsed.version)
        // Un `version` textual (como seria un dialecto) se rechaza: aqui solo
        // entra el entero de KM-0002.
        assertThrows<IllegalArgumentException> {
            AuthJsonCodec.parseChallenge(
                """{"nonce":"${"A".repeat(22)}","timestamp":1,"version":"2.0","responderIdentityId":"$responderId"}"""
            )
        }
    }

    // ===================================================================
    // Campos faltantes lanzan
    // ===================================================================

    @Test
    fun `challenge sin nonce lanza`() {
        assertThrows(IllegalArgumentException::class.java) {
            AuthJsonCodec.parseChallenge("""{"timestamp":1,"version":3,"responderIdentityId":"${"0".repeat(64)}"}""")
        }
    }

    @Test
    fun `response sin publicKey lanza`() {
        assertThrows(IllegalArgumentException::class.java) {
            AuthJsonCodec.parseResponse("""{"identityId":"${"0".repeat(64)}","signature":"${"A".repeat(86)}","protocolVersion":3}""")
        }
    }

    @Test
    fun `authOk sin serverSignature lanza`() {
        assertThrows(IllegalArgumentException::class.java) {
            AuthJsonCodec.parseAuthOk(
                """{"initiatorIdentityId":"$initiatorId","responderIdentityId":"$responderId","sessionId":"${"0".repeat(40)}","serverNonce":"${"A".repeat(22)}","responderPublicKey":"${"A".repeat(43)}"}"""
            )
        }
    }

    /** Campos ausentes del `AUTH_OK` canonico fallan cerrado (R15). */
    @Test
    fun `authOk sin initiatorIdentityId lanza`() {
        assertThrows(IllegalArgumentException::class.java) {
            AuthJsonCodec.parseAuthOk(
                """{"responderIdentityId":"$responderId","sessionId":"${"0".repeat(40)}","serverNonce":"${"A".repeat(22)}","responderPublicKey":"${"A".repeat(43)}","serverSignature":"${"A".repeat(86)}"}"""
            )
        }
    }

    /**
     * R15: los ids canonicos son requeridos y NO vacios. Un id "" no es un id
     * valido; el parseo debe fallar cerrado.
     */
    @Test
    fun `authOk con initiatorIdentityId vacio lanza`() {
        assertThrows<IllegalArgumentException> {
            AuthJsonCodec.parseAuthOk(
                """{"initiatorIdentityId":"","responderIdentityId":"$responderId","sessionId":"${"0".repeat(40)}","serverNonce":"${"A".repeat(22)}","responderPublicKey":"${"A".repeat(43)}","serverSignature":"${"A".repeat(86)}"}"""
            )
        }
    }

    @Test
    fun `authOk con responderIdentityId vacio lanza`() {
        assertThrows<IllegalArgumentException> {
            AuthJsonCodec.parseAuthOk(
                """{"initiatorIdentityId":"$initiatorId","responderIdentityId":"","sessionId":"${"0".repeat(40)}","serverNonce":"${"A".repeat(22)}","responderPublicKey":"${"A".repeat(43)}","serverSignature":"${"A".repeat(86)}"}"""
            )
        }
    }

    /**
     * R15: la serializacion tambien rechaza un id vacio. [AuthOk] trae `""`
     * por defecto para los llamantes que solo verifican, pero un `AUTH_OK` de
     * cable con un id vacio no es valido y no debe poder emitirse.
     */
    @Test
    fun `authOkWire con initiatorIdentityId vacio lanza`() {
        assertThrows<IllegalArgumentException> {
            AuthJsonCodec.authOkWire(
                AuthOk(
                    sessionId = "b0a9f8e7d6c5b4a3f2e1d0c9b8a7f6e5d4c3b2a1",
                    serverNonce = serverNonce16,
                    responderPublicKey = pubKey32,
                    signature = sig64,
                    initiatorIdentityId = "",
                    responderIdentityId = responderId,
                )
            )
        }
    }

    @Test
    fun `authOkWire con responderIdentityId vacio lanza`() {
        assertThrows<IllegalArgumentException> {
            AuthJsonCodec.authOkWire(
                AuthOk(
                    sessionId = "b0a9f8e7d6c5b4a3f2e1d0c9b8a7f6e5d4c3b2a1",
                    serverNonce = serverNonce16,
                    responderPublicKey = pubKey32,
                    signature = sig64,
                    initiatorIdentityId = initiatorId,
                    responderIdentityId = "",
                )
            )
        }
    }

    @Test
    fun `authOk sin responderPublicKey lanza`() {
        assertThrows(IllegalArgumentException::class.java) {
            AuthJsonCodec.parseAuthOk(
                """{"initiatorIdentityId":"$initiatorId","responderIdentityId":"$responderId","sessionId":"${"0".repeat(40)}","serverNonce":"${"A".repeat(22)}","serverSignature":"${"A".repeat(86)}"}"""
            )
        }
    }

    /** Codificacion Base64URL invalida (padding) en un campo de bytes: rechazo. */
    @Test
    fun `authOk con serverNonce con padding lanza`() {
        assertThrows(Exception::class.java) {
            AuthJsonCodec.parseAuthOk(
                """{"initiatorIdentityId":"$initiatorId","responderIdentityId":"$responderId","sessionId":"${"0".repeat(40)}","serverNonce":"AAAAAAAAAAAAAAAAAAAAAA==","responderPublicKey":"${"A".repeat(43)}","serverSignature":"${"A".repeat(86)}"}"""
            )
        }
    }

    // ===================================================================
    // Task 1.3 — transicion del alias identityId (R2/R3, S13/S14/S15)
    // ===================================================================

    /**
     * AUTH_OK con los campos de bytes canonicos y los campos del iniciador que
     * reciba [initiatorFields]. El prefijo debe terminar en coma (o ser vacio).
     */
    private fun authOkAliasJson(initiatorFields: String): String =
        """{$initiatorFields"responderIdentityId":"$responderId","sessionId":"${"0".repeat(40)}","serverNonce":"${Base64Url.encode(serverNonce16)}","responderPublicKey":"${Base64Url.encode(pubKey32)}","serverSignature":"${Base64Url.encode(sig64)}"}"""

    private fun parseAlias(dialect: Dialect, initiatorFields: String): AuthOkFields =
        AuthJsonCodec.parseAuthOkForDialect(authOkAliasJson(initiatorFields), dialect)

    private fun acceptedId(result: AuthOkFields): String =
        assertInstanceOf(AuthOkFields.Accepted::class.java, result).authOk.initiatorIdentityId

    private fun rejectCode(result: AuthOkFields): AuthFieldError =
        assertInstanceOf(AuthOkFields.Rejected::class.java, result).error

    /**
     * TABLA DE VERDAD autoritativa del alias (ruling del owner, 2026-10-13):
     * las diez celdas dialecto x campos. Es la fuente que el resto de los tests
     * de alias desglosa; cualquier cambio de comportamiento debe mover esta
     * tabla primero.
     */
    @Test
    fun `tabla de verdad del alias identityId en 2_0 y en 2_1`() {
        val canonicalOnly = """"initiatorIdentityId":"$initiatorId","""
        val legacyOnly = """"identityId":"$initiatorId","""
        val bothEqual = """"initiatorIdentityId":"$initiatorId","identityId":"$initiatorId","""
        val bothDifferent = """"initiatorIdentityId":"$initiatorId","identityId":"${"0".repeat(64)}","""
        val neither = ""

        // 2.0 — ventana de transicion: el alias se admite.
        assertEquals(initiatorId, acceptedId(parseAlias(Dialect.V2_0, canonicalOnly)))
        assertEquals(initiatorId, acceptedId(parseAlias(Dialect.V2_0, legacyOnly)))
        assertEquals(initiatorId, acceptedId(parseAlias(Dialect.V2_0, bothEqual)))
        assertEquals(AuthFieldError.MALFORMED_MESSAGE, rejectCode(parseAlias(Dialect.V2_0, bothDifferent)))
        assertEquals(AuthFieldError.MALFORMED_MESSAGE, rejectCode(parseAlias(Dialect.V2_0, neither)))

        // 2.1 — retirada del alias: CUALQUIER presencia es UNKNOWN_PROTOCOL.
        assertEquals(initiatorId, acceptedId(parseAlias(Dialect.V2_1, canonicalOnly)))
        assertEquals(AuthFieldError.UNKNOWN_PROTOCOL, rejectCode(parseAlias(Dialect.V2_1, legacyOnly)))
        assertEquals(AuthFieldError.UNKNOWN_PROTOCOL, rejectCode(parseAlias(Dialect.V2_1, bothEqual)))
        assertEquals(AuthFieldError.UNKNOWN_PROTOCOL, rejectCode(parseAlias(Dialect.V2_1, bothDifferent)))
        assertEquals(AuthFieldError.MALFORMED_MESSAGE, rejectCode(parseAlias(Dialect.V2_1, neither)))
    }

    /** S13: en 2.0 el canonico se acepta tal cual. */
    @Test
    fun `2_0 acepta initiatorIdentityId canonico`() {
        val result = parseAlias(Dialect.V2_0, """"initiatorIdentityId":"$initiatorId",""")
        assertEquals(initiatorId, acceptedId(result))
    }

    /** S13/R2: en 2.0 el alias legacy en solitario se acepta como iniciador. */
    @Test
    fun `2_0 acepta el alias identityId en solitario`() {
        val result = parseAlias(Dialect.V2_0, """"identityId":"$initiatorId",""")
        assertEquals(initiatorId, acceptedId(result))
    }

    /**
     * R3/S13: en 2.0, si ambos conviven y son iguales, se acepta (el valor
     * canonico gobierna). En 2.1 NO: el alias esta retirado y su presencia,
     * aunque el valor coincida, se rechaza con UNKNOWN_PROTOCOL (ruling del
     * owner, celda 8 de la tabla de verdad).
     */
    @Test
    fun `ambos campos iguales se aceptan en 2_0 y se rechazan en 2_1`() {
        val fields = """"initiatorIdentityId":"$initiatorId","identityId":"$initiatorId","""
        assertEquals(initiatorId, acceptedId(parseAlias(Dialect.V2_0, fields)))
        assertEquals(AuthFieldError.UNKNOWN_PROTOCOL, rejectCode(parseAlias(Dialect.V2_1, fields)))
    }

    /**
     * S14/P6-T: en la ventana de transicion (2.0) si ambos conviven y difieren
     * -> MALFORMED_MESSAGE. En 2.1 el alias se rechaza ANTES de comparar
     * (UNKNOWN_PROTOCOL): el nombre esta retirado (ruling del owner).
     */
    @Test
    fun `ambos campos distintos son MALFORMED_MESSAGE en 2_0 y en 2_1`() {
        val other = "0".repeat(64)
        val fields = """"initiatorIdentityId":"$initiatorId","identityId":"$other","""
        assertEquals(AuthFieldError.MALFORMED_MESSAGE, rejectCode(parseAlias(Dialect.V2_0, fields)))
        assertEquals(AuthFieldError.UNKNOWN_PROTOCOL, rejectCode(parseAlias(Dialect.V2_1, fields)))
    }

    /** S15/P6-S: en 2.1 el alias en solitario es UNKNOWN_PROTOCOL (retirado). */
    @Test
    fun `2_1 rechaza el alias identityId en solitario con UNKNOWN_PROTOCOL`() {
        val result = parseAlias(Dialect.V2_1, """"identityId":"$initiatorId",""")
        assertEquals(AuthFieldError.UNKNOWN_PROTOCOL, rejectCode(result))
        assertEquals("UNKNOWN_PROTOCOL", AuthFieldError.UNKNOWN_PROTOCOL.wireCode)
    }

    /** R2: en 2.1 el canonico sigue siendo la unica forma valida. */
    @Test
    fun `2_1 acepta initiatorIdentityId canonico`() {
        val result = parseAlias(Dialect.V2_1, """"initiatorIdentityId":"$initiatorId",""")
        assertEquals(initiatorId, acceptedId(result))
    }

    /** R15: sin canonico ni alias no hay iniciador -> MALFORMED_MESSAGE. */
    @Test
    fun `alias ausente es MALFORMED_MESSAGE en ambos dialectos`() {
        assertEquals(AuthFieldError.MALFORMED_MESSAGE, rejectCode(parseAlias(Dialect.V2_0, "")))
        assertEquals(AuthFieldError.MALFORMED_MESSAGE, rejectCode(parseAlias(Dialect.V2_1, "")))
    }

    /** Un alias canonico presente pero VACIO no es un id valido (R15). */
    @Test
    fun `initiatorIdentityId vacio es MALFORMED_MESSAGE en ambos dialectos`() {
        val fields = """"initiatorIdentityId":"","""
        assertEquals(AuthFieldError.MALFORMED_MESSAGE, rejectCode(parseAlias(Dialect.V2_0, fields)))
        assertEquals(AuthFieldError.MALFORMED_MESSAGE, rejectCode(parseAlias(Dialect.V2_1, fields)))
    }

    /**
     * El alias legacy vacio tampoco es un id valido en 2.0 (R15). En 2.1 el
     * campo esta retirado: su PRESENCIA (aunque sea vacia) es UNKNOWN_PROTOCOL,
     * porque la retirada mira el nombre, no el valor.
     */
    @Test
    fun `identityId vacio es MALFORMED_MESSAGE en 2_0 y UNKNOWN_PROTOCOL en 2_1`() {
        val fields = """"identityId":"","""
        assertEquals(AuthFieldError.MALFORMED_MESSAGE, rejectCode(parseAlias(Dialect.V2_0, fields)))
        assertEquals(AuthFieldError.UNKNOWN_PROTOCOL, rejectCode(parseAlias(Dialect.V2_1, fields)))
    }

    /** El emisor solo escribe el canonico; el parser estricto lo reafirma. */
    @Test
    fun `parseAuthOk estricto sigue exigiendo el canonico, sin alias`() {
        assertThrows<IllegalArgumentException> {
            AuthJsonCodec.parseAuthOk(authOkAliasJson(""""identityId":"$initiatorId","""))
        }
    }

    // ===================================================================
    // Campos extra se ignoran
    // ===================================================================
    @Test
    fun `challenge con campo extra se parsea correctamente`() {
        val json = """{"nonce":"${Base64Url.encode(nonce16)}","timestamp":1,"version":3,"responderIdentityId":"${"0".repeat(64)}","extra":"ignored"}"""
        val parsed = AuthJsonCodec.parseChallenge(json)
        assertNotNull(parsed)
    }

    // ===================================================================
    // Reorden de campos no afecta parseo
    // ===================================================================

    @Test
    fun `response con reorden de campos es equivalente`() {
        val original = AuthResponse(
            identityId = "a1b2c3d4e5f6a7b8c9d0e1f2a3b4c5d6e7f8a9b0c1d2e3f4a5b6c7d8e9f0a1b2",
            publicKey = pubKey32,
            signature = sig64,
            protocolVersion = Dialect.V2_0,
        )
        val wire = AuthJsonCodec.responseWire(original)
        // Construir JSON con orden inverso de claves
        val reordered = """{"protocolVersion":"2.0","signature":"${Base64Url.encode(sig64)}","publicKey":"${Base64Url.encode(pubKey32)}","identityId":"${
            "a1b2c3d4e5f6a7b8c9d0e1f2a3b4c5d6e7f8a9b0c1d2e3f4a5b6c7d8e9f0a1b2"
        }"}"""
        val parsed = AuthJsonCodec.parseResponse(reordered)
        assertEquals(original.identityId, parsed.identityId)
        assertContentEquals(original.publicKey, parsed.publicKey)
        assertContentEquals(original.signature, parsed.signature)
    }

    // ===================================================================
    // JSON malformado
    // ===================================================================

    @Test
    fun `JSON no valido lanza`() {
        assertThrows(Exception::class.java) {
            AuthJsonCodec.parseChallenge("not json")
        }
    }

    @Test
    fun `JSON array lanza`() {
        assertThrows(IllegalArgumentException::class.java) {
            AuthJsonCodec.parseChallenge("[]")
        }
    }
}