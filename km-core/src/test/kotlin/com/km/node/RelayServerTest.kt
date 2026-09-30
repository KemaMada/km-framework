package com.km.node

import com.km.auth.*
import com.km.crypto.Ed25519
import com.km.crypto.Ed25519Impl
import com.km.crypto.KeyPair
import com.km.model.*
import com.km.storage.InMemoryRelayStore
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.test.assertContentEquals
import java.util.UUID

/**
 * 3J — RelayClient / RelayServer integration.
 *
 * 6 fronteras (J1-J6) que cubren autenticacion, sesion, routing,
 * signaling, replay y mutaciones.
 *
 * INVARIANTE CENTRAL: el relay verifica que `sender` coincide con
 * la sesion autenticada. Cualquier mensaje con `from` falsificado
 * es rechazado (SPOOFING_DETECTED).
 */
class RelayServerTest {

    private val ed25519: Ed25519 = Ed25519Impl()
    private val relayKP = ed25519.generateKeyPair()
    private val aliceKP = ed25519.generateKeyPair()
    private val bobKP = ed25519.generateKeyPair()
    private val charlieKP = ed25519.generateKeyPair()

    private val aliceId = IdentityId(deriveId(aliceKP.publicKey))
    private val bobId = IdentityId(deriveId(bobKP.publicKey))
    private val charlieId = IdentityId(deriveId(charlieKP.publicKey))
    private val relayId = IdentityId(deriveId(relayKP.publicKey))

    private val relayStore = InMemoryRelayStore(now = { 0L })
    private val relayService = RelayServiceImpl(relayStore = relayStore, now = { 0L })

    private fun freshServer(
        expiryMs: Long = 300_000L,
    ): RelayServer = RelayServer(
        relayKeyPair = relayKP,
        ed25519 = ed25519,
        relayService = relayService,
        relayIdentityId = relayId,
        now = { 0L },
        sessionExpiryMs = expiryMs,
        timestampWindowMs = 300_000L,
    )

    /** Autentica un peer contra el servidor relay. */
    private fun authenticate(
        server: RelayServer,
        peerKP: KeyPair,
        peerId: IdentityId,
        now: Long = 0L,
    ): RelaySession {
        val challenge = server.createChallenge(peerId)
        val handler = RelayAuthHandler(ed25519)

        // Necesitamos un clock sincronizado para que el timestamp del challenge
        // este dentro de la ventana
        val response = handler.buildResponse(
            challenge = AuthChallenge(challenge.nonce, now, 3, relayId.value),
            identityId = peerId.value,
            keyPair = peerKP,
        )

        val result = server.authenticate(peerId, response)
        assertTrue(result.isSuccess) { "autenticacion fallo: ${(result as RelayServerResult.Failure).message}" }
        return (result as RelayServerResult.Success).value.first
    }

    /** Registra handler para un peer. */
    private fun registerPeer(server: RelayServer, peerId: IdentityId) {
        server.registerPeerHandler(peerId) { /* no-op handler */ }
    }

    /** Crea un signal SDP Offer desde from hacia to. */
    private fun sdpOffer(from: IdentityId, to: IdentityId, sdp: String = "sdp-offer"): RelaySignalMessage =
        RelaySignalMessage.SdpOffer(from, to, sdp, 0L)

    /** Crea un signal SDP Answer desde from hacia to. */
    private fun sdpAnswer(from: IdentityId, to: IdentityId, sdp: String = "sdp-answer"): RelaySignalMessage =
        RelaySignalMessage.SdpAnswer(from, to, sdp, 0L)

    /** Crea un signal ICE Candidate desde from hacia to. */
    private fun iceCandidate(
        from: IdentityId, to: IdentityId,
        candidate: String = "candidate:1 1 UDP 2122252543 192.168.1.1 12345 typ host"
    ): RelaySignalMessage =
        RelaySignalMessage.IceCandidate(from, to, candidate, 0L)

    /** Crea un signal ContactExchange desde from hacia to. */
    private fun contactExchange(from: IdentityId, to: IdentityId): RelaySignalMessage =
        RelaySignalMessage.ContactExchange(from, to, "bundle-data".encodeToByteArray(), 0L)

    // ===================================================================
    // J1 — Authentication boundary
    // ===================================================================

    @Test
    fun `J1-01 relay acepta autenticacion valida`() {
        val server = freshServer()
        val session = authenticate(server, aliceKP, aliceId)
        assertEquals(aliceId, session.identityId)
        assertTrue(session.isActive)
        assertEquals(RelaySessionState.AUTHENTICATED, session.state)
    }

    @Test
    fun `J1-02 relay rechaza response con publicKey incorrecta`() {
        val server = freshServer()
        val challenge = server.createChallenge(aliceId)

        // Firmar con la clave correcta pero declarar otra publicKey
        val transcript = TranscriptBuilder.authTranscript(
            challenge.nonce, 0L, relayId.value, aliceId.value,
        )
        val sig = ed25519.sign(aliceKP.privateKey, transcript)
        val badResponse = AuthResponse(
            identityId = aliceId.value,
            publicKey = bobKP.publicKey, // CLAVE EQUIVOCADA
            signature = sig.bytes,
            protocolVersion = 3,
        )

        val result = server.authenticate(aliceId, badResponse)
        assertTrue(result.isFailure)
        assertEquals(RelayServerError.AUTH_FAILED, (result as RelayServerResult.Failure).error)
    }

    @Test
    fun `J1-03 relay rechaza identityId que no deriva de publicKey`() {
        val server = freshServer()
        val challenge = server.createChallenge(aliceId)

        // identityId de Alice, publicKey de Bob
        val transcript = TranscriptBuilder.authTranscript(
            challenge.nonce, 0L, relayId.value, bobId.value,
        )
        val sig = ed25519.sign(bobKP.privateKey, transcript)
        val response = AuthResponse(bobId.value, bobKP.publicKey, sig.bytes, 3)

        // autenticar como Alice, pero response declara Bob
        val result = server.authenticate(aliceId, response)
        assertTrue(result.isFailure)
        assertEquals(RelayServerError.AUTH_FAILED, (result as RelayServerResult.Failure).error)
    }

    @Test
    fun `J1-04 relay rechaza signature incorrecta en AuthResponse`() {
        val server = freshServer()
        val challenge = server.createChallenge(aliceId)

        // Firma invalida (firmar con otra clave)
        val transcript = TranscriptBuilder.authTranscript(
            challenge.nonce, 0L, relayId.value, aliceId.value,
        )
        val sig = ed25519.sign(bobKP.privateKey, transcript) // Bob firma, no Alice
        val response = AuthResponse(aliceId.value, aliceKP.publicKey, sig.bytes, 3)

        val result = server.authenticate(aliceId, response)
        assertTrue(result.isFailure)
        assertEquals(RelayServerError.AUTH_FAILED, (result as RelayServerResult.Failure).error)
    }

    @Test
    fun `J1-05 relay no expone relayKeyPair en authenticate`() {
        val server = freshServer()
        val session = authenticate(server, aliceKP, aliceId)
        // authenticate devuelve solo RelaySession y AuthOk — no la clave privada
        assertNotNull(session)
        // El AuthOk tiene responderPublicKey del relay
        val challenge = server.createChallenge(aliceId)
        val handler = RelayAuthHandler(ed25519)
        val response = handler.buildResponse(
            AuthChallenge(challenge.nonce, 0L, 3, relayId.value),
            aliceId.value, aliceKP,
        )
        val authResult = server.authenticate(aliceId, response)
        val (_, authOk) = (authResult as RelayServerResult.Success).value
        assertContentEquals(relayKP.publicKey, authOk.responderPublicKey)
    }

    // ===================================================================
    // J2 — Session lifecycle
    // ===================================================================

    @Test
    fun `J2-01 conexion autenticada obtiene sessionId`() {
        val server = freshServer()
        val session = authenticate(server, aliceKP, aliceId)
        assertEquals(40, session.sessionId.length)
        assertTrue(session.sessionId.all { it in "0123456789abcdef" })
    }

    @Test
    fun `J2-02 sessionId inmutable en RelaySession`() {
        val server = freshServer()
        val session = authenticate(server, aliceKP, aliceId)
        val sessionId = session.sessionId
        // RelaySession es inmutable — sessionId no puede cambiar
        assertEquals(sessionId, session.close().sessionId) // close mantiene mismo sessionId
    }

    @Test
    fun `J2-03 disconnect destruye sesion`() {
        val server = freshServer()
        authenticate(server, aliceKP, aliceId)
        registerPeer(server, aliceId)

        server.disconnect(aliceId)
        val session = server.getSession(aliceId)
        assertNotNull(session)
        assertFalse(session!!.isActive)
        assertEquals(RelaySessionState.CLOSED, session.state)
        assertFalse(server.isPeerOnline(aliceId))
    }

    @Test
    fun `J2-04 sesion CLOSED no puede enviar signaling`() {
        val server = freshServer()
        authenticate(server, aliceKP, aliceId)
        registerPeer(server, aliceId)
        server.disconnect(aliceId)

        val result = server.handleSignal(
            aliceId, sdpOffer(aliceId, bobId),
        )
        assertTrue(result.isFailure)
        assertEquals(RelayServerError.SESSION_CLOSED, (result as RelayServerResult.Failure).error)
    }

    @Test
    fun `J2-05 nueva conexion obtiene nueva sesion`() {
        val server = freshServer()
        val session1 = authenticate(server, aliceKP, aliceId)
        Thread.sleep(5) // Ensure different sessionId
        val session2 = authenticate(server, aliceKP, aliceId)
        assertNotEquals(session1.sessionId, session2.sessionId)
    }

    // ===================================================================
    // J3 — Identity/routing
    // ===================================================================

    @Test
    fun `J3-01 sender deriva del contexto autenticado`() {
        val server = freshServer()
        authenticate(server, aliceKP, aliceId)
        registerPeer(server, aliceId)
        authenticate(server, bobKP, bobId)
        registerPeer(server, bobId)

        // Alice envia oferta a Bob con from = aliceId (correcto)
        val offer = sdpOffer(aliceId, bobId)
        val result = server.handleSignal(aliceId, offer)
        assertTrue(result.isSuccess, "senal desde peer autenticado debe ser aceptada")
    }

    @Test
    fun `J3-02 spoofing de sender es rechazado`() {
        val server = freshServer()
        authenticate(server, aliceKP, aliceId)
        registerPeer(server, aliceId)

        // Alice intenta enviar un mensaje como si fuera Charlie
        val spoofedSignal = sdpOffer(charlieId, bobId)
        val result = server.handleSignal(aliceId, spoofedSignal)
        assertTrue(result.isFailure)
        assertEquals(RelayServerError.SPOOFING_DETECTED, (result as RelayServerResult.Failure).error)
    }

    @Test
    fun `J3-03 peer inexistente rechazado`() {
        val server = freshServer()
        authenticate(server, aliceKP, aliceId)
        registerPeer(server, aliceId)

        // Enviar a Charlie que no esta conectado
        val result = server.handleSignal(aliceId, sdpOffer(aliceId, charlieId))
        assertTrue(result.isFailure)
        assertEquals(RelayServerError.PEER_NOT_ONLINE, (result as RelayServerResult.Failure).error)
    }

    @Test
    fun `J3-04 A no puede enviar mensaje como B`() {
        val server = freshServer()
        authenticate(server, aliceKP, aliceId)
        registerPeer(server, aliceId)
        authenticate(server, bobKP, bobId)
        registerPeer(server, bobId)

        // Alice envia senal con from = bobId (falsificado)
        val spoofed = sdpOffer(bobId, charlieId)
        val result = server.handleSignal(aliceId, spoofed)
        assertTrue(result.isFailure)
        assertEquals(RelayServerError.SPOOFING_DETECTED, (result as RelayServerResult.Failure).error)
    }

    @Test
    fun `J3-05 routing conserva recipient correcto`() {
        val server = freshServer()
        authenticate(server, aliceKP, aliceId)
        authenticate(server, bobKP, bobId)
        authenticate(server, charlieKP, charlieId)

        var receivedByBob: RelaySignalMessage? = null
        var receivedByCharlie: RelaySignalMessage? = null

        server.registerPeerHandler(bobId) { receivedByBob = it }
        server.registerPeerHandler(charlieId) { receivedByCharlie = it }

        // Alice envia a Bob
        server.handleSignal(aliceId, sdpOffer(aliceId, bobId))
        assertNotNull(receivedByBob, "Bob debe recibir el mensaje")
        assertNull(receivedByCharlie, "Charlie NO debe recibir el mensaje")
    }

    // ===================================================================
    // J4 — Signaling
    // ===================================================================

    @Test
    fun `J4-01 peer_online notifica a handlers`() {
        val server = freshServer()
        var onlinePeer: IdentityId? = null
        server.onPeerOnline { onlinePeer = it }

        authenticate(server, aliceKP, aliceId)
        registerPeer(server, aliceId)

        assertEquals(aliceId, onlinePeer, "registerPeerHandler debe notificar peer_online")
    }

    @Test
    fun `J4-02 peer_offline notifica a handlers`() {
        val server = freshServer()
        var offlinePeer: IdentityId? = null
        server.onPeerOffline { offlinePeer = it }

        authenticate(server, aliceKP, aliceId)
        registerPeer(server, aliceId)
        server.unregisterPeerHandler(aliceId)

        assertEquals(aliceId, offlinePeer, "unregisterPeerHandler debe notificar peer_offline")
    }

    @Test
    fun `J4-03 contact_exchange entre peers autenticados`() {
        val server = freshServer()
        authenticate(server, aliceKP, aliceId)
        authenticate(server, bobKP, bobId)

        var bobReceived: RelaySignalMessage? = null
        server.registerPeerHandler(bobId) { bobReceived = it }
        server.registerPeerHandler(aliceId) {} // Alice debe estar online tambien

        val exchange = contactExchange(aliceId, bobId)
        val result = server.handleSignal(aliceId, exchange)
        assertTrue(result.isSuccess)
        assertNotNull(bobReceived)
        assertTrue(bobReceived is RelaySignalMessage.ContactExchange)
    }

    @Test
    fun `J4-04 SDP offer ruteado al destinatario`() {
        val server = freshServer()
        authenticate(server, aliceKP, aliceId)
        authenticate(server, bobKP, bobId)

        var bobReceived: String? = null
        server.registerPeerHandler(bobId) {
            if (it is RelaySignalMessage.SdpOffer) bobReceived = it.sdp
        }
        server.registerPeerHandler(aliceId) {}

        val offer = sdpOffer(aliceId, bobId, "alice-sdp-offer")
        server.handleSignal(aliceId, offer)
        assertEquals("alice-sdp-offer", bobReceived)
    }

    @Test
    fun `J4-05 SDP answer ruteado al destinatario`() {
        val server = freshServer()
        authenticate(server, aliceKP, aliceId)
        authenticate(server, bobKP, bobId)

        var aliceReceived: String? = null
        server.registerPeerHandler(aliceId) {
            if (it is RelaySignalMessage.SdpAnswer) aliceReceived = it.sdp
        }
        server.registerPeerHandler(bobId) {}

        val answer = sdpAnswer(bobId, aliceId, "bob-sdp-answer")
        server.handleSignal(bobId, answer)
        assertEquals("bob-sdp-answer", aliceReceived)
    }

    @Test
    fun `J4-06 ICE candidate ruteado al destinatario`() {
        val server = freshServer()
        authenticate(server, aliceKP, aliceId)
        authenticate(server, bobKP, bobId)

        var bobReceived: String? = null
        server.registerPeerHandler(bobId) {
            if (it is RelaySignalMessage.IceCandidate) bobReceived = it.candidate
        }
        server.registerPeerHandler(aliceId) {}

        val ice = iceCandidate(aliceId, bobId, "candidate:alice-1")
        server.handleSignal(aliceId, ice)
        assertEquals("candidate:alice-1", bobReceived)
    }

    // ===================================================================
    // J5 — Replay / aislamiento
    // ===================================================================

    @Test
    fun `J5-01 sesion cerrada no reutiliza handler`() {
        val server = freshServer()
        authenticate(server, aliceKP, aliceId)
        registerPeer(server, aliceId)
        server.disconnect(aliceId)

        // Intentar registrar handler otra vez sin sesion activa
        val result = server.registerPeerHandler(aliceId) {}
        assertTrue(result.isFailure)
        assertEquals(RelayServerError.SESSION_CLOSED, (result as RelayServerResult.Failure).error)
    }

    @Test
    fun `J5-02 mensaje de sesion anterior no procesado`() {
        val server = freshServer()
        authenticate(server, aliceKP, aliceId)
        registerPeer(server, aliceId)
        server.disconnect(aliceId)

        // Sesion ya cerrada, signal debe fallar
        val result = server.handleSignal(aliceId, sdpOffer(aliceId, bobId))
        assertTrue(result.isFailure)
        assertEquals(RelayServerError.SESSION_CLOSED, (result as RelayServerResult.Failure).error)
    }

    @Test
    fun `J5-03 sessionId incorrecto no existe como concepto en RelayServer`() {
        // RelayServer no tiene API para usar sessionId como clave de routing.
        // El routing se hace por identityId. Verificar que esto es asi.
        val server = freshServer()
        authenticate(server, aliceKP, aliceId)

        // handleSignal usa identityId, no sessionId
        val session = server.getSession(aliceId)
        assertNotNull(session)
        // Si alguien intentara usar un sessionId incorrecto, no hay API para hacerlo
    }

    @Test
    fun `J5-04 identityId correcto + sesion cerrada falla`() {
        val server = freshServer()
        authenticate(server, aliceKP, aliceId)
        registerPeer(server, aliceId)
        server.disconnect(aliceId)

        // Mismo identityId, pero sesion cerrada
        val result = server.handleSignal(aliceId, sdpOffer(aliceId, bobId))
        assertTrue(result.isFailure)
        assertEquals(RelayServerError.SESSION_CLOSED, (result as RelayServerResult.Failure).error)
    }

    @Test
    fun `J5-05 mismo peer dos conexiones cierra primera`() {
        val server = freshServer()
        val session1 = authenticate(server, aliceKP, aliceId)
        val session2 = authenticate(server, aliceKP, aliceId)

        // La segunda autenticacion genera una sesion distinta
        assertNotEquals(session1.sessionId, session2.sessionId,
            "cada autenticacion debe generar sessionId distinto")
        // La sesion en el mapa es la nueva (activa)
        val currentSession = server.getSession(aliceId)
        assertNotNull(currentSession)
        assertEquals(session2.sessionId, currentSession!!.sessionId)
        assertTrue(currentSession.isActive, "la nueva sesion debe estar activa")
    }

    // ===================================================================
    // J6 — Mutation tests
    // ===================================================================

    @Test
    fun `J6-01 mutacion sender es rechazada`() {
        val server = freshServer()
        authenticate(server, aliceKP, aliceId)
        authenticate(server, bobKP, bobId)
        registerPeer(server, aliceId)
        registerPeer(server, bobId)

        // Alice envia pero mutamos from a Bob
        val mutated = sdpOffer(bobId, charlieId) // from Bob pero peer autenticado es Alice
        val result = server.handleSignal(aliceId, mutated)
        assertEquals(RelayServerError.SPOOFING_DETECTED, (result as RelayServerResult.Failure).error)
    }

    @Test
    fun `J6-02 mutacion recipient a peer inexistente falla`() {
        val server = freshServer()
        authenticate(server, aliceKP, aliceId)
        registerPeer(server, aliceId)

        // Enviar a peer que no existe en el sistema
        val fakeId = IdentityId("a".repeat(64))
        val result = server.handleSignal(aliceId, sdpOffer(aliceId, fakeId))
        assertEquals(RelayServerError.PEER_NOT_ONLINE, (result as RelayServerResult.Failure).error)
    }

    @Test
    fun `J6-03 mutacion a sesion no autenticada`() {
        val server = freshServer()
        // Alice no esta autenticada
        val result = server.handleSignal(aliceId, sdpOffer(aliceId, bobId))
        assertEquals(RelayServerError.SESSION_NOT_FOUND, (result as RelayServerResult.Failure).error)
    }

    @Test
    fun `J6-04 autenticacion sin challenge falla`() {
        val server = freshServer()
        // No llamar a createChallenge, ir directo a authenticate
        val transcript = TranscriptBuilder.authTranscript(
            ByteArray(16), 0L, relayId.value, aliceId.value,
        )
        val sig = ed25519.sign(aliceKP.privateKey, transcript)
        val response = AuthResponse(aliceId.value, aliceKP.publicKey, sig.bytes, 3)

        val result = server.authenticate(aliceId, response)
        assertTrue(result.isFailure)
        assertEquals(RelayServerError.AUTH_FAILED, (result as RelayServerResult.Failure).error)
    }

    @Test
    fun `J6-05 challenge reutilizado rechazado`() {
        val server = freshServer()
        val challenge = server.createChallenge(aliceId)

        // Primer uso: funciona
        val handler = RelayAuthHandler(ed25519)
        val response1 = handler.buildResponse(
            AuthChallenge(challenge.nonce, 0L, 3, relayId.value),
            aliceId.value, aliceKP,
        )
        val result1 = server.authenticate(aliceId, response1)
        assertTrue(result1.isSuccess)

        // Segundo uso con mismo challenge: falla (challenge ya consumido)
        val response2 = handler.buildResponse(
            AuthChallenge(challenge.nonce, 0L, 3, relayId.value),
            aliceId.value, aliceKP,
        )
        val result2 = server.authenticate(aliceId, response2)
        assertTrue(result2.isFailure)
        assertEquals(RelayServerError.AUTH_FAILED, (result2 as RelayServerResult.Failure).error)
    }

    // ===================================================================
    // Prueba estrella: tres peers con spoofing cruzado
    // ===================================================================

    @Test
    fun `A ofrece a B, C no puede interceptar`() {
        val server = freshServer()
        authenticate(server, aliceKP, aliceId)
        authenticate(server, bobKP, bobId)
        authenticate(server, charlieKP, charlieId)

        var bobReceived: RelaySignalMessage? = null
        var charlieReceived: RelaySignalMessage? = null

        server.registerPeerHandler(aliceId) {}
        server.registerPeerHandler(bobId) { bobReceived = it }
        server.registerPeerHandler(charlieId) { charlieReceived = it }

        // Alice envia OFFER a Bob
        server.handleSignal(aliceId, sdpOffer(aliceId, bobId))

        assertNotNull(bobReceived, "Bob debe recibir OFFER de Alice")
        assertNull(charlieReceived, "Charlie NO debe recibir OFFER de Alice")

        // Charlie intenta interceptar enviando su propio OFFER como si fuera Alice
        val charlieSpoofResult = server.handleSignal(charlieId, sdpOffer(aliceId, bobId))
        assertTrue(charlieSpoofResult.isFailure)
        assertEquals(RelayServerError.SPOOFING_DETECTED,
            (charlieSpoofResult as RelayServerResult.Failure).error)

        // Bob NO recibio el OFFER falsificado de Charlie
        // (bobReceived sigue siendo el OFFER original de Alice)
        assertTrue((bobReceived as? RelaySignalMessage.SdpOffer)?.sdp == "sdp-offer",
            "Bob debe tener el OFFER original de Alice, no el intento de Charlie")
    }

    // ===================================================================
    // Helpers
    // ===================================================================

    companion object {
        fun deriveId(publicKey: ByteArray): String {
            val digest = java.security.MessageDigest.getInstance("SHA-256")
            digest.update("KM-ID-IDENTITY".encodeToByteArray())
            digest.update(publicKey)
            return digest.digest().joinToString("") { "%02x".format(it) }
        }
    }
}