package com.example.keymessage.net

import com.example.keymessage.config.RelayEndpointConfig
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * 3.2 — Failover ORDENADO, con la lista de lo probado en el fallo.
 *
 * ## QUE AFIRMA
 *
 *  1. Con dos endpoints, si el primero falla se intenta el SEGUNDO. El caso que
 *     se caza es el de repetir el primero hasta el limite de espera: el usuario
 *     oye exactamente lo mismo y el diagnostico no avanza.
 *  2. Agotados todos, la excepcion [RelayEndpointsExhausted] NOMBRA los dos.
 *  3. El fallo de un endpoint NO es una excepcion: es un `Result`. Un rele que
 *     no responde es el resultado de un intento, y el siguiente tiene que poder
 *     intentarlo.
 */
class EndpointSequenceTest {

    private val primero = "wss://primero.invalid/km"
    private val segundo = "wss://segundo.invalid/km"
    private val tercero = "wss://tercero.invalid/km"

    @Test
    fun `si el primero falla se intenta el SEGUNDO`() {
        val intentados = mutableListOf<String>()
        val secuencia = EndpointSequence(listOf(primero, segundo))

        val valor = secuencia.connect { endpoint ->
            intentados += endpoint
            if (endpoint == primero) Result.failure(IllegalStateException("sin ruta al host $endpoint")) else Result.success("conectado")
        }

        assertEquals(listOf(primero, segundo), intentados, "se prueban EN ORDEN y una sola vez cada uno")
        assertEquals("conectado", valor, "el valor del que funciono")
    }

    @Test
    fun `el primero que funciona NO hace probar los siguientes`() {
        val intentados = mutableListOf<String>()
        val secuencia = EndpointSequence(listOf(primero, segundo, tercero))

        secuencia.connect { endpoint ->
            intentados += endpoint
            Result.success("ok")
        }

        assertEquals(listOf(primero), intentados, "en cuanto uno funciona, se para: probar el resto seria ruido")
    }

    @Test
    fun `agotados todos, la excepcion NOMBRA los probados`() {
        val secuencia = EndpointSequence(listOf(primero, segundo))

        val fallo = assertFailsWith<RelayEndpointsExhausted> {
            secuencia.connect { endpoint -> Result.failure(IllegalStateException("rechazado por $endpoint")) }
        }

        assertEquals(listOf(primero, segundo), fallo.tried, "la excepcion lleva la lista, en orden")
        assertTrue(
            fallo.message!!.contains(segundo),
            "y el mensaje la nombra: sin eso, 'sin conexion' no es accionable",
        )
    }

    @Test
    fun `un endpoint en blanco en la lista se RECHAZA al construir`() {
        assertFailsWith<IllegalArgumentException> { EndpointSequence(listOf(primero, "  ")) }
        assertFailsWith<IllegalArgumentException> { EndpointSequence(emptyList()) }
    }

    @Test
    fun `el fallo de un endpoint NO detiene el siguiente`() {
        // La excepcion que el intento LANZA tambien tiene que pasar al siguiente:
        // un endpoint que lanza al conectar es tan "fallo" como uno que devuelve
        // failure, y tragarsela seria dejar al usuario esperando en silencio.
        val intentados = mutableListOf<String>()
        val secuencia = EndpointSequence(listOf(primero, segundo))

        val valor = secuencia.connect<String> { endpoint ->
            intentados += endpoint
            if (endpoint == primero) throw IllegalStateException("el motor lanzo al conectar")
            Result.success("ok")
        }

        assertEquals(listOf(primero, segundo), intentados)
        assertEquals("ok", valor)
    }

    @Test
    fun `control la lista es la MISMA que la de configuracion y no se reordena`() {
        val configurada = RelayEndpointConfig.resolve(
            runtimeOverride = "$tercero,$primero,$segundo",
        ).endpoints
        val secuencia = EndpointSequence(configurada)
        val intentados = mutableListOf<String>()

        val fallo = assertFailsWith<RelayEndpointsExhausted> {
            secuencia.connect<String> { endpoint ->
                intentados += endpoint
                Result.failure(IllegalStateException("no"))
            }
        }

        assertEquals(
            listOf(tercero, primero, segundo),
            intentados,
            "la configuracion manda el orden: la secuencia no lo decide por su cuenta",
        )
        assertEquals(configurada, fallo.tried, "y la excepcion los lista en ese mismo orden")
    }
}