package com.example.keymessage.ui

import com.example.keymessage.ui.nav.Pantalla
import com.example.keymessage.ui.nav.TuiNavegacion
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 8.3 — El Back de Android por NIVELES (verify #381 C3).
 *
 * ## EL DEFECTO QUE VIGILA
 *
 * No existia ningun `BackHandler` en todo el modulo: el back del sistema
 * terminaba la Activity en CUALQUIER pantalla, en vez de volver a la anterior.
 * La task 8.3 afirmaba "BackHandler a INICIO" y era inexacto.
 *
 * ## QUE SE PRUEBA
 *
 * La pila es pura ([TuiNavegacion]) y por eso se puede afirmar en JVM:
 *  - una pantalla secundaria desapila a la anterior;
 *  - en la raiz (`INICIO`) no hay anterior: `volver` devuelve `null` y el
 *    sistema trata el back (sale de la app);
 *  - el cierre de un dialogo con navegacion (`sustituir`) NO deja el dialogo
 *    debajo: el back desde el chat vuelve a INICIO, no reabre IMPORTAR;
 *  - repetir la misma pantalla no apila copias (un back "que no hace nada").
 */
class TuiNavegacionTest {

    @Test
    fun `la pila arranca en INICIO`() {
        assertEquals(listOf(Pantalla.INICIO), TuiNavegacion.inicial())
        assertFalse(TuiNavegacion.puedeVolver(TuiNavegacion.inicial()))
    }

    @Test
    fun `avanzar apila y volver regresa a la pantalla anterior`() {
        val pila = TuiNavegacion.avanzar(TuiNavegacion.inicial(), Pantalla.IMPORTAR)

        assertEquals(listOf(Pantalla.INICIO, Pantalla.IMPORTAR), pila)
        assertTrue(TuiNavegacion.puedeVolver(pila))
        assertEquals(listOf(Pantalla.INICIO), TuiNavegacion.volver(pila))
    }

    @Test
    fun `en la raiz volver devuelve null y el back lo trata el sistema`() {
        assertNull(
            TuiNavegacion.volver(TuiNavegacion.inicial()),
            "sin pantalla anterior, el BackHandler se desactiva y el back sale de la app",
        )
    }

    @Test
    fun `navegacion anidada vuelve por niveles`() {
        val pila = TuiNavegacion.avanzar(
            TuiNavegacion.avanzar(TuiNavegacion.inicial(), Pantalla.DIAGNOSTICO),
            Pantalla.FRASE,
        )

        assertEquals(listOf(Pantalla.INICIO, Pantalla.DIAGNOSTICO), TuiNavegacion.volver(pila))
    }

    /**
     * Importar CIERRA el dialogo y abre el chat. Si se apilara, el back desde el
     * chat reabriria el dialogo (con su `OwnerDeApertura` recreado y el estado
     * de import perdido). `sustituir` deja la pila en [INICIO, CONVERSACION].
     */
    @Test
    fun `importar con exito sustituye el dialogo por el chat`() {
        val conDialogo = TuiNavegacion.avanzar(TuiNavegacion.inicial(), Pantalla.IMPORTAR)
        val trasImportar = TuiNavegacion.sustituir(conDialogo, Pantalla.CONVERSACION)

        assertEquals(listOf(Pantalla.INICIO, Pantalla.CONVERSACION), trasImportar)
        assertEquals(
            listOf(Pantalla.INICIO),
            TuiNavegacion.volver(trasImportar),
            "el back desde el chat NO reabre el dialogo de importar",
        )
    }

    @Test
    fun `repetir la pantalla actual no apila copias`() {
        val pila = TuiNavegacion.avanzar(TuiNavegacion.inicial(), Pantalla.DIAGNOSTICO)
        assertEquals(pila, TuiNavegacion.avanzar(pila, Pantalla.DIAGNOSTICO))
    }

    @Test
    fun `el onboarding forzado reinicia la pila con FRASE como raiz`() {
        val pila = TuiNavegacion.reiniciar(Pantalla.FRASE)

        assertEquals(listOf(Pantalla.FRASE), pila)
        assertNull(
            TuiNavegacion.volver(pila),
            "en el onboarding forzado no hay INICIO detras: el back sale, no salta el aviso",
        )
    }

    @Test
    fun `sustituir sobre una pila vacia no pierde el destino`() {
        assertEquals(
            listOf(Pantalla.CONVERSACION),
            TuiNavegacion.sustituir(emptyList(), Pantalla.CONVERSACION),
        )
    }

    @Test
    fun `las funciones NO mutan la lista de entrada`() {
        val original = TuiNavegacion.inicial()
        TuiNavegacion.avanzar(original, Pantalla.IMPORTAR)
        TuiNavegacion.volver(original)

        assertEquals(listOf(Pantalla.INICIO), original)
    }
}
