package com.keymessage.core.node

import com.keymessage.core.api.KeyMessageCore
import com.keymessage.core.model.Ack
import com.keymessage.core.model.IdentityId
import com.keymessage.core.model.Message
import com.keymessage.core.model.NodeCapability
import com.keymessage.core.model.NodeIdentity
import com.keymessage.core.model.StoredMessage

interface NodeRuntime {
    val identity: NodeIdentity
    val capabilities: Set<NodeCapability>
    val clientCore: KeyMessageCore?
    val relayService: RelayService?

    fun start(): Result<Unit>
    fun stop()
    fun isStarted(): Boolean

    fun attachPeerTransport(peerId: IdentityId, transport: RelayTransport)
    fun detachPeerTransport(peerId: IdentityId)
    fun peerOnline(peerId: IdentityId)
    fun peerOffline(peerId: IdentityId)

    fun handleInboundMessage(message: Message): Result<RelayDecision>
    fun handleInboundAck(ack: Ack): Result<Unit>
    fun expireStored(now: Long): Result<List<StoredMessage>>
    fun registerInboundHandler(handler: (Message) -> Unit)
}