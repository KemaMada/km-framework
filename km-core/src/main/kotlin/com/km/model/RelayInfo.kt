package com.km.model

data class RelayInfo(
    val identity: NodeIdentity,
    val endpoint: NodeEndpoint,
    val capabilities: Set<NodeCapability>,
    val limits: RelayLimits,
    val latencyMs: Long = -1,
    val lastSeenMs: Long = 0,
    val failureCount: Int = 0,
    val isTrusted: Boolean = false
) {
    val canStoreAndForward: Boolean
        get() = NodeCapability.STORE_AND_FORWARD in capabilities

    val canRelay: Boolean
        get() = NodeCapability.RELAY in capabilities
}