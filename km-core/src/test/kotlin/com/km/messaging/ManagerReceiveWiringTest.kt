package com.km.messaging

import com.km.model.IdentityId
import com.km.model.Message
import com.km.node.FakeTransportBackend
import com.km.node.PeerTransportManager
import com.km.node.TransportEventCallbacks
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ManagerReceiveWiringTest {
    private val contenido = "payload del camino de recepcion 8110".toByteArray()

    @Test
    fun `MW-01 un SecureFrame por eventCallbacks llega se descifra y entrega UNA vez`() {
        val mundo = SecureWorld.open()
        val recibido = mutableListOf<Message>()
        mundo.bob.core.registerMessageHandler { recibido.add(it) }

        val creado = mundo.alice.core.createMessage(mundo.bobRoot.identityId, contenido).getOrThrow()
        val capturados = mundo.alice.captureOutbound()
        mundo.alice.core.sendMessage(creado).getOrThrow()
        val wire = capturados.last()

        mundo.bob.peerTransport.backend().eventCallbacks.onDataReceived?.invoke(
            mundo.bobToAlice.remotePeerId,
            wire,
        )

        assertEquals(1, recibido.size)
        val puente = requireNotNull(mundo.bob.core.secureInbound)
        assertEquals(1, puente.delivered.get())
        assertEquals(0, puente.rejectedFrames.get())
        assertEquals(0, puente.unknownPeerFrames.get())
        assertContentEquals(contenido, recibido.single().payload)
    }

    @Test
    fun `MW-02 componer conserva el slot previo y ambos reciben el byte`() {
        val backend = FakeTransportBackend()
        val previo = mutableListOf<ByteArray>()
        backend.eventCallbacks = TransportEventCallbacks(onDataReceived = { _, data -> previo.add(data) })
        val manager = PeerTransportManager(backend)
        val handlers = mutableListOf<ByteArray>()
        manager.onData { _, data -> handlers.add(data) }

        val wire = byteArrayOf(7, 8, 9)
        backend.eventCallbacks.onDataReceived?.invoke(IdentityId("a".repeat(64)), wire)

        assertEquals(1, previo.size)
        assertEquals(1, handlers.size)
    }

    @Test
    fun `MW-03 reasignar eventCallbacks fresco DESCONECTA el cable`() {
        val backend = FakeTransportBackend()
        val manager = PeerTransportManager(backend)
        val handlers = mutableListOf<ByteArray>()
        manager.onData { _, data -> handlers.add(data) }

        backend.eventCallbacks = TransportEventCallbacks()

        val wire = byteArrayOf(1, 2, 3)
        backend.eventCallbacks.onDataReceived?.invoke(IdentityId("a".repeat(64)), wire)

        assertEquals(0, handlers.size)
    }

    @Test
    fun `MW-04 el eco local sigue vivo tras un envio propio`() {
        val mundo = SecureWorld.open()
        val capturados = mundo.alice.captureOutbound()
        val creado = mundo.alice.core.createMessage(mundo.bobRoot.identityId, contenido).getOrThrow()
        mundo.alice.core.sendMessage(creado).getOrThrow()
        assertTrue(capturados.isNotEmpty(), "el envio propio debe disparar dataHandlers")
    }

    @Test
    fun `MW-05 el frame de A no lo descifra la sesion de B`() {
        val mundo = SecureWorld.openWithoutRecipientSession()
        val puente = requireNotNull(mundo.bob.core.secureInbound)

        val creado = mundo.alice.core.createMessage(mundo.bobRoot.identityId, contenido).getOrThrow()
        val capturados = mundo.alice.captureOutbound()
        mundo.alice.core.sendMessage(creado).getOrThrow()
        val wire = capturados.last()

        mundo.bob.peerTransport.backend().eventCallbacks.onDataReceived?.invoke(
            mundo.bobToAlice.remotePeerId,
            wire,
        )

        assertEquals(0, puente.delivered.get())
        assertEquals(1, puente.unknownPeerFrames.get())
    }

    @Test
    fun `MW-06 byte que NO es SecureFrame por el canal de par cuenta legacy`() {
        val mundo = SecureWorld.open()
        val puente = requireNotNull(mundo.bob.core.secureInbound)

        mundo.bob.peerTransport.backend().eventCallbacks.onDataReceived?.invoke(
            mundo.bobToAlice.remotePeerId,
            byteArrayOf(9),
        )

        assertEquals(1, puente.legacyBytesOnPeerChannel.get())
        assertEquals(0, puente.delivered.get())
    }

    private fun assertContentEquals(expected: ByteArray, actual: ByteArray) {
        org.junit.jupiter.api.Assertions.assertArrayEquals(expected, actual)
    }
}