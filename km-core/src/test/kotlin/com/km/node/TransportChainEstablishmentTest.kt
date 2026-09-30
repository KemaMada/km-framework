package com.km.node

import com.km.auth.AuthChallenge
import com.km.auth.AuthOk
import com.km.auth.AuthSession
import com.km.auth.Clock
import com.km.auth.TranscriptBuilder
import com.km.crypto.Ed25519
import com.km.crypto.Ed25519Impl
import com.km.crypto.KeyPair
import com.km.model.IdentityId
import com.km.model.MessageId
import com.km.model.NodeAnnouncement
import com.km.model.NodeAnnouncementJsonCodec
import com.km.model.NodeIdentity
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.security.MessageDigest
import java.util.UUID

/**
 * 3Q.4 — Establecimiento por transporte, con fallback y estado terminal.
 *
 * La asimetria que este checkpoint cierra:
 *
 * ```
 * 3Q.2  envio:      los MISMOS bytes salen por el siguiente medio
 * 3Q.4  establecer: cada medio necesita su PROPIA negociacion
 * ```
 *
 * Y la condicion terminal: `initialize() == Success` no es estar listo. Un
 * medio que acepto un SDP pero cuyo canal no abre NUNCA es `READY`.
 */
class TransportChainEstablishmentTest {

    private val ed25519: Ed25519 = Ed25519Impl()
    private val aliceKP = ed25519.generateKeyPair()
    private val bobKP = ed25519.generateKeyPair()
    private val aliceId = IdentityId(deriveId(aliceKP.publicKey))
    private val bobId = IdentityId(deriveId(bobKP.publicKey))
    private val clock: Clock = Clock { 1000L }

    /** Backends y establecimientos alineados por nombre. */
    private class Fixture(val names: List<String>, private val localId: IdentityId, private val remoteId: IdentityId) {
        val backends = names.associateWith { ScriptableTransportBackend(it) }
        val chain = TransportChain(backends.values.toList())
        val establishments = mutableMapOf<String, ScriptableEstablishment>()

        init {
            names.forEach { establishments[it] = ScriptableEstablishment(it, backends.getValue(it)) }
        }

        fun est(name: String): ScriptableEstablishment = establishments.getValue(name)
        fun factory() = ScriptableEstablishment.factory(establishments)

        fun establish(timeoutMs: Long = 1_000L): EstablishmentResult =
            chain.establish(localId, remoteId, timeoutMs, factory())
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

    // ===================================================================
    // EST-01 — El primer medio que llega a READY gana
    // ===================================================================

    @Test
    @DisplayName("EST-01 P2P llega a READY: Relay y Tor ni se tocan")
    fun `EST-01 exito en el primero`() {
        val f = Fixture(listOf("P2P", "Relay", "Tor"), aliceId, bobId)
        val result = f.establish() as EstablishmentResult.Ready

        assertEquals("P2P", result.attempt.transport)
        assertEquals(EstablishmentState.READY, result.attempt.state)
        assertEquals(1, result.attempts.size, "no debe intentarse lo que no hace falta")
        assertEquals("P2P", f.chain.activeTransport)
        assertEquals(0, f.est("Relay").phases.size, "Relay intacto")
        assertEquals(0, f.est("Tor").phases.size, "Tor intacto")
    }

    @Test
    @DisplayName("EST-02 un fallo del MEDIO da a Relay un establecimiento COMPLETAMENTE NUEVO")
    fun `EST-02Relay recibe negociacion propia`() {
        val f = Fixture(listOf("P2P", "Relay"), aliceId, bobId)
        f.est("P2P").awaitReadyOutcome =
            TransportResult.Failure(TransportError.BACKEND_SEND_FAILED, "canal no abrio")

        val result = f.establish() as EstablishmentResult.Ready

        assertEquals("Relay", result.attempt.transport)
        assertEquals(2, result.attempts.size)

        // Relay debe recorrer TODAS las fases por su cuenta. Nada se reusa.
        val relay = f.est("Relay")
        assertEquals(listOf("initialize", "createBinding", "negotiate", "awaitReady"), relay.phases)
        assertEquals(EstablishmentState.READY, relay.state)

        // Y P2P quedo cerrado: no conserva autoridad ni recursos.
        val p2p = f.est("P2P")
        assertEquals(EstablishmentState.CLOSED, p2p.state, "el perdedor debe quedar cerrado")
        assertTrue(p2p.abortCount > 0, "el perdedor debe liberarse")
    }

    @Test
    @DisplayName("EST-03 un fallo de SEGURIDAD detiene: Relay no se intenta")
    fun `EST-03 seguridad detiene`() {
        for (error in listOf(
            TransportError.PEER_CONTEXT_NOT_AUTHENTICATED,
            TransportError.NEGOTIATION_HASH_MISMATCH,
            TransportError.PEER_ID_MISMATCH,
        )) {
            val f = Fixture(listOf("P2P", "Relay", "Tor"), aliceId, bobId)
            f.est("P2P").createBindingOutcome = TransportResult.Failure(error, "identidad")

            val result = f.establish() as EstablishmentResult.Stopped

            assertEquals(error, result.attempt.result.let { (it as TransportResult.Failure).error })
            assertEquals(0, f.est("Relay").phases.size, "un fallo de seguridad no se esquiva cambiando de medio")
            assertEquals(0, f.est("Tor").phases.size)
            assertNull(f.chain.activeTransport, "no debe quedar ningun medio activo")
        }
    }

    @Test
    @DisplayName("EST-04 un fallo de PROTOCOLO detiene la cadena")
    fun `EST-04 protocolo detiene`() {
        for (error in listOf(TransportError.INVALID_SDP, TransportError.INVALID_ICE_CANDIDATE)) {
            val f = Fixture(listOf("P2P", "Relay"), aliceId, bobId)
            f.est("P2P").negotiateOutcome = TransportResult.Failure(error, "sdp invalida")

            val result = f.establish() as EstablishmentResult.Stopped

            assertEquals(0, f.est("Relay").phases.size, "los mismos bytes de SDP fallarian igual")
        }
    }

    @Test
    @DisplayName("EST-05 P2P y Relay caen: Tor establece y queda como unico ACTIVO")
    fun `EST-05 cadena completa`() {
        val f = Fixture(listOf("P2P", "Relay", "Tor"), aliceId, bobId)
        f.est("P2P").initializeOutcome = TransportResult.Failure(TransportError.BACKEND_NOT_READY, "sin permisos")
        f.est("Relay").negotiateOutcome = TransportResult.Failure(TransportError.BINDING_CLOSED, "relay cayo")

        val result = f.establish() as EstablishmentResult.Ready

        assertEquals("Tor", result.attempt.transport)
        assertEquals(listOf("P2P", "Relay", "Tor"), result.attempts.map { it.transport })

        // Exactamente un medio con autoridad.
        assertEquals("Tor", f.chain.activeTransport)
        assertTrue(f.chain.isActive("Tor"))
        assertFalse(f.chain.isActive("P2P"))
        assertFalse(f.chain.isActive("Relay"))
    }

    @Test
    @DisplayName("EST-06 todos fallan: NO queda ningun medio vivo")
    fun `EST-06 sin fugas`() {
        val f = Fixture(listOf("P2P", "Relay", "Tor"), aliceId, bobId)
        f.est("P2P").initializeOutcome = TransportResult.Failure(TransportError.BACKEND_NOT_READY, "x")
        f.est("Relay").createBindingOutcome = TransportResult.Failure(TransportError.BINDING_NOT_FOUND, "y")
        f.est("Tor").awaitReadyOutcome = TransportResult.Failure(TransportError.BACKEND_SEND_FAILED, "z")

        val result = f.establish() as EstablishmentResult.Exhausted

        assertEquals(3, result.attempts.size)
        assertEquals(TransportError.BACKEND_SEND_FAILED, result.lastFailure.error, "el ultimo fallo manda")
        assertNull(f.chain.activeTransport)

        // El invariante anti-fuga: NINGUN establecimiento sobrevive.
        for (name in f.names) {
            val est = f.est(name)
            assertEquals(EstablishmentState.CLOSED, est.state, "$name quedo vivo tras un fallo global")
            assertTrue(est.abortCount > 0, "$name no libero sus recursos")
        }
    }

    // ===================================================================
    // La condicion terminal: exito intermedio NO es READY
    // ===================================================================

    @Test
    @DisplayName("EST-07 negociar bien NO es READY: si el canal no abre, no hay ganador")
    fun `EST-07 exito intermedio no es ready`() {
        // El fallo que la maquina de estados previene: `initialize`,
        // `createBinding` y `negotiate` devuelven Success, y aun asi el medio
        // no puede llevar datos porque su canal nunca abrio.
        val f = Fixture(listOf("P2P", "Relay"), aliceId, bobId)
        f.est("P2P").awaitReadyOutcome =
            TransportResult.Failure(TransportError.BACKEND_SEND_FAILED, "DataChannel no OPEN")
        f.est("Relay").awaitReadyOutcome =
            TransportResult.Failure(TransportError.BACKEND_SEND_FAILED, "DataChannel no OPEN")

        val result = f.establish() as EstablishmentResult.Exhausted

        assertNull(f.chain.activeTransport, "ningun medio llego a READY")
        // Y se-negotiation llego hasta awaitReady, o sea que no se detuvo antes.
        assertEquals(listOf("initialize", "createBinding", "negotiate", "awaitReady"), f.est("P2P").phases)
    }

    @Test
    @DisplayName("EST-18 un backend que MIENTE no gana: Success sin READY no es READY")
    fun `EST-18 backend mentiroso`() {
        // EST-07 cubre el caso honesto (el canal no abre y el backend lo
        // admite). Este cubre el caso peligroso: un backend que reporta
        // Success en CADA paso mientras su canal sigue cerrado. Si la cadena
        // seConformara con el `Success`, declararia ganador a un medio que no
        // puede llevar ni un byte.
        val f = Fixture(listOf("P2P", "Relay"), aliceId, bobId)
        f.est("P2P").claimsSuccessWithoutReady = true

        val result = f.establish() as EstablishmentResult.Ready

        assertEquals("Relay", result.attempt.transport, "un medio que nunca llega a READY no puede ganar")

        // El estado HISTORICO vive en el intento: P2P dijo Success pero la
        // cadena lo registro como NEGOTIATING, nunca READY.
        val p2p = result.attempts.first { it.transport == "P2P" }
        assertEquals(EstablishmentState.NEGOTIATING, p2p.state, "nunca estuvo READY pese a decir Success")
        assertTrue(p2p.result.isSuccess, "el backend mintio: devolvio Success")
        assertFalse(p2p.reachedReady)

        // Y el objeto vivo quedo cerrado, para no conservar recursos.
        assertEquals(EstablishmentState.CLOSED, f.est("P2P").state)
        assertFalse(f.chain.isActive("P2P"))
    }

    @Test
    @DisplayName("EST-19 una cadena de mentirosos no produce ningun medio activo")
    fun `EST-19 todos mienten`() {
        val f = Fixture(listOf("P2P", "Relay", "Tor"), aliceId, bobId)
        f.names.forEach { f.est(it).claimsSuccessWithoutReady = true }

        val result = f.establish() as EstablishmentResult.Exhausted

        assertNull(f.chain.activeTransport, "ningun medio es realmente utilizable")
        assertFalse(f.chain.acceptsInbound("P2P"))
        assertFalse(f.chain.acceptsInbound("Relay"))
        assertFalse(f.chain.acceptsInbound("Tor"))
        f.names.forEach { assertEquals(EstablishmentState.CLOSED, f.est(it).state, "$it quedo vivo") }
    }

    @Test
    @DisplayName("EST-08 la cadena se detiene en la primera fase que falla")
    fun `EST-08 no sigue intentando fases posteriores`() {
        val f = Fixture(listOf("P2P", "Relay"), aliceId, bobId)
        f.est("P2P").initializeOutcome = TransportResult.Failure(TransportError.BACKEND_NOT_READY, "sin permisos")

        f.establish()

        assertEquals(listOf("initialize"), f.est("P2P").phases, "negociar sin inicializar no tiene sentido")
    }

    @Test
    @DisplayName("EST-09 el estado CREATED y NEGOTIATING no autorizan a llevar datos")
    fun `EST-09 solo READY lleva datos`() {
        assertFalse(EstablishmentState.CREATED.canCarryData)
        assertFalse(EstablishmentState.NEGOTIATING.canCarryData)
        assertFalse(EstablishmentState.FAILED.canCarryData)
        assertFalse(EstablishmentState.CLOSED.canCarryData)
        assertTrue(EstablishmentState.READY.canCarryData)

        assertFalse(EstablishmentState.CREATED.isTerminal)
        assertFalse(EstablishmentState.NEGOTIATING.isTerminal)
        assertTrue(EstablishmentState.READY.isTerminal)
        assertTrue(EstablishmentState.FAILED.isTerminal)
        assertTrue(EstablishmentState.CLOSED.isTerminal)
    }

    // ===================================================================
    // Autoridad: un medio retirado NO puede entregar datos
    // ===================================================================

    @Test
    @DisplayName("EST-10 un medio perdedor NO puede inyectar datos en la sesion")
    fun `EST-10 autoridad de entrega`() {
        // Esta es la respuesta a "quien tiene autoridad sobre el medio muerto".
        // Un P2P retirado que siguiera entregando seria un segundo camino
        // oculto hacia la sesion, indistinguible de un atacante.
        val f = Fixture(listOf("P2P", "Relay"), aliceId, bobId)
        f.est("P2P").awaitReadyOutcome = TransportResult.Failure(TransportError.BACKEND_SEND_FAILED, "caido")
        f.establish() as EstablishmentResult.Ready

        assertTrue(f.chain.acceptsInbound("Relay"), "el ganador puede entregar")
        assertFalse(f.chain.acceptsInbound("P2P"), "el perdedor NO puede entregar")
        assertEquals(EstablishmentState.CLOSED, f.est("P2P").state)
    }

    @Test
    @DisplayName("EST-11 sin establecimiento no hay quien entregue datos")
    fun `EST-11 sin autorizacion no hay entrega`() {
        val f = Fixture(listOf("P2P", "Relay"), aliceId, bobId)
        assertFalse(f.chain.acceptsInbound("P2P"), "nadie fue establecido")
        assertFalse(f.chain.acceptsInbound("Relay"))
        assertNull(f.chain.activeTransport)
    }

    @Test
    @DisplayName("EST-12 una ronda nueva cierra la seleccion anterior")
    fun `EST-12 la ronda nueva invalida la anterior`() {
        // Si no, un fallo de la ronda nueva dejaria vivo al ganador viejo: el
        // llamante creeria tener un medio activo que en realidad es de una
        // negociacion anterior.
        val f = Fixture(listOf("P2P", "Relay"), aliceId, bobId)
        f.establish() as EstablishmentResult.Ready
        assertEquals("P2P", f.chain.activeTransport)

        // Ahora P2P deja de funcionar y la segunda ronda elige Relay.
        f.est("P2P").awaitReadyOutcome = TransportResult.Failure(TransportError.BACKEND_SEND_FAILED, "caido")
        val segunda = f.establish() as EstablishmentResult.Ready

        assertEquals("Relay", segunda.attempt.transport)
        assertEquals("Relay", f.chain.activeTransport)
        assertFalse(f.chain.acceptsInbound("P2P"))
    }

    @Test
    @DisplayName("EST-13 liberar la seleccion revoca la autoridad de entrega")
    fun `EST-13 releaseSelected revoca`() {
        val f = Fixture(listOf("P2P"), aliceId, bobId)
        f.establish() as EstablishmentResult.Ready
        assertTrue(f.chain.acceptsInbound("P2P"))

        f.chain.releaseSelected()

        assertFalse(f.chain.acceptsInbound("P2P"), "sin medio activo no hay entrega")
        assertNull(f.chain.activeTransport)
        assertEquals(EstablishmentState.CLOSED, f.est("P2P").state)
        // Idempotente: releasing dos veces no debe romper nada.
        f.chain.releaseSelected()
    }

    // ===================================================================
    // El manager ve UN SOLO transporte
    // ===================================================================

    @Test
    @DisplayName("EST-14 el manager ve exactamente UN medio activo para la sesion")
    fun `EST-14 un solo medio desde el manager`() {
        // El manager indexa por (localIdentity, remotePeerId). La cadena no
        // crea un segundo binding: el medio retirado se cierra, de modo que
        // el manager sigue teniendo un unico estado para ese peer.
        val f = Fixture(listOf("P2P", "Relay"), aliceId, bobId)
        f.est("P2P").awaitReadyOutcome = TransportResult.Failure(TransportError.BACKEND_SEND_FAILED, "caido")

        val mgr = PeerTransportManager(transportBackend = f.chain, now = { 0L })
        val ctx = peerContext()
        mgr.createBinding(aliceId, ctx).getOrThrow()
        mgr.sendOffer(aliceId, ctx, "offer", bobId).getOrThrow()
        mgr.receiveAnswer(aliceId, bobId, "answer").getOrThrow()
        mgr.markConnected(aliceId, ctx).getOrThrow()

        val result = f.establish() as EstablishmentResult.Ready
        assertEquals("Relay", result.attempt.transport)

        // Un solo binding, un solo estado, un solo medio con autoridad.
        assertEquals(1, mgr.activeBindings().size, "el manager no debe ver dos medios")
        assertEquals(1, mgr.bindingsForLocalIdentity(aliceId).size)
        assertEquals(PeerTransportState.CONNECTED, mgr.getBinding(aliceId, bobId)!!.state)
        assertEquals("Relay", f.chain.activeTransport)
        assertTrue(f.chain.isActive("Relay"))
        assertFalse(f.chain.isActive("P2P"))
    }

    @Test
    @DisplayName("EST-15 si el establecimiento falla, el envio por la cadena sigue en pie")
    fun `EST-15 establecimiento y envio son orthogonales`() {
        // El envio tiene su propia politica (3Q.2) y su propia terminacion.
        // Que un medio no se pueda establecer no significa que la cadena no
        // pueda enrutar: son dos rutas de fallo independientes.
        val f = Fixture(listOf("P2P", "Relay"), aliceId, bobId)
        f.est("P2P").initializeOutcome = TransportResult.Failure(TransportError.BACKEND_NOT_READY, "x")
        f.est("Relay").initializeOutcome = TransportResult.Failure(TransportError.BACKEND_NOT_READY, "y")
        f.establish() as EstablishmentResult.Exhausted

        // El envio, por su parte, puede funcionar si el medio lo permite.
        (f.backends["P2P"] as ScriptableTransportBackend).failWithTransport()
        assertTrue(f.chain.sendData(bobId, byteArrayOf(1)).isSuccess, "Relay absorbio el envio")

        // Y el envio NO concede autoridad de entrega: eso es del establecimiento.
        assertNull(f.chain.activeTransport, "enviar no es establecer")
        assertFalse(f.chain.acceptsInbound("Relay"), "sin READY no hay entrega")
    }

    // ===================================================================
    // Robustez del camino de error
    // ===================================================================

    @Test
    @DisplayName("EST-16 un abort() que lanza no impide cerrar los demas medios")
    fun `EST-16 abort robusto`() {
        // Si abort() revienta, se pierde la cadena entera y quedan recursos
        // vivos. El camino de error debe ser el mas defensivo del sistema.
        val f = Fixture(listOf("P2P", "Relay", "Tor"), aliceId, bobId)
        f.est("P2P").abortThrows = true
        f.est("P2P").initializeOutcome = TransportResult.Failure(TransportError.BACKEND_NOT_READY, "x")
        f.est("Relay").initializeOutcome = TransportResult.Failure(TransportError.BACKEND_NOT_READY, "y")
        f.est("Tor").awaitReadyOutcome = TransportResult.Failure(TransportError.BACKEND_SEND_FAILED, "z")

        val result = f.establish() as EstablishmentResult.Exhausted

        assertEquals(3, result.attempts.size, "los tres medios se intentaron y se cerraron")
        assertNull(f.chain.activeTransport)
    }

    @Test
    @DisplayName("EST-17 Stopped y Exhausted no son lo mismo")
    fun `EST-17 parado != agotado`() {
        // Un fallo de seguridad NO es "no quedaba ningun medio". Confundirlos
        // abriria un camino de relleno que nunca deberia existir.
        val detenido = Fixture(listOf("P2P", "Relay"), aliceId, bobId)
        detenido.est("P2P").createBindingOutcome =
            TransportResult.Failure(TransportError.PEER_ID_MISMATCH, "suplantacion")
        val r1 = detenido.establish()

        val agotado = Fixture(listOf("P2P", "Relay"), aliceId, bobId)
        agotado.est("P2P").initializeOutcome = TransportResult.Failure(TransportError.BACKEND_NOT_READY, "x")
        agotado.est("Relay").initializeOutcome = TransportResult.Failure(TransportError.BACKEND_NOT_READY, "y")
        val r2 = agotado.establish()

        val detenido1 = r1 as EstablishmentResult.Stopped
        val agotado2 = r2 as EstablishmentResult.Exhausted
        assertNull(detenido1.activeTransport)
        assertNull(agotado2.activeTransport)
        // Y Stopped no agota la cadena: solo se intento lo que se pudo.
        assertEquals(1, detenido1.attempts.size)
        assertEquals(2, agotado2.attempts.size)
    }

    private fun deriveId(publicKey: ByteArray): String =
        MessageDigest.getInstance("SHA-256").let { d ->
            d.update("KM-ID-IDENTITY".toByteArray())
            d.update(publicKey)
            d.digest().joinToString("") { "%02x".format(it) }
        }
}
