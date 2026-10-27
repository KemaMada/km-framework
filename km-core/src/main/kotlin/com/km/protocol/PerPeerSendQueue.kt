package com.km.protocol

import com.km.model.IdentityId
import com.km.model.Message
import com.km.model.MessageId
import com.km.model.MessageState

/**
 * R1/R2/R3 — LA COLA DE SALIDA ORDENADA POR PAR: la UNICA puerta de emision.
 *
 * ## QUE PROBLEMA RESUELVE, DICHO CON EL DEFECTO DELANTE
 *
 * Antes habia DOS caminos de reenvio en `SenderImpl` y ninguno mandaba sobre el
 * otro: la cola offline, que `onPeerOnline` drenaba CON AVIDEZ, y el temporizador
 * de reintentos. Un mensaje que caia en la cola durante la ventana offline
 * adelantaba a los que esperaban su reintento, y la conversacion llegaba
 * `[2,1,3,4]`. Unificar los dos caminos en UN hilo de scheduler NO arreglaba
 * nada: el mensaje nuevo entraba a 5 s y el agotado esperaba 10/20/40 s por su
 * backoff, asi que el nuevo seguia adelantando.
 *
 * Lo que lo arregla es una PUERTA: solo la CABEZA de cada par puede transmitir.
 * Un mensaje que no es cabeza espera, aunque su temporizador venza antes.
 *
 * ## ORDEN: `(timestamp, messageId)`, NO EL ORDEN DE INSERCION
 *
 * El sobre v1 no lleva numero de secuencia (la opcion B esta diferida), asi que
 * la unica marca de orden que existe es el `timestamp` que acuno
 * `createMessage`. Se ordena por `(timestamp, messageId)` y el `messageId`
 * desempata: dos mensajes acuñados en el mismo milisegundo tienen un orden
 * TOTAL y estable, no el que deparara el allocator. Ordenar por insercion seria
 * mentir cuando el proceso muere y la cola se rehidrata desde `OfflineQueue`,
 * que devuelve `ORDER BY timestamp ASC`: el orden de la rehidratacion y el de
 * la sesion viva tienen que ser el MISMO, y ese es el del tiempo.
 *
 * ## QUIEN VIVE AQUI Y QUIEN NO
 *
 * Vive el ORDEN y la exclusividad del vuelo. NO vive el wire (eso es
 * `lastWireByMessageId` en `SenderImpl`), NI el estado del mensaje (el
 * `MessageStore` es la verdad observable), NI la persistencia (el `OfflineQueue`
 * inyectado es la cara durable del `QUEUED`, D6). Esta clase es pequeña a
 * proposito: si guardara duplicados de esos tres, un dia diria algo distinto.
 *
 * No es thread-safe: `SenderImpl` la usa bajo su propio cerrojo, que es el
 * mismo que serializa el scheduler y los eventos de canal.
 */
internal class PerPeerSendQueue {

    /** Una entrada: el `messageId` y la marca de orden con la que se acuño. */
    private data class Entry(val messageId: MessageId, val timestamp: Long)

    /**
     * Cola por par, SIEMPRE ordenada por `(timestamp, messageId)`.
     *
     * `head(peer)` es `first()`: no hay otra forma de leerla.
     */
    private val queues = mutableMapOf<IdentityId, MutableList<Entry>>()

    /** Dueño de cada mensaje inscrito. Es lo que hace idempotente el `enqueue`. */
    private val peerByMessage = mutableMapOf<MessageId, IdentityId>()

    /** Marca de orden por mensaje, para rechazar una reinscripcion con otro tiempo. */
    private val timestampByMessage = mutableMapOf<MessageId, Long>()

    /**
     * Inscribe [messageId] en la cola de [peer].
     *
     * @return `false` si ya estaba inscrito (idempotencia, R5). Un mensaje se
     *   inscribe UNA vez: si el llamante vuelve a pasar por aqui —un `send`
     *   repetido, un despertar, una rehidratacion— no se duplica ni se mueve de
     *   sitio. Moverlo seria reordenar la conversacion por la puerta de atras.
     */
    fun enqueue(messageId: MessageId, peer: IdentityId, timestamp: Long): Boolean {
        if (peerByMessage.containsKey(messageId)) return false
        val entry = Entry(messageId, timestamp)
        val queue = queues.getOrPut(peer) { mutableListOf() }
        // Insercion ordenada y ESTABLE: el `indexOfFirst` encuentra el primer
        // elemento MAYOR, y con el mismo `timestamp` decide el `messageId`.
        val index = queue.indexOfFirst { compare(it, entry) > 0 }
        if (index < 0) queue.add(entry) else queue.add(index, entry)
        peerByMessage[messageId] = peer
        timestampByMessage[messageId] = timestamp
        return true
    }

    /** La cabeza del par: el UNICO elegible para transmitir. */
    fun head(peer: IdentityId): MessageId? = queues[peer]?.firstOrNull()?.messageId

    /**
     * Quita la cabeza del par (ACK) y devuelve la siguiente, que pasa a ser la
     * unica elegible.
     *
     * Devuelve `null` si el par no tenia cola: un ACK de algo que ya no estaba
     * no puede inventar una promocion.
     */
    fun advance(peer: IdentityId): MessageId? {
        val queue = queues[peer] ?: return null
        val removed = queue.removeFirstOrNull() ?: return null
        forget(removed.messageId)
        if (queue.isEmpty()) queues.remove(peer)
        return queue.firstOrNull()?.messageId
    }

    /**
     * Quita [messageId] de donde este (EXPIRED, cancelacion, rechazo terminal).
     *
     * No promueve: quien llama decide cuando despertar a la siguiente, porque
     * promover sin despertar dejaria la cola parada y promover sin mirar el
     * transporte haria un intento a ciegas.
     *
     * @return `true` si estaba inscrito.
     */
    fun discard(messageId: MessageId): Boolean {
        val peer = peerByMessage[messageId] ?: return false
        val queue = queues[peer] ?: return false
        val removed = queue.removeAll { it.messageId == messageId }
        forget(messageId)
        if (queue.isEmpty()) queues.remove(peer)
        return removed
    }

    /** El par dueño de [messageId], o `null` si no esta inscrito. */
    fun peerOf(messageId: MessageId): IdentityId? = peerByMessage[messageId]

    fun contains(messageId: MessageId): Boolean = peerByMessage.containsKey(messageId)

    /** Cuantos mensajes esperan en el par. Observabilidad y tests. */
    fun size(peer: IdentityId): Int = queues[peer]?.size ?: 0

    /** Total inscrito, todos los pares. Observabilidad y tests. */
    fun size(): Int = peerByMessage.size

    /**
     * Rehidrata la cola en memoria desde la persistencia (D6).
     *
     * ## QUE ADMITE Y QUE BARRE, Y POR QUE NO ES LO MISMO
     *
     * - **`QUEUED` (o sin registro) -> se admite.** Es un mensaje que nunca
     *   salio: su sitio es la cola ordenada.
     * - **Cualquier otro estado -> se BARRE del `OfflineQueue`.** La app nunca
     *   llama a `expire`, asi que la cola persistida acumula entradas de
     *   mensajes que ya salieron (`SENT`) y que, tras reiniciar el proceso, no
     *   tienen wire cacheado: no se pueden reenviar (re-cifrar seria un mensaje
     *   nuevo) y dejarlas ahi haria que la rehidratacion las tratara como
     *   pendientes. Se quitan y se cuentan.
     *
     * El barrido lo ejecuta [onStale] y no esta clase: quien conoce el
     * `OfflineQueue` es `SenderImpl`, y una cola de memoria que borrara de la
     * persistencia tendria dos dueños del mismo dato.
     *
     * @param stateOf estado REAL en el `MessageStore`. El `OfflineQueue` no vale
     *   como fuente: sus entradas siempre dicen `QUEUED` porque asi las escribe.
     */
    fun rehydrate(
        queued: List<Message>,
        stateOf: (MessageId) -> MessageState?,
        onStale: (Message) -> Unit = {},
    ): RehydrateReport {
        var admitted = 0
        var swept = 0
        queued.forEach { message ->
            val state = stateOf(message.messageId)
            if (state == null || state == MessageState.QUEUED) {
                if (enqueue(message.messageId, message.to, message.timestamp)) admitted++
            } else {
                swept++
                onStale(message)
            }
        }
        return RehydrateReport(admitted = admitted, swept = swept)
    }

    private fun forget(messageId: MessageId) {
        peerByMessage.remove(messageId)
        timestampByMessage.remove(messageId)
    }

    /**
     * Orden TOTAL de la cola: `(timestamp, messageId)`.
     *
     * El desempate del `messageId` NO es un UUID "arbitrario" elegido al azar por
     * este cambio: es el unico campo estable que el sobre v1 ya lleva y que
     * sobrevive a un reinicio. R10 exige que DOS MENSAJES CREADOS por este
     * emisor no empaten (la guarda monotona de `createMessage` lo garantiza); el
     * desempate queda para el caso residual de dos RECIBIDOS en el mismo
     * milisegundo, que no se pueden ordenar por secuencia porque el wire v1 no
     * la lleva y no se toca. Es estable y declarado, no depende del rowid ni del
     * orden de insercion.
     */
    private fun compare(a: Entry, b: Entry): Int {
        val byTime = a.timestamp.compareTo(b.timestamp)
        return if (byTime != 0) byTime else a.messageId.compareTo(b.messageId)
    }
}

/**
 * Lo que dejo una rehidratacion: cuantos se admitieron y cuantos se barrieron
 * de la persistencia. Es observable para que "no se rehidrato nada" se pueda
 * distinguir de "se barrio todo".
 */
internal data class RehydrateReport(val admitted: Int, val swept: Int)
