package com.example.keymessage.storage

import com.km.model.FailureCode
import com.km.model.IdentityId
import com.km.model.Message
import com.km.model.MessageId
import com.km.model.MessageState
import com.example.keymessage.model.ChatMessage
import com.example.keymessage.model.MessageStatus
import com.example.keymessage.storage.room.entity.MessageEntity
import com.example.keymessage.storage.room.entity.OfflineMessageEntity
import org.junit.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * 5.1 — La fila y el dominio dicen lo mismo, en los DOS sentidos.
 *
 * ## QUE AFIRMA
 *
 * 1. `MessageEntity.fromMessage(m).toMessage() == m`: contenido y longitudes
 *     propias, `messageId` incluido. El `messageId` es la clave primaria de la
 *     tabla: mapearlo a una constante hace que dos mensajes distintos se pisen en
 *     la MISMA fila, y el sintomo es "un mensaje desaparece", que no parece un
 *     bug de mapeo.
 * 2. `ChatMessage.from(m, local)` deja claro que el mensaje es mio o suyo SEGUN LA
 *     IDENTIDAD LOCAL, y que el estado se traduce sin inventar etiquetas.
 *
 * ## POR QUE ESTO NO ES UN `TypeConverter`
 *
 * Porque un `TypeConverter` de Room es una conversion INVISIBLE. Con los dos
 * sentidos en funciones con nombre, el mapeo es codigo que se lee y que esta
 * mutacion puede atacar.
 */
class MessageMappingTest {

    private val local = IdentityId("1a2b3c4d5e6f".repeat(5) + "1234")
    private val remoto = IdentityId("9f8e7d6c5b4a".repeat(5) + "9876")

    private fun mensaje(
        id: String = "0f1e2d3c-4b5a-6978-8796-a5b4c3d2e1f0",
        payload: String = "contenido con longitud propia 5150, no constante",
        state: MessageState = MessageState.SENT,
        failureCode: FailureCode? = null,
    ) = Message(
        messageId = MessageId.fromString(id),
        from = remoto,
        to = local,
        payload = payload.toByteArray(),
        timestamp = 1_760_123_456_789L,
        state = state,
        failureCode = failureCode,
    )

    @Test
    fun `ida y vuelta conserva el mensaje ENTERO`() {
        val original = mensaje()

        val vuelta = MessageEntity.fromMessage(original).toMessage()

        assertEquals(original, vuelta, "la fila y el dominio dicen lo mismo")
        assertEquals(original.messageId, vuelta.messageId, "incluido el messageId, que es la clave primaria")
        assertContentEquals(original.payload, vuelta.payload, "y el payload byte a byte")
    }

    @Test
    fun `el messageId NO se colapsa en una constante`() {
        val uno = mensaje(id = "11111111-1111-4111-8111-111111111111")
        val otro = mensaje(id = "22222222-2222-4222-8222-222222222222")

        assertNotEquals(
            MessageEntity.fromMessage(uno).toMessage().messageId,
            MessageEntity.fromMessage(otro).toMessage().messageId,
            "dos mensajes distintos NO pueden acabar en la misma clave primaria",
        )
    }

    @Test
    fun `el estado y el codigo de fallo sobreviven`() {
        val fallido = mensaje(state = MessageState.FAILED, failureCode = FailureCode.INTERNAL_ERROR)

        val vuelta = MessageEntity.fromMessage(fallido).toMessage()

        assertEquals(MessageState.FAILED, vuelta.state, "el estado no se normaliza al guardar")
        assertEquals(FailureCode.INTERNAL_ERROR, vuelta.failureCode, "ni se pierde el codigo de fallo")
    }

    @Test
    fun `un mensaje sin codigo de fallo no inventa uno`() {
        val enviado = mensaje(state = MessageState.SENT)

        assertEquals(
            null,
            MessageEntity.fromMessage(enviado).toMessage().failureCode,
            "un SENT no puede salir con un motivo de fallo puesto",
        )
    }

    @Test
    fun `la cola offline sabe que su estado es QUEUED`() {
        val encolado = mensaje(state = MessageState.QUEUED, failureCode = null)

        val fila = OfflineMessageEntity.fromMessage(encolado)
        assertEquals(
            MessageState.QUEUED,
            fila.toMessage().state,
            "una fila de la cola no puede salir con estado SENT: nadie lo explicaria",
        )
    }

    @Test
    fun `la proyeccion a pantalla marca lo mio y lo suyo por identidad local`() {
        val mio = mensaje(
            id = "33333333-3333-4333-8333-333333333333",
        ).copy(from = local, to = remoto)
        val suyo = mensaje(id = "44444444-4444-4444-8444-444444444444")

        val chatMio = ChatMessage.from(mio, local.value)
        val chatSuyo = ChatMessage.from(suyo, local.value)

        assertTrue(chatMio.isMine, "un mensaje cuyo emisor soy yo es mio")
        assertTrue(!chatSuyo.isMine, "y uno de otro emisor no lo es")
        assertEquals(
            mio.messageId.value.toString(),
            chatMio.id,
            "el identificador de la pantalla es el del dominio, sin transformar",
        )
        assertEquals(MessageStatus.SENT, chatMio.status, "y el estado se traduce tal cual")
    }

    @Test
    fun `QUEUED y FAILED NO se pintan como enviados`() {
        val encolado = ChatMessage.from(mensaje(state = MessageState.QUEUED), local.value)
        val fallido = ChatMessage.from(
            mensaje(state = MessageState.FAILED, failureCode = FailureCode.PEER_NOT_FOUND),
            local.value,
        )

        assertEquals(MessageStatus.QUEUED, encolado.status, "encolado es encolado")
        assertEquals(MessageStatus.FAILED, fallido.status, "fallido es fallido")
        assertNotEquals(MessageStatus.SENT, encolado.status, "encolado no es enviado")
        assertEquals(
            "PEER_NOT_FOUND",
            fallido.statusDetail,
            "y el motivo del fallo viaja a la pantalla: sin el, el usuario solo ve que no llega",
        )
    }

    @Test
    fun `un payload ilegible no rompe la pantalla`() {
        val raro = mensaje(payload = "\u0000\uFFFDno es texto").copy(payload = byteArrayOf(-17, -65, -80, 0x00))

        val chat = ChatMessage.from(raro, local.value)

        assertTrue(chat.text.isNotEmpty(), "hay algo que pintar aunque el payload no sea texto")
    }

    @Test
    fun `control el round-trip detecta una fila mal construida`() {
        // El pretesto del detector: si `toMessage` devolviera siempre el mismo
        // valor, "ida y vuelta" pasaria sin comprobar nada.
        val original = mensaje()
        val fila = MessageEntity.fromMessage(original)

        assertEquals(original.messageId.value.toString(), fila.messageId, "la fila guarda el id tal cual")
        assertEquals(original.from.value, fila.from, "y el emisor")
        assertEquals(original.to.value, fila.to, "y el receptor")
        assertEquals(original.payload.size, fila.payload.size, "y el payload sin transformar")
    }
}