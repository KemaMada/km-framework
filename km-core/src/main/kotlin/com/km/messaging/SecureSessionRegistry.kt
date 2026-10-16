package com.km.messaging

import com.km.model.IdentityId
import java.util.concurrent.ConcurrentHashMap

/**
 * Ancla de sesiones por par. UN MAP, y a proposito casi no mas.
 *
 * ## QUIEN LO POSE
 *
 * **El consumidor** —`km-desktop` o el daemon—, no km-core. Una instancia
 * corresponde a UNA identidad local: la clave es el `remotePeerId` que la
 * sesion lleva en su [SecureMessagingSession.remotePeerId], que a su vez sale
 * del `PeerContext` y no de una decision de este mapa.
 *
 * ## QUE NO HACE, Y POR QUE
 *
 * - **No cifra, no descifra y no enruta.** No tiene `send`, ni `receive`, ni
 *   referencia a ningun transporte. Guardar una referencia a una sesion no es
 *   poder usarla: quien envia decide seguir, y esa decision no se cuela aqui.
 * - **No abre ni cierra sesiones.** No llama a [SecureSessionEstablishment],
 *   no consume OPKs y no toca el estado del ratchet.
 * - **No se consulta desde el camino de envio.** Su conexion con el envio es
 *   del slice 4. Este slice deja la superficie; no la enchufa.
 *
 * Lo que si hace es resolver UNA pregunta que de otro modo no tiene sitio:
 * *dado un `IdentityId` remoto, cual es la sesion con la que hablo?*. Sin un
 * anclaje compartido, cada consumidor se inventaria su propio `Map` y el
 * problema reaparece en cada sitio.
 *
 * ## POR QUE NO ES MAS LISTO
 *
 * No decide eviccion, no serializa el estado del ratchet y no reestablece tras
 * un reinicio. Cada una de esas cosas necesita un criterio que ningun
 * consumidor ha pedido, y fijarlo aqui seria inventarlo: un registro que
 * descarta una sesion por antigüedad y otro que la conserva son ambos
 * defendibles, y la eleccion es de quien lo usa.
 */
class SecureSessionRegistry(
    /** Identidad local cuyas sesiones se registran. Solo para trazas y mensajes. */
    val localIdentity: IdentityId,
) {
    private val sessions = ConcurrentHashMap<IdentityId, SecureMessagingSession>()

    /**
     * Instala la sesion de un par, sustituyendo la anterior si la habia.
     *
     * Sustituir, y no fallar, es lo que hace util el registro en un proceso
     * que reestablece: un nuevo `PeerContext` con el mismo par es una sesion
     * nueva y su estado de ratchet no se puede mezclar con el viejo.
     *
     * @return la sesion instalada, para encadenar.
     */
    fun install(session: SecureMessagingSession): SecureMessagingSession {
        val peer = session.remotePeerId
        sessions[peer] = session
        return session
    }

    /**
     * La sesion del par, o `null` si no hay ninguna.
     *
     * `null` significa "no establecida": quien llama tiene que distinguirlo de
     * "establecida y sin plaintext", y por eso no devuelve un valor por
     * defecto que parezca lo segundo.
     */
    fun find(remotePeerId: IdentityId): SecureMessagingSession? = sessions[remotePeerId]

    /**
     * Igual que [find], pero falla si no hay sesion.
     *
     * Para el camino de envio, donde "no hay sesion" es una situacion que hay
     * que reportar y no un `null` que se puede perder en un encadenado.
     *
     * @throws IllegalStateException si no hay sesion establecida con ese par.
     */
    fun require(remotePeerId: IdentityId): SecureMessagingSession =
        sessions[remotePeerId]
            ?: throw IllegalStateException(
                "no hay sesion establecida con $remotePeerId desde $localIdentity: " +
                    "establece una con SecureSessionEstablishment antes de usarla",
            )

    /** Retira la sesion del par. Devuelve la que habia, o `null`. */
    fun remove(remotePeerId: IdentityId): SecureMessagingSession? = sessions.remove(remotePeerId)

    /** Pares con sesion registrada. */
    fun peers(): Set<IdentityId> = sessions.keys.toSet()

    /** Numero de sesiones registradas. */
    fun size(): Int = sessions.size

    /** Vacia el registro. No cierra las sesiones: quien las posee decide eso. */
    fun clear() = sessions.clear()
}