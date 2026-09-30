package com.km.node

import com.km.model.IdentityId

/**
 * Implementacion fake [TransportBackend] para tests.
 *
 * No realiza operaciones de red reales. Utilizada por los 641+ tests
 * deterministas del core.
 *
 * INVARIANTE: el fake no introduce seguridad ni identidad propia.
 * Todas las decisiones de seguridad permanecen en [PeerTransportManager].
 *
 * Comportamiento:
 * - Todos los metodos retornan exito sin efecto secundario.
 * - `sendData`: no operacion (el manager ya invoco sus handlers).
 */
class FakeTransportBackend : TransportBackend {

    override var eventCallbacks: TransportEventCallbacks = TransportEventCallbacks()

    override fun initialize(): TransportResult<Unit> =
        TransportResult.Success(Unit)

    override fun onCreateBinding(localIdentity: IdentityId, remotePeerId: IdentityId): TransportResult<Unit> =
        TransportResult.Success(Unit)

    override fun onLocalSdp(localIdentity: IdentityId, remotePeerId: IdentityId, sdp: String): TransportResult<Unit> =
        TransportResult.Success(Unit)

    override fun onRemoteSdp(localIdentity: IdentityId, remotePeerId: IdentityId, sdp: String): TransportResult<Unit> =
        TransportResult.Success(Unit)

    override fun onIceCandidate(localIdentity: IdentityId, remotePeerId: IdentityId, candidate: String): TransportResult<Unit> =
        TransportResult.Success(Unit)

    override fun sendData(remotePeerId: IdentityId, data: ByteArray): TransportResult<Unit> =
        TransportResult.Success(Unit)

    override fun onCloseBinding(localIdentity: IdentityId, remotePeerId: IdentityId): TransportResult<Unit> =
        TransportResult.Success(Unit)

    override fun shutdown(): TransportResult<Unit> =
        TransportResult.Success(Unit)
}