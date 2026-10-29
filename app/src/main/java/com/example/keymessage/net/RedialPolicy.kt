package com.example.keymessage.net

/**
 * De donde viene el evento de conectividad.
 *
 * Son DOS hechos distintos y la politica los trata distinto: una PERDIDA deja
 * al motor con un socket que ya no sirve (`#349`, PROBE-B: `ONLINE` con 0 bytes
 * entregados), y un RETORNO es la red que vuelve y permite rehacer el `AUTH`.
 */
enum class Transicion {
    /** Se perdio una red (o la que hay dejo de estar validada). */
    PERDIDA,

    /** Hay (o vuelve) una red con `INTERNET` + `VALIDATED`. */
    RETORNO,
}

/** Que hacer con un evento de red ya filtrado por la politica. */
enum class RedialDecision {
    /** Hay pila: pedir `forceRedial()` al transporte. */
    REDIAL,

    /**
     * No hay pila todavia: intentar `arrancar()`.
     *
     * Es el caso de un proceso que arranco sin red. No es "reconectar": no hay
     * nada que reconectar, hay que levantar la pila por primera vez.
     */
    REARRANCAR,

    /** Nada: evento redundante, red no utilizable o pila detenida. */
    IGNORAR,
}

/**
 * La decision de red, PURA y por tanto afirmable en un test de JVM.
 *
 * ## QUE NO HACE
 *
 * No toca Android —ni `ConnectivityManager` ni `Context`—, no lanza corrutinas
 * y no llama al transporte. Recibe un hecho y devuelve una decision. El
 * adaptador (`NetworkWatch`) es el unico que conoce la plataforma.
 *
 * ## LAS TRES REGLAS
 *
 *  1. **La perdida tambien redialea** (S1 gana a D7). Ante un cambio de red que
 *     NO cierra el socket, esperar al retorno dejaria al motor mintiendo
 *     `ONLINE` mientras la red ya no es la de la sesion. Un redial inmediato
 *     abre un socket sobre la red NUEVA; si aun no hay red, el backoff del
 *     transporte ya sabe reintentar.
 *  2. **El retorno solo cuenta si esta VALIDADO** (`INTERNET` +
 *     `VALIDATED`). Un `onAvailable` sin validacion es una red anunciada, no una
 *     red usable: redialear ahi es gastar un `AUTH` que va a fallar.
 *  3. **Sin pila no se redialea** (S11). Solo un retorno validado devuelve
 *     [RedialDecision.REARRANCAR]; una perdida sin pila no tiene nada que
 *     arreglar.
 *
 * ## DEBOUNCE: POR TRANSICION Y CON INTERVALO MINIMO
 *
 * `#349` PROBE-A/B y la propuesta fijan el peligro: `onAvailable`,
 * `onCapabilitiesChanged` y `onLost` llegan en rafaga, y un redial por evento
 * son varios `AUTH` y un rele que cierra el challenge anterior.
 *
 *  - **Por transicion**: el MISMO hecho no se atiende dos veces. Si ya se
 *    redialeo por una PERDIDA, otra PERDIDA (otra interfaz que cae) no suma
 *    otro redial; el transporte ya esta reconectando.
 *  - **Intervalo minimo** ([MIN_INTERVAL_MS]): una perdida y el retorno
 *    inmediato del cambio de interfaz son UN cambio de red, no dos. El segundo
 *    evento cae dentro de la ventana y se ignora; el redial del primero ya
 *    abrio el socket sobre la red nueva.
 *
 * ## BASELINE: EL ESTADO QUE YA EXISTIA NO ES UNA TRANSICION
 *
 * `registerDefaultNetworkCallback` entrega el estado ACTUAL al registrar:
 * `onAvailable` y `onCapabilitiesChanged` con la red que ya habia. Con la pila
 * ya arrancada —`AppContainer.arrancar` la levanta antes de observar—, ese
 * primer `RETORNO` validado parecia una red que acababa de volver y producia un
 * `forceRedial()` a los ~66 ms del arranque, que es un `AUTH` que nadie pidio.
 *
 * La regla es minima y no toca al adaptador: **el primer `RETORNO` validado con
 * pila, antes de cualquier hecho real, se siembra como baseline**. Se escribe
 * solo [ultimaTransicion] —el estado que ya existia— y se devuelve
 * [RedialDecision.IGNORAR]; [ultimoDisparoMs] queda `null`, de modo que una
 * `PERDIDA` real inmediatamente despues SI redialea (el debounce no la tapa).
 *
 *  - `PERDIDA` NUNCA siembra: perder la red es un hecho, no un estado inicial.
 *  - Sin pila no se siembra: el primer `RETORNO` validado sigue siendo
 *    [RedialDecision.REARRANCAR], que es levantar la pila por primera vez.
 *  - El `onAvailable` sin validar que el sistema puede entregar ANTES no
 *    consume el baseline ni el reloj: es parte del mismo estado inicial.
 *
 * Las dos reglas se aplican SOLO cuando hay una decision que ejecutar: un
 * evento ignorado (red sin validar) no mueve el reloj, de modo que el
 * `onCapabilitiesChanged` validado que llega medio segundo despues si dispara.
 *
 * ## SINGLE-FLIGHT
 *
 * El "maximo un redial en vuelo" (R3) NO vive aqui: una politica pura no sabe
 * cuando termina la accion. Vive en [RedialGate], que es quien ejecuta.
 */
class RedialPolicy(
    private val minIntervalMs: Long = MIN_INTERVAL_MS,
) {

    /** Ultima transicion que SI produjo una decision ejecutable. */
    private var ultimaTransicion: Transicion? = null

    /** Cuando se produjo esa decision, en el reloj del llamante. */
    private var ultimoDisparoMs: Long? = null

    /**
     * Si el estado inicial de la red sigue pendiente de sembrarse.
     *
     * No es `ultimaTransicion == null`: un `RETORNO` sin validar tambien es
     * parte del estado que el sistema entrega al registrar —`onAvailable`
     * puede llegar antes que las capacidades—, y no mueve
     * [ultimaTransicion]. Por eso el centinela es propio. Se apaga en cuanto
     * hay un hecho REAL: el baseline sembrado, una `PERDIDA` o una decision sin
     * pila (que es una accion, no un estado inicial).
     */
    private var baselinePendiente: Boolean = true

    /**
     * Decide que hacer con un evento de red.
     *
     * @param transicion que paso.
     * @param validado si la red del evento tiene `INTERNET` + `VALIDATED`. Para
     *   una [Transicion.PERDIDA] se ignora: lo que se perdio, se perdio.
     * @param pilaPresente si hay una pila de rele viva. `false` es el proceso
     *   sin conexion (o detenido), y es lo que separa REDIAL de REARRANCAR.
     * @param nowMs reloj monontonico del llamante, para el debounce.
     */
    @Synchronized
    fun decide(
        transicion: Transicion,
        validado: Boolean,
        pilaPresente: Boolean,
        nowMs: Long,
    ): RedialDecision {
        if (baselinePendiente) {
            when {
                transicion == Transicion.RETORNO && validado && pilaPresente -> {
                    // El estado que YA existia al registrar el callback. Se
                    // siembra la transicion y NADA MAS: el reloj sigue libre
                    // para que una perdida inmediata posterior redialee.
                    baselinePendiente = false
                    ultimaTransicion = Transicion.RETORNO
                    return RedialDecision.IGNORAR
                }
                transicion == Transicion.PERDIDA -> {
                    // PERDIDA nunca es baseline: es un hecho y se decide con las
                    // reglas de siempre.
                    baselinePendiente = false
                }
                !validado -> {
                    // `onAvailable` antes de las capacidades: ni decision ni
                    // baseline. El `onCapabilitiesChanged` validado que llega
                    // detras sigue siendo el candidato.
                    return RedialDecision.IGNORAR
                }
                else -> {
                    // Retorno validado SIN pila: REARRANCAR es una accion, no un
                    // estado inicial, asi que el baseline deja de estar pendiente.
                    baselinePendiente = false
                }
            }
        }

        val propuesta = proponer(transicion, validado, pilaPresente)
        if (propuesta == RedialDecision.IGNORAR) return RedialDecision.IGNORAR

        val ultimo = ultimoDisparoMs
        if (ultimo != null && nowMs - ultimo < minIntervalMs) {
            // Rafaga del MISMO cambio de red: perdida + retorno pegados.
            return RedialDecision.IGNORAR
        }
        if (ultimaTransicion == transicion) {
            // El mismo hecho, otra vez. El transporte ya esta en ello.
            return RedialDecision.IGNORAR
        }

        ultimoDisparoMs = nowMs
        ultimaTransicion = transicion
        return propuesta
    }

    private fun proponer(
        transicion: Transicion,
        validado: Boolean,
        pilaPresente: Boolean,
    ): RedialDecision = when {
        !pilaPresente && transicion == Transicion.PERDIDA -> RedialDecision.IGNORAR
        !pilaPresente && !validado -> RedialDecision.IGNORAR
        !pilaPresente -> RedialDecision.REARRANCAR
        transicion == Transicion.PERDIDA -> RedialDecision.REDIAL
        !validado -> RedialDecision.IGNORAR
        else -> RedialDecision.REDIAL
    }

    companion object {
        /**
         * Intervalo minimo entre dos decisiones ejecutables.
         *
         * Dos segundos es mas que el tiempo que tarda un `AUTH` normal en un
         * rele sano y menos que el backoff inicial del transporte (1 s..30 s),
         * de modo que no tapa un reintento legitimo.
         */
        const val MIN_INTERVAL_MS: Long = 2_000
    }
}
