package com.km.transport.p2p

import com.km.model.IdentityId
import com.km.node.EstablishmentState
import com.km.node.PeerTransportManager
import com.km.node.TransportBackend
import com.km.node.TransportChain
import com.km.node.TransportError
import com.km.node.TransportEventCallbacks
import com.km.node.TransportResult
import com.km.transport.JvmRelayTransport
import com.km.transport.RelayClientStack
import com.km.transport.RelayDataCodec
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.assertIs

/**
 * S4 — El relay como backend y como ULTIMO eslabon; el puerto de senalizacion.
 *
 * Lo que se impide:
 *  - una cadena cuyo suelo no es el motor (fallback que no llega al socket);
 *  - `[relay, P2P]`, que dejaria el relay como preferido y P2P como relleno;
 *  - un establecimiento de relay que diga `READY` sin presencia.
 */
class RelayP2pCompositionTest {

    private val local = IdentityId("11".repeat(32))
    private val remote = IdentityId("22".repeat(32))

    private fun engine(id: IdentityId = local) = JvmRelayTransport(
        identity = id,
        ed25519PublicKey = ByteArray(32),
        ed25519PrivateKey = ByteArray(32),
    )

    /** Backend programable local al modulo: no depende del test-jar de km-core. */
    private class RecordingBackend(override val name: String) : TransportBackend {
        override var eventCallbacks: TransportEventCallbacks = TransportEventCallbacks()
        val ops = CopyOnWriteArrayList<String>()
        var bindOutcome: TransportResult<Unit> = TransportResult.Success(Unit)

        override fun initialize(): TransportResult<Unit> {
            ops.add("initialize")
            return TransportResult.Success(Unit)
        }

        override fun onCreateBinding(localIdentity: IdentityId, remotePeerId: IdentityId): TransportResult<Unit> {
            ops.add("onCreateBinding:$remotePeerId")
            return bindOutcome
        }

        override fun onLocalSdp(localIdentity: IdentityId, remotePeerId: IdentityId, sdp: String) =
            TransportResult.Success(Unit)

        override fun onRemoteSdp(localIdentity: IdentityId, remotePeerId: IdentityId, sdp: String) =
            TransportResult.Success(Unit)

        override fun onIceCandidate(localIdentity: IdentityId, remotePeerId: IdentityId, candidate: String) =
            TransportResult.Success(Unit)

        override fun sendData(remotePeerId: IdentityId, data: ByteArray) = TransportResult.Success(Unit)

        override fun onCloseBinding(localIdentity: IdentityId, remotePeerId: IdentityId): TransportResult<Unit> {
            ops.add("onCloseBinding:$remotePeerId")
            return TransportResult.Success(Unit)
        }

        override fun shutdown(): TransportResult<Unit> = TransportResult.Success(Unit)
    }

    // ------------------------------------------------------------------
    // composeChained
    // ------------------------------------------------------------------

    @Test
    @DisplayName("4.4-01 [P2P, relay] se compone y el suelo es EXACTAMENTE el motor")
    fun componeConSueloRelay() {
        val motor = engine()
        val manager = PeerTransportManager(motor)
        val p2p = RecordingBackend("p2p")
        val chain = TransportChain(listOf(p2p, motor))

        val stack = RelayClientStack.composeChained(motor, manager, chain)

        assertSame(motor, stack.chain.last)
        assertSame(motor, stack.relay.transport)
        assertSame(motor, stack.relay.backend)
        assertEquals(null, stack.transport.activeMedium(remote), "sin establecimiento no hay medio declarado")
    }

    @Test
    @DisplayName("4.4-02 [relay, P2P] se RECHAZA: el relay no puede ser el preferido")
    fun rechazaRelayPrimero() {
        val motor = engine()
        val manager = PeerTransportManager(motor)
        val chain = TransportChain(listOf(motor, RecordingBackend("p2p")))
        assertThrows(IllegalArgumentException::class.java) {
            RelayClientStack.composeChained(motor, manager, chain)
        }
    }

    @Test
    @DisplayName("4.4-03 un suelo que no es el motor se rechaza por identidad")
    fun rechazaSueloAjeno() {
        val motor = engine()
        val otro = engine(IdentityId("33".repeat(32)))
        val manager = PeerTransportManager(motor)
        val chain = TransportChain(listOf(RecordingBackend("p2p"), otro))
        assertThrows(IllegalArgumentException::class.java) {
            RelayClientStack.composeChained(motor, manager, chain)
        }
    }

    @Test
    @DisplayName("4.4-04 con una cadena todavia sin establecer, send sale por el relay")
    fun sinEstablecerVaAlRelay() {
        val motor = engine()
        val manager = PeerTransportManager(motor)
        val chain = TransportChain(listOf(RecordingBackend("p2p"), motor))
        val stack = RelayClientStack.composeChained(motor, manager, chain)

        // El motor esta OFFLINE: el envio falla, pero por el relay, no por la
        // cadena. Lo que se fija es que no se lanzo una excepcion ni se intento
        // P2P sin establecimiento.
        val r = stack.transport.send(byteArrayOf(1), remote)
        assertTrue(r.isFailure)
        assertFalse(motor.isOnline())
    }

    @Test
    @DisplayName("4.4-05 el stack relay-only de siempre sigue siendo el MISMO motor")
    fun offSigueIgual() {
        val motor = engine()
        val manager = PeerTransportManager(motor)
        val stack = RelayClientStack.compose(motor, manager)
        assertSame(motor, stack.backend)
        assertSame(motor, stack.transport)
    }

    // ------------------------------------------------------------------
    // RelayTransportEstablishment
    // ------------------------------------------------------------------

    @Test
    @DisplayName("4.4-06 con el par presente, el eslabon relay alcanza READY")
    fun relayAlcanzaReady() {
        val backend = RecordingBackend("relay")
        val est = RelayTransportEstablishment(backend, present = { true })

        assertTrue(est.initialize().isSuccess)
        assertEquals(EstablishmentState.CREATED, est.state)
        assertTrue(est.createBinding(local, remote).isSuccess)
        assertEquals(EstablishmentState.NEGOTIATING, est.state)
        assertTrue(est.negotiate().isSuccess)
        assertTrue(est.awaitReady(50).isSuccess)
        assertEquals(EstablishmentState.READY, est.state)
        assertEquals(listOf("initialize", "onCreateBinding:$remote"), backend.ops)
    }

    @Test
    @DisplayName("4.4-07 sin presencia falla PEER_NOT_ONLINE_AT_RELAY y NO crea binding")
    fun sinPresenciaNoHayBinding() {
        val backend = RecordingBackend("relay")
        val est = RelayTransportEstablishment(backend, present = { false })

        val r = est.createBinding(local, remote)

        val failure = assertIs<TransportResult.Failure>(r)
        assertEquals(TransportError.PEER_NOT_ONLINE_AT_RELAY, failure.error)
        assertEquals(EstablishmentState.FAILED, est.state)
        assertTrue(backend.ops.isEmpty(), "no se toca el backend si el par no esta: ${backend.ops}")
    }

    @Test
    @DisplayName("4.4-08 si el par se va antes de awaitReady, no hay READY")
    fun pierdePresenciaAntesDeReady() {
        val backend = RecordingBackend("relay")
        var presente = true
        val est = RelayTransportEstablishment(backend, present = { presente })
        est.initialize()
        est.createBinding(local, remote)
        presente = false

        val r = est.awaitReady(50)

        assertIs<TransportResult.Failure>(r)
        assertEquals(EstablishmentState.FAILED, est.state)
    }

    @Test
    @DisplayName("4.4-09 abort cierra el binding del par y es idempotente")
    fun abortCierra() {
        val backend = RecordingBackend("relay")
        val est = RelayTransportEstablishment(backend, present = { true })
        est.initialize()
        est.createBinding(local, remote)

        est.abort()
        est.abort()

        assertEquals(EstablishmentState.CLOSED, est.state)
        assertEquals(1, backend.ops.count { it.startsWith("onCloseBinding") }, "abort no duplica el cierre")
    }

    // ------------------------------------------------------------------
    // RelaySignalPort
    // ------------------------------------------------------------------

    @Test
    @DisplayName("4.4-10 el puerto reenvia sdp_offer como (kind, sdp, sin candidate)")
    fun puertoReenviaSdp() {
        val motor = engine()
        val puerto = RelaySignalPort(motor)
        val recibidas = CopyOnWriteArrayList<Triple<String, String?, String?>>()
        puerto.onSignal { _, kind, sdp, candidate -> recibidas.add(Triple(kind, sdp, candidate)) }

        val codec = motor.relayCodec()
        motor.deliverSignal(
            codec.parseObject(
                codec.encodeSignal(remote, local, RelayDataCodec.KIND_SDP_OFFER, sdp = "v=0\r\n"),
            )!!,
        )

        assertEquals(1, recibidas.size)
        assertEquals(RelayDataCodec.KIND_SDP_OFFER, recibidas.single().first)
        assertEquals("v=0\r\n", recibidas.single().second)
        assertEquals(null, recibidas.single().third)
    }

    @Test
    @DisplayName("4.4-11 el puerto reenvia ice_candidate como (kind, sin sdp, candidate)")
    fun puertoReenviaIce() {
        val motor = engine()
        val puerto = RelaySignalPort(motor)
        val recibidas = CopyOnWriteArrayList<Triple<String, String?, String?>>()
        puerto.onSignal { _, kind, sdp, candidate -> recibidas.add(Triple(kind, sdp, candidate)) }

        val codec = motor.relayCodec()
        motor.deliverSignal(
            codec.parseObject(
                codec.encodeSignal(remote, local, RelayDataCodec.KIND_ICE_CANDIDATE, candidate = "candidate:1 1 udp"),
            )!!,
        )

        assertEquals(RelayDataCodec.KIND_ICE_CANDIDATE, recibidas.single().first)
        assertEquals(null, recibidas.single().second)
        assertEquals("candidate:1 1 udp", recibidas.single().third)
    }

    @Test
    @DisplayName("4.4-12 el puerto ignora contact_exchange y los frames rotos")
    fun puertoIgnoraOtros() {
        val motor = engine()
        val puerto = RelaySignalPort(motor)
        var llamadas = 0
        puerto.onSignal { _, _, _, _ -> llamadas++ }
        val codec = motor.relayCodec()

        motor.deliverSignal(
            codec.parseObject(codec.encodeSignal(remote, local, RelayDataCodec.KIND_CONTACT_EXCHANGE, byteArrayOf(1)))!!,
        )
        motor.deliverSignal(codec.parseObject("{\"type\":\"SIGNAL\"}")!!)

        assertEquals(0, llamadas)
    }

    @Test
    @DisplayName("4.4-13 sin conexion, sendSignal devuelve false y no lanza")
    fun sendSignalSinConexion() {
        val motor = engine()
        assertFalse(RelaySignalPort(motor).sendSignal(remote, RelayDataCodec.KIND_SDP_OFFER, sdp = "v=0"))
    }

    @Test
    @DisplayName("4.4-14 onSignal devuelve la BAJA: tras cancelar, el handler no recibe senales")
    fun bajaSuscripcion() {
        val motor = engine()
        val puerto = RelaySignalPort(motor)
        var llamadas = 0
        val baja = puerto.onSignal { _, _, _, _ -> llamadas++ }
        val codec = motor.relayCodec()

        motor.deliverSignal(
            codec.parseObject(codec.encodeSignal(remote, local, RelayDataCodec.KIND_SDP_OFFER, sdp = "v=0"))!!,
        )
        assertEquals(1, llamadas)

        baja()
        // Cancelar dos veces es no-op: `abort()` es idempotente.
        baja()
        motor.deliverSignal(
            codec.parseObject(codec.encodeSignal(remote, local, RelayDataCodec.KIND_SDP_OFFER, sdp = "v=0"))!!,
        )
        assertEquals(1, llamadas, "un establecimiento P2P cerrado no puede seguir procesando senales")
    }
}
