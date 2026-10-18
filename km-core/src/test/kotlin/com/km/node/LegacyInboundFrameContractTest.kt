package com.km.node

import com.km.model.IdentityId
import com.km.model.Message
import com.km.model.MessageId
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.util.UUID

class LegacyInboundFrameContractTest {
    private fun frame(payloadSize: Int, seed: Int): LegacyInboundFrame {
        val payload = ByteArray(payloadSize) { (it + seed).toByte() }
        val msg = Message(
            messageId = MessageId(UUID.randomUUID()),
            from = IdentityId("a".repeat(64)),
            to = IdentityId("b".repeat(64)),
            payload = payload,
            timestamp = (payloadSize + seed).toLong(),
        )
        return LegacyInboundFrame(from = IdentityId("a".repeat(64)), encoded = msg)
    }

    @Test
    fun `dos frames con contenido NO constante y tamanos distintos (37 B y 128 B) NO son iguales`() {
        val f1 = frame(37, 1)
        val f2 = frame(128, 2)
        assertNotEquals(f1, f2)
        // Hash coherente: no debe lanzar y debe ser determinista.
        assertEquals(f1.hashCode(), f1.hashCode())
        assertEquals(f2.hashCode(), f2.hashCode())
    }

    @Test
    fun `frames con el mismo contenido son iguales aunque sean instancias distintas`() {
        // Esta asercion es la que exige equals por CONTENIDO y detecta la
        // mutacion "equals por referencia".
        val a = frame(37, 7)
        val b = LegacyInboundFrame(from = a.from, encoded = a.encoded)
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
    }

    @Test
    fun `el slot onLegacyInboundFrame por defecto es null`() {
        assertNull(TransportEventCallbacks().onLegacyInboundFrame)
    }
}