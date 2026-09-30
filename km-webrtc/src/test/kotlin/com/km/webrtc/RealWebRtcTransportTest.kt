package com.km.webrtc

import com.km.model.IdentityId
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.security.MessageDigest

/**
 * 3N.1 — Integracion WebRTC real, dos PeerConnection en el mismo proceso.
 *
 * Aisla la integracion WebRTC de KM-0003 (relay). No hay PeerContext,
 * AuthSession ni RelayServer aqui: solo dos [RealWebRtcTransport] reales.
 *
 * Requiere la libreria nativa webrtc-java para la plataforma actual.
 */
class RealWebRtcTransportTest {

    private lateinit var alice: RealWebRtcTransport
    private lateinit var bob: RealWebRtcTransport
    private lateinit var link: DirectWebRtcLink

    private val aliceId = identityIdFromSeed("alice")
    private val bobId = identityIdFromSeed("bob")

    @BeforeEach
    fun setUp() {
        alice = RealWebRtcTransport()
        bob = RealWebRtcTransport()

        val aliceInit = alice.initialize()
        assertTrue(aliceInit.isSuccess, "Alice initialize fallo: $aliceInit")
        val bobInit = bob.initialize()
        assertTrue(bobInit.isSuccess, "Bob initialize fallo: $bobInit")

        // Los bindings existen en ambos lados (equivalente a createBinding).
        assertTrue(alice.onCreateBinding(aliceId, bobId).isSuccess)
        assertTrue(bob.onCreateBinding(bobId, aliceId).isSuccess)

        link = DirectWebRtcLink(alice, bob, aliceId, bobId).wire()
    }

    @AfterEach
    fun tearDown() {
        alice.shutdown()
        bob.shutdown()
        alice.disposeCallbacks()
        bob.disposeCallbacks()
    }

    // ===================================================================
    // N1 — Inicializacion
    // ===================================================================

    @Test
    fun `N1-10 initialize es idempotente`() {
        assertTrue(alice.initialize().isSuccess)
        assertTrue(alice.initialize().isSuccess)
    }

    @Test
    fun `N1-11 shutdown libera recursos`() {
        assertTrue(alice.shutdown().isSuccess)
        // Tras shutdown, crear binding falla (no inicializado).
        val r = alice.onCreateBinding(identityIdFromSeed("x"), identityIdFromSeed("y"))
        assertTrue(r.isFailure)
        // Re-inicializar funciona.
        assertTrue(alice.initialize().isSuccess)
    }

    @Test
    fun `N1-12 dos PeerConnection coexisten`() {
        assertEquals(
            dev.onvoid.webrtc.RTCPeerConnectionState.NEW,
            alice.connectionState(aliceId, bobId)
        )
        assertEquals(
            dev.onvoid.webrtc.RTCPeerConnectionState.NEW,
            bob.connectionState(bobId, aliceId)
        )
    }

    @Test
    fun `N1-13 operar sin inicializar falla explicitamente`() {
        val fresh = RealWebRtcTransport()
        val r = fresh.onCreateBinding(identityIdFromSeed("p"), identityIdFromSeed("q"))
        assertTrue(r.isFailure)
    }

    // ===================================================================
    // N2 — SDP
    // ===================================================================

    @Test
    fun `N2-01 Alice genera offer real`() {
        alice.createDataChannel(aliceId, bobId, "km")
        alice.requestOffer(aliceId, bobId)
        val offer = link.aliceLocalSdp.await()
        assertTrue(offer.contains("v=0"), "SDP debe ser un SDP real")
        assertTrue(offer.contains("m=application"), "offer debe incluir m-line de datos")
    }

    @Test
    fun `N2-02 Bob genera answer real`() {
        val (offer, answer) = link.handshake()
        assertTrue(offer.contains("v=0"))
        assertTrue(answer.contains("v=0"), "answer debe ser SDP real")
    }

    @Test
    fun `N2-03 handshake completo offer-answer aplicado`() {
        link.handshake()
        // Ambos lados aplicaron descripcion remota sin error.
        assertTrue(link.bobErrors.isEmpty(), "Bob no debe tener errores: ${link.bobErrors}")
        assertTrue(link.aliceErrors.isEmpty(), "Alice no debe tener errores: ${link.aliceErrors}")
        // Bob recibio la oferta: su conexion salio de NEW.
        assertNotEquals(
            dev.onvoid.webrtc.RTCPeerConnectionState.NEW,
            bob.connectionState(bobId, aliceId),
            "Bob debe haber procesado la oferta"
        )
    }

    // ===================================================================
    // N3 — ICE + conexion
    // ===================================================================

    @Test
    fun `N3-01 ICE candidates generados`() {
        link.handshake()
        // Espera conexion; los candidatos host deben generarse en el proceso.
        link.awaitBothConnected()
        assertTrue(link.aliceIce.isNotEmpty() || link.bobIce.isNotEmpty(),
            "al menos un lado debe generar ICE candidates")
    }

    @Test
    fun `N3-02 ambos lados alcanzan CONNECTED`() {
        link.handshake()
        link.awaitBothConnected()
    }

    @Test
    fun `N3-03 estado de conexion reportado por el transporte`() {
        link.handshake()
        link.awaitBothConnected()
        // Tras el callback CONNECTED, el estado de la conexion debe ser CONNECTED.
        assertEquals(
            dev.onvoid.webrtc.RTCPeerConnectionState.CONNECTED,
            bob.connectionState(bobId, aliceId)
        )
        assertEquals(
            dev.onvoid.webrtc.RTCPeerConnectionState.CONNECTED,
            alice.connectionState(aliceId, bobId)
        )
    }

    // ===================================================================
    // N4 — DataChannel
    // ===================================================================

    private fun connect(): DirectWebRtcLink {
        link.handshake()
        link.awaitBothConnected()
        // Espera que el DataChannel este OPEN en ambos lados antes de enviar.
        assertTrue(
            alice.awaitDataChannelOpen(aliceId, bobId),
            "DataChannel de Alice no llego a OPEN"
        )
        assertTrue(
            bob.awaitDataChannelOpen(bobId, aliceId),
            "DataChannel de Bob no llego a OPEN"
        )
        return link
    }

    @Test
    fun `N4-01 bytes binarios preservados exactamente`() {
        connect()
        val payload = byteArrayOf(0x00, 0x01, 0x02, 0xFF.toByte(), 0xFE.toByte())
        link.bobReceived.expect { it.contentEquals(payload) }
        val r = alice.sendData(bobId, payload)
        assertTrue(r.isSuccess, "sendData fallo: $r")
        val got = link.bobReceived.await()
        assertArrayEquals(payload, got, "bytes recibidos deben ser identicos")
    }

    @Test
    fun `N4-02 payload grande preservado`() {
        connect()
        val payload = ByteArray(4096) { (it % 256).toByte() }
        link.bobReceived.expect { it.size == payload.size }
        alice.sendData(bobId, payload)
        val got = link.bobReceived.await()
        assertArrayEquals(payload, got)
    }

    @Test
    fun `N4-03 bidireccional`() {
        connect()
        val fromAlice = "hola-bob".toByteArray()
        val fromBob = "hola-alice".toByteArray()

        link.bobReceived.expect { it.contentEquals(fromAlice) }
        link.aliceReceived.expect { it.contentEquals(fromBob) }

        assertTrue(alice.sendData(bobId, fromAlice).isSuccess)
        assertArrayEquals(fromAlice, link.bobReceived.await())

        assertTrue(bob.sendData(aliceId, fromBob).isSuccess)
        assertArrayEquals(fromBob, link.aliceReceived.await())
    }

    // ===================================================================
    // N5 — Teardown
    // ===================================================================

    @Test
    fun `N5-01 closeBinding cierra la conexion`() {
        connect()
        assertTrue(alice.onCloseBinding(aliceId, bobId).isSuccess)
        val deadline = System.currentTimeMillis() + 5_000
        var state = alice.connectionState(aliceId, bobId)
        while (state == dev.onvoid.webrtc.RTCPeerConnectionState.CONNECTED &&
            System.currentTimeMillis() < deadline
        ) {
            Thread.sleep(50)
            state = alice.connectionState(aliceId, bobId)
        }
        // Tras cerrar, el recurso fue removido (null).
        assertNull(alice.connectionState(aliceId, bobId))
    }

    @Test
    fun `N5-02 sendData tras cierre falla`() {
        connect()
        alice.onCloseBinding(aliceId, bobId)
        val r = alice.sendData(bobId, byteArrayOf(1, 2, 3))
        assertTrue(r.isFailure, "sendData tras cierre debe fallar")
    }

    @Test
    fun `N5-03 doble cierre es seguro`() {
        connect()
        assertTrue(alice.onCloseBinding(aliceId, bobId).isSuccess)
        // Segundo cierre: no hay recursos, pero no debe lanzar.
        assertTrue(alice.onCloseBinding(aliceId, bobId).isSuccess)
    }

    @Test
    fun `N5-04 shutdown tras conexion no lanza`() {
        connect()
        assertTrue(alice.shutdown().isSuccess)
        assertTrue(bob.shutdown().isSuccess)
    }

    // ===================================================================
    // Identidad NO participa en WebRTC
    // ===================================================================

    @Test
    fun `N6-01 identityId no aparece en la SDP`() {
        val (offer, answer) = link.handshake()
        assertFalse(offer.contains(aliceId.value), "identityId no debe filtrarse en offer")
        assertFalse(offer.contains(bobId.value), "identityId no debe filtrarse en offer")
        assertFalse(answer.contains(aliceId.value), "identityId no debe filtrarse en answer")
        assertFalse(answer.contains(bobId.value), "identityId no debe filtrarse en answer")
    }

    @Test
    fun `N6-02 sendData a peer inexistente falla`() {
        val unknown = identityIdFromSeed("unknown")
        val r = alice.sendData(unknown, byteArrayOf(1))
        assertTrue(r.isFailure)
    }

    // ===================================================================
    // Helpers
    // ===================================================================

    private fun identityIdFromSeed(seed: String): IdentityId {
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update("KM-ID-IDENTITY".toByteArray())
        digest.update(seed.toByteArray())
        return IdentityId(digest.digest().joinToString("") { "%02x".format(it) })
    }
}