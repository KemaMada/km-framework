package com.keymessage.core.node

/**
 * Estado de una sesion relay autenticada.
 *
 * KM-0003: una sesion relay representa una conexion autenticada
 * entre un peer y el relay. No es intercambiable con PeerContext
 * (autenticacion peer-to-peer).
 *
 * INVARIANTE: sessionId es opaco (40 hex), identityId es la identidad
 * autenticada por KM-0002, NO puede cambiarse una vez establecido.
 */
enum class RelaySessionState {
    /** Sesion activa y autenticada. */
    AUTHENTICATED,
    /** Sesion cerrada (disconnect, timeout, o error). */
    CLOSED,
}

/**
 * Sesion relay autenticada.
 *
 * Modelo A (por contexto): el estado autenticado es un objeto inmutable,
 * no un campo mutable. Para cerrar la sesion se reemplaza la instancia.
 *
 * @property sessionId 40 hex chars, generado por el relay en AUTH_OK.
 * @property identityId identidad del peer autenticado.
 * @property authenticatedAt timestamp de autenticacion (epoch millis).
 * @property state AUTHENTICATED o CLOSED.
 */
data class RelaySession(
    val sessionId: String,
    val identityId: com.keymessage.core.model.IdentityId,
    val authenticatedAt: Long,
    val state: RelaySessionState = RelaySessionState.AUTHENTICATED,
) {
    init {
        require(sessionId.length == 40) { "sessionId debe tener 40 hex chars, se recibieron ${sessionId.length}" }
        require(sessionId.all { it in "0123456789abcdef" }) { "sessionId debe ser hexadecimal minusculas" }
        require(identityId.value.length == 64) { "identityId debe tener 64 hex chars" }
    }

    /** Cierra esta sesion. Retorna una nueva instancia con estado CLOSED. */
    fun close(): RelaySession = copy(state = RelaySessionState.CLOSED)

    /** true si la sesion esta activa. */
    val isActive: Boolean get() = state == RelaySessionState.AUTHENTICATED
}