package com.km.node

import com.km.model.IdentityId

/**
 * Mensajes de senalizacion ruteados por el relay.
 *
 * KM-0003: el relay no interpreta el contenido de SDP/ICE/ContactBundle,
 * solo lo reenvia entre peers autenticados. La verificacion de identidad
 * ocurre en RelayServer.handleSignal(), no en el mensaje mismo.
 *
 * INVARIANTE: `from` se verifica contra la sesion autenticada antes de
 * rutear. Un mensaje con `from` falsificado es rechazado.
 */
sealed class RelaySignalMessage {

    /** `from` autenticado que envia la senal. */
    abstract val from: IdentityId
    /** `to` peer destino. */
    abstract val to: IdentityId
    /** Timestamp epoch millis. */
    abstract val timestamp: Long

    /**
     * Notificacion de presencia: peer conectado al relay.
     */
    data class PeerOnline(
        override val from: IdentityId,
        override val to: IdentityId,
        override val timestamp: Long,
    ) : RelaySignalMessage()

    /**
     * Notificacion de presencia: peer desconectado del relay.
     */
    data class PeerOffline(
        override val from: IdentityId,
        override val to: IdentityId,
        override val timestamp: Long,
    ) : RelaySignalMessage()

    /**
     * Oferta SDP para WebRTC.
     */
    data class SdpOffer(
        override val from: IdentityId,
        override val to: IdentityId,
        val sdp: String,
        override val timestamp: Long,
    ) : RelaySignalMessage()

    /**
     * Respuesta SDP para WebRTC.
     */
    data class SdpAnswer(
        override val from: IdentityId,
        override val to: IdentityId,
        val sdp: String,
        override val timestamp: Long,
    ) : RelaySignalMessage()

    /**
     * Candidato ICE para WebRTC.
     */
    data class IceCandidate(
        override val from: IdentityId,
        override val to: IdentityId,
        val candidate: String,
        override val timestamp: Long,
    ) : RelaySignalMessage()

    /**
     * Intercambio de ContactBundle entre peers.
     */
    data class ContactExchange(
        override val from: IdentityId,
        override val to: IdentityId,
        val contactBundle: ByteArray,
        override val timestamp: Long,
    ) : RelaySignalMessage()
}