package com.km.daemon.server

import com.fasterxml.jackson.databind.ObjectMapper
import com.km.auth.Base64Url
import com.km.auth.Dialect
import com.km.auth.TranscriptBuilder
import com.km.crypto.Ed25519
import com.km.crypto.Ed25519Impl
import com.km.identity.KmIds
import com.km.model.IdentityId
import com.km.node.RelayServer
import com.km.node.RelayServiceImpl
import com.km.storage.InMemoryRelayStore
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Test

/**
 * Task 1.1/1.3 — la negociacion de dialecto en el daemon (direccion de
 * respuesta) y la coherencia del dialecto a lo largo del handshake.
 *
 * Fija el comportamiento de `AuthAdapter.begin`/`complete`: acepta exactamente
 * el dialecto declarado si lo sirve y rechaza cualquier otro con
 * `UNSUPPORTED_PROTOCOL_VERSION`, sin downgrade, con eco diagnostico en el
 * `AUTH_FAIL` (N6) que NUNCA implica aceptacion. Desde la task 1.3 el conjunto
 * servido es `{2.0, 2.1}` y un handshake 2.1 completo mantiene el dialecto
 * coherente entre `AUTH_CHALLENGE` y `AUTH_OK`. La EMISION del campo
 * `protocolVersion` en el cable (challenge/OK/FAIL) se prueba ademas en
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

    // Un cliente real: su identityId DERIVA de su clave publica, para poder
    // completar la firma del transcript de 152 B.
    private val clientKp = ed25519.generateKeyPair()
    private val realClientId = IdentityId(
        KmIds.identityId(clientKp.publicKey).joinToString("") { "%02x".format(it) }
    )

    private fun realRequest(declared: String) = AuthRequestFields(
        identityId = realClientId,
        publicKey = clientKp.publicKey,
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

    // ===================================================================
    // Task 1.3 — el rele sirve el conjunto de transicion {2.0, 2.1}
    // ===================================================================

    /** El rele sirve los dos dialectos de la politica de transicion. */
    @Test
    fun `begin acepta el conjunto de transicion completo y lo confirma`() {
        for (d in Dialect.TRANSITIONAL.map { it.wire }.sorted()) {
            val step = adapter.begin(request(d))
            val challenge = assertInstanceOf(AuthStep.Challenge::class.java, step)
            assertEquals(d, mapper.readTree(challenge.wire).get("protocolVersion").asText())
        }
    }

    /** No hay downgrade: un dialecto que el rele no sirve se rechaza aunque sea menor. */
    @Test
    fun `begin rechaza un dialecto no servido sin downgrade`() {
        // Un rele que solo sirve 2.1 rechaza un 2.0 declarado, sin bajarselo.
        val only21 = AuthAdapter(relayServer, relayId, codec, supportedDialects = setOf(Dialect.V2_1))
        val step = only21.begin(request("2.0"))
        val rejected = assertInstanceOf(AuthStep.Rejected::class.java, step)
        val frame = mapper.readTree(rejected.wire)
        assertEquals(
            AuthAdapter.ErrorCode.UNSUPPORTED_PROTOCOL_VERSION,
            frame.get("errorCode").asText(),
        )
    }

    /**
     * Control 2: el rele NO anuncia 2.1 si no puede completar un handshake 2.1.
     * Aqui lo completa, y el `protocolVersion` se mantiene coherente entre el
     * `AUTH_CHALLENGE` y el `AUTH_OK` del mismo handshake.
     */
    @Test
    fun `handshake 2_1 completo mantiene el dialecto coherente en challenge y AUTH_OK`() {
        val req = realRequest("2.1")
        val challengeStep = assertInstanceOf(AuthStep.Challenge::class.java, adapter.begin(req))
        val challengeFrame = mapper.readTree(challengeStep.wire)
        assertEquals("2.1", challengeFrame.get("protocolVersion").asText())

        val nonce = Base64Url.decode(challengeFrame.get("nonce").asText())
        val timestamp = challengeFrame.get("timestamp").asLong()
        val transcript = TranscriptBuilder.authTranscript(
            nonce = nonce,
            timestampMillis = timestamp,
            responderIdentityId = relayId.value,
            identityId = realClientId.value,
        )
        val signature = ed25519.sign(clientKp.privateKey, transcript).bytes

        val accepted = assertInstanceOf(AuthStep.Accepted::class.java, adapter.complete(req, signature))
        val okFrame = mapper.readTree(accepted.wire)
        assertEquals("AUTH_OK", okFrame.get("type").asText())
        // Coherencia intra-handshake (R31/R33): el OK confirma el MISMO dialecto.
        assertEquals("2.1", okFrame.get("protocolVersion").asText())
        assertFalse(okFrame.get("protocolVersion").isNumber, "el dialecto jamas es un numero (R34)")
        // El iniciador va con el nombre canonico; el rele nunca emite el alias.
        assertEquals(realClientId.value, okFrame.get("initiatorIdentityId").asText())
        assertFalse(okFrame.has("identityId"), "el rele no emite el alias legacy identityId")
        assertFalse(okFrame.has("publicKey"), "el rele no emite el nombre retirado publicKey")
        assertFalse(okFrame.has("signature"), "el rele no emite el nombre retirado signature")
        assertEquals(relayId.value, okFrame.get("responderIdentityId").asText())
    }
}
