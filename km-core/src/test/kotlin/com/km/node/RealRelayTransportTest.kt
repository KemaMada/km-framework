package com.km.node

import com.km.auth.AuthChallenge
import com.km.auth.AuthOk
import com.km.auth.AuthSession
import com.km.auth.Clock
import com.km.auth.RelayAuthHandler
import com.km.auth.TranscriptBuilder
import com.km.crypto.Ed25519
import com.km.crypto.Ed25519Impl
import com.km.crypto.KeyPair
import com.km.model.IdentityId
import com.km.model.MessageId
import com.km.model.NodeAnnouncement
import com.km.model.NodeAnnouncementJsonCodec
import com.km.model.NodeIdentity
import com.km.storage.InMemoryRelayStore
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.assertContentEquals

/**
 * 3Q.3 — El relay como medio real: camino de datos y establecimiento.
 *
 * El relay no interpretaba datos: hasta 3Q.3 solo ruteaba senalizacion. Este
 * checkpoint le anade un camino de DATOS que cumple dos invariantes:
 *
 *  1. Anti-spoofing igual que en las senales: `from` se verifica contra la
 *     sesion autenticada ANTES de rutear.
 *  2. El relay NO puede ver el contenido. El payload es un `ByteArray` opaco
 *     y no existe ningun punto del camino donde se interprete.
 */
class RealRelayTransportTest {

    private val ed25519: Ed25519 = Ed25519Impl()
    private val relayKP = ed25519.generateKeyPair()
    private val aliceKP = ed25519.generateKeyPair()
    private val bobKP = ed25519.generateKeyPair()
    private val aliceId = IdentityId(deriveId(aliceKP.publicKey))
    private val bobId = IdentityId(deriveId(bobKP.publicKey))
    private val clock: Clock = Clock { 1000L }

    private var tick = 1_000L

    // ===================================================================
    // Arranque del relay con dos peers autenticados
    // ===================================================================

    private inner class World(maxPayload: Int = 65_535) {
        val server = RelayServer(
            relayKeyPair = relayKP,
            ed25519 = ed25519,
            relayService = RelayServiceImpl(InMemoryRelayStore(now = { tick }), now = { tick }),
            relayIdentityId = IdentityId(deriveId(relayKP.publicKey)),
            now = { tick },
            maxDataPayloadBytes = maxPayload,
        )

        fun connect(id: IdentityId, kp: KeyPair): RelayServerResult<Unit> {
            val challenge = server.createChallenge(id)
            val response = RelayAuthHandler(ed25519).buildResponse(
                challenge = AuthChallenge(challenge.nonce, tick, 3, IdentityId(deriveId(relayKP.publicKey)).value),
                identityId = id.value,
                keyPair = kp,
            )
            val r = server.authenticate(id, response)
            return if (r.isSuccess) RelayServerResult.Success(Unit) else (r as RelayServerResult.Failure)
        }

        /** Relay con Alice y Bob autenticados y registrados. */
        fun withBothOnline(): World {
            assertTrue(connect(aliceId, aliceKP).isSuccess, "Alice debe autenticarse")
            assertTrue(connect(bobId, bobKP).isSuccess, "Bob debe autenticarse")
            server.registerPeerHandler(aliceId) {}
            server.registerPeerHandler(bobId) {}
            return this
        }
    }

    // ===================================================================
    // El camino de datos
    // ===================================================================

    @Test
    @DisplayName("R3-01 el relay reenvia bytes opacos entre peers autenticados")
    fun `R3-01 reenvio de datos`() {
        val w = World().withBothOnline()
        val received = CopyOnWriteArrayList<ByteArray>()
        w.server.registerDataHandler(bobId) { received.add(it.payload) }

        val payload = ByteArray(64) { it.toByte() }
        val r = w.server.deliverData(aliceId, RelayDataEnvelope(aliceId, bobId, payload, tick))

        assertTrue(r.isSuccess, "el relay debe enrutar: $r")
        assertEquals(1, received.size)
        assertContentEquals(payload, received.first(), "el payload debe llegar intacto")
    }

    @Test
    @DisplayName("R3-02 el relay NO puede ver el contenido: solo mueve un ByteArray")
    fun `R3-02 opacidad`() {
        val w = World().withBothOnline()
        var vistoPorElRelay: ByteArray? = null
        w.server.registerDataHandler(bobId) { envelope ->
            // Lo UNICO que el relay puede hacer con el payload es reenviarlo.
            // No hay campo, ni metodo, ni ruta que lo convierta en mensaje.
            vistoPorElRelay = envelope.payload
        }

        val cifrado = ByteArray(44) { 0xAB.toByte() }
        w.server.deliverData(aliceId, RelayDataEnvelope(aliceId, bobId, cifrado, tick))

        assertContentEquals(cifrado, vistoPorElRelay, "los bytes viajan sin alterarse")

        // Y el envelope no expone ninguna semantica de mensaje: exactamente
        // cuatro campos, todos de direccion o de transporte.
        val campos = RelayDataEnvelope::class.java.declaredFields.map { it.name }.toSet()
        assertEquals(setOf("from", "to", "payload", "timestamp"), campos)
        for (prohibido in listOf("message", "messageId", "conversation", "body", "kind", "type", "ack")) {
            assertFalse(campos.any { it.contains(prohibido, ignoreCase = true) }, "$prohibido no debe existir en el camino de datos")
        }
    }

    @Test
    @DisplayName("R3-03 anti-spoofing: un `from` falso se rechaza ANTES de rutear")
    fun `R3-03 anti spoofing`() {
        val w = World().withBothOnline()
        val received = CopyOnWriteArrayList<ByteArray>()
        w.server.registerDataHandler(bobId) { received.add(it.payload) }

        // Alice intenta enviar haciendose pasar por Bob.
        val r = w.server.deliverData(aliceId, RelayDataEnvelope(bobId, bobId, byteArrayOf(1), tick))

        assertTrue(r.isFailure, "un from suplantado no puede pasar: $r")
        assertEquals(RelayServerError.SPOOFING_DETECTED, (r as RelayServerResult.Failure).error)
        assertTrue(received.isEmpty(), "nada debe rutearse si el emisor no es quien dice")
    }

    @Test
    @DisplayName("R3-04 un emisor no autenticado no puede usar el relay")
    fun `R3-04 requiere sesion`() {
        val w = World().withBothOnline()
        w.server.registerDataHandler(bobId) {}

        val r = w.server.deliverData(aliceId, RelayDataEnvelope(aliceId, bobId, byteArrayOf(1), tick))
        // Alice si esta autenticada; el que no lo esta es Charlie.
        assertTrue(r.isSuccess)

        val r2 = w.server.deliverData(
            IdentityId("c".repeat(64)),
            RelayDataEnvelope(IdentityId("c".repeat(64)), bobId, byteArrayOf(1), tick),
        )
        assertEquals(RelayServerError.SESSION_NOT_FOUND, (r2 as RelayServerResult.Failure).error)
    }

    @Test
    @DisplayName("R3-05 un payload excesivo se rechaza: el relay no es un amplificador")
    fun `R3-05 limite de payload`() {
        val w = World(maxPayload = 128).withBothOnline()
        val received = CopyOnWriteArrayList<ByteArray>()
        w.server.registerDataHandler(bobId) { received.add(it.payload) }

        val enorme = ByteArray(1024)
        val r = w.server.deliverData(aliceId, RelayDataEnvelope(aliceId, bobId, enorme, tick))

        assertTrue(r.isFailure, "un payload de 1KB no debe entrar con un tope de 128B")
        assertEquals(RelayServerError.INVALID_MESSAGE, (r as RelayServerResult.Failure).error)
        assertTrue(received.isEmpty(), "nada debe rutearse")
    }

    @Test
    @DisplayName("R3-06 el envelope compara por CONTENIDO, no por identidad de referencia")
    fun `R3-06 igualdad por contenido`() {
        // Sin esto, dos envoltorios con los mismos bytes serian distintos y la
        // deduplicacion en silencio.
        val a = RelayDataEnvelope(aliceId, bobId, byteArrayOf(1, 2, 3), 5L)
        val b = RelayDataEnvelope(aliceId, bobId, byteArrayOf(1, 2, 3), 5L)
        val c = RelayDataEnvelope(aliceId, bobId, byteArrayOf(1, 2, 4), 5L)

        assertEquals(a, b, "mismos bytes, mismo envoltorio")
        assertEquals(a.hashCode(), b.hashCode(), "el hash debe coincidir tambien")
        assertNotEquals(a, c, "bytes distintos, envoltorios distintos")
    }

    @Test
    @DisplayName("R3-07 el transporte de relay traduce los fallos a transporte correctamente")
    fun `R3-07 traduccion de errores`() {
        val w = World().withBothOnline()
        val t = RealRelayTransport(server = w.server, localIdentity = aliceId, now = { tick })

        // Destinatario no conectado: fallo del MEDIO, autoriza fallback.
        val r = t.sendData(IdentityId("d".repeat(64)), byteArrayOf(1))
        assertEquals(TransportError.PEER_NOT_ONLINE_AT_RELAY, (r as TransportResult.Failure).error)
        assertTrue(r.error.permitsFallback, "destinatario ausente es un fallo del medio")

        // Suplantacion: NUNCA autoriza fallback.
        val suplantado = w.server.deliverData(aliceId, RelayDataEnvelope(bobId, bobId, byteArrayOf(1), tick))
        assertEquals(RelayServerError.SPOOFING_DETECTED, (suplantado as RelayServerResult.Failure).error)
    }

    // ===================================================================
    // Establecimiento real por relay
    // ===================================================================

    @Test
    @DisplayName("R3-08 el relay alcanza READY cuando el destino esta en linea")
    fun `R3-08 relay ready`() {
        val w = World().withBothOnline()
        val est = RealRelayEstablishment(
            RealRelayTransport(server = w.server, localIdentity = aliceId, now = { tick }),
            bobId,
        )

        assertEquals(EstablishmentState.CREATED, est.state)
        est.initialize().getOrThrow()
        est.createBinding(aliceId, bobId).getOrThrow()
        // Negociar bien NO es estar listo: el relay no tiene SDP, pero aun
        // falta la condicion terminal.
        est.negotiate().getOrThrow()
        assertEquals(EstablishmentState.NEGOTIATING, est.state, "negociar no es READY")

        est.awaitReady(1_000L).getOrThrow()
        assertEquals(EstablishmentState.READY, est.state, "el destino en linea ES la condicion terminal")
    }

    @Test
    @DisplayName("R3-09 si el destino no esta en linea, NO hay READY (fallo del medio)")
    fun `R3-09 destino ausente`() {
        val w = World()
        assertTrue(w.connect(aliceId, aliceKP).isSuccess)
        val est = RealRelayEstablishment(
            RealRelayTransport(server = w.server, localIdentity = aliceId, now = { tick }),
            bobId,
        )
        est.initialize().getOrThrow()

        // Bob nunca se conecto al relay.
        val r = est.createBinding(aliceId, bobId) as TransportResult.Failure
        assertEquals(TransportError.PEER_NOT_ONLINE_AT_RELAY, r.error)
        assertTrue(r.error.permitsFallback, "debe poder pasar a otro medio")
        assertNotEquals(EstablishmentState.READY, est.state)
    }

    @Test
    @DisplayName("R3-10 abort cierra el establecimiento y revoca la autoridad")
    fun `R3-10 abort`() {
        val w = World().withBothOnline()
        val est = RealRelayEstablishment(
            RealRelayTransport(server = w.server, localIdentity = aliceId, now = { tick }),
            bobId,
        )
        est.initialize().getOrThrow()
        est.createBinding(aliceId, bobId).getOrThrow()
        est.negotiate().getOrThrow()
        est.awaitReady(1_000L).getOrThrow()
        assertEquals(EstablishmentState.READY, est.state)

        est.abort()

        assertEquals(EstablishmentState.CLOSED, est.state)
        assertFalse(est.state.canCarryData, "cerrado no puede llevar datos")
    }

    // ===================================================================
    // La frontera con el manager
    // ===================================================================

    @Test
    @DisplayName("R3-11 el relay como TransportBackend del manager: un solo binding")
    fun `R3-11 relay en el manager`() {
        val w = World().withBothOnline()
        val relay = RealRelayTransport(server = w.server, localIdentity = aliceId, now = { tick })
        val mgr = PeerTransportManager(transportBackend = relay, now = { tick })

        val ctx = peerContext()
        mgr.createBinding(aliceId, ctx).getOrThrow()
        mgr.sendOffer(aliceId, ctx, "offer", bobId).getOrThrow()
        mgr.receiveAnswer(aliceId, bobId, "answer").getOrThrow()
        mgr.markConnected(aliceId, ctx).getOrThrow()

        val recibido = CopyOnWriteArrayList<ByteArray>()
        // El registro de datos es POR LADO: Bob debe estar suscrito en el
        // servidor, o el relay no tiene a quien rutear y lo rechaza.
        w.server.registerDataHandler(bobId) { envelope -> recibido.add(envelope.payload) }
        relay.onDataFrom(bobId) { recibido.add(it) }

        // El frame llega a Bob por el relay, sin que el relay lo lea.
        val frame = ByteArray(44) { 0x5A.toByte() }
        assertTrue(mgr.sendData(aliceId, ctx, frame).isSuccess)

        assertEquals(1, recibido.size, "Bob recibio el frame")
        assertContentEquals(frame, recibido.first(), "el ciphertext llega intacto")
        assertEquals(1, mgr.activeBindings().size, "un solo medio para el peer")
    }

    @Test
    @DisplayName("R3-12 el relay transport no depende de WebRTC")
    fun `R3-12 sin webrtc`() {
        val dir = java.io.File("src/main/kotlin/com/km/node")
        val f = java.io.File(dir, "RealRelayTransport.kt")
        assertTrue(f.exists(), "debe existir ${f.name}")
        val code = f.readLines()
            .filterNot { val t = it.trimStart(); t.startsWith("*") || t.startsWith("//") || t.startsWith("/*") }
        assertTrue(
            code.none { it.contains("dev.onvoid") || it.contains("WebRtc") || it.contains("webrtc") },
            "el relay no puede depender de WebRTC",
        )
    }

    private fun peerContext(): PeerContext {
        val session = AuthSession.initiator(aliceKP, aliceId.value, ed25519, clock = clock)
        session.receiveChallenge(AuthChallenge(ByteArray(16), 1000L, 3, bobId.value)).getOrThrow()
        session.markResponseSent()
        val sid = "a".repeat(40)
        val snonce = ByteArray(16) { 3 }
        val transcript = TranscriptBuilder.serverAuthTranscript(sid, snonce, aliceId.value)
        session.receiveAuthOk(
            AuthOk(sid, snonce, aliceKP.publicKey, ed25519.sign(aliceKP.privateKey, transcript).bytes),
            aliceId.value,
        ).getOrThrow()
        val identity = NodeIdentity(bobId, bobKP.publicKey)
        val partial = NodeAnnouncement(
            messageId = MessageId(UUID.randomUUID()),
            timestamp = 0L,
            protocolVersion = "1.0",
            identity = identity,
            endpoints = emptyList(),
            capabilities = emptySet(),
        )
        val ann = partial.copy(
            signature = ed25519.sign(bobKP.privateKey, NodeAnnouncementJsonCodec.signableJson(partial)).bytes,
        )
        return PeerContext.authenticated(bobId, bobKP.publicKey, ann, session)
    }

    private fun deriveId(publicKey: ByteArray): String =
        MessageDigest.getInstance("SHA-256").let { d ->
            d.update("KM-ID-IDENTITY".toByteArray())
            d.update(publicKey)
            d.digest().joinToString("") { "%02x".format(it) }
        }
}
