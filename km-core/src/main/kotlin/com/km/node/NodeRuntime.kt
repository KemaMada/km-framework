package com.km.node

import com.km.api.KeyMessageCore
import com.km.model.Ack
import com.km.model.IdentityId
import com.km.model.Message
import com.km.model.NodeCapability
import com.km.model.NodeIdentity
import com.km.model.StoredMessage

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