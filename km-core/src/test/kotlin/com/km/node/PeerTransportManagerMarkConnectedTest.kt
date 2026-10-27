package com.km.node

import com.km.auth.AuthChallenge
import com.km.auth.AuthOk
import com.km.auth.AuthSession
import com.km.auth.TranscriptBuilder
import com.km.crypto.Ed25519
import com.km.crypto.Ed25519Impl
import com.km.crypto.KeyPair
import com.km.model.IdentityId
import com.km.model.MessageId
import com.km.model.NodeAnnouncement
import com.km.model.NodeCapability
import com.km.model.NodeIdentity
import com.km.model.NodeAnnouncementJsonCodec
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.concurrent.CopyOnWriteArrayList

/**
 * R4 — `markConnected` NO filtra `IllegalArgumentException` al frame-loop.
 *
 * ## EL DEFECTO QUE ESTE FICHERO FIJA
 *
 * `PeerTransportBinding.withConnected()` exige `OFFER_SENT`,
 * `ANSWER_RECEIVED` o `NEGOTIATING`. `markConnected` lo llamaba SIN capturar,
 * asi que:
 *
 *  - un `PEER_ONLINE` duplicado con el binding ya `CONNECTED` —lo que emite
 *    cada reautenticacion del par— lanzaba dentro del frame-loop;
 *  - `JvmRelayTransport.onFrame` captura POR FRAME, asi que el socket sobrevivia
 *    y el defecto se repetia en cada presencia posterior;
 *  - y el llamante no recibia un `TransportResult`, recibia una excepcion.
 *
 * El contrato nuevo es el MISMO que ya tenian `receiveAnswer` y
 * `addIceCandidate`: `Failure(INVALID_STATE_TRANSITION)`, sin notificar. NO es
 * idempotencia silenciosa: una doble transicion se sigue viendo como fallo, no
 * como exito.
 *
 * `CLOSED` y `FAILED` no llegan a `withConnected` —`getActiveBinding` los
 * filtra— y siguen devolviendo `BINDING_NOT_FOUND`: "no hay binding activo",
 * no "transicion ilegal".
 */
class PeerTransportManagerMarkConnectedTest {

    private val ed25519: Ed25519 = Ed25519Impl()
    private val aliceKP = ed25519.generateKeyPair()
    private val bobKP = ed25519.generateKeyPair()
    private val aliceId = IdentityId(deriveId(aliceKP.publicKey))
    private val bobId = IdentityId(deriveId(bobKP.publicKey))

    private fun manager(): PeerTransportManager = PeerTransportManager(now = { 1_000L })

    private fun peerContext(): PeerContext {
        val session = AuthSession.initiator(aliceKP, aliceId.value, ed25519, clock = { 1_000L })
        session.receiveChallenge(AuthChallenge(ByteArray(16), 1_000L, 3, bobId.value)).getOrThrow()
        session.markResponseSent()
        val ok = authOk(aliceKP, aliceId.value)
        session.receiveAuthOk(ok, aliceId.value).getOrThrow()

        val identity = NodeIdentity(bobId, bobKP.publicKey)
        val partial = NodeAnnouncement(
            messageId = MessageId(java.util.UUID.randomUUID()),
            timestamp = 1_000L,
            protocolVersion = "1.0",
            identity = identity,
            endpoints = emptyList(),
            capabilities = setOf(NodeCapability.CLIENT),
        )
        val sig = ed25519.sign(ed25519.generateKeyPair().privateKey, NodeAnnouncementJsonCodec.signableJson(partial))
        return PeerContext.authenticated(bobId, bobKP.publicKey, partial.copy(signature = sig.bytes), session)
    }

    private fun authOk(keyPair: KeyPair, initiatorId: String): AuthOk {
        val sid = "b".repeat(40)
        val snonce = ByteArray(16) { 7 }
        val transcript = TranscriptBuilder.serverAuthTranscript(sid, snonce, initiatorId)
        return AuthOk(sid, snonce, keyPair.publicKey, ed25519.sign(keyPair.privateKey, transcript).bytes)
    }

    /** UN binding en el estado pedido, con el ciclo recorrido por el API del manager. */
    private fun managerWith(state: PeerTransportState, ctx: PeerContext): PeerTransportManager {
        val mgr = manager()
        assertTrue(mgr.createBinding(aliceId, ctx).isSuccess, "precondicion: binding creado")
        when (state) {
            PeerTransportState.NEW -> Unit
            PeerTransportState.OFFER_SENT -> assertTrue(mgr.sendOffer(aliceId, ctx, "", bobId).isSuccess)
            PeerTransportState.ANSWER_RECEIVED -> {
                assertTrue(mgr.sendOffer(aliceId, ctx, "", bobId).isSuccess)
                assertTrue(mgr.receiveAnswer(aliceId, bobId, "").isSuccess)
            }
            PeerTransportState.CONNECTED -> {
                assertTrue(mgr.sendOffer(aliceId, ctx, "", bobId).isSuccess)
                assertTrue(mgr.receiveAnswer(aliceId, bobId, "").isSuccess)
                assertTrue(mgr.markConnected(aliceId, ctx).isSuccess)
            }
            PeerTransportState.CLOSED -> assertTrue(mgr.closeBinding(aliceId, bobId).isSuccess)
            PeerTransportState.FAILED -> assertTrue(mgr.failBinding(aliceId, bobId).isSuccess)
            PeerTransportState.NEGOTIATING -> error("NEGOTIATING no hace falta en estos casos")
        }
        return mgr
    }

    @Test
    fun `desde NEW devuelve INVALID_STATE_TRANSITION sin excepcion y sin notificar`() {
        val ctx = peerContext()
        val mgr = managerWith(PeerTransportState.NEW, ctx)
        val notificados = CopyOnWriteArrayList<PeerTransportState>()
        mgr.onStateChange { _, state -> notificados.add(state) }

        val resultado = mgr.markConnected(aliceId, ctx)

        assertTrue(resultado is TransportResult.Failure, "desde NEW no puede haber exito: $resultado")
        assertEquals(
            TransportError.INVALID_STATE_TRANSITION,
            (resultado as TransportResult.Failure).error,
            "el fallo tiene que ser de ESTADO, no una excepcion",
        )
        assertEquals(
            PeerTransportState.NEW,
            mgr.getBinding(aliceId, bobId)?.state,
            "un markConnected rechazado no puede mover el binding",
        )
        assertTrue(
            notificados.isEmpty(),
            "un fallo no puede decirle a nadie que hay enlace: $notificados",
        )
    }

    @Test
    fun `desde CONNECTED (doble markConnected) devuelve INVALID_STATE_TRANSITION sin notificar`() {
        val ctx = peerContext()
        val mgr = managerWith(PeerTransportState.CONNECTED, ctx)
        val notificados = CopyOnWriteArrayList<PeerTransportState>()
        mgr.onStateChange { _, state -> notificados.add(state) }

        val resultado = mgr.markConnected(aliceId, ctx)

        assertTrue(resultado is TransportResult.Failure, "la doble transicion es un fallo visible: $resultado")
        assertEquals(
            TransportError.INVALID_STATE_TRANSITION,
            (resultado as TransportResult.Failure).error,
        )
        assertEquals(
            PeerTransportState.CONNECTED,
            mgr.getBinding(aliceId, bobId)?.state,
            "el binding sigue CONNECTED: el rechazo no lo degrada",
        )
        assertTrue(notificados.isEmpty(), "no hay segunda notificacion de CONNECTED: $notificados")
    }

    @Test
    fun `desde CLOSED devuelve BINDING_NOT_FOUND`() {
        val ctx = peerContext()
        val mgr = managerWith(PeerTransportState.CLOSED, ctx)

        val resultado = mgr.markConnected(aliceId, ctx)

        assertTrue(resultado is TransportResult.Failure, "un binding cerrado no se reconecta por aqui: $resultado")
        assertEquals(
            TransportError.BINDING_NOT_FOUND,
            (resultado as TransportResult.Failure).error,
            "CLOSED esta filtrado por getActiveBinding: no es una transicion ilegal, es que no hay binding activo",
        )
    }

    @Test
    fun `desde FAILED devuelve BINDING_NOT_FOUND`() {
        val ctx = peerContext()
        val mgr = managerWith(PeerTransportState.FAILED, ctx)

        val resultado = mgr.markConnected(aliceId, ctx)

        assertTrue(resultado is TransportResult.Failure, "un binding fallido no se reconecta por aqui: $resultado")
        assertEquals(
            TransportError.BINDING_NOT_FOUND,
            (resultado as TransportResult.Failure).error,
        )
    }

    @Test
    fun `desde un estado valido ACEPTA y notifica una vez`() {
        val ctx = peerContext()
        val mgr = managerWith(PeerTransportState.ANSWER_RECEIVED, ctx)
        val notificados = CopyOnWriteArrayList<PeerTransportState>()
        mgr.onStateChange { _, state -> notificados.add(state) }

        val resultado = mgr.markConnected(aliceId, ctx)

        assertEquals(
            PeerTransportState.CONNECTED,
            resultado.getOrThrow().state,
            "ANSWER_RECEIVED -> CONNECTED sigue siendo una transicion legal",
        )
        assertEquals(
            listOf(PeerTransportState.CONNECTED),
            notificados.toList(),
            "la transicion legitima SI se notifica, y una sola vez",
        )
    }

    private fun deriveId(publicKey: ByteArray): String {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        digest.update("KM-ID-IDENTITY".encodeToByteArray())
        digest.update(publicKey)
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
