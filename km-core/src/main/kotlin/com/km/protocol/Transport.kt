package com.km.protocol

import com.km.model.IdentityId

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

    /** Carga de aplicacion: un `Message` de `km-core`. Sale como `MESSAGE`. */
    fun send(data: ByteArray, to: IdentityId): Result<Unit>

    /**
     * Un `Ack` de `km-core`, por un CANAL DISTINTO del de [send].
     *
     * POR QUE EXISTE Y POR QUE NO ES UN `send` MAS
     *
     * Antes de esto, un `ACK` salia por [send], y un transporte que envuelve
     * [send] en un envelope `MESSAGE` lo convertia en un `MESSAGE` mas. El
     * receptor no lo reconocia como `ACK`: lo entregaba a `messageHandler` como
     * un mensaje entrante MAS, y ese mensaje entrante generaba a su vez otro
     * `ACK`. Ese es el ciclo de amplificacion, y no se arregla cifrando otra vez
     * ni reintentando: se arregla dejando de compartir el camino.
     *
     * El contrato, entonces, es que [sendAck] NUNCA se implementa delegando en
     * [send]. Quien lo implementa tiene que emitir el discriminante `ACK`.
     */
    fun sendAck(ackBytes: ByteArray): Result<Unit>

    fun setMessageHandler(handler: (ByteArray) -> Unit)
    fun setAckHandler(handler: (ByteArray) -> Unit)
    fun isOnline(): Boolean
    fun connectionState(): ConnectionState
    fun onStateChange(handler: (ConnectionState) -> Unit)
    fun onPeerOnline(handler: (IdentityId) -> Unit)
    fun onPeerOffline(handler: (IdentityId) -> Unit)
}
