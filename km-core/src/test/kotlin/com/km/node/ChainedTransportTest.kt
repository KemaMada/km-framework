package com.km.node

import com.km.model.IdentityId
import com.km.protocol.ConnectionState
import com.km.protocol.Transport
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.assertContentEquals
import kotlin.test.assertIs

/**
 * S2 — Contrato de los 11 metodos de [Transport] sobre [ChainedTransport].
 *
 * Se prueba con dobles: el adaptador solo enruta, y lo que hay que demostrar es
 * QUE destino tomo cada llamada. Los tres fallos que estos tests impiden son
 * los que un test de "compila y devuelve success" no veria:
 *
 *  - el ACK saliendo por la cadena P2P (contrato de [Transport.sendAck]);
 *  - los mismos bytes entregados dos veces (cadena + relay);
 *  - la presencia sin efecto sobre el coordinador.
 */
class ChainedTransportTest {

    private val local = IdentityId("aa".repeat(32))
    private val remote = IdentityId("bb".repeat(32))
    private val otro = IdentityId("cc".repeat(32))

    // ------------------------------------------------------------------
    // Dobles
    // ------------------------------------------------------------------

    /** Relay de control: registra cada una de las llamadas de sesion. */
    private class FakeRelayTransport(var online: Boolean = true) : Transport {
        val calls = CopyOnWriteArrayList<String>()
        val sentTo = CopyOnWriteArrayList<IdentityId>()
        val acks = CopyOnWriteArrayList<ByteArray>()
        val peerOnlineHandlers = CopyOnWriteArrayList<(IdentityId) -> Unit>()
        val peerOfflineHandlers = CopyOnWriteArrayList<(IdentityId) -> Unit>()
        val stateHandlers = CopyOnWriteArrayList<(ConnectionState) -> Unit>()
        var messageSink: (ByteArray) -> Unit = {}
        var ackSink: (ByteArray) -> Unit = {}
        var sendOutcome: Result<Unit> = Result.success(Unit)
        var ackOutcome: Result<Unit> = Result.success(Unit)

        override fun connect(): Result<Unit> {
            calls.add("connect")
            return Result.success(Unit)
        }

        override fun disconnect() {
            calls.add("disconnect")
        }

        override fun send(data: ByteArray, to: IdentityId): Result<Unit> {
            calls.add("send")
            if (sendOutcome.isSuccess) sentTo.add(to)
            return sendOutcome
        }

        override fun sendAck(ackBytes: ByteArray): Result<Unit> {
            calls.add("sendAck")
            if (ackOutcome.isSuccess) acks.add(ackBytes.copyOf())
            return ackOutcome
        }

        override fun setMessageHandler(handler: (ByteArray) -> Unit) {
            calls.add("setMessageHandler")
            messageSink = handler
        }

        override fun setAckHandler(handler: (ByteArray) -> Unit) {
            calls.add("setAckHandler")
            ackSink = handler
        }

        override fun isOnline(): Boolean = online

        override fun connectionState(): ConnectionState =
            if (online) ConnectionState.ONLINE else ConnectionState.DISCONNECTED

        override fun onStateChange(handler: (ConnectionState) -> Unit) {
            calls.add("onStateChange")
            stateHandlers.add(handler)
        }

        override fun onPeerOnline(handler: (IdentityId) -> Unit) {
            calls.add("onPeerOnline")
            peerOnlineHandlers.add(handler)
        }

        override fun onPeerOffline(handler: (IdentityId) -> Unit) {
            calls.add("onPeerOffline")
            peerOfflineHandlers.add(handler)
        }

        fun emitPeerOnline(id: IdentityId) = peerOnlineHandlers.forEach { it(id) }
        fun emitPeerOffline(id: IdentityId) = peerOfflineHandlers.forEach { it(id) }
    }

    /** Ganchos de enlace: registra el orden de las llamadas. */
    private class FakeLinks : ChainedLinks {
        val events = CopyOnWriteArrayList<String>()
        val online = CopyOnWriteArrayList<IdentityId>()
        val offline = CopyOnWriteArrayList<IdentityId>()

        override fun onPeerOnline(remotePeerId: IdentityId) {
            events.add("online")
            online.add(remotePeerId)
        }

        override fun onPeerOffline(remotePeerId: IdentityId) {
            events.add("offline")
            offline.add(remotePeerId)
        }

        override fun closeAll() {
            events.add("closeAll")
        }
    }

    /** Monta una cadena [p2p, relay] y la deja en el estado pedido. */
    private fun chainWith(
        p2p: ScriptableTransportBackend,
        relay: ScriptableTransportBackend,
        p2pReady: Boolean = true,
    ): TransportChain {
        val chain = TransportChain(listOf(p2p, relay))
        val p2pEst = ScriptableEstablishment("p2p", p2p).apply {
            if (!p2pReady) awaitReadyOutcome = TransportResult.Failure(TransportError.BACKEND_SEND_FAILED, "sin canal")
        }
        val relayEst = ScriptableEstablishment("relay", relay)
        val result = chain.establish(local, remote, timeoutMs = 8_000) { backend ->
            if (backend === p2p) p2pEst else relayEst
        }
        assertTrue(result is EstablishmentResult.Ready, "el montaje del test debe alcanzar READY: $result")
        return chain
    }

    // ------------------------------------------------------------------
    // 11 metodos
    // ------------------------------------------------------------------

    @Test
    @DisplayName("2.4-01 connect y el estado salen del relay; los handlers se registran en el relay")
    fun controlSaleDelRelay() {
        val relay = FakeRelayTransport()
        val chained = ChainedTransport(relay)

        assertTrue(chained.connect().isSuccess)
        chained.setMessageHandler { }
        chained.setAckHandler { }
        chained.onStateChange { }
        assertTrue(chained.isOnline())
        assertEquals(ConnectionState.ONLINE, chained.connectionState())

        assertEquals(
            listOf("connect", "setMessageHandler", "setAckHandler", "onStateChange"),
            // El adaptador cablea la presencia en su `init`; esas dos altas son
            // parte del contrato y se comprueban en 2.4-09.
            relay.calls.filterNot { it == "onPeerOnline" || it == "onPeerOffline" },
        )
        assertTrue(relay.calls.none { it.startsWith("send") })
    }

    @Test
    @DisplayName("2.4-02 los handlers son multi-suscriptor y el adaptador no los intercepta")
    fun handlersDelRelay() {
        val relay = FakeRelayTransport()
        val chained = ChainedTransport(relay)
        val mensajes = CopyOnWriteArrayList<ByteArray>()
        val acks = CopyOnWriteArrayList<ByteArray>()
        chained.setMessageHandler { mensajes.add(it) }
        chained.setAckHandler { acks.add(it) }

        relay.messageSink(byteArrayOf(1))
        relay.ackSink(byteArrayOf(2))

        assertContentEquals(byteArrayOf(1), mensajes.single())
        assertContentEquals(byteArrayOf(2), acks.single())
    }

    @Test
    @DisplayName("2.4-03 sin cadena, send sale por el relay")
    fun sinCadenaSalePorRelay() {
        val relay = FakeRelayTransport()
        val chained = ChainedTransport(relay)
        val payload = byteArrayOf(7, 7, 7)

        assertTrue(chained.send(payload, remote).isSuccess)
        assertEquals(listOf(remote), relay.sentTo)
    }

    @Test
    @DisplayName("2.4-04 P2P READY: send sale SOLO por P2P, relay.send 0")
    fun p2pReadyNoTocaElRelayDeControl() {
        val p2p = ScriptableTransportBackend("p2p")
        val relayBackend = ScriptableTransportBackend("relay")
        val relay = FakeRelayTransport()
        val chain = chainWith(p2p, relayBackend)
        val chained = ChainedTransport(relay, chainFor = { if (it == remote) chain else null })

        assertTrue(chained.send(byteArrayOf(9), remote).isSuccess)

        assertEquals(1, p2p.sendCount, "el byte sale una vez por P2P")
        assertContentEquals(byteArrayOf(9), p2p.sentPayloads.single())
        assertEquals(0, relayBackend.sendCount, "la cadena no debe intentar el relay si P2P entrego")
        assertEquals(0, relay.sentTo.size, "el relay de control no puede recibir los mismos bytes")
        assertEquals(Medium.P2P, chained.activeMedium(remote))
    }

    @Test
    @DisplayName("2.4-05 P2P cae con TRANSPORT: la cadena pasa al relay UNA vez")
    fun fallbackDeDatos() {
        val p2p = ScriptableTransportBackend("p2p").apply { failWithTransport() }
        val relayBackend = ScriptableTransportBackend("relay")
        val relay = FakeRelayTransport()
        val chain = chainWith(p2p, relayBackend)
        val chained = ChainedTransport(relay, chainFor = { if (it == remote) chain else null })

        assertTrue(chained.send(byteArrayOf(5), remote).isSuccess)

        assertEquals(1, p2p.sendCount)
        assertEquals(1, relayBackend.sendCount, "el fallback lo hace la cadena, una sola vez")
        assertContentEquals(byteArrayOf(5), relayBackend.sentPayloads.single())
        assertEquals(0, relay.sentTo.size, "el adaptador no puede sumar un segundo intento")
    }

    @Test
    @DisplayName("2.4-06 SECURITY no cambia de medio ni duplica")
    fun seguridadNoCambiaDeMedio() {
        val p2p = ScriptableTransportBackend("p2p").apply {
            failWith(TransportErrorCategory.SECURITY, "peer no autenticado")
        }
        val relayBackend = ScriptableTransportBackend("relay")
        val relay = FakeRelayTransport()
        val chain = chainWith(p2p, relayBackend)
        val chained = ChainedTransport(relay, chainFor = { if (it == remote) chain else null })

        val result = chained.send(byteArrayOf(1), remote)

        assertTrue(result.isFailure)
        val error = assertIs<TransportSendException>(result.exceptionOrNull())
        assertEquals(TransportErrorCategory.SECURITY, error.error.category)
        assertEquals(0, relayBackend.sendCount, "un fallo de seguridad no se esquiva por relay")
        assertEquals(0, relay.sentTo.size)
    }

    @Test
    @DisplayName("2.4-07 sendAck SIEMPRE por el relay, incluso con P2P READY")
    fun ackSiemprePorRelay() {
        val p2p = ScriptableTransportBackend("p2p")
        val relayBackend = ScriptableTransportBackend("relay")
        val relay = FakeRelayTransport()
        val chain = chainWith(p2p, relayBackend)
        val chained = ChainedTransport(relay, chainFor = { if (it == remote) chain else null })

        val ack = byteArrayOf(3, 3)
        assertTrue(chained.sendAck(ack).isSuccess)

        assertContentEquals(ack, relay.acks.single())
        assertEquals(0, p2p.sendCount, "un ACK no puede viajar por P2P")
        assertEquals(0, relayBackend.sendCount)
    }

    @Test
    @DisplayName("2.4-08 la cadena de OTRO par no se usa")
    fun cadenaPorPar() {
        val p2p = ScriptableTransportBackend("p2p")
        val relayBackend = ScriptableTransportBackend("relay")
        val relay = FakeRelayTransport()
        val chain = chainWith(p2p, relayBackend)
        val chained = ChainedTransport(relay, chainFor = { if (it == remote) chain else null })

        assertTrue(chained.send(byteArrayOf(4), otro).isSuccess)

        assertEquals(0, p2p.sendCount, "el par sin cadena no puede usar la cadena de otro")
        assertEquals(listOf(otro), relay.sentTo)
        assertEquals(null, chained.activeMedium(otro))
    }

    @Test
    @DisplayName("2.4-09 la presencia del relay dispara prepare y close del coordinador")
    fun presenciaDisparaElEnlace() {
        val relay = FakeRelayTransport()
        val links = FakeLinks()
        val chained = ChainedTransport(relay, links = links)
        val presencia = CopyOnWriteArrayList<IdentityId>()
        chained.onPeerOnline { presencia.add(it) }

        relay.emitPeerOnline(remote)
        relay.emitPeerOffline(remote)

        assertEquals(listOf(remote), links.online, "onPeerOnline debe preparar el enlace")
        assertEquals(listOf(remote), links.offline, "onPeerOffline debe cerrarlo")
        assertEquals(listOf(remote), presencia, "el handler del consumidor sigue recibiendo el evento")
    }

    @Test
    @DisplayName("2.4-10 disconnect cierra los enlaces ANTES de soltar el relay")
    fun disconnectCierraEnlacesPrimero() {
        val relay = FakeRelayTransport()
        val links = FakeLinks()
        val chained = ChainedTransport(relay, links = links)

        chained.disconnect()

        assertEquals(listOf("closeAll"), links.events)
        assertEquals(listOf("disconnect"), relay.calls.filterNot { it == "onPeerOnline" || it == "onPeerOffline" })
    }

    @Test
    @DisplayName("2.4-11 un closeAll que revienta no impide desconectar el relay")
    fun closeAllQueRevientaNoDejaElSocketVivo() {
        val relay = FakeRelayTransport()
        val links = object : ChainedLinks {
            override fun onPeerOnline(remotePeerId: IdentityId) = Unit
            override fun onPeerOffline(remotePeerId: IdentityId) = Unit
            override fun closeAll() = throw IllegalStateException("fallo de limpieza")
        }
        ChainedTransport(relay, links = links).disconnect()

        assertEquals(listOf("disconnect"), relay.calls.filterNot { it == "onPeerOnline" || it == "onPeerOffline" })
    }

    @Test
    @DisplayName("2.4-12 activeMedium con la cadena en relay dice RELAY")
    fun medioRelay() {
        val p2p = ScriptableTransportBackend("p2p")
        val relayBackend = ScriptableTransportBackend("relay")
        val relay = FakeRelayTransport()
        val chain = chainWith(p2p, relayBackend, p2pReady = false)
        val chained = ChainedTransport(relay, chainFor = { if (it == remote) chain else null })

        assertEquals(Medium.RELAY, chained.activeMedium(remote))
        assertTrue(chained.send(byteArrayOf(6), remote).isSuccess)
        assertEquals(0, p2p.sendCount, "sin P2P READY el adaptador va al relay directo")
        assertEquals(listOf(remote), relay.sentTo)
    }
}
