package com.km.transport

import com.km.model.IdentityId
import com.km.model.Message
import com.km.model.MessageId
import com.km.node.FakeTransportBackend
import com.km.node.LegacyInboundFrame
import com.km.node.TransportEventCallbacks
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.UUID

class LegacyInboundConductorTest {
    private fun mid() = MessageId(UUID.randomUUID())
    private fun frame() = LegacyInboundFrame(
        from = IdentityId("a".repeat(64)),
        encoded = Message(mid(), IdentityId("a".repeat(64)), IdentityId("b".repeat(64)), byteArrayOf(1, 2), 0L),
    )

    @Test
    fun `LC-01 dado de alta un frame LEGADO llega al reinyectador una vez y delivered 0 secure`() {
        val backend = FakeTransportBackend()
        var reinyectados = 0
        val conductor = LegacyInboundConductor(backend) { reinyectados++ }
        conductor.attach()
        backend.eventCallbacks.onLegacyInboundFrame!!.invoke(frame(), IdentityId("a".repeat(64)))
        assertEquals(1, conductor.deliveredCount())
        assertEquals(1, reinyectados)
    }

    @Test
    fun `LC-02 tras detach sendData sigue notificando dataHandlers`() {
        val backend = FakeTransportBackend()
        val datos = mutableListOf<ByteArray>()
        backend.eventCallbacks = TransportEventCallbacks(onDataReceived = { _, data -> datos.add(data) })
        val conductor = LegacyInboundConductor(backend) {}
        conductor.attach()
        assertTrue(conductor.detach())
        val payload = byteArrayOf(9, 9)
        backend.eventCallbacks.onDataReceived?.invoke(IdentityId("a".repeat(64)), payload)
        assertEquals(1, datos.size)
    }

    @Test
    fun `LC-03 tras detach un frame opaco por onDataReceived sigue llegando`() {
        val backend = FakeTransportBackend()
        val datos = mutableListOf<ByteArray>()
        backend.eventCallbacks = TransportEventCallbacks(onDataReceived = { _, data -> datos.add(data) })
        val conductor = LegacyInboundConductor(backend) {}
        conductor.attach()
        conductor.detach()
        val payload = byteArrayOf(7, 7)
        backend.eventCallbacks.onDataReceived?.invoke(IdentityId("a".repeat(64)), payload)
        assertEquals(1, datos.size)
    }

    @Test
    fun `LC-04 dos attach no duplican la entrega legacy`() {
        val backend = FakeTransportBackend()
        var reinyectados = 0
        val conductor = LegacyInboundConductor(backend) { reinyectados++ }
        conductor.attach()
        conductor.attach()
        backend.eventCallbacks.onLegacyInboundFrame!!.invoke(frame(), IdentityId("a".repeat(64)))
        assertEquals(1, reinyectados, "dos attach no deben duplicar")
    }
}