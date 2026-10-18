package com.km.transport

import com.km.codec.JsonDecoder
import com.km.codec.JsonEncoder
import com.km.model.Ack
import com.km.model.AckStatus
import com.km.model.IdentityId
import com.km.model.Message
import com.km.model.MessageId
import org.junit.jupiter.api.Test
import java.util.Base64
import java.util.UUID
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Pruebas del codec del cable.
 *
 * El caso que mas importa es el de la carga opaca: el `payload` que sale del
 * `MESSAGE` tiene que ser EXACTAMENTE el que entro, byte a byte. Es la
 * propiedad 6.2 del documento de transporte ("el frame que entra es byte a byte
 * el frame que sale") vista desde el cliente, y ningun otro test la cubre.
 */
class RelayDataCodecTest {

    private val from = IdentityId("11".repeat(32))
    private val to = IdentityId("22".repeat(32))
    private val codec = RelayDataCodec()

    @Test
    fun `la carga opaca sale byte a byte`() {
        // Contenido NO constante: una lectura parcial que conserva la longitud
        // seria indistinguible de un entero con relleno de ceros.
        val payload = ByteArray(256) { (it * 7 + 13).toByte() }
        val frame = codec.parseObject(codec.encodeMessage(from, to, payload))!!

        assertEquals("MESSAGE", codec.typeOf(frame))
        val decoded = codec.decodeMessage(frame)!!
        assertContentEquals(payload, decoded.payload)
        assertEquals(from, decoded.from)
        assertEquals(to, decoded.to)
    }

    @Test
    fun `un Base64 invalido descarta el frame ENTERO`() {
        val frame = codec.parseObject(
            """{"type":"MESSAGE","from":"${from.value}","to":"${to.value}","data":"no-es-base64!!"}"""
        )!!
        assertNull(codec.decodeMessage(frame), "no debe haber carga parcial")
    }

    @Test
    fun `un envelope sin to se descarta`() {
        val frame = codec.parseObject("""{"type":"MESSAGE","from":"${from.value}","data":"AAA="}""")!!
        assertNull(codec.decodeMessage(frame))
    }

    @Test
    fun `un identityId de forma invalida se descarta`() {
        val frame = codec.parseObject(
            """{"type":"MESSAGE","from":"corto","to":"${to.value}","data":"AAA="}"""
        )!!
        assertNull(codec.decodeMessage(frame))
    }

    @Test
    fun `un campo desconocido no impede leer el frame`() {
        val frame = codec.parseObject(
            """{"type":"MESSAGE","from":"${from.value}","to":"${to.value}","data":"AQID","futuro":123}"""
        )!!
        assertContentEquals(byteArrayOf(1, 2, 3), codec.decodeMessage(frame)!!.payload)
    }

    @Test
    fun `la traduccion a km-core conserva la carga`() {
        val payload = "hola".toByteArray()
        val inbound = codec.decodeMessage(codec.parseObject(codec.encodeMessage(from, to, payload))!!)!!
        val bytes = codec.toCoreMessageBytes(inbound)!!

        val message = JsonDecoder().decodeMessage(bytes).getOrThrow()
        assertContentEquals(payload, message.payload)
        assertEquals(from, message.from)
        assertEquals(to, message.to)
    }

    // ==================================================================
    // R1.1 / R1.2 — el messageId de APLICACION viaja y sobrevive
    // ==================================================================

    @Test
    fun `R1_1 el MESSAGE saliente lleva el messageId que trae la carga`() {
        // Este es el fallo que hacia que ningun ACK correlacionara. El
        // identificador del envelope lo acuñaba el transporte en cada salto,
        // mientras el emisor emparejaba contra el de la carga.
        val messageId = MessageId(UUID.randomUUID())
        val payload = JsonEncoder().encodeMessage(
            Message(messageId, from, to, "hola".toByteArray(), 1_790_000_000_000),
        ).getOrThrow()

        val frame = codec.parseObject(codec.encodeMessage(from, to, payload))!!

        assertEquals(messageId.value.toString(), frame.string("messageId"))
    }

    @Test
    fun `R1_2 la carga conserva el messageId al entrar y al traducirse`() {
        val messageId = MessageId(UUID.randomUUID())
        val payload = JsonEncoder().encodeMessage(
            Message(messageId, from, to, "hola".toByteArray(), 1_790_000_000_000),
        ).getOrThrow()

        val inbound = codec.decodeMessage(codec.parseObject(codec.encodeMessage(from, to, payload))!!)!!
        val arrived = JsonDecoder().decodeMessage(codec.toCoreMessageBytes(inbound)!!).getOrThrow()

        assertEquals(messageId, arrived.messageId, "el identificador tiene que sobrevivir al salto")
    }

    @Test
    fun `R1_2 el messageId del ENVELOPE no manda sobre el de la carga`() {
        // `MessageRouter` del rele vuelve a acuñar el `messageId` del envelope al
        // reenviar (§6.2 lo reemite, y el router lo reescribe). Correlacionar con
        // ese campo seria correlacionar con algo que cambia en cada salto, asi
        // que la carga manda aunque el envelope diga otra cosa.
        val carried = MessageId(UUID.randomUUID())
        val payload = JsonEncoder().encodeMessage(
            Message(carried, from, to, "hola".toByteArray(), 1_790_000_000_000),
        ).getOrThrow()
        val relayed = MessageId(UUID.randomUUID())

        // Un frame reenviado: el `messageId` del envelope es el del ultimo salto.
        val frame = codec.parseObject(
            """{"type":"MESSAGE","messageId":"${relayed.value}","from":"${from.value}",
               "to":"${to.value}","data":"${java.util.Base64.getEncoder().encodeToString(payload)}"}"""
        )!!
        val arrived = JsonDecoder().decodeMessage(codec.toCoreMessageBytes(codec.decodeMessage(frame)!!)!!)
            .getOrThrow()

        assertEquals(carried, arrived.messageId, "manda el de la carga, no el del envelope")
    }

    @Test
    fun `una carga opaca sin messageId propio cae al del envelope`() {
        // El unico caso en el que la carga no trae identificador: un consumidor
        // que no hable el vocabulario de km-core. Ahi el del envelope es lo
        // unico que hay, y usarlo es mejor que inventar otro.
        val envelopeId = UUID.randomUUID()
        val payload = "carga opaca".toByteArray()
        val frame = codec.parseObject(
            """{"type":"MESSAGE","messageId":"$envelopeId","from":"${from.value}",
               "to":"${to.value}","data":"${java.util.Base64.getEncoder().encodeToString(payload)}"}"""
        )!!
        val arrived = JsonDecoder().decodeMessage(codec.toCoreMessageBytes(codec.decodeMessage(frame)!!)!!)
            .getOrThrow()

        assertEquals(MessageId(envelopeId), arrived.messageId)
        assertContentEquals(payload, arrived.payload)
    }

    @Test
    fun `una carga opaca SIN messageId en el envelope no inventa nada que fingir`() {
        // Sin carga de km-core y sin `messageId` en el envelope no hay
        // identificador que deducir. El codec entrega el mensaje igualmente —no puede
        // hacer otra cosa sin dejar de entregarlo—, pero el test deja escrito
        // que ese camino NO correlaciona: quien lo use tiene que saberlo, no
        // descubrirlo cuando ningun ACK confirme nada.
        val frame = codec.parseObject(
            """{"type":"MESSAGE","from":"${from.value}","to":"${to.value}","data":"aG9sYQ=="}"""
        )!!
        val arrived = JsonDecoder().decodeMessage(codec.toCoreMessageBytes(codec.decodeMessage(frame)!!)!!)
            .getOrThrow()

        assertNotNull(arrived.messageId)
        assertEquals(from, arrived.from)
    }

    @Test
    fun `un ACK travels y vuelve con los mismos campos`() {
        val originalId = MessageId(UUID.randomUUID())
        val ack = Ack(from, to, originalId, AckStatus.DELIVERED, 1_790_000_000_000, byteArrayOf(9, 8, 7))

        val frame = codec.parseObject(codec.encodeAck(ack))!!
        assertEquals("ACK", codec.typeOf(frame))
        val decoded = codec.decodeAck(frame)!!
        assertEquals(ack, decoded)

        val bytes = codec.toCoreAckBytes(decoded)!!
        assertEquals(ack, JsonDecoder().decodeAck(bytes).getOrThrow())
    }

    @Test
    fun `un ACK con status desconocido se descarta`() {
        val frame = codec.parseObject(
            """
            {"type":"ACK","from":"${from.value}","to":"${to.value}",
             "originalMessageId":"${UUID.randomUUID()}","status":"INVENTADO",
             "timestamp":1,"signature":"AQID"}
            """.trimIndent()
        )!!
        assertNull(codec.decodeAck(frame))
    }

    @Test
    fun `un ACK no se puede leer como MESSAGE, y al reves tampoco`() {
        // Es la propiedad que corta el ciclo de amplificacion, vista desde el
        // codec. Un `ACK` es un `ACK`: si `decodeMessage` lo aceptara, el
        // receptor contrario lo trataria como un mensaje entrante MAS y
        // responderia con otro `ACK`.
        val originalId = MessageId(UUID.randomUUID())
        val ackFrame = codec.parseObject(
            codec.encodeAck(Ack(from, to, originalId, AckStatus.DELIVERED, 1_790_000_000_000, byteArrayOf(9, 8, 7)))
        )!!
        assertEquals("ACK", codec.typeOf(ackFrame))
        assertNull(codec.decodeMessage(ackFrame), "un ACK no puede vestirse de MESSAGE")

        // Y el `MESSAGE` no puede vestirse de `ACK`: le falta `originalMessageId`
        // y `status`, y `decodeAck` no los inventa.
        val messageFrame = codec.parseObject(codec.encodeMessage(from, to, byteArrayOf(1, 2, 3)))!!
        assertNull(codec.decodeAck(messageFrame), "un MESSAGE no puede vestirse de ACK")
    }

    @Test
    fun `el originalMessageId de un ACK sobrevive al viaje completo`() {
        // F2/D2. El identificador que confirma es el del mensaje que CONFIRMA, no
        // uno nuevo: es contra el que el emisor empareja con `expectAck`.
        // `encodeMessage` genera un `messageId` nuevo cada vez, asi que si el
        // `ACK` pasara por ahi, el identificador se perderia en el envoltorio y
        // la deduplicacion no tendria contra que funcionar.
        val originalId = MessageId(UUID.randomUUID())
        val ack = Ack(from, to, originalId, AckStatus.DELIVERED, 1_790_000_000_000, ByteArray(64) { it.toByte() })
        val frame = codec.parseObject(codec.encodeAck(ack))!!

        assertEquals("ACK", codec.typeOf(frame))
        // El envelope lleva `originalMessageId` explicito, no un `messageId`.
        assertEquals(originalId.value.toString(), frame.string("originalMessageId"))
        assertNull(frame.string("messageId"), "el ACK no lleva messageId propio")

        // Byte a byte por el vocabulario de km-core, que es donde lo recibe
        // `AckManager`.
        val coreBytes = codec.toCoreAckBytes(codec.decodeAck(frame)!!)!!
        assertEquals(originalId, JsonDecoder().decodeAck(coreBytes).getOrThrow().originalMessageId)
    }

    @Test
    fun `un frame que no es objeto JSON se descarta sin lanzar`() {
        assertNull(codec.parseObject("esto no es json"))
        assertNull(codec.parseObject("[1,2,3]"))
        assertNull(codec.parseObject(""))
    }

    @Test
    fun `el data usa Base64 estandar, no Base64URL`() {
        // 0xFB 0xFF produce '+' y '/' en Base64 estandar y '-' y '_' en URL-safe.
        val payload = byteArrayOf(0xFB.toByte(), 0xFF.toByte(), 0xBF.toByte())
        val frame = codec.encodeMessage(from, to, payload)
        assertTrue(frame.contains("+/"), "el cable exige Base64 estandar (RFC 4648 §4): $frame")
        assertEquals(Base64.getEncoder().encodeToString(payload), Regex("\"data\":\"([^\"]+)\"").find(frame)!!.groupValues[1])
    }

    @Test
    fun `el from de un MESSAGE saliente es SIEMPRE la identidad local`() {
        // El rele rechaza con RELAY_NOT_AUTHORIZED cualquier `from` que no sea el
        // peer autenticado, asi que esto no es una preferencia: es la unica
        // forma de que el mensaje llegue.
        val frame = codec.parseObject(codec.encodeMessage(from, to, byteArrayOf(1)))!!
        assertEquals(from.value, frame.string("from"))
        assertEquals(to.value, frame.string("to"))
    }

    @Test
    fun `km-core y el codec usan campos de carga DISTINTOS`() {
        // `payload` es el campo de `JsonEncoder`; `data`, el del dialecto del
        // rele. Que sean distintos es lo que obliga a traducir (KM-WIRE-RELAY
        // §10.1), y por eso se fija con un test en vez de con un comentario.
        val coreFrame = String(JsonEncoder().encodeMessage(
            com.km.model.Message(
                MessageId(UUID.randomUUID()), from, to, byteArrayOf(1), 1,
            ),
        ).getOrThrow())
        assertTrue(coreFrame.contains("\"payload\""), coreFrame)

        val wireFrame = codec.encodeMessage(from, to, byteArrayOf(1))
        assertTrue(wireFrame.contains("\"data\""), wireFrame)
        assertTrue(!wireFrame.contains("\"payload\""), wireFrame)
    }
}
