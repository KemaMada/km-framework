package com.km.messaging

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Tarea 0.2 — la fixture [SecureWorld] sirve para algo.
 *
 * Este fichero no prueba logica del slice 4: su unico trabajo es que el
 * escenario del que dependen el resto de pruebas EXISTA y sea real. Un
 * escenario de pruebas que nadie ha ejecutado no es un escenario: es una
 * suposicion con sintaxis correcta.
 */
class SecureWorldSmokeTest {

    @Test
    fun `establece y ambos lados abren sesion`() {
        val mundo = SecureWorld.open()

        val a = assertNotNull(mundo.sessionA, "A debe tener sesion tras el establecimiento")
        val b = assertNotNull(mundo.sessionB, "B debe tener sesion tras el establecimiento")

        assertEquals(mundo.alice.device.identityId, a.localIdentity)
        assertEquals(mundo.bob.device.identityId, b.localIdentity)
        assertEquals(mundo.bob.device.identityId, a.remotePeerId)
        assertEquals(mundo.alice.device.identityId, b.remotePeerId)

        // El enlace esta CONECTED: sin eso `send` se rechaza y el guion de mas
        // abajo no probaria el cifrado sino el estado del binding.
        assertEquals(
            com.km.node.PeerTransportState.CONNECTED,
            a.bindingState(),
            "el binding de A debe estar conectado",
        )

        // Y el texto original llega entero a B.
        val texto = "hola desde la fixture del slice 4".toByteArray()
        val capturados = mundo.alice.captureOutbound()
        assertTrue(a.send(texto) is SendResult.Ok, "A debe poder enviar por su sesion")
        assertEquals(1, capturados.size, "un unico frame en el cable")

        b.onTransportBytes(capturados.single())
        assertContentEquals(texto, b.drainOutbound().single(), "B lee el texto original")
    }

    @Test
    fun `un mundo sin sesion deja el registro vacio`() {
        val mundo = SecureWorld.openWithoutSession()
        assertEquals(0, mundo.alice.registry.size(), "sin establecimiento no hay sesion registrada")
        assertEquals(null, mundo.alice.registry.find(mundo.bob.device.identityId))
    }
}