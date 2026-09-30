package com.km.node

import com.km.model.IdentityId

/**
 * Estado de un establecimiento de transporte (3Q.4).
 *
 * La distincion que importa es la SEPARACION entre exito INTERMEDIO y `READY`.
 *
 * ```
 * CREATED
 *    |
 * NEGOTIATING      <- SDP, ICE, DTLS, DataChannel: pasos intermedios
 *    |
 *  READY           <- TERMINAL. El medio puede llevar datos.
 * ```
 *
 * `initialize`, `onCreateBinding` y `onLocalSdp`devuelven `Success` siendo
 * todavia `CREATED` o `NEGOTIATING`. Confundir exito intermedio con readiness
 * es el fallo que esta maquina previene: la cadena no puede declarar ganador
 * a un medio que acepto un SDP pero cuyo DataChannel sigue sin abrir.
 */
enum class EstablishmentState {
    CREATED,
    NEGOTIATING,

    /** Terminal positivo: el medio puede llevar datos. */
    READY,

    /** Terminal negativo: este medio no sirve para este peer. */
    FAILED,

    /** Liberado. No puede volver a recibir ni entregar. */
    CLOSED,
    ;

    val isTerminal: Boolean get() = this == READY || this == FAILED || this == CLOSED

    /** Si el medio puede entregar datos a la sesion. */
    val canCarryData: Boolean get() = this == READY
}

/**
 * Unidad de establecimiento de UN transporte.
 *
 * Cada medio tiene su PROPIO establecimiento, con su propia negociacion. No
 * existe reutilizar el SDP de P2P para Relay, ni continuar el ICE de P2P
 * usando Relay: son procedimientos distintos, con estados distintos.
 *
 * Esa es la diferencia con el envio, donde los MISMOS bytes salen por el
 * siguiente medio. En el establecimiento, en cambio, el medio errado no puede
 * reusar nada del medio anterior.
 */
interface TransportEstablishment {

    /** Nombre del transporte, para diagnostico. */
    val transportName: String

    /** Estado actual. */
    val state: EstablishmentState

    /** Prepara el medio. Debe llevar a `CREATED`. */
    fun initialize(): TransportResult<Unit>

    /**
     * Crea la conexion con el peer. Debe llevar a `CREATED` (o `NEGOTIATING`).
     *
     * Es una unidad FRESCA: no hereda SDP, candidatos ni estado de P2P.
     */
    fun createBinding(localIdentity: IdentityId, remotePeerId: IdentityId): TransportResult<Unit>

    /**
     * Negociacion completa propia de este medio: offer/answer e ICE.
     *
     * `Success` aqui significa `NEGOTIATING`, NO `READY`.
     */
    fun negotiate(): TransportResult<Unit>

    /**
     * Espera a la condicion terminal.
     *
     * Devuelve `Success` solo si alcanza [EstablishmentState.READY]. Un medio
     * que negocia bien pero cuyo canal no abre NUNCA es `READY`.
     */
    fun awaitReady(timeoutMs: Long): TransportResult<Unit>

    /**
     * Libera los recursos de este establecimiento.
     *
     * Debe ser idempotente y no debe lanzar: se llama en el camino de error,
     * y una fuga alli es peor que el fallo original.
     */
    fun abort()
}

/**
 * Intento de establecimiento de un eslabon de la cadena.
 *
 * Se registra aunque el medio no haya llegado a `READY`: saber que P2P se
 * quedo en `NEGOTIATING` es distinto de saber que fallo, y ambos casos
 * terminan la cadena.
 */
data class EstablishmentAttempt(
    val index: Int,
    val transport: String,
    val state: EstablishmentState,
    val result: TransportResult<Unit>,
) {
    val reachedReady: Boolean get() = state == EstablishmentState.READY
}

/**
 * Resultado de establecer por la cadena.
 *
 * Tres desenlaces, y la distincion importa: `Stopped` NO es `Exhausted`. Un
 * fallo de seguridad no es "no quedaba ningun medio", es "no debimos seguir
 * buscando", y callers que los confundan abririan un camino de relleno que
 * nunca deberia existir.
 */
sealed class EstablishmentResult {

    /** Un medio alcanzo `READY`. Es el unico caso con transporte activo. */
    data class Ready(val attempt: EstablishmentAttempt, val attempts: List<EstablishmentAttempt>) : EstablishmentResult()

    /** Ningun medio sirvio. Todos cerrados. */
    data class Exhausted(
        val attempts: List<EstablishmentAttempt>,
        val lastFailure: TransportResult.Failure,
    ) : EstablishmentResult()

    /**
     * Un medio fallo con una categoria que NO autoriza fallback.
     *
     * Los medios anteriores ya se cerraron; los posteriores ni se intentaron.
     */
    data class Stopped(
        val attempt: EstablishmentAttempt,
        val attempts: List<EstablishmentAttempt>,
    ) : EstablishmentResult()

    val activeTransport: String?
        get() = when (this) {
            is Ready -> attempt.transport
            else -> null
        }
}
