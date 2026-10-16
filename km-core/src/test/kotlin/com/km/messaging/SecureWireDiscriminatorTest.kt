package com.km.messaging

import com.km.codec.JsonEncoder
import com.km.frame.SecureFrameSpec
import com.km.model.IdentityId
import com.km.model.Message
import com.km.model.MessageId
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Tarea 1.1 — D-1: el discriminante decide sobre BYTES REALES, no sobre una idea.
 *
 * Los tres casos usan produccion real: el wire sale de `SecureMessagingSession`
 * con el ratchet de verdad y el JSON sale de `JsonEncoder`. Un predicado
 * clasificado contra bytes inventados clasificaria el predicado, no el sistema.
 */
class SecureWireDiscriminatorTest {

    @Test
    fun `un wire capturado de session send se clasifica como SecureFrame`() {
        val mundo = SecureWorld.open()
        val sesion = requireNotNull(mundo.sessionA)
        val capturados = mundo.alice.captureOutbound()

        assertTrue(sesion.send("contenido-que-no-es-constante-7731".toByteArray()) is SendResult.Ok)
        val wire = capturados.single()

        assertEquals(
            SecureFrameSpec.VERSION_OFFSET,
            0,
            "el campo de version ocupa el byte 0 en SecureFrame v1",
        )
        assertEquals(
            SecureFrameSpec.VERSION.toByte(),
            wire[SecureFrameSpec.VERSION_OFFSET],
            "todo SecureFrame v1 empieza por su version",
        )
        assertTrue(isSecureFrameWire(wire), "el wire de la sesion es un SecureFrame")
    }

    @Test
    fun `el JSON que emite JsonEncoder NO se clasifica como SecureFrame`() {
        val mensaje = Message(
            messageId = MessageId(UUID.randomUUID()),
            from = IdentityId("aa".repeat(32)),
            to = IdentityId("bb".repeat(32)),
            payload = "otro-contenido-distinto-4242".toByteArray(),
            timestamp = 1_700_000_000_000L,
        )
        val json = JsonEncoder().encodeMessage(mensaje).getOrThrow()

        assertEquals(
            '{'.code.toByte(),
            json[0],
            "JsonEncoder emite un objeto Jackson, que empieza por 0x7B",
        )
        assertFalse(isSecureFrameWire(json), "el camino legado NO es un SecureFrame")
    }

    @Test
    fun `un array vacio NO se clasifica como SecureFrame`() {
        val vacio = ByteArray(0)
        assertFalse(isSecureFrameWire(vacio), "no hay nada que clasificar")

        // Un array JSON vacio tambien empieza por 0x5B, no por 0x01.
        assertEquals(
            '['.code.toByte(),
            byteArrayOf('['.code.toByte(), ']'.code.toByte())[0],
            "un array empieza por 0x5B",
        )
        assertFalse(
            isSecureFrameWire(byteArrayOf('['.code.toByte(), ']'.code.toByte())),
            "un array vacio tampoco es un SecureFrame",
        )
    }
}