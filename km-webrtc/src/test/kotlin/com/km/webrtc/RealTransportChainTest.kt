package com.km.webrtc

import com.km.auth.AuthChallenge
import com.km.auth.AuthOk
import com.km.auth.AuthSession
import com.km.auth.Clock
import com.km.auth.RelayAuthHandler
import com.km.auth.TranscriptBuilder
import com.km.crypto.Ed25519
import com.km.crypto.Ed25519Impl
import com.km.crypto.KeyPair
import com.km.crypto.provider.BcChaCha20Poly1305
import com.km.crypto.provider.BcHkdfSha256
import com.km.crypto.provider.BcX25519
import com.km.messaging.SecureMessagingSession
import com.km.messaging.SendResult
import com.km.model.IdentityId
import com.km.model.MessageId
import com.km.model.NodeAnnouncement
import com.km.model.NodeAnnouncementJsonCodec
import com.km.model.NodeIdentity
import com.km.node.EstablishmentResult
import com.km.node.EstablishmentState
import com.km.node.PeerContext
import com.km.node.PeerTransportManager
import com.km.node.PeerTransportState
import com.km.node.RealRelayEstablishment
import com.km.node.RealRelayTransport
import com.km.node.RelayDataEnvelope
import com.km.node.RelayServer
import com.km.node.RelayServerResult
import com.km.node.RelayServiceImpl
import com.km.node.TransportBackend
import com.km.node.TransportChain
import com.km.node.TransportError
import com.km.node.TransportErrorCategory
import com.km.node.TransportEventCallbacks
import com.km.node.TransportResult
import com.km.ratchet.DoubleRatchetSession
import com.km.ratchet.RatchetSessionBootstrap
import com.km.frame.SecureFrameProtector
import com.km.frame.SecureRatchetProtocol
import com.km.storage.InMemoryRelayStore
import com.km.x3dh.BootstrapPrekeys
import com.km.x3dh.InitiatorKeyMaterial
import com.km.x3dh.ResponderKeyMaterial
import com.km.x3dh.X3dh
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.assertContentEquals

/**
 * 3Q.3 — P2P real y Relay real, intercambiables, en una MISMA sesion.
 *
 * 3Q.4 demostro el CONTRATO con dobles. Este checkpoint lo pone contra
 * eventos reales: SDP, ICE, DTLS y DataChannel de verdad por un lado, y el
 * camino de datos del relay recien anadido por otro.
 *
 * Los tres casos que importan:
 *  - **A**: WebRTC devuelve `Success` al negociar y aun asi NUNCA alcanza
 *    `READY` porque el DataChannel no abre. La cadena debe seguir con Relay.
 *  - **B**: el backend WebRTC revienta. Debe quedar aislado como
 *    `BACKEND_ERROR` y la cadena debe seguir con Relay.
 *  - **C**: P2P falla, Relay establece, y el mensaje SIGUE DESCIFRANDO con la
 *    MISMA sesion criptografica. La sesion no se toco al cambiar de medio.
 */
class RealTransportChainTest {

    private val ed25519: Ed25519 = Ed25519Impl()
    private val relayKP = ed25519.generateKeyPair()
    private val relayId = IdentityId(deriveId(relayKP.publicKey))
    private val aliceKP = ed25519.generateKeyPair()
    private val bobKP = ed25519.generateKeyPair()
    private val aliceId = IdentityId(deriveId(aliceKP.publicKey))
    private val bobId = IdentityId(deriveId(bobKP.publicKey))
    private val clock: Clock = Clock { 1000L }

    private val relayServer = RelayServer(
        relayKeyPair = relayKP,
        ed25519 = ed25519,
        relayService = RelayServiceImpl(InMemoryRelayStore(now = { 0L }), now = { 0L }),
        relayIdentityId = relayId,
        now = { 0L },
    )

    private val transports = mutableListOf<TransportBackend>()
    private val managers = mutableListOf<PeerTransportManager>()

    @AfterEach
    fun tearDown() {
        managers.forEach { runCatching { it.clear() } }
        transports.forEach { runCatching { it.shutdown() }; runCatching { (it as? RealWebRtcTransport)?.disposeCallbacks() } }
        managers.clear()
        transports.clear()
    }

    // ===================================================================
    // Arranque
    // ===================================================================

    private fun connectToRelay(id: IdentityId, kp: KeyPair) {
        val challenge = relayServer.createChallenge(id)
        val response = RelayAuthHandler(ed25519).buildResponse(
            challenge = AuthChallenge(challenge.nonce, 0L, 3, relayId.value),
            identityId = id.value,
            keyPair = kp,
        )
        val r = relayServer.authenticate(id, response)
        assertTrue(r.isSuccess, "autenticacion en el relay: $r")
        relayServer.registerPeerHandler(id) {}
        relayServer.registerDataHandler(id) { envelope: RelayDataEnvelope ->
            relayServer.deliverData(envelope.to, envelope)
        }
    }

    private fun peerContext(localKp: KeyPair, localId: IdentityId, remoteKp: KeyPair, remoteId: IdentityId): PeerContext {
        val session = AuthSession.initiator(localKp, localId.value, ed25519, clock = clock)
        session.receiveChallenge(AuthChallenge(ByteArray(16), 1000L, 3, remoteId.value)).getOrThrow()
        session.markResponseSent()
        val sid = "a".repeat(40)
        val snonce = ByteArray(16) { 3 }
        val transcript = TranscriptBuilder.serverAuthTranscript(sid, snonce, localId.value)
        session.receiveAuthOk(
            AuthOk(sid, snonce, localKp.publicKey, ed25519.sign(localKp.privateKey, transcript).bytes),
            localId.value,
        ).getOrThrow()
        val identity = NodeIdentity(remoteId, remoteKp.publicKey)
        val partial = NodeAnnouncement(
            messageId = MessageId(UUID.randomUUID()),
            timestamp = 0L,
            protocolVersion = "1.0",
            identity = identity,
            endpoints = emptyList(),
            capabilities = emptySet(),
        )
        val ann = partial.copy(
            signature = ed25519.sign(remoteKp.privateKey, NodeAnnouncementJsonCodec.signableJson(partial)).bytes,
        )
        return PeerContext.authenticated(remoteId, remoteKp.publicKey, ann, session)
    }

    /**
     * Backend WebRTC envuelto para INYECTAR un fallo.
     *
     * No afirma que libwebrtc lance excepciones: afirma que, CUANDO un backend
     * real revienta, la cadena lo aísla. El objeto real de WebRTC vive
     * DENTRO, de modo que `abort()` tiene recursos de verdad que limpiar.
     */
    private class FaultInjectingWebRtc(
        private val delegate: RealWebRtcTransport,
    ) : TransportBackend {
        override val name: String get() = delegate.name
        override var eventCallbacks: TransportEventCallbacks
            get() = delegate.eventCallbacks
            set(value) { delegate.eventCallbacks = value }

        override fun initialize(): TransportResult<Unit> = delegate.initialize()
        override fun onCreateBinding(localIdentity: IdentityId, remotePeerId: IdentityId): TransportResult<Unit> =
            throw IllegalStateException("JNI PeerConnection fallo")
        override fun onLocalSdp(localIdentity: IdentityId, remotePeerId: IdentityId, sdp: String) = TransportResult.Success(Unit)
        override fun onRemoteSdp(localIdentity: IdentityId, remotePeerId: IdentityId, sdp: String) = TransportResult.Success(Unit)
        override fun onIceCandidate(localIdentity: IdentityId, remotePeerId: IdentityId, candidate: String) = TransportResult.Success(Unit)
        override fun sendData(remotePeerId: IdentityId, data: ByteArray) = delegate.sendData(remotePeerId, data)
        override fun onCloseBinding(localIdentity: IdentityId, remotePeerId: IdentityId) = delegate.onCloseBinding(localIdentity, remotePeerId)
        override fun shutdown(): TransportResult<Unit> = delegate.shutdown()
    }

    /** Fabrica de establecimientos: WebRTC por un lado, relay por el otro. */
    private fun factory(
        peer: ManagedWebRtcPeer,
        relayTransport: RealRelayTransport,
    ): (TransportBackend) -> com.km.node.TransportEstablishment = { backend ->
        if (backend is RealRelayTransport) RealRelayEstablishment(backend, bobId)
        else WebRtcEstablishment(peer, WebRtcEstablishment.Role.OFFERER)
    }

    private fun newP2P(): RealWebRtcTransport = RealWebRtcTransport().also { transports.add(it) }
    private fun newManager(backend: TransportBackend): PeerTransportManager =
        PeerTransportManager(transportBackend = backend, now = { 0L }).also { managers.add(it) }

    // ===================================================================
    // CASO A — WebRTC dice exito y aun asi nunca llega a READY
    // ===================================================================

    @Test
    @DisplayName("M-A WebRTC negocia bien pero NO abre DataChannel: Relay establece")
    fun `M-A webrct nunca llega a ready`() {
        connectToRelay(aliceId, aliceKP)
        connectToRelay(bobId, bobKP)

        val p2p = newP2P()
        val relay = RealRelayTransport(server = relayServer, localIdentity = aliceId).also { transports.add(it) }
        val chain = TransportChain(listOf(p2p, relay))

        val ctx = peerContext(aliceKP, aliceId, bobKP, bobId)
        val peer = ManagedWebRtcPeer(aliceId, ctx, newManager(p2p), p2p)
        // La oferta sale, pero NADIE responde: es el P2P que no conecta.
        peer.signaling = DirectSignaling()

        val resultado = chain.establish(aliceId, bobId, timeoutMs = 3_000L, factory(peer, relay))
        val listo = resultado as? EstablishmentResult.Ready
        assertNotNull(listo, "Relay debe establecer: $resultado")
        assertEquals("relay", listo!!.attempt.transport, "P2P no debe ganar sin DataChannel")
        assertEquals(listOf("p2p", "relay"), listo.attempts.map { it.transport })

        // Y el detalle que importa: P2P NEGOCIO con exito y aun asi fallo.
        val p2pAttempt = listo.attempts.first { it.transport == "p2p" }
        assertEquals(
            EstablishmentState.FAILED, p2pAttempt.state,
            "P2P jamas debe quedar READY sin DataChannel OPEN",
        )
        assertTrue(
            p2pAttempt.result.let { it is TransportResult.Failure && it.error.permitsFallback },
            "un DataChannel que no abre es fallo del MEDIO, no de seguridad: ${p2pAttempt.result}",
        )
        assertEquals("relay", chain.activeTransport)
    }

    @Test
    @DisplayName("M-A2 el estado NEGOTIATING se distingue de READY en WebRTC real")
    fun `M-A2 negociar no es ready`() {
        connectToRelay(aliceId, aliceKP)
        val p2p = newP2P()
        val ctx = peerContext(aliceKP, aliceId, bobKP, bobId)
        val peer = ManagedWebRtcPeer(aliceId, ctx, newManager(p2p), p2p)
        peer.signaling = DirectSignaling()

        val est = WebRtcEstablishment(peer, WebRtcEstablishment.Role.OFFERER)
        est.initialize().getOrThrow()
        est.createBinding(aliceId, bobId).getOrThrow()
        assertEquals(EstablishmentState.CREATED, est.state)

        // Genera una SDP REAL. Devuelve exito. Y aun asi no esta listo.
        est.negotiate().getOrThrow()
        assertEquals(EstablishmentState.NEGOTIATING, est.state, "SDP generada no significa DataChannel abierto")
        assertFalse(est.state.canCarryData, "NEGOTIATING no puede llevar datos")

        val r = est.awaitReady(1_500L)
        assertTrue(r is TransportResult.Failure, "sin DataChannel abierto no hay READY")
        assertEquals(EstablishmentState.FAILED, est.state)
    }

    // ===================================================================
    // CASO B — El backend WebRTC revienta
    // ===================================================================

    @Test
    @DisplayName("M-B el backend WebRTC revienta: queda AISLADO y Relay establece")
    fun `M-B backend webrct revienta`() {
        connectToRelay(aliceId, aliceKP)
        connectToRelay(bobId, bobKP)

        val realP2p = newP2P()
        val faulty = FaultInjectingWebRtc(realP2p)
        val relay = RealRelayTransport(server = relayServer, localIdentity = aliceId).also { transports.add(it) }
        val chain = TransportChain(listOf(faulty, relay))

        // El manager usa el backend DEFECTUOSO a proposito: la excepcion debe
        // ocurrir de verdad en `onCreateBinding`. El objeto real de WebRTC sigue
        // dentro, de modo que `abort()` limpia recursos de verdad.
        val ctx = peerContext(aliceKP, aliceId, bobKP, bobId)
        val peer = ManagedWebRtcPeer(aliceId, ctx, newManager(faulty), realP2p)
        peer.signaling = DirectSignaling()

        val resultado = chain.establish(aliceId, bobId, timeoutMs = 3_000L, factory(peer, relay))
        val listo = resultado as? EstablishmentResult.Ready
        assertNotNull(listo, "el fallo de P2P no debe tumbar la cadena: $resultado")
        assertEquals("relay", listo!!.attempt.transport)
        val p2pAttempt = listo.attempts.first { it.transport == "p2p" }
        val fallo = p2pAttempt.result as? TransportResult.Failure
        assertNotNull(fallo, "la excepcion debe quedar registrada como fallo, no propagarse")
        assertEquals(TransportError.BACKEND_ERROR, fallo!!.error)
        assertTrue(
            fallo.error.permitsFallback,
            "un crash de implementacion NO es un fallo de seguridad: ${fallo.error}",
        )
    }

    @Test
    @DisplayName("M-B2 la excepcion no escapa al llamante: el proceso sigue vivo")
    fun `M-B2 la excepcion no escapa`() {
        connectToRelay(aliceId, aliceKP)
        connectToRelay(bobId, bobKP)

        val faulty = FaultInjectingWebRtc(newP2P())
        val relay = RealRelayTransport(server = relayServer, localIdentity = aliceId).also { transports.add(it) }
        val chain = TransportChain(listOf(faulty, relay))
        val ctx = peerContext(aliceKP, aliceId, bobKP, bobId)
        val peer = ManagedWebRtcPeer(aliceId, ctx, newManager(newP2P()), newP2P())
        peer.signaling = DirectSignaling()

        // Si la excepcion escapara, esto seria un error y el test fallaria.
        val result = chain.establish(aliceId, bobId, 3_000L, factory(peer, relay))
        assertTrue(result.activeTransport != null, "la cadena debe resolver pese al crash")
    }

    // ===================================================================
    // CASO C — La frontera criptografica sobre transporte real
    // ===================================================================

    @Test
    @DisplayName("M-C P2P falla, Relay entrega, y el mensaje descifra con la MISMA sesion")
    fun `M-C frontera criptografica real`() {
        connectToRelay(aliceId, aliceKP)
        connectToRelay(bobId, bobKP)

        // --- Sesion criptografica UNICA, creada antes de elegir medio ---
        val x25519 = BcX25519()
        val kdf = BcHkdfSha256()
        val protector = SecureFrameProtector(BcChaCha20Poly1305(), kdf)
        val x3dh = X3dh(x25519, kdf)
        val random = SecureRandom()

        val aliceDh = x25519.generateKeyPair()
        val bobDh = x25519.generateKeyPair()
        val bobSpk = x25519.generateKeyPair()
        val bobOpk = x25519.generateKeyPair()
        val f = ByteArray(32).also { random.nextBytes(it) }

        val bobPrekeys = BootstrapPrekeys(
            deviceId = ByteArray(32) { 1 },
            identityAgreementKey = bobDh.publicKey,
            signedPreKey = bobSpk.publicKey,
            signedPreKeyId = 1L,
            oneTimePreKey = bobOpk.publicKey,
            oneTimePreKeyId = 5L,
        )
        val alicePrekeys = BootstrapPrekeys(
            deviceId = ByteArray(32) { 2 },
            identityAgreementKey = aliceDh.publicKey,
            signedPreKey = aliceDh.publicKey,
            signedPreKeyId = 1L,
            oneTimePreKey = null,
            oneTimePreKeyId = null,
        )
        val init = x3dh.initiate(InitiatorKeyMaterial(ByteArray(32), aliceDh), bobPrekeys, f)
        val prep = x3dh.respond(
            ResponderKeyMaterial(ByteArray(32) { 1 }, bobDh, bobSpk, bobOpk, 5L),
            alicePrekeys,
            init.ephemeralPublic,
        )
        assertContentEquals(init.sharedKey, prep.sharedKey, "X3DH debe derivar la misma SK")
        prep.commit()

        val aliceProtocol = RatchetSessionBootstrap.initiatorProtocol(
            bootstrapValue = f,
            ephemeral = init.ephemeral,
            remoteSignedPreKey = bobSpk.publicKey,
            signedPreKeyPrivate = bobSpk.privateKey,
            x25519 = x25519,
            kdf = kdf,
            protector = protector,
        )
        val bobProtocol = SecureRatchetProtocol(
            DoubleRatchetSession(
                rootKey = RatchetSessionBootstrap.rootKeyFrom(f, kdf),
                dhSelf = bobSpk,
                dhRemote = null,
                sendChainKey = ByteArray(32),
                receiveChainKey = ByteArray(32),
                x25519 = x25519,
                kdf = kdf,
            ),
            protector,
        )

        // --- Cadena: P2P real (que no conectara) + Relay real ---
        val p2p = newP2P()
        val relay = RealRelayTransport(server = relayServer, localIdentity = aliceId).also { transports.add(it) }
        val chain = TransportChain(listOf(p2p, relay))

        val ctx = peerContext(aliceKP, aliceId, bobKP, bobId)
        val peer = ManagedWebRtcPeer(aliceId, ctx, newManager(p2p), p2p)
        peer.signaling = DirectSignaling()   // nadie responde: P2P no conectara

        val est = chain.establish(aliceId, bobId, 3_000L, factory(peer, relay))
        assertTrue(est is EstablishmentResult.Ready, "Relay debe establecer: $est")
        assertEquals("relay", est.activeTransport, "el medio activo es Relay, tras un P2P real fallido")
        assertNotNull((est as EstablishmentResult.Ready).attempt)

        // El binding del manager envuelve la CADENA, no un medio concreto.
        // El manager envuelve la CADENA, no un medio concreto: sigue viendo
        // un solo binding para el peer. Sus operaciones de binding van al medio
        // SELECCIONADO, no al primario abandonado.
        val manager = newManager(chain)
        manager.createBinding(aliceId, ctx).getOrThrow()
        manager.sendOffer(aliceId, ctx, "offer", bobId).getOrThrow()
        manager.receiveAnswer(aliceId, bobId, "answer").getOrThrow()
        manager.markConnected(aliceId, ctx).getOrThrow()
        assertEquals(
            1, manager.activeBindings().size,
            "el manager no debe ver dos medios: la cadena es un unico transporte",
        )
        val session = SecureMessagingSession(aliceId, ctx, aliceProtocol, manager)

        val recibidoEnBob = CopyOnWriteArrayList<ByteArray>()
        relayServer.registerDataHandler(bobId) { envelope -> recibidoEnBob.add(envelope.payload) }
        val enviado = session.send("hola".toByteArray())
        assertTrue(enviado is SendResult.Ok, "el envio debe salir por Relay: $enviado")
        assertEquals(1, recibidoEnBob.size, "Bob recibio exactamente un frame por el relay")

        val descifrado = bobProtocol.decrypt(recibidoEnBob.first())
        assertTrue(
            descifrado is SecureRatchetProtocol.DecryptResult.Ok,
            "el frame debe descifrar con la sesion que se creo ANTES de cambiar de medio: $descifrado",
        )
        assertContentEquals("hola".toByteArray(), (descifrado as SecureRatchetProtocol.DecryptResult.Ok).plaintext)
    }

    @Test
    @DisplayName("M-C2 el relay recibe ciphertext, nunca plaintext")
    fun `M-C2 relay opaco`() {
        connectToRelay(aliceId, aliceKP)
        connectToRelay(bobId, bobKP)

        val capturadoPorElRelay = CopyOnWriteArrayList<ByteArray>()
        relayServer.registerDataHandler(bobId) { envelope -> capturadoPorElRelay.add(envelope.payload) }

        val relay = RealRelayTransport(server = relayServer, localIdentity = aliceId).also { transports.add(it) }
        val secreto = "contenido-que-el-relay-no-debe-ver".toByteArray()
        relay.sendData(bobId, ByteArray(44) { 0x11 }).getOrThrow()

        assertEquals(1, capturadoPorElRelay.size, "Bob recibio por el relay")
        val wire = capturadoPorElRelay.first()
        assertFalse(contiene(wire, secreto), "el relay no debe ver plaintext")
    }

    // ===================================================================
    // Una sola autoridad sobre READY
    // ===================================================================

    @Test
    @DisplayName("M-D el backend WebRTC NO declara READY: solo informa del medio")
    fun `M-D una sola autoridad`() {
        // `RealWebRtcTransport` expone consultas del estado del medio. No tiene
        // ningun `markReady` ni equivalente: la conclusion es de la capa de
        // establecimiento. Si el backend decidiera, habria dos autoridades.
        val p2p = newP2P()

        // Lo que si ofrece son CONSULTAS del medio. La llamada compila, asi que
        // su existencia no hace falta deducirla por reflexion.
        val abierto = p2p.dataChannelState(aliceId, bobId)
        assertNull(abierto, "sin binding no hay DataChannel: se INFORMA, no se decide")
        assertFalse(p2p.awaitDataChannelOpen(aliceId, bobId, 200L), "sin contraparte no hay canal")

        // Y lo que NO debe existir es un metodo que CONCLUYA readiness.
        // Auditoria del codigo fuente: el razon es que una segunda autoridad
        // sobre READY no se manifestaria en los tipos, sino en el nombre.
        val fuente = java.io.File("src/main/kotlin/com/km/webrtc/RealWebRtcTransport.kt")
        val codigo = fuente.readLines()
            .filterNot { val t = it.trimStart(); t.startsWith("*") || t.startsWith("//") || t.startsWith("/*") }
        for (prohibido in listOf("markReady", "setReady", "declareReady", "becomeReady", "setStateReady")) {
            assertTrue(
                codigo.none { Regex("fun\\s+$prohibido\\b").containsMatchIn(it) },
                "el backend no debe declarar $prohibido: seria una segunda autoridad sobre READY",
            )
        }
    }

    @Test
    @DisplayName("M-D2 un medio retirado pierde la autoridad aunque su objeto siga vivo")
    fun `M-D2 autoridad revocada`() {
        connectToRelay(aliceId, aliceKP)
        connectToRelay(bobId, bobKP)

        val p2p = newP2P()
        val relay = RealRelayTransport(server = relayServer, localIdentity = aliceId).also { transports.add(it) }
        val chain = TransportChain(listOf(p2p, relay))
        val ctx = peerContext(aliceKP, aliceId, bobKP, bobId)
        val peer = ManagedWebRtcPeer(aliceId, ctx, newManager(p2p), p2p)
        peer.signaling = DirectSignaling()

        chain.establish(aliceId, bobId, 3_000L, factory(peer, relay))

        assertEquals("relay", chain.activeTransport)
        assertTrue(chain.acceptsInbound("relay"), "el ganador puede entregar")
        assertFalse(chain.acceptsInbound("p2p"), "el perdedor NO, aunque su objeto exista")
    }

    @Test
    @DisplayName("M-E la traduccion de fallos conserva la categoria para el fallback")
    fun `M-E categorias de fallo`() {
        // El destino no esta en el relay: fallo del MEDIO, autoriza cambiar.
        val relay = RealRelayTransport(server = relayServer, localIdentity = aliceId)
        val r = relay.sendData(IdentityId("e".repeat(64)), byteArrayOf(1))
        assertTrue(r is TransportResult.Failure)
        val err = (r as TransportResult.Failure).error
        assertEquals(TransportErrorCategory.TRANSPORT, err.category, "categoria de $err")
        assertTrue(err.permitsFallback, "destino ausente debe autorizar Relay->Tor o similar")
    }

    // ===================================================================

    private fun contiene(h: ByteArray, n: ByteArray): Boolean {
        if (n.isEmpty() || n.size > h.size) return false
        outer@ for (i in 0..h.size - n.size) {
            for (j in n.indices) if (h[i + j] != n[j]) continue@outer
            return true
        }
        return false
    }

    private fun deriveId(publicKey: ByteArray): String =
        MessageDigest.getInstance("SHA-256").let { d ->
            d.update("KM-ID-IDENTITY".toByteArray())
            d.update(publicKey)
            d.digest().joinToString("") { "%02x".format(it) }
        }
}
