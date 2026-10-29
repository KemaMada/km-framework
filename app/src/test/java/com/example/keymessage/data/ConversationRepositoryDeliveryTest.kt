package com.example.keymessage.data

import com.example.keymessage.storage.room.dao.MessageDao
import com.example.keymessage.storage.room.entity.MessageEntity
import com.example.keymessage.support.InMemoryMessageDao
import com.km.api.KeyMessageCore
import com.km.model.Ack
import com.km.model.FailureCode
import com.km.model.IdentityId
import com.km.model.Message
import com.km.model.MessageId
import com.km.model.MessageState
import com.km.protocol.SendResult
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Test
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * T2 (app) — LA COSTURA DEL DAO Y EL PROVIDER DE NUCLEO.
 *
 * ## POR QUE NO ROBOLECTRIC
 *
 * La app no lo tiene como dependencia y anadirla traeria un runtime de Android
 * al test de JVM. La costura es [InMemoryMessageDao]: comportamiento y orden de
 * la conversacion. El `ORDER BY` REAL de Room se prueba aparte, contra el motor
 * SQLite de verdad (`MessageDaoSqlParityTest`); el wiring de la base generada
 * queda como follow-up declarado (androidTest).
 *
 * ## QUE AFIRMA
 *
 * 1. Diez FILAS llegan a la pantalla y en orden `(timestamp, messageId)`, no por
 *    texto pintado ni por viewport.
 * 2. El repositorio resuelve el nucleo VIGENTE en cada envio: reemplazarlo no
 *    deja al repositorio pegado al anterior (R14).
 * 3. Sin nucleo el envio DEGRADA con motivo, no revienta.
 * 4. `resend`/`discard` viajan con el MISMO `messageId` que la fila.
 */
class ConversationRepositoryDeliveryTest {

    private val local = IdentityId("aa".repeat(32))
    private val peer = IdentityId("bb".repeat(32))

    private fun fila(id: String, ts: Long, state: MessageState = MessageState.QUEUED): MessageEntity = MessageEntity(
        messageId = id,
        from = peer.value,
        to = local.value,
        payload = "carga $ts".toByteArray(),
        timestamp = ts,
        state = state.name,
    )

    private fun id(n: Int): String = "00000000-0000-0000-0000-%012d".format(n)

    // ===================================================================
    // 1. Diez filas, orden estable
    // ===================================================================

    @Test
    fun `diez filas llegan a la conversacion en orden por timestamp y messageId`() {
        val dao = InMemoryMessageDao()
        // Se insertan al reves a proposito: el orden NO es el de insercion.
        (10 downTo 1).forEach { n -> runBlocking { dao.insert(fila(id(n), ts = n * 1_000L)) } }

        val repo = ConversationRepository({ FakeCore("A") }, dao, local)
        val conversacion = runBlocking { repo.observeConversation(peer).first() }

        assertEquals(10, conversacion.size, "diez FILAS, no diez burbujas visibles")
        assertEquals(
            (1..10).map { id(it) },
            conversacion.map { it.id },
            "y en orden de creacion observable",
        )
    }

    @Test
    fun `con el mismo timestamp el desempate es estable y declarado por messageId`() {
        val dao = InMemoryMessageDao()
        val b = "00000000-0000-0000-0000-0000000000bb"
        val a = "00000000-0000-0000-0000-0000000000aa"
        val c = "00000000-0000-0000-0000-0000000000cc"
        runBlocking {
            dao.insert(fila(b, ts = 7_000L))
            dao.insert(fila(a, ts = 7_000L))
            dao.insert(fila(c, ts = 7_000L))
        }

        val repo = ConversationRepository({ FakeCore("A") }, dao, local)
        val conversacion = runBlocking { repo.observeConversation(peer).first() }

        assertEquals(listOf(a, b, c), conversacion.map { it.id }, "empate resuelto por messageId, no por insercion")
    }

    // ===================================================================
    // 2-4. El provider y las acciones explicitas
    // ===================================================================

    @Test
    fun `tras reemplazar el nucleo el envio sale por el NUEVO`() {
        val viejo = FakeCore("viejo")
        val nuevo = FakeCore("nuevo")
        var vigente: KeyMessageCore? = viejo
        val repo = ConversationRepository({ vigente }, InMemoryMessageDao(), local)

        repo.send(peer, "antes".toByteArray())
        assertEquals(1, viejo.sends, "el primero sale por el vigente")

        vigente = nuevo
        repo.send(peer, "despues".toByteArray())

        assertEquals(1, viejo.sends, "el viejo NO recibe mas envios: no se queda capturado")
        assertEquals(1, nuevo.sends, "el nuevo es el que envia")
    }

    @Test
    fun `sin nucleo el envio degrada con motivo en vez de reventar`() {
        val repo = ConversationRepository({ null }, InMemoryMessageDao(), local)
        val envio = repo.send(peer, "hola".toByteArray())

        assertFalse(envio.sent)
        assertNotNull(envio.motivo)
        assertTrue(envio.motivo!!.contains("sin nucleo"), "el motivo dice la verdad: ${envio.motivo}")
        assertNull(envio.messageId, "y no se inventa un messageId que nadie persistio")
    }

    @Test
    fun `resend y discard llevan el MISMO messageId de la fila`() {
        val core = FakeCore("A")
        val repo = ConversationRepository({ core }, InMemoryMessageDao(), local)
        val objetivo = id(42)
        val esperado = MessageId.fromString(objetivo)

        val reenvio = repo.resend(objetivo)
        val descarte = repo.discard(objetivo)

        assertEquals(listOf(esperado), core.resends, "el reenvio no acuna otro id")
        assertEquals(listOf(esperado), core.discards, "y el descarte apunta a la misma fila")
        assertNull(reenvio.motivo, "sin error, sin banner")
        assertNull(descarte.motivo)
    }

    @Test
    fun `un messageId malformado se rechaza con motivo y no llega al nucleo`() {
        val core = FakeCore("A")
        val repo = ConversationRepository({ core }, InMemoryMessageDao(), local)

        val envio = repo.resend("no-es-un-uuid")

        assertFalse(envio.sent)
        assertNotNull(envio.motivo)
        assertTrue(core.resends.isEmpty(), "el nucleo no recibe basura")
    }

    @Test
    fun `un rechazo del nucleo se reporta como no enviado con su motivo`() {
        val core = FakeCore("A").apply { fallo = IllegalStateException("sin sesion cifrada") }
        val dao = InMemoryMessageDao()
        val repo = ConversationRepository({ core }, dao, local)

        val envio = repo.send(peer, "hola".toByteArray())

        assertFalse(envio.sent)
        assertTrue(envio.motivo!!.contains("sin sesion cifrada"), "motivo real: ${envio.motivo}")
    }

    /**
     * Nucleo de mentira para la costura del repositorio.
     *
     * NO simula criptografia ni transporte: registra lo que el repositorio le
     * pide, que es lo unico que esta capa decide.
     */
    private class FakeCore(private val nombre: String) : KeyMessageCore {
        var sends: Int = 0
            private set
        val resends = mutableListOf<MessageId>()
        val discards = mutableListOf<MessageId>()
        var fallo: Throwable? = null

        override fun start(): Result<Unit> = Result.success(Unit)
        override fun stop() {}
        override fun close() {}

        override fun createMessage(to: IdentityId, payload: ByteArray): Result<Message> = Result.success(
            Message(
                messageId = MessageId(UUID.randomUUID()),
                from = IdentityId("aa".repeat(32)),
                to = to,
                payload = payload,
                timestamp = 1_000L,
                state = MessageState.CREATED,
            ),
        )

        override fun sendMessage(message: Message): Result<SendResult> {
            fallo?.let { return Result.failure(it) }
            sends++
            return Result.success(SendResult(message.messageId, MessageState.SENT))
        }

        override fun getMessageState(messageId: MessageId): Result<MessageState> = Result.success(MessageState.SENT)
        override fun getConversation(peerId: IdentityId): Result<List<Message>> = Result.success(emptyList())
        override fun resend(messageId: MessageId): Result<Unit> {
            resends.add(messageId)
            return Result.success(Unit)
        }

        override fun discard(messageId: MessageId): Result<Unit> {
            discards.add(messageId)
            return Result.success(Unit)
        }

        override fun registerAckHandler(handler: (Ack) -> Unit) {}
        override fun registerMessageHandler(handler: (Message) -> Unit) {}
        override fun registerStateChangeHandler(handler: (MessageId, MessageState) -> Unit) {}
    }
}
