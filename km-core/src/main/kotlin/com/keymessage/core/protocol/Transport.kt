package com.keymessage.core.protocol

import com.keymessage.core.model.IdentityId

enum class ConnectionState {
    DISCONNECTED,
    CONNECTING,
    AUTHENTICATING,
    ONLINE,
    RECONNECTING
}

interface Transport {
    fun connect(): Result<Unit>
    fun disconnect()
    fun send(data: ByteArray, to: IdentityId): Result<Unit>
    fun setMessageHandler(handler: (ByteArray) -> Unit)
    fun setAckHandler(handler: (ByteArray) -> Unit)
    fun isOnline(): Boolean
    fun connectionState(): ConnectionState
    fun onStateChange(handler: (ConnectionState) -> Unit)
    fun onPeerOnline(handler: (IdentityId) -> Unit)
    fun onPeerOffline(handler: (IdentityId) -> Unit)
}
