package com.keymessage.core.node

import com.keymessage.core.model.IdentityId

/**
 * Intento de envio performed por un eslabon de la [TransportChain].
 *
 * Existe para que un fallo sea diagnosticable: no basta con saber que "el
 * envio fallo", hay que saber que fallo, en que eslabon y con que categoria.
 */
data class ChainAttempt(
    /** Posicion del transporte dentro del orden configurado. */
    val index: Int,
    /** Nombre del transporte ([TransportBackend.name]). */
    val transport: String,
    /** Destinatario. */
    val remotePeerId: IdentityId,
    /** Resultado devuelto por ese transporte. */
    val result: TransportResult<Unit>,
) {
    /** `true` si el envio salio por este eslabon. */
    val delivered: Boolean get() = result is TransportResult.Success
}

/**
 * Cadena ORDENADA de transportes (3Q.2).
 *
 * Decide UNICAMENTE por donde salen los bytes. No sabe nada de criptografia,
 * de ratchets ni de mensajes: si aparece unconcepto de sesion en esta clase,
 * la frontera se rompio.
 *
 * ```
 * SecureMessagingSession      <- unica sesion criptografica
 *          |
 *          v
 * PeerTransportManager
 *          |
 *          v
 * TransportChain               <- solo enruta
 *    +-----+-----+-----+
 *    v     v     v
 *   P2P   Relay   Tor
 * ```
 *
 * NO existe una sesion por transporte. Cambiar de P2P a Relay no ejecuta
 * X3DH, no deriva una RootKey y no reinicia el ratchet: los MISMOS bytes
 * cifrados salen por el siguiente medio. Ese es el invariante que CHAIN-08
 * verifica.
 *
 * ## Politica de fallback
 *
 * [TransportError.permitsFallback] es la frontera normativa, y es
 * deliberadamente estrecha:
 *
 * ```
 * TRANSPORT -> el MEDIO fallo      -> probar el siguiente eslabon
 * SECURITY  -> no sabemos quien es -> DETENER
 * PROTOCOL  -> bytes invalidos     -> DETENER
 * STATE     -> el estado impide    -> DETENER
 * ```
 *
 * Un fallo de seguridad NO se convierte en un cambio de ruta silencioso:
 * reintentar por otro medio no arregla una suplantacion, solo la esconde.
 *
 * ## Lo que esta clase NO hace
 *
 * **No reenvia mensajes de aplicacion.** Un fallo de envio NO significa que
 * el mensaje no llegara: el backend puede haberlo aceptado y no poder
 * confirmarlo. Reenviar el mismo frame por otro medio puede producir un
 * duplicado en el receptor. Esa decision es de ENTREGA (ACK, `messageId`) y
 * pertenece a KM-0004, no aqui. Aqui solo se decide el MEDIO.
 *
 * **No resuelve el establecimiento.** Las operaciones distintas de `sendData`
 * se delegan al PRIMER eslabon. Elegir que medio establecer, y con que
 * procedimiento de negociacion, es 3Q.4.
 *
 * ## Orden
 *
 * El orden configurado se respeta literalmente. `[P2P]` significa P2P y nada
 * mas: si falla, la cadena termina. No existe ningun eslabon implicito.
 */
class TransportChain(
    /** Transportes en orden de preferencia. No vacio. */
    val transports: List<TransportBackend>,
) : TransportBackend {

    init {
        require(transports.isNotEmpty()) {
            "TransportChain requiere al menos un transporte; una cadena vacia no es una configuracion, es un error"
        }
    }

    override val name: String get() = "chain[${transports.joinToString(">") { it.name }}]"

    /** El primer transporte configurado, que es el que establece. */
    val primary: TransportBackend get() = transports.first()

    private val attemptObservers = mutableListOf<(ChainAttempt) -> Unit>()

    /**
     * El UNICO establecimiento con autoridad para entregar datos.
     *
     * Cuando un medio pierde, su establecimiento se cierra y deja de tener
     * autoridad aunque el objeto siga existiendo. No es una formalidad: un P2P
     * retirado que siguiera entregando bytes seria un segundo camino oculto
     * hacia la sesion, invisible para el llamante que cree haber establecido
     * por Relay.
     */
    @Volatile
    private var selected: TransportEstablishment? = null

    /** Nombre del medio activo, o `null` si no hay ninguno. */
    val activeTransport: String? get() = selected?.transportName

    /** Si el medio indicado tiene autoridad para entregar datos. */
    fun isActive(transport: String): Boolean = selected?.transportName == transport

    /** Observa cada intento, para diagnostico y para tests. */
    fun onAttempt(observer: (ChainAttempt) -> Unit) {
        attemptObservers.add(observer)
    }

    // ===================================================================
    // 3Q.4 — Establecimiento por transporte
    // ===================================================================

    /**
     * Establece por la cadena, intentanto UN medio a la vez y en orden.
     *
     * Cada medio recibe un establecimiento FRESCO. No se reusa nada del
     * anterior: si P2P negocio y fallo, Relay negocia desde cero, con su
     * propio SDP y sus propios candidatos. Un medio cerrado no vuelve a ser
     * intentable dentro de la misma ronda.
     *
     * La politica de parada es la MISMA que en el envio y por la misma razon:
     * un fallo de seguridad no se esquiva cambiando de medio.
     *
     * Garantias al terminar:
     * - [EstablishmentResult.Ready]: exactamente un medio en `READY`. Todos
     *   los perdedores estan cerrados.
     * - [EstablishmentResult.Stopped]: los intentados estan cerrados y los
     *   siguientes ni se tocaron.
     * - [EstablishmentResult.Exhausted]: TODOS cerrados, ninguno vivo. Un
     *   medio a medias que sobrevive a un fallo global es un recurso que
     *   nadie volvera a cerrar.
     *
     * @param factory crea el establecimiento FRESCO de un medio. Se invoca una
     *   vez por eslabon, y solo si toca intentarlo.
     */
    fun establish(
        localIdentity: IdentityId,
        remotePeerId: IdentityId,
        timeoutMs: Long = 10_000L,
        factory: (TransportBackend) -> TransportEstablishment,
    ): EstablishmentResult {
        // Una ronda nueva invalida cualquier seleccion previa: sin este paso,
        // un fallo de la nueva dejaria vivo al ganador viejo.
        releaseSelected()

        val attempts = mutableListOf<EstablishmentAttempt>()

        for ((index, backend) in transports.withIndex()) {
            val est = factory(backend)
            val result = runEstablishment(est, localIdentity, remotePeerId, timeoutMs)
            // El nombre que se registra es el del ESTABLECIMIENTO, no el del
            // backend: es el mismo que usa `selected` y `acceptsInbound`. Si se
            // registrara el del backend, el diagnostico podria nombrar un
            // eslabon al que la autoridad no corresponde.
            val linkName = est.transportName
            val attempt = EstablishmentAttempt(index, linkName, est.state, result)
            attempts.add(attempt)
            record(ChainAttempt(index, linkName, remotePeerId, result))

            if (result is TransportResult.Success && est.state == EstablishmentState.READY) {
                // Unico punto del sistema donde se concede autoridad.
                selected = est
                return EstablishmentResult.Ready(attempt, attempts.toList())
            }

            // No es READY: este medio queda sin autoridad y se cierra.
            safeAbort(est)
            val failure = result as? TransportResult.Failure
            if (failure != null && !failure.error.permitsFallback) {
                return EstablishmentResult.Stopped(attempt, attempts.toList())
            }
        }

        val last = attempts.lastOrNull()?.result as? TransportResult.Failure
        return EstablishmentResult.Exhausted(
            attempts.toList(),
            last ?: TransportResult.Failure(
                TransportError.BACKEND_NOT_READY,
                "ningun transporte pudo establecer con $remotePeerId",
            ),
        )
    }

    /**
     * Recorre las fases de un establecimiento, propagando el primer fallo.
     *
     * Un `Success` intermedio NO es exito terminal: se sigue hasta
     * [TransportEstablishment.awaitReady], que es el unico que puede afirmar
     * `READY`.
     */
    private fun runEstablishment(
        est: TransportEstablishment,
        localIdentity: IdentityId,
        remotePeerId: IdentityId,
        timeoutMs: Long,
    ): TransportResult<Unit> {
        val fases: List<() -> TransportResult<Unit>> = listOf(
            { est.initialize() },
            { est.createBinding(localIdentity, remotePeerId) },
            { est.negotiate() },
            { est.awaitReady(timeoutMs) },
        )
        for (fase in fases) {
            val r = try {
                fase()
            } catch (e: Throwable) {
                // Una implementacion de `TransportEstablishment` que revienta
                // no puede tumbar la cadena. Es la misma disciplina que
                // `sendData`: un medio roto degrada, no detiene.
                //
                // Sin este `catch`, un backend con un bug abortaria la ronda y
                // dejaria VIVOS los medios que faltaban intentar, que es
                // justo la fuga que `safeAbort` evita.
                TransportResult.Failure(
                    TransportError.BACKEND_ERROR,
                    "${est.transportName} lanzo ${e::class.simpleName} en una fase: ${e.message}",
                )
            }
            when (r) {
                is TransportResult.Success -> Unit
                is TransportResult.Failure -> return r
            }
        }
        return TransportResult.Success(Unit)
    }

    /**
     * Cierra un establecimiento sin dejar que su fallo se propague.
     *
     * El camino de error es el mas delicadeo del sistema: si `abort()` lanza
     * mientras ya estamos STEMOS, la excepcion escapa, la ronda se aborta, y
     * los medios que faltaban intentarse quedan con recursos VIVOS que nadie
     * volvera a cerrar. Un fallo de limpieza no puede convertirse en una fuga.
     *
     * Misma disciplina que el `catch` de `sendData`: un backend roto no puede
     * derribar la composicion.
     */
    private fun safeAbort(est: TransportEstablishment) {
        try {
            est.abort()
        } catch (_: Throwable) {
            // Se ignora a proposito. Si el medio no se puede cerrar, lo unico
            // honesto que queda es seguir intentando los demas y que el
            // llamante vea el fallo real del establecimiento, no el del
            // limpieza.
        }
    }

    /** Cierra el establecimiento con autoridad, si lo hay. Idempotente. */
    fun releaseSelected() {
        selected?.let { safeAbort(it) }
        selected = null
    }

    /**
     * Entrega datos de un medio a la sesion, SOLO si tiene autoridad.
     *
     * Los bytes de un medio retirado se descartan. Un medio perdedor que
     * siguiera inyectando datos seria indistinguible de un atacante: el
     * receptor no tendria forma de saber de donde vinieron.
     */
    fun acceptsInbound(transport: String): Boolean {
        val est = selected ?: return false
        return est.transportName == transport && est.state == EstablishmentState.READY
    }

    // ===================================================================
    // La unica operacion con politica de fallback: enviar
    // ===================================================================

    /**
     * Envia por el primer eslabon disponible, respetando el orden.
     *
     * Ante un fallo, solo continua si [TransportError.permitsFallback] lo
     * autoriza. Cualquier otra categoria se propaga de inmediato, sin tocar
     * los eslabones siguientes: esos eslabones no pueden arreglar un problema
     * de seguridad, de protocolo o de estado.
     *
     * Si todos los eslabones fallan con errores de transporte, se devuelve el
     * fallo del ULTIMO: es el mas proximo al estado actual de la cadena.
     */
    override fun sendData(remotePeerId: IdentityId, data: ByteArray): TransportResult<Unit> {
        var lastFailure: TransportResult.Failure? = null

        for ((index, transport) in transports.withIndex()) {
            val result = try {
                transport.sendData(remotePeerId, data)
            } catch (e: Throwable) {
                // Un eslabon que revienta no puede derribar la cadena: los
                // demas medios siguen siendo validos. Se clasifica como
                // BACKEND_ERROR (categoria TRANSPORT) porque un fallo de
                // implementacion del medio no es un problema de seguridad, ni
                // de protocolo, ni de estado.
                //
                // ADVERTENCIA: si el eslabon lanzo DESPUES de escribir, no
                // sabemos si los bytes salieron. Aqui solo se decide el MEDIO.
                // Si esos bytes salieron, la pregunta de entrega (ACK,
                // `messageId`) es de KM-0004: por eso la cadena nunca reenvia
                // mensajes de aplicacion por su cuenta.
                TransportResult.Failure(
                    TransportError.BACKEND_ERROR,
                    "${transport.name} lanzo ${e::class.simpleName}: ${e.message}",
                )
            }
            record(ChainAttempt(index, transport.name, remotePeerId, result))

            when (result) {
                is TransportResult.Success -> return result
                is TransportResult.Failure -> {
                    if (!result.error.permitsFallback) {
                        // SECURITY / PROTOCOL / STATE: el siguiente medio
                        // recibiria exactamente los mismos bytes y el mismo
                        // contexto, y fallaria igual. Continuar solo
                        // esconderia la causa real.
                        return result
                    }
                    lastFailure = result
                }
            }
        }
        return lastFailure ?: TransportResult.Failure(
            TransportError.BACKEND_NOT_READY,
            "ningun transporte pudo enviar a $remotePeerId",
        )
    }

    // ===================================================================
    // Establecimiento: delegado al PRIMER eslabon (3Q.4)
    // ===================================================================

    override var eventCallbacks: TransportEventCallbacks
        get() = primary.eventCallbacks
        set(value) {
            // El manager cablea la cadena una sola vez. Propagar a todos los
            // eslabones evita que uno de ellos quede mudo.
            transports.forEach { it.eventCallbacks = value }
        }

    override fun initialize(): TransportResult<Unit> = foldAll("initialize") { it.initialize() }

    override fun onCreateBinding(localIdentity: IdentityId, remotePeerId: IdentityId): TransportResult<Unit> =
        foldAll("onCreateBinding") { it.onCreateBinding(localIdentity, remotePeerId) }

    override fun onLocalSdp(localIdentity: IdentityId, remotePeerId: IdentityId, sdp: String): TransportResult<Unit> =
        foldAll("onLocalSdp") { it.onLocalSdp(localIdentity, remotePeerId, sdp) }

    override fun onRemoteSdp(localIdentity: IdentityId, remotePeerId: IdentityId, sdp: String): TransportResult<Unit> =
        foldAll("onRemoteSdp") { it.onRemoteSdp(localIdentity, remotePeerId, sdp) }

    override fun onIceCandidate(localIdentity: IdentityId, remotePeerId: IdentityId, candidate: String): TransportResult<Unit> =
        foldAll("onIceCandidate") { it.onIceCandidate(localIdentity, remotePeerId, candidate) }

    override fun onCloseBinding(localIdentity: IdentityId, remotePeerId: IdentityId): TransportResult<Unit> =
        foldAll("onCloseBinding") { it.onCloseBinding(localIdentity, remotePeerId) }

    /**
     * Cierra en TODOS los eslabones.
     *
     * A diferencia de las demas operaciones de establecimiento, el cierre si
     * se propaga a toda la cadena: un eslabon que no se cierra seguiria
     * manteniendo recursos vivos y podria recibir datos de un binding que el
     * manager ya dio por cerrado. Cerrar solo el primario dejaria fugas.
     */
    override fun shutdown(): TransportResult<Unit> {
        var last: TransportResult.Failure? = null
        for (transport in transports) {
            if (transport.shutdown() is TransportResult.Failure) {
                last = TransportResult.Failure(
                    TransportError.BACKEND_ERROR, "fallo al cerrar ${transport.name}",
                )
            }
        }
        return last ?: TransportResult.Success(Unit)
    }

    /**
     * Aplica una operacion de establecimiento al PRIMER eslabon.
     *
     * NO hay fallback aqui, y es una decision consciente: si un medio no
     * puede establecer, probar el siguiente exige un procedimiento de
     * negociacion DISTINTO para ese medio (otro SDP, otro ICE, otro
     *handshake). Reintentar la misma operacion sobre otro eslabon no es un
     * fallback, es repetir un intento que ya se sabe inaplicable. Ese
     * procedimiento es 3Q.4.
     *
     * Se delega al primario y no a "el que funciono al enviar" porque la
     * eleccion de medio para ESTABLECER precede a cualquier envio.
     */
    private inline fun foldAll(
        op: String,
        block: (TransportBackend) -> TransportResult<Unit>,
    ): TransportResult<Unit> {
        // Destino: el medio SELECCIONADO si ya hubo establecimiento; el
        // primario si todavia no lo hubo.
        //
        // Apuntar siempre al primario es un bug real: tras un fallback, el
        // manager abriria su binding contra un medio que la cadena ya
        // abandono, y la sesion believes estar sobre Relay mientras opera
        // sobre P2P.
        val target = selected?.let { findBackend(it.transportName) } ?: primary
        return try {
            block(target)
        } catch (e: Throwable) {
            TransportResult.Failure(TransportError.BACKEND_ERROR, "$op fallo en ${target.name}: ${e.message}")
        }
    }

    /** Localiza el backend cuyo establecimiento lleva ese nombre. */
    private fun findBackend(linkName: String): TransportBackend? =
        transports.firstOrNull { it.name == linkName } ?: primary

    private fun record(attempt: ChainAttempt) {
        attemptObservers.forEach { it(attempt) }
    }
}
