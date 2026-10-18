package com.km.desktop.support

import com.km.model.IdentityId
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * 0.2 — El arnes CUENTA bytes, y cuenta bytes de verdad.
 *
 * ## POR QUE ESTE TEST EXISTE ANTES QUE NINGUNO QUE LO USE
 *
 * Todas las afirmaciones de esta fase son de la forma "CERO bytes salieron" o
 * "el byte que sale NO contiene el texto". Las dos descansan en este doble, y un
 * doble que no cuenta lo que dice contar convierte las dos en frases bonitas.
 * Este test mira el doble antes de que nadie dependa de el.
 *
 * ## POR QUE NO SE USA `FakeTransportBackend` A SECO
 *
 * Porque `sendData` es alli una no-operacion documentada: cero bytes contados y
 * cero bytes esperados darian verde tanto si el camino funciona como si esta
 * roto. Por eso [RelayProbe] lo delega y lo cuenta por encima.
 *
 * ## CONTROLES
 *
 * - `el contenido contados es el que se metio`: si el doble guardara una copia
 *   compartida y otro la mutara, todos los tests de la fase medirian una cosa
 *   distinta de la que crees.
 * - `el pretesto NO constante`: un payload de texto repetido daria el mismo
 *   resultado mida lo que mida, y "contiene el payload" seria verdad por la
 *   forma del assert, no por lo que el doble midio.
 */
class RelayProbeSelfTest {

    private val identidadRemota = IdentityId("a".repeat(64))

    @Test
    fun `el doble cuenta los BYTES que el manager entrega, no las llamadas`() {
        val (_, probe) = relayProbeManager()

        // Contenido NO constante y de tamano PROPIO: una cadena repetida
        // daria la misma imagen con cualquier detector de bytes.
        val carga = "contenido del arnes que no se repite 0f3a9c".toByteArray(Charsets.UTF_8)
        probe.sendData(identidadRemota, carga)

        assertEquals(1, probe.enviados.size, "un envio es un envio")
        assertEquals(
            carga.size,
            probe.bytesSalientes,
            "y lo que se cuenta son BYTES: es lo que un rechazo afirma que son cero",
        )
        assertEquals(
            carga.toList(),
            probe.enviados.single().second.toList(),
            "los bytes contados son exactamente los que se metieron",
        )
    }

    @Test
    fun `el doble cuenta los bytes de un frame delivered, no los reordena`() {
        val (_, probe) = relayProbeManager()
        val carga = "segundo marco con longitud distinta a propósito 9911".toByteArray(Charsets.UTF_8)

        probe.deliver(identidadRemota, carga)

        assertEquals(1, probe.recibidos.size, "el cable de entrada se puede empujar a mano")
        assertEquals(carga.toList(), probe.recibidos.single().second.toList())
    }

    @Test
    fun `control negativo un envio de CERO bytes no se confunde con no enviar`() {
        val (_, probe) = relayProbeManager()

        probe.sendData(identidadRemota, ByteArray(0))

        // Esta es la asercion que separa "no salio nada" de "salio un frame
        // vacio". Sin ella, un doble que contara llamadas daria 1 en los dos
        // casos, y "cero bytes" seria indistinguible de "no hubo intento".
        assertEquals(1, probe.llamadas, "hubo UNA llamada")
        assertEquals(0, probe.bytesSalientes, "pero CERO bytes salieron")
    }

    @Test
    fun `control el doble no inventa bytes que nadie le dio`() {
        val (_, probe) = relayProbeManager()

        assertEquals(0, probe.enviados.size, "sin envios no hay envios")
        assertEquals(0, probe.bytesSalientes)
        assertTrue(probe.llamadas == 0)
    }

    @Test
    fun `el doble copia el contenido y no guarda una referencia viva`() {
        val (_, probe) = relayProbeManager()
        val carga = "original que se muta despues".toByteArray(Charsets.UTF_8)

        probe.sendData(identidadRemota, carga)
        carga[0] = 'X'.code.toByte()

        assertEquals(
            'o'.code.toByte(),
            probe.enviados.single().second[0],
            "lo contado es una COPIA: si guardara la referencia, mutar el buffer lo cambiaria",
        )
    }
}