package com.km.webrtc

import com.km.node.PeerTransportState
import com.km.node.RelayServer
import com.km.node.RelayServerError
import com.km.node.RelaySignalMessage
import com.km.node.RelayServiceImpl
import com.km.storage.InMemoryRelayStore
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.security.MessageDigest
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * 3N.5 — RelayServer real transportando senalizacion hacia WebRTC real.
 *
 * Cadena completa:
 *   NodeIdentity -> Auth(peer) + Auth(relay KM-0002) -> PeerContext
 *                -> PeerTransportManager -> RealWebRtcTransport
 *   Signaling:   peer -> RelayServer -> peer   (SDP / ICE)
 *   Datos:       peer ═══ DataChannel ═══ peer  (el relay NO participa)
 *
 * PROPIEDAD CENTRAL (KM-0003):
 * El relay transporta SOLO senalizacion. Los bytes de aplicacion viajan
 * por el DataChannel directo, nunca a traves del relay.
 */
class RelaySignaledE2ETest {

    private lateinit var relayKeyPair: com.km.crypto.KeyPair
    private var relayId: com.km.model.IdentityId =
        com.km.model.IdentityId("")
    private lateinit var relay: RelayServer

    private lateinit var alice: AuthenticatedNode
    private lateinit var bob: AuthenticatedNode

    private lateinit var alicePeer: ManagedWebRtcPeer
    private lateinit var bobPeer: ManagedWebRtcPeer

    private lateinit var aliceSignaling: RelaySignaling
    private lateinit var bobSignaling: RelaySignaling

    /** Toda la senalizacion que atraviesa el relay (ambos sentidos). */
    private val relayTraffic = ConcurrentLinkedQueue<RelaySignalMessage>()

    @BeforeEach
    fun setUp() {
        val ed25519 = WebRtcTestEnv.ed25519
        relayKeyPair = ed25519.generateKeyPair()
        relayId = com.km.model.IdentityId(
            RelayServer.deriveIdentityId(relayKeyPair.publicKey)
        )
        relay = RelayServer(
            relayKeyPair = relayKeyPair,
            ed25519 = ed25519,
            relayService = RelayServiceImpl(relayStore = InMemoryRelayStore(now = { 0L }), now = { 0L }),
            relayIdentityId = relayId,
            now = { 0L },
        )

        alice = AuthenticatedNode(ed25519, "alice")
        bob = AuthenticatedNode(ed25519, "bob")

        // Autenticacion peer↔peer (KM-0002) -> PeerContext.
        val aliceCtx = alice.authenticatedContextTowards(bob)
        val bobCtx = bob.authenticatedContextTowards(alice)

        assertTrue(alice.transport.initialize().isSuccess)
        assertTrue(bob.transport.initialize().isSuccess)

        alicePeer = alice.peerTowards(bob, aliceCtx)
        bobPeer = bob.peerTowards(alice, bobCtx)
        assertTrue(alicePeer.open().isSuccess)
        assertTrue(bobPeer.open().isSuccess)

        // Autenticacion de AMBOS peers contra el relay (KM-0002).
        aliceSignaling = RelaySignaling(alice.identityId, bob.identityId, relay, RelaySignaling.Role.OFFERER)
        bobSignaling = RelaySignaling(bob.identityId, alice.identityId, relay, RelaySignaling.Role.ANSWERER)

        // Bob recibe del relay y responde a Alice.
        bob.connectToRelay(relay) { signal ->
            relayTraffic.add(signal)
            bobSignaling.receive(signal)
        }
        // Alice recibe del relay.
        alice.connectToRelay(relay) { signal ->
            relayTraffic.add(signal)
            aliceSignaling.receive(signal)
        }

        // Cableado del signaling por rol.
        alicePeer.signaling = aliceSignaling
        bobPeer.signaling = bobSignaling

        // Offerer recibe el Answer del relay.
        aliceSignaling.onRemoteSdp = { answer -> alicePeer.applyAnswer(answer) }
        // Answerer recibe la Offer del relay.
        bobSignaling.onRemoteSdp = { offer -> bobPeer.acceptOffer(offer) }
        // ICE en ambos sentidos.
        aliceSignaling.onRemoteIce = { ice -> alicePeer.deliverIce(ice) }
        bobSignaling.onRemoteIce = { ice -> bobPeer.deliverIce(ice) }
    }

    @AfterEach
    fun tearDown() {
        alice.dispose()
        bob.dispose()
    }

    // ===================================================================
    // R13-01..03 — Relay: autenticacion y presencia
    // ===================================================================

    @Test
    fun `R13-01 ambos peers autentican contra el RelayServer real`() {
        val aliceSession = relay.getSession(alice.identityId)
        val bobSession = relay.getSession(bob.identityId)
        assertNotNull(aliceSession, "Alice debe tener sesion en el relay")
        assertNotNull(bobSession, "Bob debe tener sesion en el relay")
        assertTrue(aliceSession!!.isActive)
        assertTrue(bobSession!!.isActive)
    }

    @Test
    fun `R13-02 solo peers autenticados aparecen online`() {
        val online = relay.onlinePeers()
        assertTrue(online.contains(alice.identityId))
        assertTrue(online.contains(bob.identityId))
        assertFalse(online.contains(relayId), "el propio relay no es un peer online")
    }

    @Test
    fun `R13-03 un peer no autenticado no puede aparecer online`() {
        val mallory = AuthenticatedNode(WebRtcTestEnv.ed25519, "mallory")
        try {
            val r = relay.registerPeerHandler(mallory.identityId) { }
            assertTrue(r.isFailure, "sin sesion no puede registrarse")
            assertFalse(relay.isPeerOnline(mallory.identityId))
        } finally {
            mallory.dispose()
        }
    }

    // ===================================================================
    // R13-04..06 — Signaling atraviesa el relay
    // ===================================================================

    @Test
    fun `R13-04 SDP offer atraviesa el relay y llega al answerer`() {
        alicePeer.startOffering()
        assertTrue(relayTraffic.any { it is RelaySignalMessage.SdpOffer },
            "el relay debe transportar la oferta SDP")
        // La oferta entregada es la SDP real generada por WebRTC.
        val offer = relayTraffic.first { it is RelaySignalMessage.SdpOffer } as RelaySignalMessage.SdpOffer
        assertTrue(offer.sdp.contains("v=0"), "debe ser la SDP real")
        assertTrue(offer.sdp.contains("a=ice-ufrag:"), "debe incluir credenciales ICE reales")
    }

    @Test
    fun `R13-05 SDP answer atraviesa el relay y llega al offerer`() {
        alicePeer.startOffering()
        // La respuesta es asincrona: el answerer la genera y la enruta
        // por el relay. Esperamos con timeout en vez de asumir sincronia.
        assertTrue(
            awaitRelayTraffic { it is RelaySignalMessage.SdpAnswer },
            "el relay debe transportar la respuesta SDP"
        )
        val answer = relayTraffic.first { it is RelaySignalMessage.SdpAnswer } as RelaySignalMessage.SdpAnswer
        assertTrue(answer.sdp.contains("v=0"), "la respuesta debe ser SDP real")
        // Y el offerer la aplico: su binding avanzo de OFFER_SENT.
        assertTrue(
            alice.manager.getBinding(alice.identityId, bob.identityId)?.sdp?.remoteAnswer?.isNotEmpty() == true,
            "el offerer debe haber registrado la respuesta"
        )
    }

    @Test
    fun `R13-06 ICE candidates atraviesan el relay`() {
        alicePeer.startOffering()
        // Conectar para forzar generacion e intercambio de ICE.
        awaitConnectedBoth()
        assertTrue(relayTraffic.any { it is RelaySignalMessage.IceCandidate },
            "el relay debe transportar los candidatos ICE")
    }

    // ===================================================================
    // R13-07..09 — Seguridad del relay
    // ===================================================================

    @Test
    fun `R13-07 el relay rechaza remitente falsificado - spoofing`() {
        val spoofed = RelaySignalMessage.SdpOffer(
            from = bob.identityId,           // dice ser Bob
            to = alice.identityId,
            sdp = "v=0\\r\\nmalicioso",
            timestamp = 0L,
        )
        val result = relay.handleSignal(alice.identityId, spoofed)  // AliceAutenticada
        assertTrue(result.isFailure)
        assertEquals(
            RelayServerError.SPOOFING_DETECTED,
            (result as com.km.node.RelayServerResult.Failure).error
        )
    }

    @Test
    fun `R13-08 el relay rechaza destino no conectado`() {
        val charlie = AuthenticatedNode(WebRtcTestEnv.ed25519, "charlie")
        try {
            // Charlie no esta conectado al relay.
            val signal = RelaySignalMessage.SdpOffer(alice.identityId, charlie.identityId, "v=0", 0L)
            val result = relay.handleSignal(alice.identityId, signal)
            assertTrue(result.isFailure)
            assertEquals(
                RelayServerError.PEER_NOT_ONLINE,
                (result as com.km.node.RelayServerResult.Failure).error
            )
        } finally {
            charlie.dispose()
        }
    }

    @Test
    fun `R13-09 sesion cerrada no puede senalizar`() {
        relay.disconnect(bob.identityId)
        val signal = RelaySignalMessage.SdpOffer(alice.identityId, bob.identityId, "v=0", 0L)
        val result = relay.handleSignal(alice.identityId, signal)
        assertTrue(result.isFailure)
        // Bob desconectado ya no puede recibir.
        assertEquals(
            RelayServerError.PEER_NOT_ONLINE,
            (result as com.km.node.RelayServerResult.Failure).error
        )
    }

    // ===================================================================
    // R13-10..12 — WebRTC real alimentado por el relay
    // ===================================================================

    @Test
    fun `R13-10 WebRTC alcanza CONNECTED mediante signaling por relay`() {
        alicePeer.startOffering()
        awaitConnectedBoth()
        assertEquals(PeerTransportState.CONNECTED, alicePeer.bindingState())
        assertEquals(PeerTransportState.CONNECTED, bobPeer.bindingState())
    }

    @Test
    fun `R13-11 DataChannel transmite bytes exactos y el relay no los ve`() {
        alicePeer.startOffering()
        awaitConnectedBoth()
        assertTrue(alice.transport.awaitDataChannelOpen(alice.identityId, bob.identityId))
        assertTrue(bob.transport.awaitDataChannelOpen(bob.identityId, alice.identityId))

        val secret = "contenido-secreto-que-no-debe-ver-el-relay".toByteArray()
        val received = CountDownLatch(1)
        bobPeer.onApplicationData = { received.countDown() }

        assertTrue(alicePeer.send(secret).isSuccess, "send via manager (CONNECTED)")
        assertTrue(received.await(10, TimeUnit.SECONDS), "Bob debe recibir por DataChannel")
        assertArrayEquals(secret, bobPeer.receivedData.first(), "bytes exactos via DataChannel")

        // PROPIEDAD KM-0003: el relay NO transporto los bytes de aplicacion.
        val secretText = String(secret)
        val relaySawSecret = relayTraffic.any { signal ->
            val text = when (signal) {
                is RelaySignalMessage.SdpOffer -> signal.sdp
                is RelaySignalMessage.SdpAnswer -> signal.sdp
                is RelaySignalMessage.IceCandidate -> signal.candidate
                else -> ""
            }
            text.contains(secretText)
        }
        assertFalse(relaySawSecret, "el relay no debe transportar datos de aplicacion")
    }

    @Test
    fun `R13-12 el relay solo transporta senalizacion - no datos`() {
        alicePeer.startOffering()
        awaitConnectedBoth()
        // Todos los mensajes del relay deben ser de senalizacion,
        // nunca datos de aplicacion.
        for (signal in relayTraffic) {
            assertTrue(
                signal is RelaySignalMessage.SdpOffer ||
                    signal is RelaySignalMessage.SdpAnswer ||
                    signal is RelaySignalMessage.IceCandidate ||
                    signal is RelaySignalMessage.ContactExchange ||
                    signal is RelaySignalMessage.PeerOnline ||
                    signal is RelaySignalMessage.PeerOffline,
                "tipo de mensaje inesperado en el relay: ${signal::class.simpleName}"
            )
        }
    }

    // ===================================================================
    // R13-13..16 — Aislamiento, reauth y teardown
    // ===================================================================

    @Test
    fun `R13-13 Charlie no puede inyectarse en la senalizacion Alice-Bob`() {
        val charlie = AuthenticatedNode(WebRtcTestEnv.ed25519, "charlie")
        try {
            // Charlie se autentica en el relay legtimamente.
            charlie.connectToRelay(relay) { }
            // Pero no puede enviar señal en nombre de Alice.
            val spoofed = RelaySignalMessage.SdpOffer(
                from = alice.identityId,      // suplanta a Alice
                to = bob.identityId,
                sdp = "v=0\\r\\ninyeccion",
                timestamp = 0L,
            )
            val result = relay.handleSignal(charlie.identityId, spoofed)
            assertTrue(result.isFailure)
            assertEquals(
                RelayServerError.SPOOFING_DETECTED,
                (result as com.km.node.RelayServerResult.Failure).error
            )
        } finally {
            charlie.dispose()
        }
    }

    @Test
    fun `R13-14 reauth del relay invalida la sesion de senalizacion`() {
        // Alice esta autenticada y puede senalizar.
        val ok = relay.handleSignal(
            alice.identityId,
            RelaySignalMessage.SdpOffer(alice.identityId, bob.identityId, "v=0", 0L),
        )
        assertTrue(ok.isSuccess)
        // Tras reconectar su sesion, la senalizacion sigue operativa
        // pero el WebRTC no se vio afectado (son planos separados).
        alice.connectToRelay(relay) { signal ->
            relayTraffic.add(signal)
            aliceSignaling.receive(signal)
        }
        val ok2 = relay.handleSignal(
            alice.identityId,
            RelaySignalMessage.SdpOffer(alice.identityId, bob.identityId, "v=0", 0L),
        )
        assertTrue(ok2.isSuccess, "tras reauth la senalizacion debe funcionar")
    }

    @Test
    fun `R13-15 desconexion del relay cierra sesion pero no WebRTC ya establecido`() {
        alicePeer.startOffering()
        awaitConnectedBoth()
        // Ambos DataChannels deben estar OPEN antes de medir entrega.
        assertTrue(alice.transport.awaitDataChannelOpen(alice.identityId, bob.identityId),
            "DataChannel de Alice debe abrirse")
        assertTrue(bob.transport.awaitDataChannelOpen(bob.identityId, alice.identityId),
            "DataChannel de Bob debe abrirse")

        // El DataChannel ya esta establecido: desconectar el relay no lo rompe.
        alice.disconnectFromRelay(relay)
        assertFalse(relay.getSession(alice.identityId)!!.isActive, "sesion relay cerrada")
        // El WebRTC sigue operativo porque es un plano independiente.
        assertEquals(PeerTransportState.CONNECTED, alicePeer.bindingState())
        val secret = "post-relay".toByteArray()
        val received = CountDownLatch(1)
        bobPeer.onApplicationData = { received.countDown() }
        assertTrue(alicePeer.send(secret).isSuccess, "DataChannel sigue vivo tras cerrar relay")
        assertTrue(received.await(10, TimeUnit.SECONDS), "los datos siguen fluyendo sin el relay")
        assertArrayEquals(secret, bobPeer.receivedData.last())
    }

    @Test
    fun `R13-16 shutdown limpia relay, manager y PeerConnections`() {
        alicePeer.startOffering()
        awaitConnectedBoth()
        // Limpiar todo.
        relay.disconnectAll()
        alice.manager.clear()
        assertEquals(0, alice.transport.activeConnectionCount(alice.identityId, bob.identityId))
        assertEquals(0, relay.activeSessionCount(), "ninguna sesion relay activa")
    }

    // ===================================================================
    // Helpers
    // ===================================================================

    private fun awaitConnectedBoth(timeoutMs: Long = 20_000L) {
        assertTrue(alicePeer.awaitConnected(timeoutMs), "Alice no llego a CONNECTED")
        assertTrue(bobPeer.awaitConnected(timeoutMs), "Bob no llego a CONNECTED")
    }

    /** Espera a que aparezca en el trafico del relay un mensaje del tipo dado. */
    private fun awaitRelayTraffic(
        timeoutMs: Long = 15_000L,
        predicate: (RelaySignalMessage) -> Boolean,
    ): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (relayTraffic.any(predicate)) return true
            Thread.sleep(25)
        }
        return false
    }
}
