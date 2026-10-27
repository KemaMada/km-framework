package com.km.protocol

import com.km.model.IdentityId
import com.km.model.Message
import com.km.model.MessageId
import com.km.model.MessageState
import org.junit.jupiter.api.Test
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * R1/R2/R4 — la cola ordenada por par, sola, sin emisor y sin red.
 *
 * Lo que se pinza aqui es el ORDEN y la PUERTA: dos mensajes del mismo par no
 * pueden transmitir a la vez, y el orden no es "el que llego antes al metodo"
 * sino `(timestamp, messageId)`, que es lo unico que sobrevive a un reinicio
 * (el sobre v1 no lleva secuencia y el `OfflineQueue` ordena por `timestamp`).
 */
class PerPeerSendQueueTest {

    private val par = IdentityId("aa".repeat(32))
    private val otroPar = IdentityId("bb".repeat(32))

    private fun id(): MessageId = MessageId(UUID.randomUUID())

    private fun mensaje(id: MessageId, ts: Long, to: IdentityId = par, state: MessageState = MessageState.QUEUED) =
        Message(
            messageId = id,
            from = IdentityId("cc".repeat(32)),
            to = to,
            payload = "carga".toByteArray(),
            timestamp = ts,
            state = state,
        )

    // ===================================================================
    // Orden: (timestamp, messageId) y NO el orden de inscripcion
    // ===================================================================

    @Test
    fun `el orden es por timestamp aunque se inscriban al reves`() {
        val cola = PerPeerSendQueue()
        val primero = id()
        val segundo = id()
        val tercero = id()

        // Se inscriben 3, 1, 2: si el orden fuera de insercion, la cabeza seria
        // el tercero. Con `(timestamp, messageId)` la cabeza es el 1.
        cola.enqueue(tercero, par, timestamp = 3000)
        cola.enqueue(primero, par, timestamp = 1000)
        cola.enqueue(segundo, par, timestamp = 2000)

        assertEquals(primero, cola.head(par), "la cabeza es el mas antiguo, no el ultimo inscrito")
        assertEquals(segundo, cola.advance(par), "quitar la cabeza devuelve la siguiente")
        assertEquals(segundo, cola.head(par))
        assertEquals(tercero, cola.advance(par))
        assertEquals(tercero, cola.head(par))
        assertNull(cola.advance(par), "vacia: no hay siguiente")
    }

    @Test
    fun `con el mismo timestamp desempata el messageId`() {
        val cola = PerPeerSendQueue()
        // Dos ids con el mismo milisegundo: el orden tiene que ser TOTAL y
        // estable, no el que deparara el allocator.
        val a = MessageId(UUID.fromString("00000000-0000-0000-0000-000000000001"))
        val b = MessageId(UUID.fromString("00000000-0000-0000-0000-000000000002"))
        cola.enqueue(b, par, timestamp = 7_000)
        cola.enqueue(a, par, timestamp = 7_000)

        assertEquals(a, cola.head(par), "mismo tiempo: decide el messageId")
        cola.advance(par)
        assertEquals(b, cola.head(par))
    }

    // ===================================================================
    // Idempotencia y un vuelo por par
    // ===================================================================

    @Test
    fun `inscribir dos veces el mismo mensaje no lo duplica ni lo mueve`() {
        val cola = PerPeerSendQueue()
        val primero = id()
        val segundo = id()

        assertTrue(cola.enqueue(primero, par, timestamp = 1000))
        assertFalse(cola.enqueue(primero, par, timestamp = 1000), "ya estaba: no se reinscribe")
        // Y tampoco lo reordena si llega con otro tiempo: moverlo seria
        // reordenar la conversacion por la puerta de atras.
        assertFalse(cola.enqueue(primero, par, timestamp = 99))

        cola.enqueue(segundo, par, timestamp = 2000)
        assertEquals(2, cola.size(par))
        assertEquals(primero, cola.head(par), "sigue siendo el primero")
        assertEquals(par, cola.peerOf(primero))
    }

    @Test
    fun `cada par tiene su propia cabeza`() {
        val cola = PerPeerSendQueue()
        val a1 = id()
        val b1 = id()
        cola.enqueue(a1, par, timestamp = 1000)
        cola.enqueue(b1, otroPar, timestamp = 500)

        assertEquals(a1, cola.head(par))
        assertEquals(b1, cola.head(otroPar), "el reloj de un par no manda sobre el otro")
        assertEquals(2, cola.size(), "dos inscritos en total")
    }

    @Test
    fun `discard quita del medio y libera el dueno`() {
        val cola = PerPeerSendQueue()
        val primero = id()
        val segundo = id()
        cola.enqueue(primero, par, timestamp = 1000)
        cola.enqueue(segundo, par, timestamp = 2000)

        assertTrue(cola.discard(segundo), "estaba inscrito")
        assertFalse(cola.contains(segundo))
        assertNull(cola.peerOf(segundo), "y ya no pertenece a ningun par")
        assertEquals(primero, cola.head(par))
        assertFalse(cola.discard(segundo), "quitar dos veces no es un error, es no-op")
    }

    // ===================================================================
    // Rehidratacion (D6)
    // ===================================================================

    /**
     * La rehidratacion es donde se decide que sobrevive a un reinicio.
     *
     * `OfflineQueue.peekAll()` SIEMPRE devuelve `QUEUED` (asi escribe sus
     * entradas), asi que el estado real hay que preguntarselo al `MessageStore`.
     * Un `SENT` sin wire cacheado no se puede reenviar —re-cifrar seria un
     * mensaje nuevo— y se BARRE: la app nunca llama a `expire`, de modo que sin
     * este barrido la cola persistida solo crece.
     */
    @Test
    fun `rehydrate admite QUEUED y barre lo que ya salio`() {
        val cola = PerPeerSendQueue()
        val pendiente = id()
        val salido = id()
        val entregado = id()
        val estados = mapOf(
            pendiente to MessageState.QUEUED,
            salido to MessageState.SENT,
            entregado to MessageState.DELIVERED,
        )
        val barridos = mutableListOf<MessageId>()

        val report = cola.rehydrate(
            queued = listOf(
                mensaje(pendiente, ts = 1000),
                mensaje(salido, ts = 2000),
                mensaje(entregado, ts = 3000),
            ),
            stateOf = { estados[it] },
            onStale = { barridos.add(it.messageId) },
        )

        assertEquals(1, report.admitted, "solo el QUEUED se admite")
        assertEquals(2, report.swept, "y los dos que ya salieron se barren")
        assertEquals(listOf(salido, entregado), barridos, "se barren los dos, con su id")
        assertEquals(pendiente, cola.head(par))
        assertEquals(1, cola.size(par))
    }

    @Test
    fun `rehydrate deja el orden por tiempo y no el de la cola persistida`() {
        val cola = PerPeerSendQueue()
        val primero = id()
        val segundo = id()
        // La persistencia los entrega al reves a proposito: el orden lo fija el
        // tiempo, no el orden en que `peekAll` los devuelva.
        cola.rehydrate(
            queued = listOf(mensaje(segundo, ts = 2000), mensaje(primero, ts = 1000)),
            stateOf = { MessageState.QUEUED },
        )
        assertEquals(primero, cola.head(par))
    }

    // ===================================================================
    // R4 — el techo de la cabeza, con numeros
    // ===================================================================

    @Test
    fun `el techo de la cabeza son 155 segundos, y son la suma del backoff`() {
        val retry = RetryManagerImpl(scheduler = ManualRetryScheduler())
        val suma = (1..retry.maxRetries()).sumOf { retry.backoffDelay(it) }

        assertEquals(5_000L * (1 + 2 + 4 + 8 + 16), suma, "5 + 10 + 20 + 40 + 80 segundos")
        assertEquals(suma, SenderImpl.HEAD_WAIT_CEILING_MS, "el techo DECLARADO es el backoff real")
        assertEquals(155_000L, SenderImpl.HEAD_WAIT_CEILING_MS)
    }
}
