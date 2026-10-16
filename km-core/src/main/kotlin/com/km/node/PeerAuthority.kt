package com.km.node

import com.km.auth.AuthSession
import com.km.auth.AuthSessionState
import com.km.identity.VerifiedContactBundle
import com.km.model.IdentityId
import com.km.model.NodeAnnouncement

/**
 * Que autoridad tiene el par remoto de una sesion 1:1, y solo eso.
 *
 * ## EL TIPO SUMO, Y POR QUE ES SUMO Y NO UNA CLASE CON DOS CAMPOS
 *
 * Antes de esto, `PeerContext` llevaba `authSession: AuthSession` y
 * `announcement: NodeAnnouncement` OBLIGATORIOS, mas un `contactBundle` opcional
 * que se anadia despues con `withContactBundle`. Eso describia a un par de tres
 * maneras a la vez y dejaba abierta la combinacion que no significa nada: un
 * `AuthSession` de nodo junto a un bundle de contacto. Quien leyera el contexto
 * no podia saber si estaba hablando con un nodo o con una persona.
 *
 * Aqui hay dos casos y solo dos, cerrados por el compilador:
 *
 * - [NodeAuthenticated]: el par paso el handshake de nodo KM-0002 y trae su
 *   [NodeAnnouncement] firmado. Es un nodo.
 * - [ContactVerified]: el par se identifica con un `ContactBundle` que ya
 *   pasó [com.km.identity.ContactBundle.verifyBundle]. Es una persona.
 *
 * ## LO QUE EL TIPO NO EXPONE, Y POR QUE
 *
 * - **No hay `isNode`, ni `isAuthenticated`, ni `capabilities`.** El unico
 *   predicado se llama [authorizesPeerSession] y responde a una pregunta
 *   ACOTADA: autoriza la sesion 1:1 y su binding. Si el unico booleano
 *   respondiera "esta autenticado", el compilers no impediria reutilizarlo en
 *   una superficie de nodo (rele, store-and-forward, DHT) y la
 *   autenticacion de nodo se saltaria sin que nadie lo note.
 * - **[ContactVerified] no produce ni contiene un [NodeAnnouncement]** y no
 *   ofrece ninguna via de vuelta a [NodeAuthenticated]. Las superficies de rol
 *   (`PeerRoleDirectory.record`) consumen `NodeAnnouncement`, luego no hay
 *   conversion posible: la no-negociabilidad es ESTRUCTURAL, no disciplina.
 * - **No guarda el documento crudo.** El mapa verificado no sale de
 *   [VerifiedContactBundle]; lo que se expone son accesores tipados.
 *
 * ## POR QUE `authorizesPeerSession()` CONSULTA EL ESTADO VIVO
 *
 * Porque una foto es falsificable por el paso del tiempo: si el predicado
 * guardase "se autentico a las 12:00", un `AuthSession` que despues recibe un
 * `AUTH_FAIL` seguiria autorizando. Preguntando al `AuthSession` cada vez, el
 * rechazo del mismo contexto tras `receiveAuthFail()` es inmediato.
 */
sealed interface PeerAuthority {

    /**
     * Autoriza a abrir y usar la sesion 1:1 con este par, y su binding.
     *
     * NO contesta "el par esta autenticado": contesta una sola pregunta, la de
     * esta sesion y este binding. Ese es el limite que hace el nombre honesto.
     */
    fun authorizesPeerSession(): Boolean

    /**
     * Autoridad de NODO: el handshake KM-0002 completo, con anuncio firmado.
     *
     * Constructor privado: la unica puerta es [of], que concentra las dos
     * invariantes de `PeerContext.authenticated` —estado vivo `AUTHENTICATED` y
     * `announcement.nodeId == remotePeerId`— en un unico punto donde se pueden
     * comprobar. Un choke point se revisa; cinco, no.
     */
    class NodeAuthenticated private constructor(
        val authSession: AuthSession,
        val announcement: NodeAnnouncement,
    ) : PeerAuthority {

        /**
         * Estado VIVO del handshake, no una foto: una sesion que luego recibe
         * `AUTH_FAIL` deja de autorizar en el mismo instante.
         */
        override fun authorizesPeerSession(): Boolean =
            authSession.state == AuthSessionState.AUTHENTICATED

        companion object {
            /**
             * UNICA forma de construir autoridad de nodo.
             *
             * Absorbe los dos `require` que vivian en
             * `PeerContext.authenticated`, con el MISMO tipo de excepcion y los
             * MISMOS mensajes, para que los tests que los comprueban sigan
             * viendo lo que veian.
             *
             * @throws IllegalArgumentException si el estado no es
             *   `AUTHENTICATED`, o si el anuncio no es del par indicado.
             */
            fun of(
                remotePeerId: IdentityId,
                announcement: NodeAnnouncement,
                authSession: AuthSession,
            ): NodeAuthenticated {
                require(authSession.state == AuthSessionState.AUTHENTICATED) {
                    "PeerContext solo se puede crear desde una sesion AUTHENTICATED (actual: ${authSession.state})"
                }
                require(announcement.identity.nodeId == remotePeerId) {
                    "announcement.nodeId (${announcement.identity.nodeId}) debe coincidir con remotePeerId ($remotePeerId)"
                }
                return NodeAuthenticated(authSession, announcement)
            }
        }

        override fun toString(): String =
            "NodeAuthenticated(${announcement.identity.nodeId}, state=${authSession.state})"
    }

    /**
     * Autoridad de CONTACTO: un bundle que ya verifico su firma.
     *
     * Constructor privado y las dos fabricas DENTRO del tipo, el mismo patron
     * que [VerifiedContactBundle.verified]: no hay forma de obtener autoridad de
     * contacto sin un bundle verificado, y no hay bundle verificado sin pasar
     * por `ContactBundle.verifyBundle`.
     *
     * Lo que autoriza NO incluye: figurar en el directorio de roles, relevar,
     * hacer store-and-forward o participar en el DHT. Esos siguen exigiendo el
     * handshake de nodo, y no por una convencion: la superficie de roles pide
     * un [NodeAnnouncement], que esta clase no puede producir.
     */
    class ContactVerified private constructor(
        val bundle: VerifiedContactBundle,
    ) : PeerAuthority {

        /**
         * Un bundle verificado autoriza la sesion 1:1 y nada mas.
         *
         * Aqui la respuesta es constante, y a proposito: la invariante la
         * sostiene el TIPO (el constructor es privado), no una comprobacion
         * repetida en cada guard.
         */
        override fun authorizesPeerSession(): Boolean = true

        companion object {
            /**
             * Verifica el documento CRUDO y devuelve autoridad de contacto.
             *
             * Es el punto de entrada de un `.kmc` o un QR: verifica primero y
             * envuelve despues, luego una firma alterada o una raiz inesperada
             * no producen autoridad.
             *
             * @throws com.km.identity.KmIdVerificationException si el bundle no
             *   verifica.
             */
            fun verified(
                raw: Map<String, Any?>,
                expectedIdentityRoot: ByteArray? = null,
            ): ContactVerified =
                ContactVerified(VerifiedContactBundle.verified(raw, expectedIdentityRoot))

            /**
             * Envuelve un bundle YA verificado.
             *
             * No vuelve a verificar: [VerifiedContactBundle] no puede existir sin
             * haber pasado por el verificador, luego verificar otra vez solo
             * seria trabajo y ninguna garantia.
             */
            fun of(bundle: VerifiedContactBundle): ContactVerified = ContactVerified(bundle)
        }

        override fun toString(): String = "ContactVerified(${bundle.subjectDeviceId.size}B device)"
    }
}