package com.km.daemon.server

import com.km.model.IdentityId
import org.java_websocket.WebSocket
import org.slf4j.Logger

/**
 * Una conexion WebSocket entrante, con su estado de autenticacion.
 *
 * Es el unico objeto que sabe "quien es esta conexion". Ese par
 * `socket <-> IdentityId` es el mapeo que el spec exige mantener (§9, anti-
 * spoofing de §11): a partir de el, `RelayServer` puede comprobar que
 * `envelope.from == peerId` sin que el daemon tenga que saber nada del
 * contenido.
 *
 * Estados, en orden:
 *
 * ```
 * nuevo --AUTH_REQUEST--> challenged --AUTH_RESPONSE OK--> authenticated
 *   |                            |                              |
 *   +-- cualquier frame de datos o señal --> RECHAZADO          close --> liberada
 * ```
 *
 * No hay mas. Un `MESSAGE` antes de `AUTH_OK` no se guarda para despues: la
 * sesion que lo autorizaria no existe todavia, y `RelayServer` lo rechaza por
 * `SESSION_NOT_FOUND` si se le hiciera el caso.
 */
class RelayWebSocket(
    private val socket: WebSocket,
    private val logger: Logger,
) {

    /** `peerId` una vez autenticado. `null` antes del `AUTH_OK`. */
    @Volatile
    var peerId: IdentityId? = null
        private set

    /**
     * `sessionId` emitida en el `AUTH_OK` de ESTA conexion.
     *
     * Se guarda para una comprobacion concreta: `RelayServer` indexa las
     * sesiones por identidad, asi que una reconexion del mismo peer invalida
     * la sesion anterior. Al cerrar, la conexion vieja solo puede llamar a
     * `disconnect` si la sesion que ve sigue siendo la suya; si no, cerraria
     * en los pies de la conexion viva.
     */
    @Volatile
    var sessionId: String? = null
        private set

    /** Datos del `AUTH_REQUEST` que abrio el challenge pendiente. */
    @Volatile
    var pendingAuth: AuthRequestFields? = null
        private set

    val isAuthenticated: Boolean get() = peerId != null

    val isOpen: Boolean get() = socket.isOpen

    // --- transiciones de estado --------------------------------------------

    fun onChallengeIssued(request: AuthRequestFields) {
        pendingAuth = request
    }

    fun onAuthenticated(identity: IdentityId, newSessionId: String) {
        peerId = identity
        sessionId = newSessionId
        pendingAuth = null
    }

    /** Cierre del socket. No toca `RelayServer`: de eso se encarga el router. */
    fun markClosed() {
        pendingAuth = null
        peerId = null
        sessionId = null
    }

    // --- envio --------------------------------------------------------------

    /** Envia un frame de texto. No devuelve excepcion: la conexion caida no es un error del router. */
    fun send(text: String) {
        if (!socket.isOpen) {
            logger.debug("descarte de frame para conexion cerrada")
            return
        }
        socket.send(text)
    }

    fun close(code: Int, reason: String) {
        runCatching { socket.close(code, reason) }
            .onFailure { logger.debug("cierre de socket fallo: {}", it.message) }
    }

    /** Identidad de la conexion a efectos de log. NUNCA el contenido de la carga. */
    override fun toString(): String =
        "RelayWebSocket(peer=${peerId?.value?.take(8) ?: "anonimo"}, open=${socket.isOpen})"
}
