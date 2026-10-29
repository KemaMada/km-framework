package com.example.keymessage.net

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.SystemClock
import com.example.keymessage.util.KMLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

/**
 * La plataforma de red, DETRAS DE UNA INTERFAZ.
 *
 * ## POR QUE ESTA INTERFAZ EXISTE
 *
 * `ConnectivityManager` no se puede instanciar en un test de JVM, y la regla de
 * S11 —"sin pila y sin red validada no se toca el transporte"— es exactamente
 * la que hay que poder afirmar sin dispositivo. Con esta costura, [NetworkWatch]
 * es JVM puro y el unico codigo que conoce Android es [AndroidNetworkMonitor].
 *
 * Los eventos se declaran con el mismo vocabulario que la politica
 * ([Transicion]) y con el hecho de si la red es USABLE (`INTERNET` +
 * `VALIDATED`), no con los detalles de `NetworkCapabilities`.
 */
interface NetworkMonitor {

    /**
     * Empieza a observar la red por defecto.
     *
     * Debe ser IDEMPOTENTE: dos [start] seguidos sin [stop] no registran dos
     * callbacks. `AppContainer.arrancar` se repite en recuperacion, y dos
     * callbacks serian dos rediales por evento.
     */
    fun start(onEvent: (Transicion, Boolean) -> Unit)

    /** Deja de observar. Idempotente: dos [stop] seguidos no fallan. */
    fun stop()
}

/**
 * Single-flight de la ACCION, no de la politica.
 *
 * [RedialPolicy] decide; este candado impide que dos decisiones ejecutables
 * corran a la vez. Es lo que hace que dos eventos que pasaron el debounce —o un
 * callback que se dispara durante un `arrancar()` bloqueante— no abran dos
 * pilas.
 *
 * ## QUE NO HACE
 *
 * No espera a que el `AUTH` termine: `forceRedial()` ya es single-flight en el
 * transporte y devuelve antes de reconectar. Este candado solo serializa la
 * INVOCACION, que es lo unico que una pieza pura puede garantizar.
 *
 * `desactivar()` suelta ambos estados. Sin eso, un redial que quedo "en vuelo"
 * al parar la app bloquearia el primer evento despues de volver a arrancar.
 */
class RedialGate {

    private val activo = AtomicBoolean(false)
    private val enVuelo = AtomicBoolean(false)

    /**
     * Activa el portero.
     *
     * @return `true` solo en la transicion inactivo -> activo, de modo que el
     *   llamante pueda registrar el callback una sola vez.
     */
    fun activar(): Boolean = activo.compareAndSet(false, true)

    /** Desactiva el portero y suelta cualquier accion en vuelo. Idempotente. */
    fun desactivar() {
        activo.set(false)
        enVuelo.set(false)
    }

    fun estaActivo(): Boolean = activo.get()

    /**
     * Ejecuta [accion] si el portero esta activo y no hay otra en vuelo.
     *
     * @return `true` si la accion corrio; `false` si se descarto.
     */
    fun ejecutar(accion: () -> Unit): Boolean {
        if (!activo.get()) return false
        if (!enVuelo.compareAndSet(false, true)) return false
        return try {
            accion()
            true
        } finally {
            enVuelo.set(false)
        }
    }
}

/**
 * El adaptador entre el sistema operativo y el motor: cambio de red -> redial.
 *
 * ## QUE HACE
 *
 *  1. Registra UN `NetworkCallback` (idempotente) cuando la pila arranca.
 *  2. Traduce cada evento a `(Transicion, validado)`.
 *  3. Pregunta a [RedialPolicy]; si la decision es ejecutable, la corre en
 *     `Dispatchers.IO` —el callback del sistema NO corre en IO y `arrancar()`
 *     bloquea—.
 *  4. Desregistra y apaga en `parar()`.
 *
 * ## POR QUE LA PERDIDA TAMBIEN REDIALEA
 *
 * Porque un cambio de interfaz puede dejar el socket VIEJO abierto y en
 * `ONLINE` aunque ya no entregue nada (`#349`, PROBE-B). Esperar al retorno es
 * esperar a que el usuario se de cuenta. El redial abre un socket sobre la red
 * nueva; si aun no hay red, el backoff del transporte ya reintenta.
 *
 * ## POR QUE NO HACE NADA SI NO HAY PILA
 *
 * S11: sin pila no hay socket que rehacer. Un retorno validado en ese estado
 * devuelve [RedialDecision.REARRANCAR]: el proceso arranco sin red y hay que
 * levantar la pila por primera vez, que es otra operacion.
 *
 * ## LA UI NO SE TOCA AQUI
 *
 * El estado que ve el usuario lo publica `AppContainer` desde
 * `onStateChange`; este adaptador no escribe `_state` ni conoce `StateFlow`.
 * Una sola fuente de verdad.
 */
class NetworkWatch(
    private val monitor: NetworkMonitor,
    private val scope: CoroutineScope,
    private val pilaPresente: () -> Boolean,
    private val redial: () -> Unit,
    private val arrancar: () -> Unit,
    private val policy: RedialPolicy = RedialPolicy(),
    private val gate: RedialGate = RedialGate(),
    private val ahoraMs: () -> Long = { SystemClock.elapsedRealtime() },
    /**
     * Como se despacha una accion. Inyectable para que el test la corra en el
     * hilo del llamante en vez de esperar a IO.
     */
    private val dispatch: ((() -> Unit) -> Unit)? = null,
) {

    private val despachar: (() -> Unit) -> Unit =
        dispatch ?: { accion -> scope.launch(Dispatchers.IO) { accion() } }

    /**
     * Empieza a vigilar. Idempotente: llamarlo dos veces no registra dos
     * callbacks.
     */
    fun iniciar() {
        if (!gate.activar()) return
        try {
            monitor.start(::onEvento)
            KMLog.event("[RELAY]", "observando cambios de red")
        } catch (e: Exception) {
            // Sin observador no hay redial proactivo; el camino pasivo del
            // transporte sigue siendo la red de seguridad. Se DICE, no se traga.
            gate.desactivar()
            KMLog.event("[RELAY]", "no se pudo observar la red: ${e.message}", KMLog.Level.WARN)
        }
    }

    /** Deja de vigilar. Idempotente. */
    fun parar() {
        if (!gate.estaActivo()) return
        monitor.stop()
        gate.desactivar()
        KMLog.event("[RELAY]", "observador de red detenido")
    }

    /**
     * Un evento ya traducido de la plataforma.
     *
     * `internal` y no privado: es el punto que el test de JVM ejercita para
     * fijar S11 sin tocar Android.
     */
    internal fun onEvento(transicion: Transicion, validado: Boolean) {
        if (!gate.estaActivo()) return
        val decision = policy.decide(
            transicion = transicion,
            validado = validado,
            pilaPresente = pilaPresente(),
            nowMs = ahoraMs(),
        )
        when (decision) {
            RedialDecision.REDIAL -> {
                KMLog.event("[RELAY]", "redial por $transicion")
                despachar { gate.ejecutar(redial) }
            }
            RedialDecision.REARRANCAR -> {
                KMLog.event("[RELAY]", "hay red y no hay pila: arrancando")
                despachar { gate.ejecutar(arrancar) }
            }
            RedialDecision.IGNORAR -> Unit
        }
    }
}

/**
 * El unico codigo de este fichero que conoce Android.
 *
 * Usa `registerDefaultNetworkCallback`: la app tiene UNA red por defecto —la que
 * usan los sockets—, y observar todas las interfaces produciria un redial por
 * cada red secundaria que aparezca. El callback se registra y desregistra desde
 * el hilo de control; los eventos llegan por el hilo de `ConnectivityManager` y
 * [NetworkWatch] los mueve a IO.
 *
 * `onAvailable` puede llegar antes de que haya capacidades: se traduce con
 * `validado=false` y la politica lo ignora. El `onCapabilitiesChanged`
 * posterior, ya con `VALIDATED`, es el que dispara. Es justo el orden que el
 * debounce de la politica espera.
 */
class AndroidNetworkMonitor(private val context: Context) : NetworkMonitor {

    private val connectivity: ConnectivityManager
        get() = context.getSystemService(ConnectivityManager::class.java)
            ?: error("ConnectivityManager no disponible")

    @Volatile
    private var callback: ConnectivityManager.NetworkCallback? = null

    @Synchronized
    override fun start(onEvent: (Transicion, Boolean) -> Unit) {
        if (callback != null) return
        val nuevo = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                onEvent(Transicion.RETORNO, usable(network))
            }

            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                onEvent(Transicion.RETORNO, usable(caps))
            }

            override fun onLost(network: Network) {
                onEvent(Transicion.PERDIDA, false)
            }

            override fun onUnavailable() {
                onEvent(Transicion.PERDIDA, false)
            }
        }
        connectivity.registerDefaultNetworkCallback(nuevo)
        callback = nuevo
    }

    @Synchronized
    override fun stop() {
        val registrado = callback ?: return
        callback = null
        runCatching { connectivity.unregisterNetworkCallback(registrado) }
            .onFailure {
                KMLog.event("[RELAY]", "no se pudo desregistrar el callback: ${it.message}", KMLog.Level.WARN)
            }
    }

    private fun usable(network: Network): Boolean = usable(connectivity.getNetworkCapabilities(network))

    /** `INTERNET` + `VALIDATED`: una red ANUNCIADA no es una red USABLE. */
    private fun usable(caps: NetworkCapabilities?): Boolean =
        caps != null &&
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
}
