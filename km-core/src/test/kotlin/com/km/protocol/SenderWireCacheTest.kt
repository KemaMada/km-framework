package com.km.protocol

import com.km.codec.JsonEncoder
import com.km.codec.ValidatorImpl
import com.km.crypto.Ed25519Impl
import com.km.messaging.SecureOutbound
import com.km.messaging.SecureSendOutcome
import com.km.messaging.SecureSendReject
import com.km.messaging.SecureSendRejectedException
import com.km.model.IdentityId
import com.km.model.Message
import com.km.model.MessageId
import com.km.storage.InMemoryMessageStore
import com.km.storage.InMemoryOfflineQueue
import com.km.storage.MessageStore
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.UUID

class SenderWireCacheTest {
    private class RecordingTransport(private val initialOnline: Boolean) : com.km.protocol.Transport {
        @Volatile var online: Boolean = initialOnline
        override fun connect(): Result<Unit> { online = true; return Result.success(Unit) }
        override fun disconnect() { online = false }
        override fun send(data: ByteArray, to: IdentityId): Result<Unit> = Result.success(Unit)
        override fun sendAck(ackBytes: ByteArray): Result<Unit> = Result.success(Unit)
        override fun setMessageHandler(handler: (ByteArray) -> Unit) {}
        override fun setAckHandler(handler: (ByteArray) -> Unit) {}
        override fun isOnline(): Boolean = online
        override fun connectionState(): com.km.protocol.ConnectionState =
            if (online) com.km.protocol.ConnectionState.ONLINE else com.km.protocol.ConnectionState.DISCONNECTED
        override fun onStateChange(handler: (com.km.protocol.ConnectionState) -> Unit) {}
        override fun onPeerOnline(handler: (IdentityId) -> Unit) {}
        override fun onPeerOffline(handler: (IdentityId) -> Unit) {}
    }

    private class FakeOutbound(private val wire: ByteArray) : SecureOutbound {
        override fun send(message: Message): SecureSendOutcome = SecureSendOutcome.Encrypted(wire, wire.size)
        override fun retransmit(message: Message, wire: ByteArray): SecureSendOutcome = SecureSendOutcome.Encrypted(wire, wire.size)
    }

    private fun buildSender(transport: RecordingTransport, outbound: SecureOutbound): Triple<SenderImpl, MessageStore, MessageId> {
        val store = InMemoryMessageStore()
        val queue = InMemoryOfflineQueue()
        val ackManager = AckManagerImpl(store, Ed25519Impl())
        val retryManager = RetryManagerImpl()
        val localId = IdentityId("11".repeat(32))
        val toId = IdentityId("22".repeat(32))
        val messageId = MessageId(UUID.randomUUID())
        val msg = Message(messageId = messageId, from = localId, to = toId, payload = "payload".toByteArray(), timestamp = 1L)
        store.insert(msg).getOrThrow()
        val sender = SenderImpl(
            messageStore = store,
            offlineQueue = queue,
            encoder = JsonEncoder(),
            validator = ValidatorImpl(),
            ed25519 = Ed25519Impl(),
            transport = transport,
            ackManager = ackManager,
            retryManager = retryManager,
            localIdentity = localId,
            localPrivateKey = ByteArray(32),
            secureOutbound = outbound,
        )
        return Triple(sender, store, messageId)
    }

    @Test
    fun `SW-01 tras un ACK autenticado cachedWireCount es 0 y retry falla con NO_SESSION`() {
        val transport = RecordingTransport(true)
        val wire = byteArrayOf(1, 2, 3)
        val (sender, _, messageId) = buildSender(transport, FakeOutbound(wire))

        val message = sender.javaClass.getDeclaredField("messageStore").let { f -> f.isAccessible = true; f.get(sender) as MessageStore }.get(messageId).getOrThrow()!!
        assertTrue(sender.send(message).isSuccess)

        assertEquals(1, sender.cachedWireCount(), "el envio cifrado deja UN wire cacheado")

        sender.onAckConfirmed(messageId)

        assertEquals(0, sender.cachedWireCount(), "el ACK autenticado libera el wire")

        val retry = sender.retry(messageId)
        assertTrue(retry.isFailure, "retry debe fallar tras liberar el wire")
        val ex = retry.exceptionOrNull()
        assertTrue(ex is SecureSendRejectedException, "debe ser rechazo de envio cifrado")
        assertEquals(SecureSendReject.NO_SESSION, (ex as SecureSendRejectedException).reason)
    }

    @Test
    fun `SW-02 tras un encolado por offline el cache sigue`() {
        val transport = RecordingTransport(true)
        val wire = byteArrayOf(1, 2, 3)
        val (sender, store, messageId) = buildSender(transport, FakeOutbound(wire))

        val message = store.get(messageId).getOrThrow()!!
        assertTrue(sender.send(message).isSuccess)
        assertEquals(1, sender.cachedWireCount())

        transport.online = false
        sender.send(message)
        assertEquals(1, sender.cachedWireCount(), "encolar NO puede borrar el wire cacheado")
    }
}