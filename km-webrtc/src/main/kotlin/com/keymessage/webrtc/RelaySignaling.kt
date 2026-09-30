package com.keymessage.webrtc

import com.keymessage.core.model.IdentityId
import com.keymessage.core.node.RelayServer
import com.keymessage.core.node.RelaySignalMessage
import com.keymessage.core.node.TransportBackend
import com.keymessage.core.node.TransportError
import com.keymessage.core.node.TransportResult

/**
 * Señalizacion WebRTC transportada por el [RelayServer] real de km-core.
 *
 * KM-0003: el relay transporta SDP/ICE entre peers autenticados. NO es una
 * autoridad de identidad peer-to-peer y NO transporta datos de aplicacion.
 *
 * <pre>
 *   Alice ──SdpOffer──► RelayServer ──SdpOffer──► Bob
 *   Alice ◄─SdpAnswer── RelayServer ◄─SdpAnswer── Bob
 *
 *   Alice ═══ DataChannel (bytes) ═══ Bob        ← el relay NO participa
 * </pre>
 *
 * El `from` de cada mensaje SIEMPRE es la identidad local de la sesion
 * autenticada en el relay. El [RelayServer] verifica esa correspondencia
 * y rechaza el spoofing; el backend nunca puede elegir el emisor.
 */
class RelaySignaling(
    /** Identidad local, ya autenticada en el relay. */
    private val localIdentityId: IdentityId,
    /** Peer remoto autenticado (destinatario del signaling). */
    private val remotePeerId: IdentityId,
    private val relayServer: RelayServer,
    /** Rol WebRTC: determina si enviamos Offer o Answer. */
    private val role: Role,
    private val now: () -> Long = { 0L },
    /** Registro de toda la senalizacion que atraviesa el relay (tests/observabilidad). */
    private val sentLog: MutableList<RelaySignalMessage> = mutableListOf(),
) : PeerSignaling {

    enum class Role { OFFERER, ANSWERER }

    /** Callback al recibir SDP remota (offer si somos answerer, answer si offerer). */
    @Volatile var onRemoteSdp: ((String) -> Unit)? = null

    /** Callback al recibir un candidato ICE. */
    @Volatile var onRemoteIce: ((String) -> Unit)? = null

    /**
     * Envia la SDP local al peer a traves del relay.
     *
     * El tipo de mensaje depende del rol: el offerer manda Offer y el
     * answerer manda Answer. El relay solo reenvia; no interpreta.
     */
    override fun sendSdp(toPeer: IdentityId, sdp: String) {
        if (toPeer != remotePeerId) return
        val message = when (role) {
            Role.OFFERER -> RelaySignalMessage.SdpOffer(localIdentityId, toPeer, sdp, now())
            Role.ANSWERER -> RelaySignalMessage.SdpAnswer(localIdentityId, toPeer, sdp, now())
        }
        deliver(message)
    }

    /**
     * Envia un candidato ICE al peer a traves del relay.
     *
     * El candidato viaja como texto opaco. El relay no lo interpreta.
     */
    override fun sendIce(toPeer: IdentityId, candidate: String) {
        if (toPeer != remotePeerId) return
        deliver(RelaySignalMessage.IceCandidate(localIdentityId, toPeer, candidate, now()))
    }

    /**
     * Entrega el mensaje al relay, que verifica la sesion autenticada
     * y enruta al destinatario.
     */
    private fun deliver(message: RelaySignalMessage) {
        val result = relayServer.handleSignal(localIdentityId, message)
        if (result.isSuccess) {
            synchronized(sentLog) { sentLog.add(message) }
        } else {
            // Un rechazo del relay (sesion expirada, destinatario offline,
            // spoofing) no debe propagarse como excepcion hacia WebRTC.
            lastError = (result as com.keymessage.core.node.RelayServerResult.Failure).error
        }
    }

    /** Ultimo error del relay, para diagnostico. */
    @Volatile var lastError: com.keymessage.core.node.RelayServerError? = null

    /**
     * Procesa una senal recibida del relay (invocado por el handler de
     * presencia/registro del peer).
     *
     * Solo acepta mensajes dirigidos a la identidad local; cualquier otro
     * se descarta aqui.
     */
    fun receive(signal: RelaySignalMessage) {
        if (signal.to != localIdentityId) return
        when (signal) {
            is RelaySignalMessage.SdpOffer -> onRemoteSdp?.invoke(signal.sdp)
            is RelaySignalMessage.SdpAnswer -> onRemoteSdp?.invoke(signal.sdp)
            is RelaySignalMessage.IceCandidate -> onRemoteIce?.invoke(signal.candidate)
            // ContactExchange y presencia no forman parte de 3N.5.
            is RelaySignalMessage.PeerOnline -> Unit
            is RelaySignalMessage.PeerOffline -> Unit
            is RelaySignalMessage.ContactExchange -> Unit
        }
    }
}
