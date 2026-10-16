package com.km.node

import com.km.auth.AuthSession
import com.km.identity.KmIds
import com.km.identity.VerifiedContactBundle
import com.km.model.IdentityId
import com.km.model.NodeAnnouncement
import com.km.model.toHexString
import com.km.negotiation.Km7Negotiation
import com.km.negotiation.NegotiationResult

/**
 * Contexto completo de un par: con quien hablo, y con que autoridad.
 *
 * ## LA AUTORIDAD, Y POR QUE ES UN TIPO Y NO TRES CAMPOS
 *
 * Este contexto lleva [authority], que es un [PeerAuthority]: o
 * [PeerAuthority.NodeAuthenticated] (handshake de nodo) o
 * [PeerAuthority.ContactVerified] (bundle de contacto verificado). Los dos
 * casos son excluyentes y el compilador lo garantiza.
 *
 * Antes llevaba `authSession: AuthSession` y `announcement: NodeAnnouncement`
 * obligatorios, mas un `contactBundle` opcional que se anadia despues con
 * `withContactBundle`. Eso permitia la combinacion que no significa nada —un
 * `AuthSession` de nodo junto a un bundle de contacto— y obligaba a que la
 * sesion comprobara `authSession.state` en cuatro sitios distintos.
 *
 * [authSession], [announcement] y [contactBundle] siguen existiendo como
 * LECTURAS DERIVADAS de la autoridad, y por eso son NULLABLES: un contexto de
 * contacto no tiene sesion de autenticacion ni anuncio, y decirlo con `null` es
 * mas honesto que un valor inventado. Para el camino de envio no hace falta: lo
 * que se pregunta es `authority.authorizesPeerSession()`.
 *
 * ## LAS DOS PUERTAS
 *
 * - [authenticated] — autoridad de NODO. Firma y orden SIN CAMBIOS: hay 30
 *   llamadas en el repositorio y no todas las pasan por nombre. No se depreca:
 *   sigue siendo la unica via a la autoridad de nodo, y deprecarla seria
 *   mentir sobre su status.
 * - [forContact] — autoridad de CONTACTO. Deriva el `remotePeerId` de la RAIZ
 *   del bundle ([KmIds.identityId]); el llamante no puede colar nada.
 *
 * `withContactBundle` ya no existe: no tenia un solo llamante de produccion y
 * su semantica aditiva —anadir un bundle a un contexto de nodo— deja de tener
 * sentido con una autoridad unica.
 */
data class PeerContext(
    /**
     * Identidad del par, y CLAVE DE TODO lo que se indexa por par: el
     * `SecureSessionRegistry` y el `PeerTransportBinding`.
     *
     * De que raiz es depende de quien fabrica el contexto, no de esta clase:
     * [authenticated] lo recibe del llamante (grano del que el handshake de nodo
     * traiga) y [forContact] lo DERIVA de la raiz del bundle. Lo que si es
     * cierto para las dos es que la granularidad de aqui es la que gobierna la
     * granularidad de los dos indexadores.
     */
    val remotePeerId: IdentityId,
    /** Clave publica del par. El TIPO la exige; quien la consume decide si tiene garantia. */
    val publicKey: ByteArray,
    /** La unica fuente de verdad sobre quien es el par y que puede hacer. */
    val authority: PeerAuthority,
    val negotiationResult: NegotiationResult? = null,
    val negotiationHash: ByteArray? = null,
) {
    /**
     * Bundle de contacto verificado, o `null` si la autoridad es de nodo.
     *
     * Es lo que consume [com.km.messaging.SecureSessionEstablishment] para el
     * X3DH. Salir de la autoridad, y no de un campo aparte, es lo que impide
     * que un contexto de nodo lleve material de contacto.
     */
    val contactBundle: VerifiedContactBundle? get() = (authority as? PeerAuthority.ContactVerified)?.bundle

    /**
     * `AuthSession` del handshake de nodo, o `null` si la autoridad es de
     * contacto.
     *
     * Nullable a proposito: un contacto no tiene handshake de nodo. Quien lo
     * necesite para una operacion que es de nodo tiene que mirar la autoridad.
     */
    val authSession: AuthSession? get() = (authority as? PeerAuthority.NodeAuthenticated)?.authSession

    /**
     * Anuncio de nodo firmado, o `null` si la autoridad es de contacto.
     *
     * Nullable a proposito: lo mismo. El directorio de roles y el runtime
     * consumen `NodeAnnouncement`; aqui no hay de donde sacarlo.
     */
    val announcement: NodeAnnouncement? get() = (authority as? PeerAuthority.NodeAuthenticated)?.announcement

    companion object {
        /**
         * Construye un PeerContext con autoridad de NODO.
         *
         * Solo transiciona a autenticado si:
         *   1. authSession.state == AUTHENTICATED
         *   2. announcement.identity.nodeId == remotePeerId
         *
         * Las dos comprobaciones viven en [PeerAuthority.NodeAuthenticated.of]:
         * un unico choke point en vez de cuatro. Mismo tipo de excepcion y
         * mismos mensajes que antes.
         *
         * @throws IllegalArgumentException si alguna invariante falla.
         */
        fun authenticated(
            remotePeerId: IdentityId,
            publicKey: ByteArray,
            announcement: NodeAnnouncement,
            authSession: AuthSession,
        ): PeerContext = PeerContext(
            remotePeerId = remotePeerId,
            publicKey = publicKey,
            authority = PeerAuthority.NodeAuthenticated.of(
                remotePeerId = remotePeerId,
                announcement = announcement,
                authSession = authSession,
            ),
        )

        /**
         * Construye un PeerContext con autoridad de CONTACTO desde un bundle
         * VERIFICADO.
         *
         * El `remotePeerId` se DERIVA de `identityRoot` con
         * [KmIds.identityId], y no se acepta como parametro: si el llamante
         * pudiera elegirlo, la clave de enrutado dejaria de ser la identidad
         * que el bundle declara y verifica, y un `subjectDeviceId` podria
         * colarse como clave de registro.
         *
         * ## QUE ES "GRANO DE RAIZ", Y DONDE APLICA Y DONDE NO
         *
         * La granularidad es la CONVERSACION, no el dispositivo: dos dispositivos
         * de la misma raiz dan la misma clave. Y esa clave gobierna los DOS
         * indexadores de la superficie 1:1 —el `SecureSessionRegistry` y el
         * `PeerTransportBinding`—, no solo los de esta rama.
         *
         * Conviene decirlo con precision, porque la lectura easy seria falsa:
         * **lo que [forContact] garantiza no es "todo el `PeerContext` va a grano
         * de raiz", sino "esta puerta produce siempre una clave de raiz"**.
         * [authenticated] no restringe la granularidad en absoluto: recibe el
         * `remotePeerId` que le pase el llamante, que puede ser de dispositivo.
         * En el camino de contacto el que impone la raiz es esta fabrica; en el
         * de nodo, quien construye el contexto. Es una limitacion declarada, no
         * un accidente: ver el CHANGELOG.
         *
         * @param bundle bundle que ya paso por [VerifiedContactBundle].
         */
        fun forContact(bundle: VerifiedContactBundle): PeerContext {
            val rootIdentityId = KmIds.identityId(bundle.identityRoot)
            return PeerContext(
                remotePeerId = IdentityId(rootIdentityId.toHexString()),
                publicKey = bundle.subjectSigningKey,
                authority = PeerAuthority.ContactVerified.of(bundle),
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
}