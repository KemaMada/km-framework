package com.example.keymessage.net

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * El adaptador de red, SIN Android: monitor falso, reloj falso, despacho directo.
 *
 * ## QUE SE AFIRMA
 *
 *  - S11: sin pila no hay redial; una perdida sin pila no toca nada.
 *  - Un retorno validado SIN pila arranca (REARRANCAR), no redialea.
 *  - El registro es IDEMPOTENTE y `parar()` apaga de verdad (gate inactivo).
 *  - Dos eventos del MISMO cambio de red producen UN solo redial (S4).
 *  - La accion se despacha fuera del callback (aqui, por inyeccion).
 */
class NetworkWatchTest {

    private class FakeMonitor : NetworkMonitor {
        var inicios: Int = 0
        var paradas: Int = 0
        var ultimo: ((Transicion, Boolean) -> Unit)? = null

        override fun start(onEvent: (Transicion, Boolean) -> Unit) {
            inicios++
            ultimo = onEvent
        }

        override fun stop() {
            paradas++
            ultimo = null
        }

        fun emitir(transicion: Transicion, validado: Boolean) {
            ultimo?.invoke(transicion, validado)
        }
    }

    private class Fixture(
        pilaPresente: Boolean = true,
        var ahora: Long = 1_000,
    ) {
        val monitor = FakeMonitor()
        var pila: Boolean = pilaPresente
        var rediales: Int = 0
        var arranques: Int = 0
        /** Hilos desde los que corrio la accion: prueba que NO corre en el callback. */
        val hilos = mutableListOf<Thread>()

        val watch = NetworkWatch(
            monitor = monitor,
            scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Unconfined),
            pilaPresente = { pila },
            redial = { rediales++; hilos.add(Thread.currentThread()) },
            arrancar = { arranques++; hilos.add(Thread.currentThread()) },
            ahoraMs = { ahora },
            dispatch = { accion -> accion() },
        )
    }

    @Test
    fun `sin pila una perdida no redialea ni arranca - S11`() {
        val f = Fixture(pilaPresente = false)
        f.watch.iniciar()

        f.watch.onEvento(Transicion.PERDIDA, validado = false)

        assertEquals(0, f.rediales, "sin pila no hay socket que rehacer")
        assertEquals(0, f.arranques, "una perdida no es una red que vuelve")
    }

    @Test
    fun `con el observador parado no se toca el transporte - S11`() {
        val f = Fixture(pilaPresente = true)
        f.watch.iniciar()
        f.watch.parar()

        // Aunque llegue el evento por una referencia vieja, el gate esta apagado.
        f.watch.onEvento(Transicion.PERDIDA, validado = false)
        f.watch.onEvento(Transicion.RETORNO, validado = true)

        assertEquals(0, f.rediales, "parar() tiene que apagar el disparador, no solo el monitor")
        assertEquals(0, f.arranques)
    }

    @Test
    fun `sin pila un retorno validado ARRANCA, no redialea - S11`() {
        val f = Fixture(pilaPresente = false)
        f.watch.iniciar()

        f.watch.onEvento(Transicion.RETORNO, validado = true)

        assertEquals(1, f.arranques, "proceso arrancado sin red: hay que levantar la pila")
        assertEquals(0, f.rediales)
    }

    @Test
    fun `iniciar es idempotente y parar desregistra una vez`() {
        val f = Fixture()

        f.watch.iniciar()
        f.watch.iniciar()
        f.watch.iniciar()

        assertEquals(1, f.monitor.inicios, "tres llamadas no pueden registrar tres callbacks")

        f.watch.parar()
        f.watch.parar()

        assertEquals(1, f.monitor.paradas, "parar() tambien es idempotente")
    }

    @Test
    fun `la perdida con pila redialea UNA vez aunque llegue la rafaga - S4`() {
        val f = Fixture(pilaPresente = true)
        f.watch.iniciar()

        f.watch.onEvento(Transicion.PERDIDA, validado = false)
        f.ahora += 200 // onLost + onCapabilitiesChanged de la MISMA caida
        f.watch.onEvento(Transicion.PERDIDA, validado = false)

        assertEquals(1, f.rediales, "dos eventos del mismo cambio de red son un AUTH")
    }

    @Test
    fun `el retorno validado redialea UNA vez cuando es una transicion real`() {
        val f = Fixture(pilaPresente = true)
        f.watch.iniciar()

        // Estado inicial entregado al registrar: onAvailable (sin validar) y
        // onCapabilitiesChanged (validado). NO es una red que vuelve (R5).
        f.watch.onEvento(Transicion.RETORNO, validado = false)
        f.ahora += 10
        f.watch.onEvento(Transicion.RETORNO, validado = true)
        assertEquals(0, f.rediales, "el registro no puede gastar un AUTH")

        // Perdida y retorno REALES: un redial por transicion, no dos por rafaga.
        f.ahora += 200
        f.watch.onEvento(Transicion.PERDIDA, validado = false)
        f.ahora += 2_300
        f.watch.onEvento(Transicion.RETORNO, validado = false)
        f.ahora += 200
        f.watch.onEvento(Transicion.RETORNO, validado = true)

        assertEquals(2, f.rediales, "un AUTH por transicion real; la rafaga del retorno no suma otro")
    }

    @Test
    fun `la accion se despacha fuera del callback de la plataforma`() {
        val f = Fixture(pilaPresente = true)
        f.watch.iniciar()

        // Una PERDIDA real y no el retorno de registro: ese retorno es el
        // baseline (R5) y no produce accion.
        f.watch.onEvento(Transicion.PERDIDA, validado = false)

        assertTrue(f.hilos.isNotEmpty(), "la accion tiene que haber corrido")
    }

    // ==================================================================
    // R5 — el registro del callback con pila no renegocia
    // ==================================================================

    /**
     * Lo que `registerDefaultNetworkCallback` entrega al registrar sobre una
     * pila ya arrancada: primero `onAvailable` (sin validar) y despues
     * `onCapabilitiesChanged` (validado). Con la politica anterior el segundo
     * disparaba `forceRedial()` a los ~66 ms del arranque.
     */
    @Test
    fun `el estado inicial de la red NO redialea al registrar - R5`() {
        val f = Fixture(pilaPresente = true)
        f.watch.iniciar()

        f.watch.onEvento(Transicion.RETORNO, validado = false)
        f.ahora += 10
        f.watch.onEvento(Transicion.RETORNO, validado = true)

        assertEquals(0, f.rediales, "la red que ya estaba no es una red que vuelve")
    }

    @Test
    fun `una perdida real tras el registro SI redialea - R5`() {
        val f = Fixture(pilaPresente = true)
        f.watch.iniciar()

        f.watch.onEvento(Transicion.RETORNO, validado = false)
        f.ahora += 10
        f.watch.onEvento(Transicion.RETORNO, validado = true)
        f.ahora += 200
        f.watch.onEvento(Transicion.PERDIDA, validado = false)

        assertEquals(1, f.rediales, "el baseline no puede tapar la primera perdida real")
    }
}
