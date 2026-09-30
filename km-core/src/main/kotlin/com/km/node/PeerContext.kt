package com.km.node

import com.km.auth.AuthSession
import com.km.auth.AuthChallenge
import com.km.auth.AuthResponse
import com.km.auth.AuthOk
import com.km.auth.AuthSessionState
import com.km.auth.TranscriptBuilder
import com.km.negotiation.NegotiationResult
import com.km.negotiation.Km7Negotiation
import com.km.negotiation.CapabilitySet
import com.km.identity.ContactBundle
import com.km.model.IdentityId
import com.km.model.NodeAnnouncement
import com.km.model.NodeIdentity

/**
 * Contexto completo de un peer autenticado.
 *
 * Invariante: solo se puede crear via [authenticated], que exige
 * que la identidad haya pasado por verificación criptográfica.
 */
data class PeerContext(
    val remotePeerId: IdentityId,
    val publicKey: ByteArray,
    val announcement: NodeAnnouncement,
    val authSession: AuthSession,
    val negotiationResult: NegotiationResult? = null,
    val negotiationHash: ByteArray? = null,
    val contactBundle: ContactBundle? = null,
) {
    companion object {
        /**
         * Construye un PeerContext AUTENTICADO.
         *
         * Solo transiciona a autenticado si:
         *   1. authSession.state == AUTHENTICATED
         *   2. remotePeerId == authSession.peerIdentityId
         *   3. announcement.identity.nodeId == remotePeerId
         *
         * @throws IllegalArgumentException si alguna invariante falla.
         */
        fun authenticated(
            remotePeerId: IdentityId,
            publicKey: ByteArray,
            announcement: NodeAnnouncement,
            authSession: AuthSession,
        ): PeerContext {
            require(authSession.state == AuthSessionState.AUTHENTICATED) {
                "PeerContext solo se puede crear desde una sesion AUTHENTICATED (actual: ${authSession.state})"
            }
            require(announcement.identity.nodeId == remotePeerId) {
                "announcement.nodeId (${announcement.identity.nodeId}) debe coincidir con remotePeerId ($remotePeerId)"
            }
            return PeerContext(
                remotePeerId = remotePeerId,
                publicKey = publicKey,
                announcement = announcement,
                authSession = authSession,
            )
        }
    }

    /**
     * Vincula la negociacion KM-0007 al contexto autenticado.
     *
     * @throws IllegalStateException si ya hay una negociacion vinculada.
     */
    fun withNegotiation(negotiationResult: NegotiationResult): PeerContext {
        check(this.negotiationResult == null) { "ya existe una negociacion vinculada a este peer" }
        val hash = Km7Negotiation.negotiationHash(negotiationResult)
        return copy(
            negotiationResult = negotiationResult,
            negotiationHash = hash,
        )
    }

    /**
     * Vincula un ContactBundle al contexto autenticado.
     */
    fun withContactBundle(bundle: ContactBundle): PeerContext {
        return copy(contactBundle = bundle)
    }
}