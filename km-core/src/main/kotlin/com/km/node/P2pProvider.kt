package com.km.node

import com.km.model.IdentityId

/**
 * Rol SDP de este extremo en la negociacion P2P.
 *
 * Se decide por orden LEXICOGRAFICO de `identityId` (menor = [OFFERER]), y no
 * por quien llega primero: un tie-break temporal cambia segun la red, produce
 * glare intermitente y hace que el mismo escenario pase o falle entre
 * ejecuciones.
 */
enum class P2pRole {
    OFFERER,
    ANSWERER,
    ;

    companion object {
        /**
         * Rol determinista para el par.
         *
         * Comparacion de strings hex normalizados (`IdentityId` se guarda en
         * minuscula), de modo que no depende del locale ni de bytes.
         */
        fun forPair(localIdentity: IdentityId, remotePeerId: IdentityId): P2pRole =
            if (localIdentity.value < remotePeerId.value) OFFERER else ANSWERER
    }
}

/**
 * Puerto de senalizacion del par: SDP e ICE hacia/desde el otro extremo.
 *
 * ## Por que un puerto y no el motor del relay
 *
 * El compositor del tier P2P (`km-transport`) NO puede depender de
 * `km-webrtc`: la flecha va en un solo sentido. Este puerto es lo que permite
 * que el coordinador conduzca una negociacion sin conocer el medio, y que el
 * escritorio inyecte la implementacion real sobre `JvmRelayTransport`.
 *
 * ## Contrato
 *
 * - [sendSignal] devuelve `false` si la senal no salio al cable; no lanza.
 * - [onSignal] es MULTI-suscriptor: un consumidor mas no puede pisar al
 *   anterior. El emisor del puerto decide el despacho; aqui solo se registra.
 * - `sdp` y `candidate` son mutuamente excluyentes por `kind`
 *   (`sdp_offer`/`sdp_answer` llevan SDP; `ice_candidate` lleva candidato).
 */
interface PeerSignalPort {

    /**
     * Emite una senal al par.
     *
     * @param kind uno de los `kind` del dialecto (`sdp_offer`, `sdp_answer`,
     *   `ice_candidate`).
     * @param sdp SDP, solo para los `kind` de descripcion.
     * @param candidate candidato ICE ya codificado, solo para `ice_candidate`.
     * @return `true` si el frame salio; `false` si no (sin lanzar).
     */
    fun sendSignal(to: IdentityId, kind: String, sdp: String? = null, candidate: String? = null): Boolean

    /**
     * Registra un consumidor de senales entrantes.
     *
     * @return funcion que DA DE BAJA el consumidor. Omitirla no rompe la
     *   llamada, pero un establecimiento P2P que se reintenta cada 60 s dejaria
     *   un handler por intento procesando senales de pares ya cerrados. Quien
     *   se suscribe en nombre de un recurso con vida propia debe cancelar en su
     *   cierre.
     */
    fun onSignal(
        handler: (from: IdentityId, kind: String, sdp: String?, candidate: String?) -> Unit,
    ): () -> Unit
}

/**
 * Los `kind` de senalizacion que viajan por [PeerSignalPort].
 *
 * Viven en `km-core` y no en un modulo de red porque los DOS extremos del
 * puerto los nombran: el establecimiento de `km-webrtc` al emitir y el
 * compositor de `km-transport` al traducir. Duplicarlos por modulo haria que un
 * typo en un lado compilara y no llegara nunca.
 *
 * El dialecto del relay declara los mismos valores en `RelayDataCodec`; ese es
 * el unico sitio que los escribe en el cable.
 */
object P2pSignalKind {
    const val SDP_OFFER: String = "sdp_offer"
    const val SDP_ANSWER: String = "sdp_answer"
    const val ICE_CANDIDATE: String = "ice_candidate"
}

/**
 * Puerto del medio P2P: construye el backend y el establecimiento de un par.
 *
 * `km-core` declara el contrato; `km-webrtc` lo implementa. El compositor de
 * `km-transport` solo ve este puerto, asi que puede probarse con un fake y no
 * arrastra JNI.
 */
interface P2pProvider {

    /**
     * El backend del medio.
     *
     * Es UNO solo para todos los pares: el backend ya multiplexa por
     * `(localIdentity, remotePeerId)`. Crear uno por par abriria una
     * `PeerConnectionFactory` por vecino.
     */
    fun backend(): TransportBackend

    /**
     * Establecimiento FRESCO para un par.
     *
     * @param remotePeerId par remoto ya autenticado por la capa de sesion.
     * @param role rol SDP derivado de [P2pRole.forPair].
     * @param signals puerto por el que viajan SDP e ICE.
     */
    fun link(remotePeerId: IdentityId, role: P2pRole, signals: PeerSignalPort): TransportEstablishment
}
