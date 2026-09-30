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
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.assertContentEquals

/**
 * 3Q.0 / 3Q.1 — Convencion de nombres y taxonomia de errores de transporte.
 *
 * 3Q.0  `peerId` generico -> `remotePeerId` en toda la frontera de transporte.
 * 3Q.1  Los fallos de transporte se CLASIFICAN, y el manager PROPAGA el
 *       resultado del backend en lugar de reportar exito incondicional.
 *
 * El objetivo no es cosmetico. Antes de este cambio, `TransportChain` (3Q.2)
 * no podia existir: el manager informaba `Success` aunque el medio hubiera
 * rechazado el envio, de modo que ninguna cadena podia distinguir
 * "este medio no sirve" de "todo listo". Y `permitsFallback` no tenia forma de
 * expresar que un fallo de autenticacion NO debe reintentarse por otro medio.
 */
class TransportErrorTaxonomyTest {

    private val ed25519: Ed25519 = Ed25519Impl()
    private val aliceKP = ed25519.generateKeyPair()
    private val bobKP = ed25519.generateKeyPair()
    private val aliceId = IdentityId(deriveId(aliceKP.publicKey))
    private val bobId = IdentityId(deriveId(bobKP.publicKey))
    private val clock: Clock = Clock { 1000L }

    // ===================================================================
    // Backend de transporte programable
    // ===================================================================

    /**
     * Backend cuyo resultado de `sendData` el test decide.
     *
     * Existe precisamente para poder observar lo que hacia el manager cuando
     * el MEDIO falla, algo que `FakeTransportBackend` no permite (siempre
     * devuelve `Success`).
     */
    private class ScriptedBackend(
        var sendOutcome: TransportResult<Unit> = TransportResult.Success(Unit),
    ) : TransportBackend {
        override var eventCallbacks: TransportEventCallbacks = TransportEventCallbacks()
        var initialized = false
        val sentPayloads = CopyOnWriteArrayList<ByteArray>()

        override fun initialize(): TransportResult<Unit> {
            initialized = true
            return TransportResult.Success(Unit)
        }

        override fun onCreateBinding(localIdentity: IdentityId, remotePeerId: IdentityId) = TransportResult.Success(Unit)
        override fun onLocalSdp(localIdentity: IdentityId, remotePeerId: IdentityId, sdp: String) = TransportResult.Success(Unit)
        override fun onRemoteSdp(localIdentity: IdentityId, remotePeerId: IdentityId, sdp: String) = TransportResult.Success(Unit)
        override fun onIceCandidate(localIdentity: IdentityId, remotePeerId: IdentityId, candidate: String) = TransportResult.Success(Unit)
        override fun onCloseBinding(localIdentity: IdentityId, remotePeerId: IdentityId) = TransportResult.Success(Unit)
        override fun shutdown(): TransportResult.Success<Unit> = TransportResult.Success(Unit)

        override fun sendData(remotePeerId: IdentityId, data: ByteArray): TransportResult<Unit> {
            if (sendOutcome is TransportResult.Success) sentPayloads.add(data.copyOf())
            return sendOutcome
        }
    }

    private fun peerContext(): PeerContext {
        val session = AuthSession.initiator(aliceKP, aliceId.value, ed25519, clock = clock)
        session.receiveChallenge(AuthChallenge(ByteArray(16), 1000L, 3, bobId.value)).getOrThrow()
        session.markResponseSent()
        val sid = "a".repeat(40)
        val snonce = ByteArray(16) { 3 }
        val transcript = TranscriptBuilder.serverAuthTranscript(sid, snonce, aliceId.value)
        session.receiveAuthOk(AuthOk(sid, snonce, aliceKP.publicKey, ed25519.sign(aliceKP.privateKey, transcript).bytes), aliceId.value).getOrThrow()
        val identity = NodeIdentity(bobId, bobKP.publicKey)
        val partial = NodeAnnouncement(
            messageId = MessageId(UUID.randomUUID()),
            timestamp = 0L,
            protocolVersion = "1.0",
            identity = identity,
            endpoints = emptyList(),
            capabilities = emptySet(),
        )
        val signable = NodeAnnouncementJsonCodec.signableJson(partial)
        val ann = partial.copy(signature = ed25519.sign(bobKP.privateKey, signable).bytes)
        return PeerContext.authenticated(bobId, bobKP.publicKey, ann, session)
    }

    /**
     * Manager con el binding ya en CONNECTED, listo para enviar.
     *
     * Se recorre la negociacion SDP COMPLETA porque la maquina de estados de
     * 3K no admite NEW -> CONNECTED: el binding debe pasar por OFFER_SENT y
     * ANSWER_RECEIVED. Saltarse ese camino no seria un atajo, seria usar una
     * transicion que el protocolo prohibe.
     */
    private fun connectedManager(backend: TransportBackend): Pair<PeerTransportManager, PeerContext> {
        val mgr = PeerTransportManager(transportBackend = backend, now = { 0L })
        val ctx = peerContext()
        mgr.createBinding(aliceId, ctx).getOrThrow()
        mgr.sendOffer(aliceId, ctx, "offer-sdp", bobId).getOrThrow()
        mgr.receiveAnswer(aliceId, bobId, "answer-sdp").getOrThrow()
        mgr.markConnected(aliceId, ctx).getOrThrow()
        assertEquals(PeerTransportState.CONNECTED, mgr.getBinding(aliceId, bobId)!!.state)
        return mgr to ctx
    }

    // ===================================================================
    // 3Q.1 — La taxonomia: que fallos justifican fallback
    // ===================================================================

    @Test
    @DisplayName("3Q1-01 solo los fallos del MEDIO autorizan fallback")
    fun `3Q1-01 solo TRANSPORT autoriza fallback`() {
        val fallbackWorthy = TransportError.entries.filter { it.permitsFallback }
        assertTrue(
            fallbackWorthy.all { it.category == TransportErrorCategory.TRANSPORT },
            "ningun error fuera de TRANSPORT puede autorizar fallback: $fallbackWorthy",
        )
    }

    @Test
    @DisplayName("3Q1-02 un fallo de autenticacion NO autoriza fallback")
    fun `3Q1-02 seguridad no hace fallback`() {
        // Reintentar por otro medio no cambia el hecho de que no sabemos con
        // quien hablamos, y el fallback enmascararia el ataque.
        for (e in listOf(
            TransportError.PEER_CONTEXT_NOT_AUTHENTICATED,
            TransportError.SESSION_MISMATCH,
            TransportError.PEER_ID_MISMATCH,
            TransportError.NEGOTIATION_HASH_MISMATCH,
        )) {
            assertEquals(TransportErrorCategory.SECURITY, e.category, "categoria de $e")
            assertFalse(e.permitsFallback, "$e no debe autorizar fallback")
        }
    }

    @Test
    @DisplayName("3Q1-03 un fallo de PROTOCOLO no hace fallback: los mismos bytes fallan igual")
    fun `3Q1-03 protocolo no hace fallback`() {
        for (e in listOf(TransportError.INVALID_SDP, TransportError.INVALID_ICE_CANDIDATE)) {
            assertEquals(TransportErrorCategory.PROTOCOL, e.category, "categoria de $e")
            assertFalse(e.permitsFallback, "$e no debe autorizar fallback")
        }
    }

    @Test
    @DisplayName("3Q1-04 un fallo de ESTADO no hace fallback: reintentar no cambia el estado")
    fun `3Q1-04 estado no hace fallback`() {
        for (e in listOf(TransportError.BINDING_ALREADY_EXISTS, TransportError.INVALID_STATE_TRANSITION)) {
            assertEquals(TransportErrorCategory.STATE, e.category, "categoria de $e")
            assertFalse(e.permitsFallback, "$e no debe autorizar fallback")
        }
    }

    @Test
    @DisplayName("3Q1-05 un fallo del MEDIO SI hace fallback")
    fun `3Q1-05 medio si hace fallback`() {
        for (e in listOf(
            TransportError.BINDING_NOT_FOUND,
            TransportError.BINDING_CLOSED,
            TransportError.BINDING_FAILED,
            TransportError.BACKEND_NOT_READY,
            TransportError.BACKEND_SEND_FAILED,
            TransportError.BACKEND_ERROR,
        )) {
            assertEquals(TransportErrorCategory.TRANSPORT, e.category, "categoria de $e")
            assertTrue(e.permitsFallback, "$e debe autorizar fallback")
        }
    }

    @Test
    @DisplayName("3Q1-06 la particion es total y no solapable")
    fun `3Q1-06 la particion es total`() {
        // Ningun error puede quedarse sin categoria, y cada categoria debe
        // decidir lo mismo sobre el fallback. Si alguien anade un error sin
        // categoria, la compilacion lo obliga a declararla.
        for (e in TransportError.entries) {
            assertNotNull(e.category, "$e sin categoria declarada")
        }
        // Y el criterio no puede depender del caso: se deriva de la categoria.
        for (e in TransportError.entries) {
            val esperado = e.category == TransportErrorCategory.TRANSPORT
            assertEquals(esperado, e.permitsFallback, "permitsFallback incoherente en $e")
        }
    }

    // ===================================================================
    // 3Q.1 — Propagacion del resultado del backend
    // ===================================================================

    @Test
    @DisplayName("3Q1-07 el manager PROPAGA el fallo del medio, no reporta exito")
    fun `3Q1-07 el fallo del medio se propaga`() {
        val backend = ScriptedBackend(
            sendOutcome = TransportResult.Failure(
                TransportError.BACKEND_SEND_FAILED, "DataChannel no OPEN",
            )
        )
        val (mgr, ctx) = connectedManager(backend)

        val result = mgr.sendData(aliceId, ctx, "hola".toByteArray())

        assertTrue(result is TransportResult.Failure, "un medio caido no puede reportarse como exito: $result")
        val failure = result as TransportResult.Failure
        assertEquals(TransportError.BACKEND_SEND_FAILED, failure.error)
        assertTrue(failure.error.permitsFallback, "este fallo debe permitir probar el siguiente transporte")
        assertTrue(failure.message.contains("DataChannel"), "el mensaje del medio debe llegar intacto: ${failure.message}")
    }

    @Test
    @DisplayName("3Q1-08 la categoria del backend sobrevive a la propagacion")
    fun `3Q1-08 la categoria sobrevive`() {
        // Si el backend reportara un problema de SEGURIDAD, el manager no debe
        // reinterpretarlo como fallo de medio y autorizar un fallback.
        val backend = ScriptedBackend(
            sendOutcome = TransportResult.Failure(TransportError.NEGOTIATION_HASH_MISMATCH, "hash distinto")
        )
        val (mgr, ctx) = connectedManager(backend)

        val result = mgr.sendData(aliceId, ctx, byteArrayOf(1)) as TransportResult.Failure

        assertEquals(TransportErrorCategory.SECURITY, result.error.category)
        assertFalse(result.error.permitsFallback, "un fallo de seguridad no puede convertirse en fallback")
    }

    @Test
    @DisplayName("3Q1-09 los bytes que NO salieron del nodo no se contabilizan")
    fun `3Q1-09 un envio fallido no infla los contadores`() {
        val backend = ScriptedBackend(
            sendOutcome = TransportResult.Failure(TransportError.BACKEND_SEND_FAILED, "caido")
        )
        val (mgr, ctx) = connectedManager(backend)
        val before = mgr.getBinding(aliceId, bobId)!!.dataReceived

        mgr.sendData(aliceId, ctx, "no debe contar".toByteArray())

        val after = mgr.getBinding(aliceId, bobId)!!.dataReceived
        assertEquals(before, after, "bytes que no salieron no pueden contabilizarse como enviados")
    }

    @Test
    @DisplayName("3Q1-10 los handlers NO se notifican cuando el medio rechaza el envio")
    fun `3Q1-10 no se notifica lo que no salio`() {
        val backend = ScriptedBackend(
            sendOutcome = TransportResult.Failure(TransportError.BACKEND_SEND_FAILED, "caido")
        )
        val (mgr, ctx) = connectedManager(backend)
        val observed = CopyOnWriteArrayList<ByteArray>()
        mgr.onData { _, data -> observed.add(data) }

        mgr.sendData(aliceId, ctx, "fantasma".toByteArray())

        assertTrue(observed.isEmpty(), "no se debe notificar un envio que el medio rechazo: ${observed.size}")
    }

    @Test
    @DisplayName("3Q1-11 un envio aceptado si se contabiliza y se notifica")
    fun `3Q1-11 el camino feliz se mantiene`() {
        val backend = ScriptedBackend()
        val (mgr, ctx) = connectedManager(backend)
        val observed = CopyOnWriteArrayList<ByteArray>()
        mgr.onData { remote, data -> observed.add(data); assertEquals(bobId, remote) }

        val payload = "hola".toByteArray()
        val result = mgr.sendData(aliceId, ctx, payload)

        assertTrue(result.isSuccess)
        assertEquals(1, observed.size, "un envio exitoso debe notificarse")
        assertContentEquals(payload, observed.first())
        assertEquals(1, backend.sentPayloads.size, "el medio debe recibir el payload")
        assertEquals(4, mgr.getBinding(aliceId, bobId)!!.dataReceived)
    }

    @Test
    @DisplayName("3Q1-12 el fallo de estado previo sigue siendo de ESTADO, no de TRANSPORTE")
    fun `3Q1-12 un binding no conectado no autoriza fallback`() {
        val backend = ScriptedBackend()
        val mgr = PeerTransportManager(transportBackend = backend, now = { 0L })
        val ctx = peerContext()
        mgr.createBinding(aliceId, ctx).getOrThrow()   // NEW, no CONNECTED

        val result = mgr.sendData(aliceId, ctx, byteArrayOf(1)) as TransportResult.Failure

        assertEquals(TransportError.INVALID_STATE_TRANSITION, result.error)
        assertFalse(
            result.error.permitsFallback,
            "un binding sin conectar no es un fallo del MEDIO: la cadena, no el medio, debe decidir",
        )
    }

    @Test
    @DisplayName("3Q1-13 el manager inicializa el backend que recibe")
    fun `3Q1-13 el backend se inicializa`() {
        val backend = ScriptedBackend()
        PeerTransportManager(transportBackend = backend, now = { 0L })
        assertTrue(backend.initialized, "el manager debe inicializar el backend al construirse")
    }

    // ===================================================================
    // 3Q.0 — La convencion de nombres queda fijada por el codigo
    // ===================================================================

    @Test
    @DisplayName("3Q0-01 ningun parametro `peerId` sobrevive en la frontera de transporte")
    fun `3Q0-01 sin peerId generico en transporte`() {
        // La ambiguedad que motivo 3Q.0 costo tres correcciones en 3P: el
        // signaling entrega al REMOTO y `sendData` al DESTINATARIO. Este test
        // falla si alguien reintroduce un `peerId` sin calificar en el
        // contrato de transporte.
        //
        // El alcance es deliberado: NO cubre RelayServer ni NodeRuntime, donde
        // `peerId` designa al par registrado en el relay. Ahi no hay dos
        // extremos en juego, asi que el nombre generico no es ambiguo y
        // renombrarlo solo mezclaria dos conceptos distintos.
        val alcance = listOf("TransportBackend.kt", "FakeTransportBackend.kt", "PeerTransportManager.kt")
        val dir = java.io.File("src/main/kotlin/com/km/node")
        assertTrue(dir.exists(), "debe existir $dir")
        val offenders = alcance.flatMap { nombre ->
            dir.walkTopDown().filter { it.name == nombre }.toList()
        }.filter { f ->
            f.readLines()
                .filterNot { t -> val s = t.trimStart(); s.startsWith("*") || s.startsWith("//") || s.startsWith("/*") }
                .any { Regex("""\bpeerId\b""").containsMatchIn(it) }
        }.map { it.name }.toList()
        assertTrue(offenders.isEmpty(), "ningun `peerId` generico debe quedar en el contrato: $offenders")
    }

    @Test
    @DisplayName("3Q0-02 el contrato nombra explicitamente localIdentity y remotePeerId")
    fun `3Q0-02 el contrato es explicito`() {
        val source = java.io.File("src/main/kotlin/com/km/node/TransportBackend.kt").readText()
        // Cada operacion que designa al otro extremo debe decirlo.
        for (m in listOf("onCreateBinding", "onLocalSdp", "onRemoteSdp", "onIceCandidate", "sendData", "onCloseBinding")) {
            val line = source.lines().firstOrNull { "fun $m(" in it }
            assertNotNull(line, "debe existir $m en el contrato")
            assertTrue(
                "remotePeerId" in line!!,
                "$m debe nombrar al otro extremo como remotePeerId: $line",
            )
        }
    }

    @Test
    @DisplayName("3Q0-03 remotePeerId sigue siendo el peer autenticado, no el local")
    fun `3Q0-03 remotePeerId es el remoto`() {
        // El renombrado no puede haber invertido la direccion: el error
        // anterior mas frecuente fue cablear el hook de un lado al otro.
        val ctx = peerContext()
        assertEquals(bobId, ctx.remotePeerId)
        assertNotEquals(aliceId, ctx.remotePeerId, "remotePeerId jamas debe ser la identidad local")
    }

    private fun deriveId(publicKey: ByteArray): String =
        MessageDigest.getInstance("SHA-256").let { d ->
            d.update("KM-ID-IDENTITY".toByteArray())
            d.update(publicKey)
            d.digest().joinToString("") { "%02x".format(it) }
        }
}
