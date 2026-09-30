package com.keymessage.core.node

import com.keymessage.core.model.IdentityId
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Establecimiento programable, para los tests de 3Q.4.
 *
 * Modela lo que [TransportChain] no puede suponer: que "todo fue bien" NO
 * significa `READY`. Este doble permite que la negociacion funcione y el
 * DataChannel no abra, que es el caso que un `initialize() == Success` no
 * detectaria.
 */
class ScriptableEstablishment(
    override val transportName: String,
    private val backend: TransportBackend,
) : TransportEstablishment {

    /** Resultado de `initialize`. */
    var initializeOutcome: TransportResult<Unit> = TransportResult.Success(Unit)

    /** Resultado de `createBinding`. */
    var createBindingOutcome: TransportResult<Unit> = TransportResult.Success(Unit)

    /** Resultado de `negotiate`. */
    var negotiateOutcome: TransportResult<Unit> = TransportResult.Success(Unit)

    /**
     * Resultado de `awaitReady`.
     *
     * `Success` lleva a `READY`. Cualquier `Failure` deja el establecimiento
     * en un estado NO terminal positivo, que es el caso interesante.
     */
    var awaitReadyOutcome: TransportResult<Unit> = TransportResult.Success(Unit)

    /** Cuando es `true`, `abort()` lanza. El camino de error no debe fallar. */
    var abortThrows = false

    /**
     * Backend que MIENTE: `awaitReady` devuelve `Success` pero el medio nunca
     * alcanza [EstablishmentState.READY].
     *
     * Modela el fallo real que la condicion terminal previene: un backend mal
     * implementado que reporta exito en cada paso mientras su canal sigue
     * cerrado. Un doble que solo sabe fallar honestamente NO ejercita esa
     * defensa, porque entonces el `Success` y el `READY` coinciden siempre.
     */
    var claimsSuccessWithoutReady = false

    override var state: EstablishmentState = EstablishmentState.CREATED
        private set

    /** Fases recorridas, en orden. */
    val phases = CopyOnWriteArrayList<String>()

    /** Veces que se llamo `abort()`. */
    @Volatile var abortCount: Int = 0
        private set

    override fun initialize(): TransportResult<Unit> {
        phases.add("initialize")
        when (val r = initializeOutcome) {
            is TransportResult.Success -> { state = EstablishmentState.CREATED }
            is TransportResult.Failure -> { state = EstablishmentState.FAILED }
        }
        return initializeOutcome
    }

    override fun createBinding(localIdentity: IdentityId, remotePeerId: IdentityId): TransportResult<Unit> {
        phases.add("createBinding")
        when (createBindingOutcome) {
            is TransportResult.Success -> state = EstablishmentState.CREATED
            is TransportResult.Failure -> state = EstablishmentState.FAILED
        }
        return createBindingOutcome
    }

    override fun negotiate(): TransportResult<Unit> {
        phases.add("negotiate")
        when (negotiateOutcome) {
            // Negociar bien NO es estar listo: el medio sigue en curso.
            is TransportResult.Success -> state = EstablishmentState.NEGOTIATING
            is TransportResult.Failure -> state = EstablishmentState.FAILED
        }
        return negotiateOutcome
    }

    override fun awaitReady(timeoutMs: Long): TransportResult<Unit> {
        phases.add("awaitReady")
        if (claimsSuccessWithoutReady) {
            // Dice que si, sin estarlo. El estado se queda en NEGOTIATING.
            return TransportResult.Success(Unit)
        }
        when (val r = awaitReadyOutcome) {
            is TransportResult.Success -> state = EstablishmentState.READY
            // El medio negocio bien pero no abrio canal: no esta READY.
            is TransportResult.Failure -> state = EstablishmentState.FAILED
        }
        return awaitReadyOutcome
    }

    override fun abort() {
        abortCount++
        if (abortThrows) throw IllegalStateException("abort fallo")
        state = EstablishmentState.CLOSED
    }

    companion object {
        /**
         * Fabrica que produce autenticidad real de la cadena: cada eslabon
         * recibe un establecimiento NUEVO, construido con su propio backend.
         *
         * Si la cadena reusara uno, el nombre del medio no coincidiria con el
         * eslabon, y eso es precisamente lo que EST-02 debe detectar.
         */
        fun factory(config: Map<String, ScriptableEstablishment>): (TransportBackend) -> TransportEstablishment = { backend ->
            config[backend.name] ?: error("sin configuracion para ${backend.name}")
        }
    }
}
