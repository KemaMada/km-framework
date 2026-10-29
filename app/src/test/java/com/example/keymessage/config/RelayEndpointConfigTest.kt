package com.example.keymessage.config

import com.km.transport.JvmRelayTransport
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * 3.1 — Precedencia de la configuracion de arranque, y lista ORDENADA.
 *
 * ## LOS CASOS
 *
 *  - El override de runtime gana sobre todo.
 *  - Sin override, gana `BuildConfig`.
 *  - Sin ninguno de los dos, el default DEL MOTOR —no una copia escrita en la
 *    app—.
 *  - Con dos o mas endpoints, la lista sale ORDENADA y COMPLETA. El failover es
 *    de `EndpointSequence`; aqui se afirma que la lista que le llega no ha
 *    perdido nada por el camino.
 *  - Una entrada en blanco LANZA. Una lista que se filtra en silencio acaba
 *    vacia, y una lista vacia en el arranque se parece muchisimo a "el rele no
 *    contesta".
 */
class RelayEndpointConfigTest {

    @Test
    fun `el override de runtime gana sobre BuildConfig y sobre el default`() {
        val resuelta = RelayEndpointConfig.resolve(
            runtimeOverride = "wss://override.invalid/km",
            buildConfig = "wss://build.invalid/km",
        )

        assertEquals(listOf("wss://override.invalid/km"), resuelta.endpoints, "gana el override")
        assertEquals(
            ResolvedEndpoints.Source.RUNTIME_OVERRIDE,
            resuelta.source,
            "y se DECLARA de donde salio: sin esto no se puede diagnosticar un arranque raro",
        )
    }

    @Test
    fun `sin override gana BuildConfig`() {
        val resuelta = RelayEndpointConfig.resolve(
            runtimeOverride = null,
            buildConfig = "wss://build.invalid/km",
        )

        assertEquals(listOf("wss://build.invalid/km"), resuelta.endpoints)
        assertEquals(ResolvedEndpoints.Source.BUILD_CONFIG, resuelta.source)
    }

    @Test
    fun `sin ninguno gana el default DEL MOTOR`() {
        val resuelta = RelayEndpointConfig.resolve(runtimeOverride = null, buildConfig = null)

        assertEquals(listOf(JvmRelayTransport.DEFAULT_ENDPOINT), resuelta.endpoints, "el default del motor, sin copiarlo")
        assertEquals("wss://keymessage.xyz/km", resuelta.preferred, "y es el endpoint documentado del proyecto")
        assertEquals(ResolvedEndpoints.Source.DEFAULT, resuelta.source)
    }

    @Test
    fun `varios endpoints se conservan EN ORDEN`() {
        val resuelta = RelayEndpointConfig.resolve(
            runtimeOverride = " wss://uno.invalid/km , wss://dos.invalid/km ,wss://tres.invalid/km ",
        )

        assertEquals(
            listOf("wss://uno.invalid/km", "wss://dos.invalid/km", "wss://tres.invalid/km"),
            resuelta.endpoints,
            "la lista mantiene el orden y recorta los espacios: el failover depende de este orden",
        )
        assertEquals("wss://uno.invalid/km", resuelta.preferred, "y el primero es el preferido")
    }

    @Test
    fun `una entrada EN BLANCO se rechaza con excepcion`() {
        val fallo = assertFailsWith<RelayEndpointConfigException> {
            RelayEndpointConfig.resolve(runtimeOverride = "wss://uno.invalid/km,,wss://dos.invalid/km")
        }
        assertTrue(
            fallo.message!!.contains("posicion 1"),
            "el fallo dice QUE entrada y en que posicion: ${fallo.message}",
        )
    }

    @Test
    fun `un override EN BLANCO se rechaza en vez de caer al default`() {
        // La distincion que importa: "no configurado" cae al siguiente nivel,
        // "configurado pero vacio" es un error de operacion.
        assertFailsWith<RelayEndpointConfigException> {
            RelayEndpointConfig.resolve(runtimeOverride = "   ", buildConfig = "wss://build.invalid/km")
        }
    }

    @Test
    fun `una lista de un solo elemento NO es un caso degenerado`() {
        val resuelta = RelayEndpointConfig.resolve(runtimeOverride = "wss://uno.invalid/km")

        assertEquals(1, resuelta.endpoints.size, "un solo endpoint es valido")
        assertEquals("wss://uno.invalid/km", resuelta.preferred)
    }

    @Test
    fun `control un endpoint con espacios conserva su forma`() {
        // El pretesto del recortado: si el detector de espacios no existiera, el
        // test de "varios endpoints en orden"aria probando el recorte y no el orden.
        val conEspacios = RelayEndpointConfig.resolve(runtimeOverride = "  wss://a.invalid/km  ")
        assertEquals("wss://a.invalid/km", conEspacios.preferred, "los espacios sobrantes se van")
    }
}