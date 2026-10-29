package com.example.keymessage.net

import org.junit.Test
import kotlin.test.assertEquals

/**
 * La politica de red, SIN Android y SIN reloj real.
 *
 * ## QUE SE AFIRMA (spec #352)
 *
 *  - S1: una perdida redialea aunque no haya red validada (perder la red es el
 *    disparador; esperar al retorno es lo que dejaba el motor mintiendo ONLINE).
 *  - S2/S4: el retorno validado redialea, pero la rafaga de eventos de UN mismo
 *    cambio no produce dos `AUTH`.
 *  - S11: sin pila no hay redial.
 *
 * El modo de fallo que estas pruebas cierran es el de `#349`: cero obser-
 * vadores de red, y por tanto cero disparadores. Una politica que dispara de
 * mas (un redial por cada `onCapabilitiesChanged`) es el otro extremo y tambien
 * se fija aqui.
 */
class RedialPolicyTest {

    private fun policy() = RedialPolicy()

    @Test
    fun `la perdida redialea aunque no haya red validada - S1`() {
        val p = policy()

        assertEquals(
            RedialDecision.REDIAL,
            p.decide(Transicion.PERDIDA, validado = false, pilaPresente = true, nowMs = 1_000),
            "perder la red es el disparador: si se espera al retorno, el socket viejo sigue mintiendo ONLINE",
        )
    }

    @Test
    fun `el retorno validado redialea - S2`() {
        val p = policy()
        // El PRIMER retorno validado es el baseline (R5): no es una red que
        // volvio, es la que ya habia. El retorno que redialea es el que sigue a
        // una perdida real, que es el hecho al que S2 se refiere.
        assertEquals(
            RedialDecision.REDIAL,
            p.decide(Transicion.PERDIDA, validado = false, pilaPresente = true, nowMs = 1_000),
            "precondicion: la perdida es real y redialea",
        )
        assertEquals(
            RedialDecision.REDIAL,
            p.decide(Transicion.RETORNO, validado = true, pilaPresente = true, nowMs = 3_500),
            "la red que VUELVE, con validacion, adelanta el backoff",
        )
    }

    @Test
    fun `el retorno sin validar se ignora`() {
        assertEquals(
            RedialDecision.IGNORAR,
            policy().decide(Transicion.RETORNO, validado = false, pilaPresente = true, nowMs = 1_000),
            "una red anunciada no es una red usable: el AUTH fallaria",
        )
    }

    @Test
    fun `dos eventos iguales seguidos no redialean dos veces - S4`() {
        val p = policy()
        assertEquals(RedialDecision.REDIAL, p.decide(Transicion.PERDIDA, false, true, nowMs = 1_000))
        assertEquals(
            RedialDecision.IGNORAR,
            p.decide(Transicion.PERDIDA, false, true, nowMs = 1_100),
            "la rafaga de onLost/onCapabilitiesChanged no puede producir dos AUTH",
        )
    }

    @Test
    fun `perdida y retorno pegados colapsan en UN redial - S2 sin doble AUTH`() {
        val p = policy()
        assertEquals(RedialDecision.REDIAL, p.decide(Transicion.PERDIDA, false, true, nowMs = 1_000))
        assertEquals(
            RedialDecision.IGNORAR,
            p.decide(Transicion.RETORNO, true, true, nowMs = 1_400),
            "el retorno de la MISMA transicion de red cae dentro del intervalo minimo (2 s)",
        )
    }

    @Test
    fun `una transicion distinta tras el intervalo si dispara - S2`() {
        val p = policy()
        assertEquals(RedialDecision.REDIAL, p.decide(Transicion.PERDIDA, false, true, nowMs = 1_000))
        assertEquals(
            RedialDecision.REDIAL,
            p.decide(Transicion.RETORNO, true, true, nowMs = 3_500),
            "pasado el intervalo, la red que vuelve adelanta el backoff",
        )
    }

    @Test
    fun `la misma transicion fuera del intervalo NO vuelve a disparar`() {
        val p = policy()
        assertEquals(RedialDecision.REDIAL, p.decide(Transicion.PERDIDA, false, true, nowMs = 1_000))
        assertEquals(
            RedialDecision.IGNORAR,
            p.decide(Transicion.PERDIDA, false, true, nowMs = 60_000),
            "otra interfaz que cae no es otro cambio de red: el transporte ya reconecta",
        )
    }

    @Test
    fun `sin pila una perdida no redialea - S11`() {
        assertEquals(
            RedialDecision.IGNORAR,
            policy().decide(Transicion.PERDIDA, validado = false, pilaPresente = false, nowMs = 1_000),
            "sin pila no hay socket que rehacer; arrancar es otra decision",
        )
    }

    @Test
    fun `sin pila y con red validada se REARRANCA, no se redialea`() {
        assertEquals(
            RedialDecision.REARRANCAR,
            policy().decide(Transicion.RETORNO, validado = true, pilaPresente = false, nowMs = 1_000),
            "proceso arrancado sin red: cuando la red aparece hay que levantar la pila, no reconectar",
        )
    }

    @Test
    fun `sin pila y sin red validada se ignora`() {
        assertEquals(
            RedialDecision.IGNORAR,
            policy().decide(Transicion.RETORNO, validado = false, pilaPresente = false, nowMs = 1_000),
        )
    }

    @Test
    fun `un evento ignorado NO mueve el reloj del debounce`() {
        val p = policy()
        assertEquals(
            RedialDecision.IGNORAR,
            p.decide(Transicion.RETORNO, validado = false, pilaPresente = true, nowMs = 500),
            "onAvailable del registro, sin validar: no es una decision",
        )
        assertEquals(
            RedialDecision.IGNORAR,
            p.decide(Transicion.RETORNO, validado = true, pilaPresente = true, nowMs = 600),
            "el estado inicial validado se siembra como baseline",
        )
        assertEquals(
            RedialDecision.REDIAL,
            p.decide(Transicion.PERDIDA, validado = false, pilaPresente = true, nowMs = 1_000),
            "precondicion: una perdida real ejecuta y abre la ventana de 2 s",
        )
        assertEquals(
            RedialDecision.IGNORAR,
            p.decide(Transicion.RETORNO, validado = false, pilaPresente = true, nowMs = 2_500),
            "red anunciada: se ignora",
        )
        assertEquals(
            RedialDecision.IGNORAR,
            p.decide(Transicion.RETORNO, validado = true, pilaPresente = true, nowMs = 2_900),
            "validada, pero a 1,9 s de la perdida: cae en la ventana abierta en t=1000",
        )
        assertEquals(
            RedialDecision.REDIAL,
            p.decide(Transicion.RETORNO, validado = true, pilaPresente = true, nowMs = 3_100),
            "si los eventos ignorados hubieran movido el reloj, este seguiria dentro de la ventana",
        )
    }

    // ==================================================================
    // R5 — baseline: el estado que YA existia al registrar no redialea
    // ==================================================================

    @Test
    fun `el primer retorno validado con pila se siembra y NO redialea - R5`() {
        assertEquals(
            RedialDecision.IGNORAR,
            policy().decide(Transicion.RETORNO, validado = true, pilaPresente = true, nowMs = 1_000),
            "registerDefaultNetworkCallback entrega la red que ya habia: redialear ahi es el AUTH de los ~66 ms",
        )
    }

    @Test
    fun `una perdida REAL inmediatamente despues del baseline SI redialea - R5`() {
        val p = policy()
        assertEquals(
            RedialDecision.IGNORAR,
            p.decide(Transicion.RETORNO, validado = true, pilaPresente = true, nowMs = 1_000),
            "precondicion: el baseline se sembro",
        )
        assertEquals(
            RedialDecision.REDIAL,
            p.decide(Transicion.PERDIDA, validado = false, pilaPresente = true, nowMs = 1_100),
            "el baseline NO puede sembrar ultimoDisparoMs y tapar una perdida real dentro de los 2 s",
        )
    }

    @Test
    fun `un retorno posterior a una perdida si redialea - R5`() {
        val p = policy()
        assertEquals(RedialDecision.IGNORAR, p.decide(Transicion.RETORNO, true, true, nowMs = 1_000))
        assertEquals(RedialDecision.REDIAL, p.decide(Transicion.PERDIDA, false, true, nowMs = 1_100))
        assertEquals(
            RedialDecision.REDIAL,
            p.decide(Transicion.RETORNO, true, true, nowMs = 3_500),
            "el baseline solo tapa el PRIMER retorno; los reales siguen redialeando",
        )
    }

    @Test
    fun `la perdida inicial nunca siembra baseline - R5`() {
        assertEquals(
            RedialDecision.REDIAL,
            policy().decide(Transicion.PERDIDA, validado = false, pilaPresente = true, nowMs = 1_000),
            "perder la red es un hecho, no un estado inicial: PERDIDA nunca es baseline",
        )
    }

    @Test
    fun `sin pila el primer retorno validado REARRANCA y no se siembra - R5`() {
        val p = policy()
        assertEquals(
            RedialDecision.REARRANCAR,
            p.decide(Transicion.RETORNO, validado = true, pilaPresente = false, nowMs = 1_000),
            "proceso arrancado sin red: levantar la pila es otra decision, no un baseline",
        )
        assertEquals(
            RedialDecision.REDIAL,
            p.decide(Transicion.PERDIDA, validado = false, pilaPresente = true, nowMs = 3_500),
            "con la pila ya arriba, la primera perdida real redialea",
        )
        assertEquals(
            RedialDecision.REDIAL,
            p.decide(Transicion.RETORNO, validado = true, pilaPresente = true, nowMs = 5_600),
            "y el retorno que la sigue tambien: el REARRANCAR no sembro el baseline",
        )
    }
}
