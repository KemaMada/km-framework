package com.km.daemon.server

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.km.auth.Base64Url
import com.km.auth.Dialect
import com.km.auth.DialectCheck
import com.km.auth.DialectNegotiation
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
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Task 1.3 / control 4 — la matriz de compatibilidad de P6 como RESULTADOS de
 * test, no como prosa.
 *
 * La matriz (obs #174 Parte 2, R24/S16) es una tabla de compatibilidad con DOS
 * ejes independientes que este test mantiene separados a proposito:
 *
 *  - **`connects`**: si el handshake COMPLETA. Lo decide la negociacion real de
 *    dialecto (relé: `AuthAdapter.begin`/`complete`; cliente:
 *    `DialectNegotiation.checkChallenge`/`checkAuthOk`) mas la presencia de los
 *    campos canonicos del `AUTH_OK`.
 *  - **`p6Protected`**: si la sesion queda **autenticada mutuamente** por P6. Es
 *    un predicado de la especificacion: dialecto coherente **Y** el cliente
 *    VERIFICA `serverSignature` **Y** el relé aporta el material de verificacion
 *    (`serverNonce`, `responderPublicKey`) anclado al pin.
 *
 * La distincion es la que exige el owner: **un cliente antiguo que conecta NO
 * queda protegido por P6**, aunque haya conectado. `connects` y `p6Protected`
 * son cosas distintas y este test las afirma por separado.
 *
 * ALCANCE HONESTO: la verificacion de `serverSignature` en el cliente NO existe
 * todavia (es P6 / 4.1 Android, 5.2 escritorio). Por eso el cliente P6
 * "conforme" de la fila 3 es un PERFIL de la especificacion, no un cliente
 * entregado: el test lo declara explicitamente y ademas afirma que los clientes
 * que SI se entregan hoy ([DELIVERED_ANDROID], [DELIVERED_DESKTOP]) no verifican
 * la firma y por tanto **ninguna fila los declara protegidos**. Ningun cliente
 * entregado es P6-protegido hoy.
 */
class P6CompatibilityMatrixTest {

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

    private val clientKp = ed25519.generateKeyPair()
    private val clientId = IdentityId(
        KmIds.identityId(clientKp.publicKey).joinToString("") { "%02x".format(it) }
    )

    // ===================================================================
    // Perfiles de la matriz
    // ===================================================================

    /** Perfil de CLIENTE: dialecto implementado + si verifica la respuesta. */
    private data class ClientProfile(
        val name: String,
        val dialect: Dialect,
        val checksResponseDialect: Boolean,
        val verifiesServerSignature: Boolean,
    )

    /** Perfil de RELE: conjunto servido + si emite el material de verificacion. */
    private data class RelayProfile(
        val name: String,
        val served: Set<Dialect>,
        val emitsVerificationMaterial: Boolean,
    )

    private data class Outcome(val connects: Boolean, val p6Protected: Boolean, val note: String)

    // Clientes.
    /** Cliente pre-P6: declara 2.0, ignora el dialecto de la respuesta, no verifica nada. */
    private val legacyOldClient = ClientProfile("old-client(pre-P6)", Dialect.V2_0, checksResponseDialect = false, verifiesServerSignature = false)
    /** Cliente consciente de dialecto 2.0: verifica el dialecto, NO verifica la firma. */
    private val client20 = ClientProfile("client-2.0", Dialect.V2_0, checksResponseDialect = true, verifiesServerSignature = false)
    /** Cliente P6 conforme 2.1: verifica el dialecto Y la firma del servidor. */
    private val client21 = ClientProfile("client-2.1(conforme)", Dialect.V2_1, checksResponseDialect = true, verifiesServerSignature = true)
    /** Lo que HOY se entrega: dialecto 2.0 verificado, sin verificacion de firma (4.1/5.2 pendientes). */
    private val DELIVERED_ANDROID = ClientProfile("delivered-android", Dialect.V2_0, checksResponseDialect = true, verifiesServerSignature = false)
    private val DELIVERED_DESKTOP = ClientProfile("delivered-desktop", Dialect.V2_0, checksResponseDialect = true, verifiesServerSignature = false)

    // Reles.
    /** Relé pre-P6: su `AUTH_OK` 2.0 NO trae `serverNonce`/`responderPublicKey`. */
    private val oldRelayNoMaterial = RelayProfile("old-daemon(2.0,sin material)", setOf(Dialect.V2_0), emitsVerificationMaterial = false)
    /** Relé de transicion {2.0,2.1} con material completo (el `AuthAdapter` real). */
    private val transitionalRelay = RelayProfile("updated-relay{2.0,2.1}", Dialect.TRANSITIONAL, emitsVerificationMaterial = true)
    /** Relé final 2.1-only (2.0 retirado). */
    private val finalRelay21 = RelayProfile("updated-relay(2.1-only)", setOf(Dialect.V2_1), emitsVerificationMaterial = true)
    /** Relé que solo sirve 2.0 con material. */
    private val relay20Only = RelayProfile("relay(2.0-only)", setOf(Dialect.V2_0), emitsVerificationMaterial = true)

    // ===================================================================
    // Motor: negocia con componentes REALES y decide connect/protected
    // ===================================================================

    private fun signChallenge(challengeFrame: JsonNode): ByteArray {
        val nonce = Base64Url.decode(challengeFrame.get("nonce").asText())
        val timestamp = challengeFrame.get("timestamp").asLong()
        val transcript = TranscriptBuilder.authTranscript(
            nonce = nonce,
            timestampMillis = timestamp,
            responderIdentityId = relayId.value,
            identityId = clientId.value,
        )
        return ed25519.sign(clientKp.privateKey, transcript).bytes
    }

    /** `AUTH_OK` de un relé pre-P6: 2.0, sin `serverNonce`/`responderPublicKey`. */
    private fun legacyAuthOkFrame(dialect: String): JsonNode {
        val node = mapper.createObjectNode()
        node.put("type", "AUTH_OK")
        node.put("protocolVersion", dialect)
        node.put("initiatorIdentityId", clientId.value)
        node.put("responderIdentityId", relayId.value)
        node.put("sessionId", "a".repeat(40))
        return node
    }

    private fun runHandshake(relay: RelayProfile, client: ClientProfile): Outcome {
        val adapter = AuthAdapter(relayServer, relayId, codec, supportedDialects = relay.served)
        val request = AuthRequestFields(
            identityId = clientId,
            publicKey = clientKp.publicKey,
            protocolVersion = client.dialect.wire,
            responderIdentityId = null,
        )

        // 1. Negociacion en el relé (REAL).
        when (val begin = adapter.begin(request)) {
            is AuthStep.Rejected -> return Outcome(false, false, "relé rechaza '${client.dialect.wire}' (sin downgrade)")
            is AuthStep.Accepted -> return Outcome(false, false, "begin no emite AUTH_OK")
            is AuthStep.Challenge -> {
                val challengeFrame = mapper.readTree(begin.wire)
                val challengeDialect = Dialect.fromWireOrNull(challengeFrame.get("protocolVersion").asText())
                    ?: return Outcome(false, false, "challenge sin dialecto")

                // 2. Verificacion del dialecto del challenge en el cliente (REAL, task 1.1).
                if (client.checksResponseDialect &&
                    DialectNegotiation.checkChallenge(client.dialect, challengeDialect.wire) !is DialectCheck.Accepted
                ) {
                    return Outcome(false, false, "cliente rechaza el dialecto del challenge")
                }

                // 3. Emision del AUTH_OK.
                val okFrame: JsonNode = if (relay.emitsVerificationMaterial) {
                    when (val done = adapter.complete(request, signChallenge(challengeFrame))) {
                        is AuthStep.Rejected -> return Outcome(false, false, "relé rechaza la respuesta")
                        is AuthStep.Accepted -> mapper.readTree(done.wire)
                        is AuthStep.Challenge -> error("complete no devuelve Challenge")
                    }
                } else {
                    legacyAuthOkFrame(challengeDialect.wire)
                }

                val okDialect = Dialect.fromWireOrNull(okFrame.get("protocolVersion").asText())
                    ?: return Outcome(false, false, "AUTH_OK sin dialecto")

                // 4. Verificacion del dialecto del AUTH_OK en el cliente (REAL).
                if (client.checksResponseDialect &&
                    DialectNegotiation.checkAuthOk(client.dialect, challengeDialect, okDialect.wire) !is DialectCheck.Accepted
                ) {
                    return Outcome(false, false, "cliente rechaza el dialecto del AUTH_OK")
                }

                // Un cliente consciente de dialecto no puede leer un AUTH_OK sin
                // los campos canonicos: MALFORMED_MESSAGE -> no conecta.
                if (client.checksResponseDialect && !relay.emitsVerificationMaterial) {
                    return Outcome(false, false, "AUTH_OK sin campos canonicos -> MALFORMED_MESSAGE")
                }

                val consistent = challengeDialect == okDialect
                val p6Protected = client.verifiesServerSignature && relay.emitsVerificationMaterial && consistent
                return Outcome(true, p6Protected, if (consistent) "dialecto coherente" else "dialecto inconsistente")
            }
        }
    }

    // ===================================================================
    // La matriz, fila a fila (obs #174 Parte 2)
    // ===================================================================

    @Test
    fun `fila 1 - rele antiguo sin material x cliente antiguo conecta pero NO esta protegido`() {
        val o = runHandshake(oldRelayNoMaterial, legacyOldClient)
        assertTrue(o.connects, "el cliente antiguo debe conectar")
        assertFalse(o.p6Protected, "un cliente antiguo que conecta NO queda protegido por P6")
    }

    @Test
    fun `fila 2 - rele {2_0,2_1} x cliente antiguo conecta bajo 2_0 pero NO esta protegido`() {
        val o = runHandshake(transitionalRelay, legacyOldClient)
        assertTrue(o.connects)
        assertFalse(o.p6Protected, "conecta bajo 2.0 legacy; el cliente no verifica la firma")
    }

    @Test
    fun `fila 2b - rele 2_1-only x cliente consciente 2_0 NO conecta`() {
        val o = runHandshake(finalRelay21, client20)
        assertFalse(o.connects, "el relé 2.1-only rechaza un AUTH_REQUEST 2.0")
        assertFalse(o.p6Protected)
    }

    @Test
    fun `fila 3 - rele 2_1 x cliente P6 2_1 conecta y queda mutuamente autenticado`() {
        val o = runHandshake(finalRelay21, client21)
        assertTrue(o.connects)
        assertTrue(o.p6Protected, "dialecto coherente + verificacion de firma + material anclado al pin")
        // Transicion: el rele de transicion tambien sirve al cliente 2.1.
        val t = runHandshake(transitionalRelay, client21)
        assertTrue(t.connects)
        assertTrue(t.p6Protected)
    }

    @Test
    fun `fila 4 - rele sin material x cliente P6 2_1 NO conecta`() {
        val o = runHandshake(oldRelayNoMaterial, client21)
        assertFalse(o.connects)
        assertFalse(o.p6Protected)
    }

    @Test
    fun `fila 5 - rele 2_0-only x cliente P6 2_1 NO conecta`() {
        val o = runHandshake(relay20Only, client21)
        assertFalse(o.connects)
        assertFalse(o.p6Protected)
    }

    @Test
    fun `fila 6 - rele 2_1-only x cliente antiguo NO conecta`() {
        val o = runHandshake(finalRelay21, legacyOldClient)
        assertFalse(o.connects, "un relé 2.1-only rechaza el 2.0 legacy sin downgrade")
        assertFalse(o.p6Protected)
    }

    @Test
    fun `un cliente consciente de dialecto 2_0 conecta bajo 2_0 pero no esta protegido`() {
        val o = runHandshake(transitionalRelay, client20)
        assertTrue(o.connects)
        assertFalse(o.p6Protected, "verifica el dialecto, no la firma del servidor")
    }

    // ===================================================================
    // La distincion del owner: NINGUN cliente entregado es P6-protegido hoy
    // ===================================================================

    @Test
    fun `ningun cliente entregado hoy es P6-protegido en ninguna fila`() {
        val relays = listOf(oldRelayNoMaterial, transitionalRelay, finalRelay21, relay20Only)
        for (delivered in listOf(DELIVERED_ANDROID, DELIVERED_DESKTOP)) {
            for (relay in relays) {
                val o = runHandshake(relay, delivered)
                assertFalse(
                    o.p6Protected,
                    "${delivered.name} contra ${relay.name} NO puede estar P6-protegido: " +
                        "la verificacion de serverSignature (4.1/5.2) no existe todavia",
                )
            }
        }
    }
}
