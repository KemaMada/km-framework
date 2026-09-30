package com.keymessage.core.node

import com.keymessage.core.model.IdentityId
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Backend de transporte programable, compartido por los tests de 3Q.
 *
 * EXISTE POR UNA RAZON CONCRETA: [FakeTransportBackend] devuelve `Success`
 * SIEMPRE. Como es el backend por defecto de [PeerTransportManager], el camino
 * de fallo era estructuralmente intestable antes de 3Q.1: ningun test podia
 * observar que el manager|reportaba exito cuando el medio rechazaba el envio.
 *
 * Este doble permite decidir el resultado de cada operacion, y registra lo que
 * cada transporte recibio realmente, que es lo que permite demostrar que el
 * MISMO ciphertext sale por el eslabon que succeed (CHAIN-08).
 */
class ScriptableTransportBackend(
    override val name: String = "scriptable",
) : TransportBackend {

    override var eventCallbacks: TransportEventCallbacks = TransportEventCallbacks()

    /** Resultado de `sendData`. Por defecto acepta. */
    var sendOutcome: TransportResult<Unit> = TransportResult.Success(Unit)

    /** Excepcion que `sendData` debe lanzar, si se requiere. */
    var sendThrows: Throwable? = null

    /** Payloads que este transporte recibio de verdad. */
    val sentPayloads = CopyOnWriteArrayList<ByteArray>()

    /** Veces que se invoco `sendData`. */
    @Volatile var sendCount: Int = 0
        private set

    /** Operaciones de establecimiento que este transporte recibio. */
    val establishmentOps = CopyOnWriteArrayList<String>()

    @Volatile var initialized = false
        private set

    @Volatile var shutdownCalled = false
        private set

    override fun initialize(): TransportResult<Unit> {
        initialized = true
        return TransportResult.Success(Unit)
    }

    override fun onCreateBinding(localIdentity: IdentityId, remotePeerId: IdentityId): TransportResult<Unit> {
        establishmentOps.add("onCreateBinding")
        return TransportResult.Success(Unit)
    }

    override fun onLocalSdp(localIdentity: IdentityId, remotePeerId: IdentityId, sdp: String): TransportResult<Unit> {
        establishmentOps.add("onLocalSdp")
        return TransportResult.Success(Unit)
    }

    override fun onRemoteSdp(localIdentity: IdentityId, remotePeerId: IdentityId, sdp: String): TransportResult<Unit> {
        establishmentOps.add("onRemoteSdp")
        return TransportResult.Success(Unit)
    }

    override fun onIceCandidate(localIdentity: IdentityId, remotePeerId: IdentityId, candidate: String): TransportResult<Unit> {
        establishmentOps.add("onIceCandidate")
        return TransportResult.Success(Unit)
    }

    override fun onCloseBinding(localIdentity: IdentityId, remotePeerId: IdentityId): TransportResult<Unit> {
        establishmentOps.add("onCloseBinding")
        return TransportResult.Success(Unit)
    }

    override fun shutdown(): TransportResult<Unit> {
        shutdownCalled = true
        return TransportResult.Success(Unit)
    }

    override fun sendData(remotePeerId: IdentityId, data: ByteArray): TransportResult<Unit> {
        sendCount++
        sendThrows?.let { throw it }
        if (sendOutcome is TransportResult.Success) sentPayloads.add(data.copyOf())
        return sendOutcome
    }

    /** Programa un fallo del MEDIO: autoriza fallback. */
    fun failWithTransport(error: TransportError = TransportError.BACKEND_SEND_FAILED, why: String = "medio caido") {
        sendOutcome = TransportResult.Failure(error, why)
    }

    /** Programa un fallo de la categoria indicada. */
    fun failWith(category: TransportErrorCategory, why: String = "fallo programado") {
        val error = TransportError.entries.firstOrNull { it.category == category }
            ?: error("ningun TransportError de categoria $category")
        sendOutcome = TransportResult.Failure(error, why)
    }

    /** Programa un rechazo de envio del backend. */
    fun accept() {
        sendOutcome = TransportResult.Success(Unit)
    }
}
