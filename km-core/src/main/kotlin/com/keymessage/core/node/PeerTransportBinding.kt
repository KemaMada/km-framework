package com.keymessage.core.node

/**
 * Estados del ciclo de vida de un transporte peer-to-peer (WebRTC).
 *
 * KM-0003: el transporte peer-to-peer es independiente del relay.
 * El relay solo participa en senalizacion (SDP/ICE); una vez que
 * el DataChannel se establece, la comunicacion es directa.
 *
 * INVARIANTE: la transicion CONNECTED -> CONNECTING no es legal.
 * Una reconexion requiere cerrar y crear un nuevo transporte.
 */
enum class PeerTransportState {
    /** Transporte creado pero sin negociacion iniciada. */
    NEW,
    /** Oferta SDP enviada, esperando respuesta. */
    OFFER_SENT,
    /** Respuesta SDP recibida, negociando ICE. */
    ANSWER_RECEIVED,
    /** Recolectando/verificando candidatos ICE. */
    NEGOTIATING,
    /** DataChannel abierto y operativo. */
    CONNECTED,
    /** Transporte cerrado voluntariamente. */
    CLOSED,
    /** Transporte fallido (timeout, error ICE, desconexion). */
    FAILED,
}

/**
 * Par de sesion WebRTC: oferta + respuesta SDP.
 *
 * Inmutable: una vez establecido, el par no puede modificarse.
 * Para re-negociar, debe crearse un nuevo par.
 */
data class SdpPair(
    val localOffer: String,
    val remoteAnswer: String,
    val createdAt: Long,
)

/**
 * Binding entre PeerContext y un transporte peer-to-peer.
 *
 * INVARIANTES:
 * - peerContext DEBE estar AUTHENTICATED.
 * - peerContext.remotePeerId es el unico destinatario valido para SDP/ICE.
 * - Un solo binding activo por (localIdentity, remotePeerId) — idempotencia.
 * - Si peerContext se cierra, el binding se invalida.
 * - negotiationHash queda ligado al binding.
 */
data class PeerTransportBinding(
    val peerContext: PeerContext,
    val state: PeerTransportState,
    val createdAt: Long,
    val sdp: SdpPair? = null,
    val iceCandidates: List<String> = emptyList(),
    val dataReceived: Long = 0L,
) {
    init {
        require(peerContext.authSession.state == com.keymessage.core.auth.AuthSessionState.AUTHENTICATED) {
            "PeerContext debe estar AUTHENTICATED para crear binding"
        }
    }

    /** Crea un binding con oferta SDP enviada. */
    fun withOffer(offer: String, now: Long): PeerTransportBinding {
        require(state == PeerTransportState.NEW) { "solo NEW puede enviar oferta, estado actual: $state" }
        return copy(
            state = PeerTransportState.OFFER_SENT,
            sdp = SdpPair(localOffer = offer, remoteAnswer = "", createdAt = now),
        )
    }

    /** Acepta respuesta SDP. */
    fun withAnswer(answer: String, now: Long): PeerTransportBinding {
        require(state == PeerTransportState.OFFER_SENT) { "solo OFFER_SENT puede recibir answer, estado actual: $state" }
        val currentSdp = sdp ?: throw IllegalStateException("no hay SDP para recibir answer")
        return copy(
            state = PeerTransportState.ANSWER_RECEIVED,
            sdp = currentSdp.copy(remoteAnswer = answer),
        )
    }

    /** Anade un candidato ICE. */
    fun withIceCandidate(candidate: String): PeerTransportBinding {
        require(state in listOf(PeerTransportState.OFFER_SENT, PeerTransportState.ANSWER_RECEIVED, PeerTransportState.NEGOTIATING)) {
            "ICE candidates solo en negociacion, estado actual: $state"
        }
        return copy(
            state = PeerTransportState.NEGOTIATING,
            iceCandidates = iceCandidates + candidate,
        )
    }

    /** Marca como CONNECTED. */
    fun withConnected(): PeerTransportBinding {
        require(state in listOf(PeerTransportState.OFFER_SENT, PeerTransportState.ANSWER_RECEIVED, PeerTransportState.NEGOTIATING)) {
            "transicion a CONNECTED solo desde OFFER_SENT/ANSWER_RECEIVED/NEGOTIATING, estado actual: $state"
        }
        return copy(state = PeerTransportState.CONNECTED)
    }

    /** Cierra el transporte. */
    fun close(): PeerTransportBinding = copy(state = PeerTransportState.CLOSED)

    /** Marca como fallido. */
    fun fail(): PeerTransportBinding = copy(state = PeerTransportState.FAILED)

    val isActive: Boolean get() = state in listOf(
        PeerTransportState.NEW,
        PeerTransportState.OFFER_SENT,
        PeerTransportState.ANSWER_RECEIVED,
        PeerTransportState.NEGOTIATING,
        PeerTransportState.CONNECTED,
    )
}