package com.keymessage.core.node

import com.keymessage.core.api.KeyMessageCore
import com.keymessage.core.codec.JsonDecoder
import com.keymessage.core.codec.JsonEncoder
import com.keymessage.core.codec.JsonRelayControlCodec
import com.keymessage.core.model.*
import com.keymessage.core.protocol.ConnectionState
import com.keymessage.core.protocol.SendResult
import com.keymessage.core.storage.InMemoryRelayStore
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class NodeRuntimeImplTest {

    private val nodeId = IdentityId("relay-node")
    private val identity = NodeIdentity(nodeId = nodeId, publicKey = ByteArray(32), nodeName = "test-relay")
    private val alice = IdentityId("alice-identity")
    private val bob = IdentityId("bob-identity")

    private val encoder = JsonEncoder()
    private val decoder = JsonDecoder()
    private val controlCodec = JsonRelayControlCodec()

    private fun message(to: IdentityId) = Message(
        messageId = MessageId.random(),
        from = alice,
        to = to,
        payload = "hello".toByteArray(),
        timestamp = 1000L
    )

    private fun ackFor(msg: Message) = Ack(
        from = msg.to,
        to = msg.from,
        originalMessageId = msg.messageId,
        status = AckStatus.DELIVERED,
        timestamp = 2000L,
        signature = ByteArray(64)
    )

    private class FakeClientCore(
        var startCount: Int = 0,
        var stopCount: Int = 0
    ) : KeyMessageCore {
        override fun start(): Result<Unit> {
            startCount++
            return Result.success(Unit)
        }

        override fun stop() {
            stopCount++
        }

        override fun createMessage(to: IdentityId, payload: ByteArray): Result<Message> =
            Result.failure(UnsupportedOperationException())

        override fun sendMessage(message: Message): Result<SendResult> =
            Result.failure(UnsupportedOperationException())

        override fun getMessageState(messageId: MessageId): Result<MessageState> =
            Result.failure(UnsupportedOperationException())

        override fun getConversation(peerId: IdentityId): Result<List<Message>> =
            Result.failure(UnsupportedOperationException())

        override fun registerAckHandler(handler: (Ack) -> Unit) {}
        override fun registerMessageHandler(handler: (Message) -> Unit) {}
        override fun registerStateChangeHandler(handler: (MessageId, MessageState) -> Unit) {}
    }

    private inner class FakeRelayTransport(
        val peerId: IdentityId
    ) : RelayTransport {
        val sentFrames = mutableListOf<ByteArray>()
        var onMessageBytes: ((ByteArray) -> Unit)? = null
        var onAckBytes: ((ByteArray) -> Unit)? = null
        var onStored: ((StoredReceipt) -> Unit)? = null
        var onRelayExpired: ((RelayExpiredNotice) -> Unit)? = null
        val peerOnlineHandlers = mutableListOf<(IdentityId) -> Unit>()
        val peerOfflineHandlers = mutableListOf<(IdentityId) -> Unit>()
        var online = false

        override fun connect(): Result<Unit> {
            online = true
            peerOnlineHandlers.forEach { it(peerId) }
            return Result.success(Unit)
        }

        override fun disconnect() {
            online = false
            peerOfflineHandlers.forEach { it(peerId) }
        }

        override fun send(data: ByteArray, to: IdentityId): Result<Unit> {
            sentFrames.add(data)
            return Result.success(Unit)
        }

        override fun setMessageHandler(handler: (ByteArray) -> Unit) {
            onMessageBytes = handler
        }

        override fun setAckHandler(handler: (ByteArray) -> Unit) {
            onAckBytes = handler
        }

        override fun isOnline(): Boolean = online

        override fun connectionState(): ConnectionState =
            if (online) ConnectionState.ONLINE else ConnectionState.DISCONNECTED

        override fun onStateChange(handler: (ConnectionState) -> Unit) {}
        override fun onPeerOnline(handler: (IdentityId) -> Unit) {
            peerOnlineHandlers.add(handler)
        }

        override fun onPeerOffline(handler: (IdentityId) -> Unit) {
            peerOfflineHandlers.add(handler)
        }

        override fun setStoredHandler(handler: (StoredReceipt) -> Unit) {
            onStored = handler
        }

        override fun setRelayExpiredHandler(handler: (RelayExpiredNotice) -> Unit) {
            onRelayExpired = handler
        }

        fun receiveMessage(msg: Message) {
            onMessageBytes?.invoke(encoder.encodeMessage(msg).getOrThrow())
        }

        fun receiveAck(ack: Ack) {
            onAckBytes?.invoke(encoder.encodeAck(ack).getOrThrow())
        }
    }

    private fun relayNode(store: InMemoryRelayStore = InMemoryRelayStore()) =
        NodeRuntimeImpl(
            identity = identity,
            capabilities = setOf(NodeCapability.RELAY, NodeCapability.STORE_AND_FORWARD),
            relayService = RelayServiceImpl(relayStore = store)
        )

    @Test
    fun `requires at least one capability`() {
        assertThrows(IllegalArgumentException::class.java) {
            NodeRuntimeImpl(identity = identity, capabilities = emptySet())
        }
    }

    @Test
    fun `client capability requires a client core`() {
        assertThrows(IllegalArgumentException::class.java) {
            NodeRuntimeImpl(
                identity = identity,
                capabilities = setOf(NodeCapability.CLIENT),
                clientCore = null
            )
        }
    }

    @Test
    fun `relay capability requires a relay service`() {
        assertThrows(IllegalArgumentException::class.java) {
            NodeRuntimeImpl(
                identity = identity,
                capabilities = setOf(NodeCapability.RELAY),
                relayService = null
            )
        }
    }

    @Test
    fun `start and stop are idempotent`() {
        val core = FakeClientCore()
        val node = NodeRuntimeImpl(
            identity = identity,
            capabilities = setOf(NodeCapability.CLIENT),
            clientCore = core
        )
        node.start().getOrThrow()
        node.start().getOrThrow()
        assertTrue(node.isStarted())
        assertEquals(1, core.startCount)

        node.stop()
        node.stop()
        assertFalse(node.isStarted())
        assertEquals(1, core.stopCount)
    }

    @Test
    fun `relay node stores pass through message for offline recipient`() {
        val node = relayNode()
        val aliceTransport = FakeRelayTransport(alice)
        node.attachPeerTransport(alice, aliceTransport)
        val msg = message(to = bob)
        node.handleInboundMessage(msg).getOrThrow()
        assertTrue(node.relayService!!.relayStore.exists(msg.messageId).getOrThrow())
    }

    @Test
    fun `relay node forwards message to online recipient`() {
        val node = relayNode()
        val aliceTransport = FakeRelayTransport(alice)
        val bobTransport = FakeRelayTransport(bob)
        node.attachPeerTransport(alice, aliceTransport)
        node.attachPeerTransport(bob, bobTransport)

        val msg = message(to = bob)
        node.handleInboundMessage(msg).getOrThrow()
        assertFalse(node.relayService!!.relayStore.exists(msg.messageId).getOrThrow())
        assertTrue(bobTransport.sentFrames.isNotEmpty())
        val forwarded = decoder.decodeMessage(bobTransport.sentFrames.first()).getOrThrow()
        assertEquals(msg.messageId, forwarded.messageId)
    }

    @Test
    fun `relay node sends STORED receipt to sender when storing`() {
        val node = relayNode()
        val aliceTransport = FakeRelayTransport(alice)
        node.attachPeerTransport(alice, aliceTransport)
        val msg = message(to = bob)
        node.handleInboundMessage(msg).getOrThrow()

        val storedFrames = aliceTransport.sentFrames.mapNotNull { controlCodec.decodeStored(it).getOrNull() }
        assertEquals(1, storedFrames.size)
        val receipt = storedFrames.first()
        assertEquals(msg.messageId, receipt.originalMessageId)
        assertEquals(nodeId, receipt.relayNodeId)
        assertEquals(bob, receipt.to)
    }

    @Test
    fun `relay node sends RELAY_ERROR on oversized message`() {
        val node = relayNode()
        val aliceTransport = FakeRelayTransport(alice)
        node.attachPeerTransport(alice, aliceTransport)
        val big = Message(
            messageId = MessageId.random(),
            from = alice,
            to = bob,
            payload = ByteArray((RelayLimits.DEFAULT_MAX_MESSAGE_SIZE + 1).toInt()),
            timestamp = 1000L
        )
        val decision = node.handleInboundMessage(big).getOrThrow()
        assertEquals(RelayDecision.Rejected(FailureCode.MESSAGE_TOO_LARGE), decision)

        val errorFrames = aliceTransport.sentFrames.mapNotNull { controlCodec.decodeError(it).getOrNull() }
        assertEquals(1, errorFrames.size)
        assertEquals(RelayErrorCode.MESSAGE_TOO_LARGE, errorFrames.first().errorCode)
        assertEquals(big.messageId, errorFrames.first().originalMessageId)
    }

    @Test
    fun `deliverStored on reconnect sends stored messages to recipient`() {
        val node = relayNode()
        val aliceTransport = FakeRelayTransport(alice)
        node.attachPeerTransport(alice, aliceTransport)
        val msg = message(to = bob)
        node.handleInboundMessage(msg).getOrThrow()
        assertTrue(node.relayService!!.relayStore.exists(msg.messageId).getOrThrow())

        val bobTransport = FakeRelayTransport(bob)
        node.attachPeerTransport(bob, bobTransport)
        assertTrue(bobTransport.sentFrames.isNotEmpty())
        val delivered = decoder.decodeMessage(bobTransport.sentFrames.first()).getOrThrow()
        assertEquals(msg.messageId, delivered.messageId)
    }

    @Test
    fun `ack forwarding deletes stored copy and reaches sender`() {
        val node = relayNode()
        val aliceTransport = FakeRelayTransport(alice)
        node.attachPeerTransport(alice, aliceTransport)
        val msg = message(to = bob)
        node.handleInboundMessage(msg).getOrThrow()
        assertTrue(node.relayService!!.relayStore.exists(msg.messageId).getOrThrow())

        node.handleInboundAck(ackFor(msg)).getOrThrow()
        assertFalse(node.relayService!!.relayStore.exists(msg.messageId).getOrThrow())

        val ackFrames = aliceTransport.sentFrames.mapNotNull { decoder.decodeAck(it).getOrNull() }
        assertEquals(1, ackFrames.size)
        assertEquals(msg.messageId, ackFrames.first().originalMessageId)
    }

    @Test
    fun `expireStored sends RELAY_EXPIRED to sender`() {
        val store = InMemoryRelayStore(now = { 0L })
        val node = NodeRuntimeImpl(
            identity = identity,
            capabilities = setOf(NodeCapability.RELAY, NodeCapability.STORE_AND_FORWARD),
            relayService = RelayServiceImpl(relayStore = store, now = { 0L })
        )
        val aliceTransport = FakeRelayTransport(alice)
        node.attachPeerTransport(alice, aliceTransport)
        val msg = message(to = bob)
        node.handleInboundMessage(msg).getOrThrow()

        val expired = node.expireStored(RelayLimits.DEFAULT_MAX_MESSAGE_AGE_MS + 1).getOrThrow()
        assertEquals(1, expired.size)

        val noticeFrames = aliceTransport.sentFrames.mapNotNull { controlCodec.decodeRelayExpired(it).getOrNull() }
        assertEquals(1, noticeFrames.size)
        assertEquals(msg.messageId, noticeFrames.first().originalMessageId)
        assertEquals(alice, noticeFrames.first().to)
        assertEquals("TTL_EXPIRED", noticeFrames.first().reason)
    }

    @Test
    fun `client node delivers self addressed message to inbound handlers`() {
        val core = FakeClientCore()
        val node = NodeRuntimeImpl(
            identity = identity,
            capabilities = setOf(NodeCapability.CLIENT),
            clientCore = core
        )
        var delivered: Message? = null
        node.registerInboundHandler { delivered = it }
        val msg = message(to = nodeId)
        node.handleInboundMessage(msg).getOrThrow()
        assertEquals(msg, delivered)
    }

    @Test
    fun `relay node rejects self addressed message`() {
        val node = relayNode()
        val decision = node.handleInboundMessage(message(to = nodeId)).getOrThrow()
        assertEquals(RelayDecision.Rejected(FailureCode.INVALID_MESSAGE), decision)
    }

    @Test
    fun `expireStored returns empty without relay service`() {
        val core = FakeClientCore()
        val node = NodeRuntimeImpl(
            identity = identity,
            capabilities = setOf(NodeCapability.CLIENT),
            clientCore = core
        )
        assertEquals(0, node.expireStored(6000L).getOrThrow().size)
    }

    @Test
    fun `handleInboundAck is a no-op without relay service`() {
        val core = FakeClientCore()
        val node = NodeRuntimeImpl(
            identity = identity,
            capabilities = setOf(NodeCapability.CLIENT),
            clientCore = core
        )
        node.handleInboundAck(ackFor(message(to = bob))).getOrThrow()
    }

    @Test
    fun `peerOffline removes presence so next message is stored`() {
        val node = relayNode()
        val aliceTransport = FakeRelayTransport(alice)
        val bobTransport = FakeRelayTransport(bob)
        node.attachPeerTransport(alice, aliceTransport)
        node.attachPeerTransport(bob, bobTransport)

        val onlineMsg = message(to = bob)
        node.handleInboundMessage(onlineMsg).getOrThrow()
        assertFalse(node.relayService!!.relayStore.exists(onlineMsg.messageId).getOrThrow())

        node.peerOffline(bob)
        val offlineMsg = message(to = bob)
        node.handleInboundMessage(offlineMsg).getOrThrow()
        assertTrue(node.relayService!!.relayStore.exists(offlineMsg.messageId).getOrThrow())
    }
}
