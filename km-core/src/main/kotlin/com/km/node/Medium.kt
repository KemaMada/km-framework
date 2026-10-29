package com.km.node

/**
 * Medio por el que viajan los DATOS de una sesion.
 *
 * No confundir con el estado del ENLACE al relay (`P2P[o|~|x|?]` de la TUI):
 * ese glifo sigue significando "hay socket autenticado con el relay", y el
 * medio de datos es otra pregunta. Con P2P activo y el relay caido, los datos
 * siguen saliendo por P2P aunque el glifo diga `x`.
 */
enum class Medium {

    /** Enlace directo por par (WebRTC/DataChannel). */
    P2P,

    /** Relay autenticado. Es el suelo y el unico medio del modo `off`. */
    RELAY,
    ;

    /**
     * Nombre del eslabon en la cadena.
     *
     * Es el que devuelve [TransportEstablishment.transportName] del
     * establecimiento P2P; la cadena y el adaptador comparan por ESTE valor y
     * no por el nombre del backend, que es un detalle de implementacion.
     */
    val linkName: String get() = name.lowercase()
}

/**
 * Motivos ESTABLES del tier P2P.
 *
 * Estables significa: no se derivan de mensajes de excepcion, no se traducen
 * y no cambian con el idioma. DIAGNOSTICO y los tests los comparan por
 * igualdad, y un operador puede buscarlos en los logs.
 *
 * Cada uno declara una causa DISTINTA. Colapsarlos en "p2p_failed" borraria
 * justo la informacion que hace accionable el diagnostico.
 */
object P2pReason {

    /** El modo es `off`: P2P no se intento a proposito. */
    const val OFF: String = "p2p_off"

    /** Modo `auto` sin ninguna URL ICE: no se intenta (host-only no basta). */
    const val NO_ICE: String = "p2p_no_ice"

    /** Modo `on` sin ICE: es un error de configuracion, no una degradacion. */
    const val ON_NO_ICE: String = "p2p_on_no_ice"

    /**
     * Modo `auto` con ICE pero SIN servidor TURN utilizable.
     *
     * STUN solo descubre la IP publica; no garantiza traversal y expone
     * candidatos `host`/`srflx` al par. Por eso `auto` no intenta P2P hasta
     * tener un relay configurado: sin TURN, la respuesta honesta es relay.
     */
    const val NO_TURN: String = "p2p_no_turn"

    /**
     * Modo `on` con ICE pero SIN servidor TURN utilizable.
     *
     * Igual que [NO_TURN], pero `on` no degrada en silencio: es un error de
     * configuracion (exit 2), porque el operador pidio P2P y solo hay STUN.
     */
    const val ON_NO_TURN: String = "p2p_on_no_turn"

    /** Un medio alcanzo `READY`; el enlace es P2P. */
    const val READY: String = "p2p_ready"

    /** El establecimiento no completo dentro del presupuesto. */
    const val TIMEOUT: String = "p2p_timeout"

    /** El establecimiento fallo con una categoria que autoriza fallback. */
    const val FAILED: String = "p2p_failed"

    /** El enlace se cerro (desconexion del par o cierre local). */
    const val CLOSED: String = "p2p_closed"

    /** Ya hay un intento en vuelo para este par: single-flight. */
    const val IN_FLIGHT: String = "p2p_in_flight"

    /** El backoff de 60 s aun no vencio. */
    const val BACKOFF: String = "p2p_backoff"

    /** No hay cadena montada para el par. */
    const val NO_CHAIN: String = "p2p_no_chain"
}
