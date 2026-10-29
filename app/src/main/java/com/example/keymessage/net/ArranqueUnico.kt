package com.example.keymessage.net

/**
 * CRITICAL #1 — El candado del arranque: una conexion, un intento, un ganador.
 *
 * ## POR QUE NO BASTA CON `if (stack != null) return true`
 *
 * Porque ese `if` NO es atomico. `KeyMessageApp` arranca el
 * `KeyMessageService`, cuyo `onCreate` llama a `AppContainer.arrancar()`, y la
 * `MainActivity` tambien lo llamaba desde su propio `onCreate`. Los dos hilos
 * leian `stack == null` a la vez, los dos pasaban el guard y los dos construian
 * un `JvmRelayTransport` con la MISMA identidad.
 *
 * El rele acepta UN challenge por identidad: la segunda conexion fallaba con
 * `AUTH_FAIL AUTH_FAILED ... INVALID_SIGNATURE` y el perdedor pisaba
 * `_state`/`_lastError` con `DISCONNECTED` aunque el ganador tuviera un socket
 * `ONLINE` huerfano. El sintoma no era "una conexion mala", era una conexion
 * buena con la app diciendo que no habia ninguna — y no determinista, porque
 * dependia de que hilo ganaba la carrera.
 *
 * ## LO QUE GARANTIZA
 *
 * - Un intento CONCURRENTE: los hilos que llegan mientras otro intenta ESPERAN su
 *   resultado en vez de abrir una segunda conexion. Es la parte que cierra la
 *   carrera.
 * - Un intento tras un EXITO: no-op. No hay nada que reconectar y un segundo
 *   motor seria el mismo bug con otro disfraz.
 * - Un intento tras un FALLO: SI se reintenta. Un candado que se cierra de golpe
 *   con un fallo dejaria la app sin poder reconectar nunca, que es peor que la
 *   carrera que arregla.
 *
 * ## POR QUE ES UNA CLASE APARTE Y NO UN `synchronized` EN `AppContainer`
 *
 * Porque `AppContainer` necesita un `Application` —`Settings.Global`,
 * `Room.databaseBuilder`, `EncryptedSharedPreferences`— y no se puede instanciar
 * en un test de JVM. Esta parte SI es pura, y por eso la garantia de atomicidad
 * se puede AFIRMAR con un test en vez de declararse.
 */
class ArranqueUnico {

    /** El monitor por el que esperan los llamantes concurrentes. */
    private val lock = Object()

    /** `true` mientras un hilo esta ejecutando el intento. */
    private var enCurso: Boolean = false

    /** `true` cuando un intento ya tuvo EXITO: a partir de ahi, no-op. */
    private var completado: Boolean = false

    /** El ultimo resultado, para que un llamante que espera lo reciba. */
    private var ultimo: Boolean = false

    /**
     * Ejecuta [intento] como maximo UNA vez de forma concurrente.
     *
     * @param intento el trabajo de conexion.
     * @return el resultado: el del que ejecuto, o el del intento en curso que
     *   este hilo espero.
     */
    fun unaVez(intento: () -> Boolean): Boolean {
        synchronized(lock) {
            // Ya conectado: no hay nada que hacer.
            if (completado) return ultimo
            // Otro hilo esta conectando: esperar SU resultado, sin abrir una
            // segunda conexion. Es exactamente la carrera del CRITICAL #1.
            while (enCurso) lock.wait()
            if (completado) return ultimo
            enCurso = true
        }

        val exito = try {
            intento()
        } finally {
            synchronized(lock) {
                enCurso = false
                lock.notifyAll()
            }
        }

        synchronized(lock) {
            ultimo = exito
            // Solo un EXITO cierra el candado. Un fallo deja la puerta abierta
            // para que el siguiente `arrancar()` vuelva a intentarlo.
            if (exito) completado = true
            lock.notifyAll()
            return exito
        }
    }

    /** `true` si ya hubo un intento con exito y no hay nada nuevo que hacer. */
    fun yaConectado(): Boolean = synchronized(lock) { completado }

    /** Vuelve a estado "nadie ha conectado". Lo usa `AppContainer.parar`. */
    fun reiniciar() = synchronized(lock) {
        completado = false
        ultimo = false
    }
}
