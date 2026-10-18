package com.km.transport

import com.km.model.IdentityId
import com.km.node.PeerTransportManager
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class RelayClientStackTest {
    private val identity = IdentityId("11".repeat(32))

    @Test
    fun `RC-01 compone y expone las piezas`() {
        val engine = JvmRelayTransport(identity = identity, ed25519PublicKey = ByteArray(32), ed25519PrivateKey = ByteArray(32))
        val manager = PeerTransportManager(engine)
        val stack = RelayClientStack.compose(engine, manager)
        assertSame(engine, stack.transport)
        assertSame(manager, stack.manager)
        assertSame(engine, stack.backend)
    }

    @Test
    fun `RC-02 backend es identico al transport`() {
        val engine = JvmRelayTransport(identity = identity, ed25519PublicKey = ByteArray(32), ed25519PrivateKey = ByteArray(32))
        val manager = PeerTransportManager(engine)
        val stack = RelayClientStack.compose(engine, manager)
        assertSame(stack.transport, stack.backend)
    }

    @Test
    fun `RC-03 registrar dos veces NO duplica la entrega legacy`() {
        val engine = JvmRelayTransport(identity = identity, ed25519PublicKey = ByteArray(32), ed25519PrivateKey = ByteArray(32))
        val manager = PeerTransportManager(engine)
        val stack = RelayClientStack.compose(engine, manager)
        assertTrue(stack.attachLegacy(), "la primera alta debe tener exito")
        assertEquals(false, stack.attachLegacy(), "la segunda debe rechazarse")
    }

    @Test
    fun `compose rechaza un manager con backend distinto`() {
        val engine = JvmRelayTransport(identity = identity, ed25519PublicKey = ByteArray(32), ed25519PrivateKey = ByteArray(32))
        val other = JvmRelayTransport(identity = identity, ed25519PublicKey = ByteArray(32), ed25519PrivateKey = ByteArray(32))
        val foreign = PeerTransportManager(other)
        assertThrows(IllegalArgumentException::class.java) {
            RelayClientStack.compose(engine, foreign)
        }
    }
}