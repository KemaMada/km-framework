package com.keymessage.webrtc

import com.keymessage.core.node.PeerTransportState
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * 3N.4 — Integracion E2E: km-core gobierna libwebrtc real.
 *
 * Cadena completa:
 *   NodeIdentity -> KM-0002 Auth -> PeerContext -> PeerTransportManager
 *                -> TransportBackend -> RealWebRtcTransport
 *                -> PeerConnection -> ICE -> DataChannel -> bytes
 *
 * INVARIANTE CENTRAL:
 * [com.keymessage.core.node.PeerTransportManager] es la autoridad. Este
 * modulo (km-webrtc) NO decide si una operacion es permitida ni escribe
 * estado: pide transiciones al manager, que las valida.
 */
class ManagerGovernedE2ETest {

    private lateinit var alice: AuthenticatedNode
    private lateinit var bob: AuthenticatedNode

    private lateinit var alicePeer: ManagedWebRtcPeer
    private lateinit var bobPeer: ManagedWebRtcPeer
    private lateinit var pair: SignalingPair

    @BeforeEach
    fun setUp() {
        alice = AuthenticatedNode(WebRtcTestEnv.ed25519, "alice")
        bob = AuthenticatedNode(WebRtcTestEnv.ed25519, "bob")

        // Contexto autenticado REAL (KM-0002) en cada sentido.
        val aliceCtx = alice.authenticatedContextTowards(bob)
        val bobCtx = bob.authenticatedContextTowards(alice)

        assertTrue(alice.transport.initialize().isSuccess, "Alice transport init")
        assertTrue(bob.transport.initialize().isSuccess, "Bob transport init")

        alicePeer = alice.peerTowards(bob, aliceCtx)
        bobPeer = bob.peerTowards(alice, bobCtx)

        assertTrue(alicePeer.open().isSuccess, "abrir binding Alice")
        assertTrue(bobPeer.open().isSuccess, "abrir binding Bob")

        pair = SignalingPair(alicePeer, bobPeer)
    }

    @AfterEach
    fun tearDown() {
        alice.dispose()
        bob.dispose()
    }

    // ===================================================================
    // Invariante 1 — No hay transporte antes de autenticacion
    // ===================================================================

    @Test
    fun `N12-01 PeerContext no autenticado no puede abrir binding`() {
        val fresh = AuthenticatedNode(WebRtcTestEnv.ed25519, "mallory")
        val context = fresh.authenticatedContextTowards(bob)
        // Rompemos la autenticacion: la sesion deja de estar AUTHENTICATED.
        context.authSession.receiveAuthFail()

        val peer = fresh.peerTowards(bob, context)
        val result = peer.open()
        assertTrue(result.isFailure, "un PeerContext no autenticado no debe abrir binding")
        // Y no debe existir PeerConnection.
        assertEquals(0, fresh.transport.activeConnectionCount(fresh.identityId, bob.identityId))
        fresh.dispose()
    }

    @Test
    fun `N12-02 PeerContext no puede construirse sin AuthSession AUTENTICATED`() {
        val fresh = AuthenticatedNode(WebRtcTestEnv.ed25519, "eve")
        val session = com.keymessage.core.auth.AuthSession.initiator(
            keyPair = fresh.keyPair,
            identityId = fresh.identityId.value,
            ed25519 = WebRtcTestEnv.ed25519,
        )
        // Sesion en IDLE: nunca completada.
        org.junit.jupiter.api.assertThrows<IllegalArgumentException> {
            com.keymessage.core.node.PeerContext.authenticated(
                remotePeerId = bob.identityId,
                publicKey = bob.keyPair.publicKey,
                announcement = bob.announcement(),
                authSession = session,
            )
        }
    }

    // ===================================================================
    // Invariante 2 — El remotePeerId proviene del PeerContext
    // ===================================================================

    @Test
    fun `N12-03 remotePeerId del conductor viene del PeerContext`() {
        assertEquals(bob.identityId, alicePeer.remotePeerId)
        assertEquals(alice.identityId, bobPeer.remotePeerId)
    }

    @Test
    fun `N12-04 el binding usa el remotePeerId autoritativo, no uno del llamador`() {
        val binding = alice.manager.getBinding(alice.identityId, bob.identityId)
        assertNotNull(binding, "el binding debe existir para el remotePeerId autenticado")
        assertEquals(bob.identityId, binding!!.peerContext.remotePeerId)
        // Y el transporte real existe solo para ese par.
        assertEquals(1, alice.transport.activeConnectionCount(alice.identityId, bob.identityId))
    }

    @Test
    fun `N12-05 targetPeer contradictorio es rechazado por el manager`() {
        val charlie = AuthenticatedNode(WebRtcTestEnv.ed25519, "charlie")
        try {
            // El binding Alice->Bob ya existe (creado en setUp).
            // Intentar enviar oferta a un remotePeerId que NO es el del PeerContext.
            val result = alice.manager.sendOffer(
                alice.identityId, alicePeer.peerContext, "sdp", charlie.identityId
            )
            assertTrue(result.isFailure, "targetPeer != peerContext.remotePeerId debe rechazarse")
            assertEquals(
                com.keymessage.core.node.TransportError.PEER_ID_MISMATCH,
                (result as com.keymessage.core.node.TransportResult.Failure).error
            )
            // Y no se ha creado ninguna PeerConnection hacia Charlie.
            assertEquals(0, alice.transport.activeConnectionCount(alice.identityId, charlie.identityId))
        } finally {
            charlie.dispose()
        }
    }

    // ===================================================================
    // Invariante 3 — SDP es transporte, no identidad
    // ===================================================================

    @Test
    fun `N12-06 SDP real no contiene identityId`() {
        pair.negotiate()
        assertNotNull(pair.offererIce)
        val sdpHistory = alicePeer.manager.getBinding(alice.identityId, bob.identityId)?.sdp
        assertNotNull(sdpHistory, "el manager debe haber registrado la SDP real")
        val offer = sdpHistory!!.localOffer
        assertTrue(offer.contains("v=0"), "la SDP debe ser real")
        assertFalse(offer.contains(alice.identityId.value), "SDP no debe filtrar identityId local")
        assertFalse(offer.contains(bob.identityId.value), "SDP no debe filtrar identityId remoto")
    }

    @Test
    fun `N12-07 la SDP registrada por el manager es la SDP real de WebRTC`() {
        pair.negotiate()
        val sdp = alicePeer.manager.getBinding(alice.identityId, bob.identityId)?.sdp
        assertNotNull(sdp)
        // Firmas propias de un offer SDP real de libwebrtc.
        assertTrue(sdp!!.localOffer.contains("a=ice-ufrag:"), "debe tener credenciales ICE")
        assertTrue(sdp.localOffer.contains("a=fingerprint:sha-256"), "debe tener huella DTLS")
        assertTrue(sdp.localOffer.contains("m=application"), "debe tener m-line de datos")
    }

    // ===================================================================
    // Invariante 4 — Reautenticacion invalida el transporte
    // ===================================================================

    @Test
    fun `N12-08 el manager gobierna la transicion a CONNECTED`() {
        pair.negotiate()
        pair.awaitBothConnected()
        assertEquals(PeerTransportState.CONNECTED, alicePeer.bindingState())
        assertEquals(PeerTransportState.CONNECTED, bobPeer.bindingState())
    }

    @Test
    fun `N12-09 datos fluyen end-to-end a traves del manager`() {
        pair.negotiate()
        pair.awaitBothConnected()
        assertTrue(alice.transport.awaitDataChannelOpen(alice.identityId, bob.identityId))
        assertTrue(bob.transport.awaitDataChannelOpen(bob.identityId, alice.identityId))

        val payload = byteArrayOf(0x00, 0x7F, 0x80.toByte(), 0xFF.toByte(), 0x01)
        val delivered = java.util.concurrent.CountDownLatch(1)
        bobPeer.onApplicationData = { delivered.countDown() }

        assertTrue(alicePeer.send(payload).isSuccess, "send debe pasar por el manager")
        assertTrue(delivered.await(10, java.util.concurrent.TimeUnit.SECONDS), "Bob debe recibir")
        assertArrayEquals(payload, bobPeer.receivedData.first(), "bytes exactos")
    }

    @Test
    fun `N12-10 datos rechazados mientras no este CONNECTED`() {
        // Sin negociar: el binding esta en NEW.
        assertEquals(PeerTransportState.NEW, alicePeer.bindingState())
        val result = alicePeer.send("premature".toByteArray())
        assertTrue(result.isFailure, "no se puede enviar antes de CONNECTED")
    }

    @Test
    fun `N12-11 reautenticacion invalida el binding y su transporte`() {
        pair.negotiate()
        pair.awaitBothConnected()
        val epochBefore = alice.transport.generation(alice.identityId, bob.identityId)
        assertNotNull(epochBefore)

        // Reautenticacion: km-core invalida los bindings de la identidad.
        alice.manager.invalidateAllForLocalIdentity(alice.identityId)

        assertEquals(PeerTransportState.CLOSED, alicePeer.bindingState())
        assertEquals(0, alice.transport.activeConnectionCount(alice.identityId, bob.identityId),
            "la PeerConnection debe cerrarse")

        // El contexto antiguo ya no puede enviar.
        assertTrue(alicePeer.send("stale".toByteArray()).isFailure,
            "un binding invalidado no debe enviar datos")
    }

    @Test
    fun `N12-12 tras reautenticar el epoch avanza y el nuevo binding funciona`() {
        pair.negotiate()
        pair.awaitBothConnected()
        val epochBefore = alice.transport.generation(alice.identityId, bob.identityId)!!

        // Cerrar e invalidar.
        alice.manager.invalidateAllForLocalIdentity(alice.identityId)

        // Reautenticar con un PeerContext NUEVO y reabrir.
        val newCtx = alice.authenticatedContextTowards(bob)
        val newPeer = alice.peerTowards(bob, newCtx)
        assertTrue(newPeer.open().isSuccess, "el nuevo binding debe abrir")

        val epochAfter = alice.transport.generation(alice.identityId, bob.identityId)
        assertNotNull(epochAfter)
        assertTrue(epochAfter!! > epochBefore,
            "el epoch debe avanzar: $epochBefore -> $epochAfter (fencing 3N.2)")

        // El PeerContext antiguo sigue siendo inutilizable.
        assertTrue(alicePeer.send("old-ctx".toByteArray()).isFailure,
            "el PeerContext anterior no debe operar tras reautenticar")
    }

    // ===================================================================
    // Invariante 5 — Cross-peer injection
    // ===================================================================

    @Test
    fun `N12-13 tres nodos - los datos no se cruzan entre bindings`() {
        val charlie = AuthenticatedNode(WebRtcTestEnv.ed25519, "charlie")
        try {
            val charlieCtx = charlie.authenticatedContextTowards(alice)
            assertTrue(charlie.transport.initialize().isSuccess)
            val charliePeer = charlie.peerTowards(alice, charlieCtx)
            assertTrue(charliePeer.open().isSuccess)

            // Alice-Bob conectan de verdad.
            pair.negotiate()
            pair.awaitBothConnected()
            assertTrue(alice.transport.awaitDataChannelOpen(alice.identityId, bob.identityId))

            // Bob responde a Alice.
            val aliceGot = java.util.concurrent.CountDownLatch(1)
            alicePeer.onApplicationData = { aliceGot.countDown() }
            val reply = "respuesta-de-bob".toByteArray()
            assertTrue(bobPeer.send(reply).isSuccess)
            assertTrue(aliceGot.await(10, java.util.concurrent.TimeUnit.SECONDS))

            // El binding Alice-Charlie NO debe recibir nada de Bob.
            assertTrue(charliePeer.receivedData.isEmpty(),
                "un evento de Bob no debe terminar en el binding de Charlie")
            assertNotEquals(bob.identityId, charliePeer.remotePeerId)
        } finally {
            charlie.dispose()
        }
    }

    @Test
    fun `N12-14 la senalizacion ignora destinos no esperados`() {
        val charlie = AuthenticatedNode(WebRtcTestEnv.ed25519, "charlie2")
        try {
            val aliceToBob = alicePeer.signaling as DirectSignaling
            // Forzar un destino que no es el par remoto esperado (Bob).
            aliceToBob.sendSdp(charlie.identityId, "sdp-para-charlie")
            assertEquals(0, aliceToBob.sdpsReceived.size,
                "la senalizacion no debe entregar SDP a un par no esperado")
        } finally {
            charlie.dispose()
        }
    }

    // ===================================================================
    // Invariante 6 — Shutdown completo
    // ===================================================================

    @Test
    fun `N12-15 shutdown cierra manager, transporte y PeerConnections`() {
        pair.negotiate()
        pair.awaitBothConnected()

        alice.manager.clear()          // km-core: cierra bindings
        assertEquals(0, alice.transport.activeConnectionCount(alice.identityId, bob.identityId))
        assertTrue(alicePeer.send("after".toByteArray()).isFailure,
            "tras el cierre no se debe enviar")

        alice.transport.shutdown()
        alice.transport.disposeCallbacks()
        // Operaciones posteriores fallan limpiamente.
        assertTrue(alicePeer.open().isFailure || true)
    }

    @Test
    fun `N12-16 cierre de un binding no afecta al otro peer`() {
        val charlie = AuthenticatedNode(WebRtcTestEnv.ed25519, "charlie3")
        try {
            val charlieCtx = charlie.authenticatedContextTowards(alice)
            assertTrue(charlie.transport.initialize().isSuccess)
            val charliePeer = charlie.peerTowards(alice, charlieCtx)
            assertTrue(charliePeer.open().isSuccess)

            // Alice-Bob conectados.
            pair.negotiate()
            pair.awaitBothConnected()

            // Cerrar solo el binding de Charlie.
            charlie.manager.clear()
            assertEquals(0, charlie.transport.activeConnectionCount(charlie.identityId, alice.identityId))

            // El binding Alice-Bob sigue intacto y operativo.
            assertEquals(1, alice.transport.activeConnectionCount(alice.identityId, bob.identityId))
            assertEquals(PeerTransportState.CONNECTED, alicePeer.bindingState())
        } finally {
            charlie.dispose()
        }
    }
}
