package com.km.daemon.server

import com.fasterxml.jackson.databind.ObjectMapper
import com.km.crypto.Ed25519
import com.km.crypto.Ed25519Impl
import com.km.identity.KmIds
import com.km.model.IdentityId
import com.km.node.RelayServer
import com.km.node.RelayServiceImpl
import com.km.storage.InMemoryRelayStore
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.assertInstanceOf

/**
 * Task 1.1 — la negociacion de dialecto en el daemon (direccion de respuesta).
 *
 * Fija el comportamiento de `AuthAdapter.begin`: acepta exactamente el dialecto
 * declarado si lo sirve y rechaza cualquier otro con
 * `UNSUPPORTED_PROTOCOL_VERSION`, sin downgrade, con eco diagnostico en el
 * `AUTH_FAIL` (N6) que NUNCA implica aceptacion. La EMISION del campo
 * `protocolVersion` en el cable (challenge/OK/FAIL) se prueba en
 * `JsonWireCodecMessageTest`.
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
