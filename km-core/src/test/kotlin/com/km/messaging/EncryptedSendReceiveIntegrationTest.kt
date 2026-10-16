package com.km.messaging

import com.km.codec.JsonDecoder
import com.km.frame.SecureFrameSpec
import com.km.model.Ack
import com.km.model.AckStatus
import com.km.model.Message
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Tarea 6.1 — el punto a punto, con lo que hay y sin lo que no hay.
 *
 * Dos `KeyMessageCoreImpl` de verdad, una sesion establecida por
 * `SecureSessionEstablishment`, instalada en el registro de cada uno, y un
 * mensaje que sale de A y llega al handler de B.
 *
 * ## LO QUE ESTE TEST NO DICE
 *
 * No dice que km-desktop haga esto. No lo hace: no tiene origen de
 * `VerifiedContactBundle`, luego no puede establecer sesion, luego su `send`
 * sigue por el camino legado EN CLARO. Eso esta declarado en el KDoc de
 * `SecureOutboundImpl` y en el CHANGELOG, y aqui no se escribe ningun test que
 * fije ese comportamiento en claro como si fuera el correcto: un test asi
 * documentaria un defecto como si fuera un requisito.
 */
class EncryptedSendReceiveIntegrationTest {

    private val secretos = listOf(
        "primera carga con contenido propio 0001".toByteArray(),
        "segunda, de otra longitud del todo, para que el sobre no mida igual".toByteArray(),
        byteArrayOf(1, 3, 3, 7, 7, 7, 9, 9, 9, 9),
    )

    @Test
    fun `de A a B por el camino cifrado, extremo a extremo`() {
        val mundo = SecureWorld.open()
        val recibidos = mutableListOf<Message>()
        mundo.bob.core.registerMessageHandler { recibidos.add(it) }
        val capturados = mundo.alice.captureOutbound()

        // --- A envia, y lo que sale por el cable se mira antes de entregarlo ---
        val ids = secretos.map { carga ->
            val creado = mundo.alice.core.createMessage(mundo.bob.device.identityId, carga).getOrThrow()
            mundo.alice.core.sendMessage(creado).getOrThrow()
            creado.messageId
        }
        assertEquals(secretos.size, capturados.size, "un frame por mensaje")
        assertEquals(0, mundo.alice.transport.sendCount, "nada por el camino legado en claro")

        capturados.forEach { wire ->
            assertEquals(SecureFrameSpec.VERSION.toByte(), wire[0], "cada frame es un SecureFrame v1")
        }
        secretos.forEachIndexed { i, carga ->
            assertFalse(capturados[i].containsBytesOf(carga), "la carga $i no viaja en claro")
        }

        // --- B recibe por su camino de par ---
        capturados.forEach { wire ->
            mundo.bob.deliverInboundAs(mundo.alice.device.identityId, mundo.bobToAlice, wire)
        }

        assertEquals(secretos.size, recibidos.size, "B recibe todos, en orden")
        recibidos.forEachIndexed { i, m ->
            assertContentEquals(secretos[i], m.payload, "carga $i intacta")
            assertEquals(ids[i], m.messageId, "messageId $i es el del sobre")
            assertEquals(mundo.alice.device.identityId, m.from, "emisor $i")
            assertEquals(mundo.bob.device.identityId, m.to, "receptor $i")
            assertTrue(m.state == com.km.model.MessageState.DELIVERED, "y queda DELIVERED en su registro")
        }

        // El ACK que B emite sale por el canal `ACK`, FIRMADO y EN CLARO. No se
        // finge lo contrario: se comprueba que existe, y el KDoc de `SecureInbound`
        // dice que sigue siendo eso.
        assertTrue(mundo.bob.transport.sentAcks.isNotEmpty(), "B responde con su ACK")
    }

    @Test
    fun `el mismo mensaje reintentado no llega dos veces a la aplicacion`() {
        val mundo = SecureWorld.open()
        val recibidos = mutableListOf<Message>()
        mundo.bob.core.registerMessageHandler { recibidos.add(it) }
        val capturados = mundo.alice.captureOutbound()

        val carga = "retransmision idempotente 2024-05-05".toByteArray()
        val creado = mundo.alice.core.createMessage(mundo.bob.device.identityId, carga).getOrThrow()
        mundo.alice.core.sendMessage(creado).getOrThrow()
        mundo.alice.core.retryMessage(creado.messageId).getOrThrow()

        assertEquals(2, capturados.size, "dos frames en el cable")
        assertTrue(
            capturados[0].contentEquals(capturados[1]),
            "pero el segundo es el MISMO wire, no uno re-cifrado",
        )

        // C-1: el segundo cable. Una fuga EN CLARO ADITIVA —el frame cifrado Y
        // ADEMAS el JSON por `Transport.send`— deja los dos frames del cable
        // identicos y no la ven las aserciones de arriba. Solo la ve el legado.
        assertEquals(
            0,
            mundo.alice.transport.sendCount,
            "el reintento NO manda nada en claro por Transport.send",
        )
        assertTrue(mundo.alice.transport.sent.isEmpty(), "el camino legado esta vacio, byte a byte")

        // B recibe el frame DOS veces, como en una red real, y entrega UNA.
        capturados.forEach { wire ->
            mundo.bob.deliverInboundAs(mundo.alice.device.identityId, mundo.bobToAlice, wire)
        }
        assertEquals(
            1,
            recibidos.size,
            "el segundo es un replay del ratchet y no llega a la aplicacion: lo que evita " +
                "el doble cifrado de verdad es esto",
        )
        assertContentEquals(carga, recibidos.single().payload)
    }

    @Test
    fun `el ACK de un envio cifrado correlaciona, se verifica y cierra el ciclo`() {
        // S-3: el `ACK` del camino cifrado EXISTIA pero no estaba probado, y
        // decir que existe no es probarlo. Lo que hace falta aqui es la
        // CORRELACION: que el `originalMessageId` que B firma sea el del SOBRE
        // que descifro, y que A lo verifique con la clave de firma de B y cierre
        // su propio mensaje.
        //
        // Y hace falta el resolver de claves de la fixture por una razon concreta:
        // sin el, `AckManagerImpl.onAckReceived` devuelve exito sin procesar nada
        // (defecto D3) y este test pasaria aunque el `ACK` no sirviera para nada.
        val mundo = SecureWorld.open()
        mundo.bob.core.registerMessageHandler { }
        val capturados = mundo.alice.captureOutbound()
        val carga = "este mensaje espera su ACK 4402".toByteArray()
        val creado = mundo.alice.core.createMessage(mundo.bob.device.identityId, carga).getOrThrow()
        mundo.alice.core.sendMessage(creado).getOrThrow()
        mundo.bob.deliverInboundAs(mundo.alice.device.identityId, mundo.bobToAlice, capturados.single())

        assertEquals(1, mundo.bob.transport.sentAcks.size, "B responde por el canal ACK")
        assertTrue(mundo.bob.transport.sent.isEmpty(), "y no por MESSAGE: el ciclo de amplificacion sigue cerrado")

        // El `ACK` va FIRMADO y EN CLARO. No se finge lo contrario: se comprueba
        // que hay firma, y el KDoc de `SecureInbound` dice que sigue siendo eso.
        val ack = JsonDecoder().decodeAck(mundo.bob.transport.sentAcks.single()).getOrThrow()
        assertEquals(creado.messageId, ack.originalMessageId, "correlaciona con el mensaje CIFRADO")
        assertEquals(mundo.bob.device.identityId, ack.from, "y lo firma quien lo recibio")
        assertEquals(AckStatus.DELIVERED, ack.status)
        assertTrue(ack.signature.isNotEmpty(), "la firma va en el ACK")

        // Y A lo recibe, lo VERIFICA y deja de esperar.
        val acksVistos = mutableListOf<Ack>()
        mundo.alice.core.registerAckHandler { acksVistos.add(it) }
        mundo.alice.transport.deliverLegacyAck(mundo.bob.transport.sentAcks.single())

        assertEquals(
            listOf(creado.messageId),
            acksVistos.map { it.originalMessageId },
            "A lo procesa: la firma verifica contra la clave de B",
        )
        assertEquals(
            com.km.model.MessageState.DELIVERED,
            mundo.alice.core.getMessageState(creado.messageId).getOrThrow(),
            "y el mensaje sale de SENT a DELIVERED: el ciclo cierra",
        )
    }

    @Test
    fun `sin sesion el mensaje no sale y se ve por el API publico`() {
        val mundo = SecureWorld.openWithoutSession()
        val carga = "esto no lo va a leer nadie en claro".toByteArray()
        val creado = mundo.alice.core.createMessage(mundo.bob.device.identityId, carga).getOrThrow()

        mundo.alice.core.sendMessage(creado).getOrThrow()

        assertEquals(0, mundo.alice.transport.sendCount, "nada en claro por el camino legado")
        val guardado = mundo.alice.core.getConversation(mundo.bob.device.identityId).getOrThrow()
            .single { it.messageId == creado.messageId }
        assertEquals(com.km.model.MessageState.FAILED, guardado.state)
        assertEquals(com.km.model.FailureCode.AUTH_FAILED, guardado.failureCode)
    }
}
