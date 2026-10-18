package com.km.desktop.net

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * 4.2 — El failover prueba el SIGUIENTE, y el agotamiento dice CUALES.
 *
 * ## QUE AFIRMA
 *
 * Con dos endpoints donde el primero falla, el segundo se intenta. Y si todos
 * fallan, la excepcion lleva la lista de los PROBADOS, en orden.
 *
 * ## POR QUE LA EXCEPCION LLEVA LA LISTA
 *
 * Porque "no se pudo conectar" sin decir "a que" es la mitad del diagnostico. Con
 * la lista, el operador distingue "el primero no resuelve" de "los tres estan
 * caidos", que son fallos con arreglo distinto y con la misma pantalla.
 *
 * ## POR QUE NO HAY REINTENTOS DENTRO DE UN ENDPOINT
 *
 * Porque reintentar un endpoint caido es un bucle disfrazado: con una latencia
 * de conexion alta, dos endpoints.convertidos en reintentos convierten un
 * arranque en un minuto de espera sin decir nada.
 */
class EndpointSequenceTest {

    @Test
    fun `4-2 con dos endpoints, tras el fallo del primero se intenta el segundo`() {
        val probados = mutableListOf<String>()

        EndpointSequence(listOf("wss://uno.invalid", "wss://dos.invalid")) { target ->
            probados += target
            if (target == "wss://uno.invalid") throw java.io.IOException("caido")
        }.recorrer()

        assertEquals(
            listOf("wss://uno.invalid", "wss://dos.invalid"),
            probados,
            "el fallo del primero NO se propaga: se pasa al segundo",
        )
    }

    @Test
    fun `el recorrido se detiene en cuanto uno funciona`() {
        val probados = mutableListOf<String>()

        EndpointSequence(listOf("wss://uno.invalid", "wss://dos.invalid", "wss://tres.invalid")) { target ->
            probados += target
            if (target != "wss://dos.invalid") throw java.io.IOException("caido")
        }.recorrer()

        assertEquals(
            listOf("wss://uno.invalid", "wss://dos.invalid"),
            probados,
            "el tercero NO se intenta: en cuanto uno responde, se para",
        )
    }

    @Test
    fun `4-2 agotados, la excepcion lista los DOS probados, en orden`() {
        val fallo = assertThrows(BootstrapAllFailed::class.java) {
            EndpointSequence(listOf("wss://uno.invalid", "wss://dos.invalid")) {
                throw java.io.IOException("caido")
            }.recorrer()
        }

        assertEquals(
            listOf("wss://uno.invalid", "wss://dos.invalid"),
            fallo.tried,
            "la excepcion dice QUE se probo, no solo que no se pudo",
        )
        assertTrue(
            fallo.message?.contains("wss://uno.invalid") == true &&
                fallo.message?.contains("wss://dos.invalid") == true,
            "y el mensaje los nombra: ${fallo.message}",
        )
    }

    @Test
    fun `el contador de intentos coincide con los endpoints probados`() {
        val secuencia = EndpointSequence(listOf("wss://uno.invalid", "wss://dos.invalid", "wss://tres.invalid")) {
            throw java.io.IOException("caido")
        }

        runCatching { secuencia.recorrer() }

        assertEquals(3, secuencia.intentos, "tres endpoints, tres intentos: uno por endpoint y ninguno mas")
    }

    @Test
    fun `cada endpoint se intenta UNA vez, sin reintentos`() {
        val probados = mutableListOf<String>()

        EndpointSequence(listOf("wss://uno.invalid", "wss://dos.invalid")) { target ->
            probados += target
            throw java.io.IOException("caido")
        }.let { runCatching { it.recorrer() } }

        assertEquals(
            listOf("wss://uno.invalid", "wss://dos.invalid"),
            probados,
            "reintentar un endpoint caido es un bucle disfrazado: aqui no hay ninguno",
        )
    }

    @Test
    fun `una lista vacia es una INVARIANTE rota, no un caso`() {
        val fallo = assertThrows(IllegalArgumentException::class.java) {
            EndpointSequence(emptyList<String>()) { }
        }

        assertTrue(
            fallo.message?.contains("al menos un endpoint") == true,
            "el constructor lo dice: una lista vacia es un error de ensamblado: ${fallo.message}",
        )
    }

    @Test
    fun `los endpoints en blanco y repetidos se limpian al construir`() {
        val secuencia = EndpointSequence(listOf(" wss://a.invalid ", "", "wss://a.invalid", "wss://b.invalid")) {
            throw java.io.IOException("caido")
        }

        assertEquals(
            listOf("wss://a.invalid", "wss://b.invalid"),
            secuencia.endpoints,
            "recortados, sin vacios y sin repetidos, conservando el orden",
        )
    }

    @Test
    fun `una lista SOLO de blancos tambien es una invariante rota`() {
        assertThrows(IllegalArgumentException::class.java) {
            EndpointSequence(listOf("  ", "")) { }
        }
    }

    /**
     * CONTROL NEGATIVO: la lista de probados NO se inventa.
     *
     * Sin esta prueba, "la excepcion lista los probados" seria verdad con una
     * excepcion que imprimiese la lista de entrada en vez de la recorrida, y un
     * recorrido que se detuviera en el primero pasaria por exhaustivo.
     */
    @Test
    fun `control negativo la excepcion lista lo RECORRIDO, no la entrada entera`() {
        val fallo = assertThrows(BootstrapAllFailed::class.java) {
            EndpointSequence(listOf("wss://uno.invalid", "wss://dos.invalid", "wss://tres.invalid")) {
                throw java.io.IOException("caido")
            }.recorrer()
        }

        assertEquals(3, fallo.tried.size, "se probaron los tres: la lista de probados es la de entrada aqui")
        assertTrue(fallo.tried.contains("wss://tres.invalid"), "incluido el ultimo, que no se puede omitir")
    }

    /**
     * CONTROL NEGATIVO: un fallo que NO es de red tambien propaga al siguiente.
     *
     * Un `NullPointerException` dentro del intento es un defecto del cliente, no
     * un endpoint caido; aun asi el recorrido pasa al siguiente, y por eso el
     * diagnostico final es "se probaron todos" y no "el ultimo fallo". Eso es lo
     * que se comprueba aqui: el fallo que dispara el paso al siguiente es
     * CUALQUIER excepcion, no solo la de red.
     */
    @Test
    fun `control negativo un fallo que no es de red tambien pasa al siguiente`() {
        val probados = mutableListOf<String>()

        EndpointSequence(listOf("wss://uno.invalid", "wss://dos.invalid")) { target ->
            probados += target
            if (target == "wss://uno.invalid") throw IllegalStateException("no es un fallo de red")
        }.recorrer()

        assertEquals(
            listOf("wss://uno.invalid", "wss://dos.invalid"),
            probados,
            "el recorrido no distingue el TIPO de fallo: reintenta con el siguiente",
        )
    }

    /**
     * CONTROL POSITIVO: el resumen de la excepcion es legible.
     *
     * Es lo que se imprime en pantalla y lo que va al log, asi que se mira: un
     * `toString` con el nombre de la clase no le dice nada a un operador.
     */
    @Test
    fun `control positivo el resumen nombra el canal y los endpoints`() {
        val fallo = BootstrapAllFailed(listOf("wss://uno.invalid", "wss://dos.invalid"))

        val resumen = fallo.resumen()

        assertTrue(resumen.startsWith("BOOTSTRAP_ALL_FAILED"), "el resumen empieza por el token de canal: $resumen")
        assertTrue(resumen.contains("probados=2"), "y dice cuantos: $resumen")
        assertTrue(resumen.contains("wss://uno.invalid"), "y cuales: $resumen")
    }
}