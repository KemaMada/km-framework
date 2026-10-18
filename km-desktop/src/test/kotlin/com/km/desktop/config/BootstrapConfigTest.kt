package com.km.desktop.config

import com.km.transport.JvmRelayTransport
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * 4.1 — El bootstrap se CONFIGURA, tiene VARIOS, y nunca devuelve vacio.
 *
 * ## LOS TRES CASOS DE LA PRECEDENCIA
 *
 *  - El override de linea de comandos gana.
 *  - Tres endpoints sobreviven, EN ORDEN.
 *  - Una entrada en blanco LANZA, y nunca devuelve una lista vacia en silencio.
 *
 * ## POR QUE EL DEFAULT SE LEE DEL MOTOR
 *
 * Porque `DEFAULT` es una `val` que lee `JvmRelayTransport.DEFAULT_ENDPOINT` en
 * cada llamada, no una constante escrita aqui. Escribirla seria una segunda
 * fuente de verdad que divergiria en cuanto el motor cambiase el suyo, y el
 * desktop seguiria conectando al destino viejo sin que nadie lo notase.
 */
class BootstrapConfigTest {

    @Test
    fun `sin configuracion, la lista es el default DEL MOTOR`() {
        val resuelta = BootstrapConfig.resolve(cli = emptyList(), entorno = emptyMap())

        assertEquals(1, resuelta.size, "un solo destino por defecto")
        assertEquals(
            JvmRelayTransport.DEFAULT_ENDPOINT,
            resuelta.single(),
            "y es el del motor, leido de alli y no copiado aqui",
        )
    }

    @Test
    fun `el override de linea de comandos GANA al entorno y al default`() {
        val resuelta = BootstrapConfig.resolve(
            cli = listOf("wss://override.invalid/km"),
            entorno = mapOf(
                BootstrapConfig.ENV_MUCHOS to "wss://entorno-a.invalid,wss://entorno-b.invalid",
                BootstrapConfig.ENV_UNO to "wss://entorno-singular.invalid",
            ),
        )

        assertEquals(
            listOf("wss://override.invalid/km"),
            resuelta,
            "lo que se escribio en ESTA invocacion gana sobre el entorno y sobre el default",
        )
    }

    @Test
    fun `el entorno gana al default y el plural gana al singular`() {
        val conPlural = BootstrapConfig.resolve(
            cli = emptyList(),
            entorno = mapOf(
                BootstrapConfig.ENV_MUCHOS to "wss://a.invalid,wss://b.invalid",
                BootstrapConfig.ENV_UNO to "wss://singular.invalid",
            ),
        )
        assertEquals(
            listOf("wss://a.invalid", "wss://b.invalid"),
            conPlural,
            "KM_BOOTSTRAPS expresa varios y por eso gana sobre la singular",
        )

        val conSingular = BootstrapConfig.resolve(
            cli = emptyList(),
            entorno = mapOf(BootstrapConfig.ENV_UNO to "wss://singular.invalid"),
        )
        assertEquals(
            listOf("wss://singular.invalid"),
            conSingular,
            "sin la plural, la singular sigue valiendo: es el nombre historico",
        )
    }

    @Test
    fun `TRES endpoints sobreviven EN ORDEN`() {
        val entrada = listOf("wss://uno.invalid", "wss://dos.invalid", "wss://tres.invalid")

        val resuelta = BootstrapConfig.resolve(cli = entrada, entorno = emptyMap())

        assertEquals(entrada, resuelta, "los tres, y en el orden en que se dieron: el orden es la politica")
    }

    @Test
    fun `los endpoints se recortan y los repetidos se quitan conservando el orden`() {
        val resuelta = BootstrapConfig.resolve(
            cli = listOf("  wss://a.invalid  ", "wss://b.invalid", "wss://a.invalid"),
            entorno = emptyMap(),
        )

        assertEquals(
            listOf("wss://a.invalid", "wss://b.invalid"),
            resuelta,
            "espacios fuera y repetidos dentro, sin alterar el orden de los que quedan",
        )
    }

    @Test
    fun `4-1 una entrada en blanco LANZA y NUNCA devuelve una lista vacia en silencio`() {
        val fallo = assertThrows(IllegalArgumentException::class.java) {
            BootstrapConfig.resolve(cli = listOf("wss://a.invalid", "   "), entorno = emptyMap())
        }

        assertTrue(
            fallo.message?.contains("--bootstrap") == true,
            "el mensaje NOMBRA la fuente, porque hay tres y el operador tiene que saber cual fallo: ${fallo.message}",
        )
        assertTrue(
            fallo.message?.contains("BLANCO") == true,
            "y dice que el problema es una entrada en blanco, no un destino que no exista: ${fallo.message}",
        )
    }

    @Test
    fun `una lista SOLO de blancos tambien lanza`() {
        assertThrows(IllegalArgumentException::class.java) {
            BootstrapConfig.resolve(cli = listOf("", "  "), entorno = emptyMap())
        }
    }

    @Test
    fun `una variable de entorno EN BLANCO se ignora y se cae al default`() {
        // Distinto del caso anterior, y a proposito: una variable de entorno
        // VACIA es "no configurada": es lo que pasa con un `.env` que no la define, mientras que una lista de argumentos con
        // un blanco en medio es un error de invocacion.
        val resuelta = BootstrapConfig.resolve(
            cli = emptyList(),
            entorno = mapOf(BootstrapConfig.ENV_MUCHOS to "   "),
        )

        assertEquals(
            listOf(JvmRelayTransport.DEFAULT_ENDPOINT),
            resuelta,
            "una variable vacia no configura nada: el cliente usa el default",
        )
    }

    @Test
    fun `un default vacio LANZA en vez de devolver una lista vacia`() {
        val fallo = assertThrows(IllegalArgumentException::class.java) {
            BootstrapConfig.resolve(cli = emptyList(), entorno = emptyMap(), porDefecto = emptyList())
        }

        assertTrue(
            fallo.message?.contains("ningun bootstrap") == true,
            "el fallo dice que no hay ningun destino, no devuelve []: ${fallo.message}",
        )
    }

    /**
     * CONTROL NEGATIVO: la precedencia esta en la ORDEN correcta, no en
     * cualquier orden.
     *
     * Sin esta prueba, "el override gana" seria verdad con la precedencia
     * invertida si los tres casos no se cruzaran. Aqui se comprueba que el
     * entorno gana al default y que la linea de comandos gana al entorno, que es
     * exactamente lo que un orden equivocado romperia.
     */
    @Test
    fun `control negativo invertir la precedencia cambia el resultado`() {
        val soloEntorno = BootstrapConfig.resolve(
            cli = emptyList(),
            entorno = mapOf(BootstrapConfig.ENV_UNO to "wss://entorno.invalid"),
            porDefecto = listOf("wss://default.invalid"),
        )
        assertEquals(listOf("wss://entorno.invalid"), soloEntorno, "el entorno gana al default")

        val conTodo = BootstrapConfig.resolve(
            cli = listOf("wss://cli.invalid"),
            entorno = mapOf(BootstrapConfig.ENV_UNO to "wss://entorno.invalid"),
            porDefecto = listOf("wss://default.invalid"),
        )
        assertEquals(listOf("wss://cli.invalid"), conTodo, "la linea de comandos gana al entorno")

        assertNotEquals(
            listOf("wss://default.invalid"),
            soloEntorno,
            "y el default NO gana nunca cuando hay otra fuente: una precedencia invertida lo haria",
        )
    }

    /**
     * CONTROL POSITIVO: las tres fuentes producen RESULTADOS DISTINTOS.
     *
     * El pretesto de toda la clase: si las tres dieran la misma lista, las
     * aserciones de precedencia serian verde por construccion del arnes.
     */
    @Test
    fun `control positivo las tres fuentes dan listas DISTINTAS`() {
        val delDefault = BootstrapConfig.resolve(emptyList(), emptyMap())
        val delEntorno = BootstrapConfig.resolve(emptyList(), mapOf(BootstrapConfig.ENV_UNO to "wss://entorno.invalid"))
        val deLaCli = BootstrapConfig.resolve(listOf("wss://cli.invalid"), mapOf(BootstrapConfig.ENV_UNO to "wss://entorno.invalid"))

        assertNotEquals(delDefault, delEntorno, "default y entorno NO dan lo mismo")
        assertNotEquals(delEntorno, deLaCli, "entorno y linea de comandos NO dan lo mismo")
        assertNotEquals(delDefault, deLaCli, "default y linea de comandos NO dan lo mismo")
        assertEquals(3, setOf(delDefault, delEntorno, deLaCli).size, "tres listas distintas de verdad")
    }

    /**
     * CONTROL NEGATIVO: el separador de `KM_BOOTSTRAPS` parte de verdad.
     *
     * Sin esto, "tres endpoints sobreviven" seria verdad por construccion de la
     * arnes con la linea de comandos, y la variable de entorno podria estar rota
     * sin que nadie lo notase.
     */
    @Test
    fun `control negativo la variable de entorno SI parte por coma`() {
        val resuelta = BootstrapConfig.resolve(
            cli = emptyList(),
            entorno = mapOf(BootstrapConfig.ENV_MUCHOS to "wss://a.invalid, wss://b.invalid ,wss://c.invalid"),
        )

        assertEquals(
            listOf("wss://a.invalid", "wss://b.invalid", "wss://c.invalid"),
            resuelta,
            "y tambien recorta los espacios que deja la coma",
        )
    }
}