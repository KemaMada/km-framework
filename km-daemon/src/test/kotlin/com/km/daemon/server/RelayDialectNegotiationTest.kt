package com.km.daemon.server

import com.fasterxml.jackson.databind.ObjectMapper
import com.km.auth.Dialect
import com.km.crypto.Ed25519
import com.km.crypto.Ed25519Impl
import com.km.identity.KmIds
import com.km.model.IdentityId
import com.km.node.RelayServer
import com.km.node.RelayServiceImpl
import com.km.storage.InMemoryRelayStore
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.assertInstanceOf

/**
 * Task 1.1 — el rele confirma el dialecto del handshake (direccion de respuesta).
 *
 * Fija dos cosas del lado del daemon:
 *
 * 1. `AUTH_CHALLENGE` y `AUTH_OK` LLEVAN `protocolVersion` como STRING (R15,
 *    R34 / §9.7); antes el challenge no lo emitia en absoluto, que es el hueco
 *    A3 que el cliente no podia detectar.
 * 2. `AuthAdapter.begin` acepta exactamente el dialecto declarado si lo sirve y
 *    rechaza cualquier otro con `UNSUPPORTED_PROTOCOL_VERSION`, sin downgrade,
 *    con eco diagnostico en el `AUTH_FAIL` (N6) que NUNCA implica aceptacion.
 */
class RelayDialectNegotiationTest {

    private val mapper = ObjectMapper()
    private val ed25519: Ed25519 = Ed25519Impl()
    private val nowMs = 1_790_000_000_000L

    private val relayKp = ed25519.generateKeyPair()
    private val relayId = IdentityId(
        KmIds.identityId(relayKp.publicKey).joinToString("") { "%02x".format(it) }
    )
    private val codec = JsonWireCodec()

    private val relayServer = RelayServer(
        relayKeyPair = relayKp,
        ed25519 = ed25519,
        relayService = RelayServiceImpl(relayStore = InMemoryRelayStore(now = { nowMs }), now = { nowMs }),
        relayIdentityId = relayId,
        now = { nowMs },
    )

    private val adapter = AuthAdapter(relayServer, relayId, codec)

    private val clientId = IdentityId("a".repeat(64))

    private fun request(declared: String) = AuthRequestFields(
        identityId = clientId,
        publicKey = ByteArray(32) { it.toByte() },
        protocolVersion = declared,
        responderIdentityId = null,
    )

    // ===================================================================
    // Emision: el dialecto viaja como string en challenge y en OK
    // ===================================================================

    @Test
    fun `el AUTH_CHALLENGE lleva protocolVersion como string`() {
        val frame = mapper.readTree(
            codec.authChallenge(
                nonce = ByteArray(16) { it.toByte() },
                timestamp = nowMs,
                responderIdentityId = relayId.value,
                dialect = Dialect.V2_0.wire,
            )
        )
        assertTrue(frame.get("protocolVersion").isTextual, "protocolVersion debe ser un string, no un numero (R34)")
        assertEquals("2.0", frame.get("protocolVersion").asText())
    }

    @Test
    fun `el AUTH_OK confirma el mismo dialecto, como string`() {
        val frame = mapper.readTree(
            codec.authOk(
                sessionId = "b".repeat(40),
                serverSignature = ByteArray(64),
                initiatorIdentityId = clientId.value,
                responderIdentityId = relayId.value,
                dialect = Dialect.V2_0.wire,
            )
        )
        assertTrue(frame.get("protocolVersion").isTextual)
        assertEquals("2.0", frame.get("protocolVersion").asText())
    }

    @Test
    fun `el AUTH_FAIL puede llevar protocolVersion solo como diagnostico`() {
        val withEcho = mapper.readTree(
            codec.authFail(
                AuthAdapter.ErrorCode.UNSUPPORTED_PROTOCOL_VERSION,
                "no soportado",
                dialect = "9.9",
            )
        )
        assertEquals("9.9", withEcho.get("protocolVersion").asText())

        val withoutEcho = mapper.readTree(codec.authFail(AuthAdapter.ErrorCode.AUTH_FAILED, "firma mala"))
        assertFalse(withoutEcho.has("protocolVersion"), "el eco es opcional (N6)")
    }

    // ===================================================================
    // Negociacion: begin acepta el declarado y rechaza el resto
    // ===================================================================

    @Test
    fun `begin acepta el dialecto servido y lo confirma en el challenge`() {
        val step = adapter.begin(request("2.0"))
        val challenge = assertInstanceOf(AuthStep.Challenge::class.java, step)
        val frame = mapper.readTree(challenge.wire)
        assertEquals("2.0", frame.get("protocolVersion").asText())
    }

    @Test
    fun `begin rechaza un dialecto desconocido sin challenge`() {
        val step = adapter.begin(request("9.9"))
        val rejected = assertInstanceOf(AuthStep.Rejected::class.java, step)
        val frame = mapper.readTree(rejected.wire)
        assertEquals("AUTH_FAIL", frame.get("type").asText())
        assertEquals(
            AuthAdapter.ErrorCode.UNSUPPORTED_PROTOCOL_VERSION,
            frame.get("errorCode").asText(),
        )
        // Eco diagnostico del dialecto pedido (N6), jamas prueba de aceptacion.
        assertEquals("9.9", frame.get("protocolVersion").asText())
    }

    @Test
    fun `begin rechaza un dialecto 2_1 no implementado todavia (sin downgrade)`() {
        val step = adapter.begin(request("2.1"))
        val rejected = assertInstanceOf(AuthStep.Rejected::class.java, step)
        val frame = mapper.readTree(rejected.wire)
        assertEquals(
            AuthAdapter.ErrorCode.UNSUPPORTED_PROTOCOL_VERSION,
            frame.get("errorCode").asText(),
        )
    }
}
