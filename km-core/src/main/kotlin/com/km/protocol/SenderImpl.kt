package com.km.protocol

import com.km.codec.Encoder
import com.km.codec.Validator
import com.km.crypto.Ed25519
import com.km.messaging.SecureOutbound
import com.km.messaging.SecureSendOutcome
import com.km.messaging.SecureSendReject
import com.km.messaging.SecureSendRejectedException
import com.km.model.*
import com.km.storage.MessageStore
import com.km.storage.OfflineQueue

/**
 * Emisor de km-core, y R1: EL PUNTO DE DECISION entre salir cifrado y salir en
 * claro.
 *
 * ## EL ORDEN DE LAS COMPROBACIONES, Y POR QUE ES ESTE
 *
 * 1. Hay canal? `Transport.isOnline()` responde primero. Si no lo hay NO se
 *    emite nada, y por eso no se decide nada de cifrado: el mensaje se encola
 *    como haria el camino legado. Encolar no es una forma de enviar en claro,
 *    porque lo que sale de la cola vuelve a pasar por [send], y ahi decide la
 *    sesion.
 * 2. Hay camino cifrado? Si el consumidor inyecto un `SecureOutbound`, el
 *    sobre va a la sesion del destinatario. O sale un `SecureFrame`, o se
 *    RECHAZA de forma observable. Nunca se cae a `Transport.send`.
 * 3. No hay camino cifrado: se conserva el camino legado. Y aqui esta la parte
 *    que no hay que maquillar: ESE MENSAJE SALE EN CLARO. Es compatibilidad, no
 *    cifrado, y ningun consumidor debe leerlo de otra manera.
 *
 * R3 (DISPONIBILIDAD, y solo disponibilidad): el paso 1 pregunta al `Transport`
 * legado y el paso 2 al `PeerTransportManager`. Son dos nociones distintas de "hay
 * quien me escuche" y aqui se mezclan a proposito, porque el emisor ya recibia
 * las dos: se conserva la primera y se deja que la segunda decida por si sola.
 *
 * ## UN SOLO CAMINO, UNA SOLA CABEZA (R1, R2, R3, R8)
 *
 * Antes habia DOS salidas de reenvio: `onPeerOnline` drenaba la cola offline
 * con avidez y el `RetryManagerImpl` reintentaba por su cuenta. Ninguna mandaba
 * sobre la otra, y el mensaje que caia en la cola ADELANTABA a los que
 * esperaban su reintento: la conversacion llegaba `[2,1,3,4]`.
 *
 * Ahora hay UN camino. Todo mensaje no confirmado de un par se inscribe en
 * [sendQueue] (`PerPeerSendQueue`, orden `(timestamp, messageId)`) y SOLO la
 * CABEZA del par transmite. `onPeerOnline` ya no recorre nada: despierta la
 * cabeza y se va (R8). El orden no depende de quien tenga el temporizador mas
 * corto, sino de quien es cabeza.
 *
 * ## EL TECHO DE LA CABEZA, DICHO CON NUMEROS
 *
 * La cabeza no puede bloquear al resto para siempre (R4). Su cota es
 * [HEAD_WAIT_CEILING_MS]: `5 s + 10 + 20 + 40 + 80 = 155 s`, el backoff de
 * siempre (`initialDelayMs = 5000`, x2, `maxRetryCount = 5`) hasta el quinto
 * vencimiento. Al quinto no se reintenta mas: la cabeza pasa a `EXPIRED` CON su
 * motivo, sale de la cola del par y del `OfflineQueue` (la FILA se conserva como
 * tombstone visible y reenviable), y la siguiente ocupa su sitio. Un ACK perdido
 * retrasa a los siguientes del par hasta 155 s; ese es el peor caso y esta
 * declarado.
 *
 * ## EL WIRE ES UNO (R5)
 *
 * `lastWireByMessageId` cachea el frame cifrado. El reintento REENVIA ese mismo
 * frame (`retransmit`), nunca re-cifra: re-cifrar quemaria otra clave del
 * ratchet por un mensaje que no cambio. Un mensaje se inscribe UNA vez; un
 * despertar repetido no lo duplica ni lo reordena.
 *
 * ## LO QUE PASA CON UN DESTINO OFFLINE, DICHO SIN MAQUILLAR
 *
 * Con `SecureOutbound` inyectado, un destino offline se ENCOLA en el paso 1 y se
 * vacia por el PASO 2, es decir **CIFRADO**, o **NO se vacia**: la sesion exige
 * que el binding este CONNECTED, y si no lo esta el envio se RECHAZA de forma
 * observable. Lo que NO puede pasar —y por eso no hay que escribirlo al reves—
 * es que se vacie por el paso 3: el paso 3 es el camino legado EN CLARO y solo
 * existe cuando `SecureOutbound == null`.
 *
 * Desde D9, ese rechazo por transporte (`TRANSPORT_REJECTED`) NO es terminal:
 * el binding puede estar aun a medio conducir a CONNECTED y el mensaje vuelve a
 * la cabeza con el MISMO wire. `NO_SESSION` y `NOT_AUTHENTICATED` siguen
 * terminales: ahi no hay sesion ni autoridad, y reintentar no las crea.
 *
 * ## POR QUE EL RECHAZO NO ES UNA EXCEPCION QUE SUBE
 *
 * Porque quien llama necesita distinguir "no salio" de "salio", y para eso hace
 * falta un estado, no un fallo desatendido: el mensaje queda `FAILED` con su
 * codigo de fallo en el registro, que es lo unico que sobrevive al proceso. Un
 * reintento en claro como plan B seria peor que el fallo: el usuario creeria
 * que ha enviado algo y lo que sale por la red no es lo que cree.
 */
class SenderImpl(
    private val messageStore: MessageStore,
    private val offlineQueue: OfflineQueue,
    private val encoder: Encoder,
    private val validator: Validator,
    private val ed25519: Ed25519,
    private val transport: Transport,
    private val ackManager: AckManagerImpl,
    private val retryManager: RetryManagerImpl,
    private val localIdentity: IdentityId,
    private val localPrivateKey: ByteArray,
    /**
     * Camino cifrado, o `null` si el consumidor no lo inyecto.
     *
     * `null` = el envio sale EN CLARO por `Encoder` + `Transport.send`. Ver el
     * KDoc de la clase y el de `SecureOutbound`.
     */
    private val secureOutbound: SecureOutbound? = null,
) : Sender {

    companion object {
        /**
         * R4 — cota de espera de la cabeza, en milisegundos.
         *
         * `5 + 10 + 20 + 40 + 80 = 155`. No es un numero elegido: es la suma
         * del backoff vigente (`initialDelayMs = 5000`, factor x2,
         * `maxRetryCount = 5`) hasta el quinto vencimiento. Si el backoff
         * cambiara, esta constante dejaria de describirlo y `RetryManagerTest`
         * es donde eso se ve.
         */
        const val HEAD_WAIT_CEILING_MS: Long = 155_000L
    }

    private val stateHandlers = mutableListOf<(MessageId, MessageState, FailureCode?) -> Unit>()

    /** R1/R2 — la cola ordenada por par: la unica puerta de emision. */
    private val sendQueue = PerPeerSendQueue()

    /**
     * Ultimo wire cifrado por mensaje. Es lo que hace idempotente el reintento.
     *
     * Solo se escribe cuando el envio sale cifrado y con exito, y se borra en
     * cuanto ese wire deja de servir: un `ACK`, un rechazo terminal, un
     * `cancel`. Un mensaje sin entrada aqui NO se re-cifra por el camino de
     * reintento: ver [retrySecure].
     */
    private val lastWireByMessageId = mutableMapOf<MessageId, ByteArray>()

    /**
     * Mensajes que YA salieron y esperan ACK/EXPIRED.
     *
     * Es lo que impide el doble envio por despertar repetido: un `PEER_ONLINE`
     * y el `CONNECTED` del canal pueden llegar los dos, y el segundo NO puede
     * poner un segundo frame del mismo mensaje en el cable. Se limpia en ACK,
     * EXPIRED, rechazo terminal y `cancel`.
     */
    private val inFlight = mutableSetOf<MessageId>()

    /**
     * Cabeza cuyo temporizador de reintento ya esta programado, por par.
     *
     * Sin esto, cada despertar reprogramaria el intento 1 y el mensaje no
     * llegaria NUNCA a agotar los intentos: su techo de 155 s seria infinito.
     */
    private val headRetryScheduled = mutableMapOf<IdentityId, MessageId>()

    // ===================================================================
    // Entrada publica
    // ===================================================================

    /**
     * R1 — el UNICO punto de entrada de envio.
     *
     * Inscribe el mensaje en la cola del par y despierta la cabeza. No decide
     * por donde sale: eso lo hace [transmit], y solo la cabeza llega ahi.
     */
    @Synchronized
    override fun send(message: Message): Result<Unit> {
        validator.validateMessage(message).getOrThrow()
        // F1: el registro lo escribio `createMessage`, y aqui SOLO se lee.
        //
        // Insertar aqui seria un segundo dono del mismo mensaje: el reintento y
        // el despertar de cabecera pasan por este mismo camino, asi que un
        // `insert` recrearia el registro en cada intento y el `messageId` -- que
        // es la clave contra la que `AckManager` empareja -- dejaria de ser
        // estable. Un envio de un mensaje que nadie persistio no es un envio
        // recuperable: si la conexion se cae a mitad, no hay de donde reintentar.
        val stored = messageStore.get(message.messageId).getOrNull()
            ?: return Result.failure(
                NoSuchElementException("Message ${message.messageId} not found")
            )
        return enqueueForSend(stored)
    }

    /**
     * R1/R2 — un mensaje entra (o vuelve a entrar) en la cola ordenada.
     *
     * La inscripcion es IDEMPOTENTE: un `send` repetido del mismo mensaje no lo
     * duplica ni lo mueve de sitio. La persistencia (`OfflineQueue`) es la cara
     * durable del `QUEUED` (D6): el mensaje que espera sobrevive al proceso y se
     * rehidrata al arrancar. El borrado de esa entrada NO ocurre aqui: se
     * difiere al ACK, porque mientras no haya ACK el mensaje sigue pendiente.
     */
    private fun enqueueForSend(stored: Message): Result<Unit> {
        val peer = stored.to
        val added = sendQueue.enqueue(stored.messageId, peer, stored.timestamp)
        if (added) {
            // Un mensaje que ya tiene wire no se re-persiste como pendiente de
            // envio: lo que le falta es el ACK, no salir.
            //
            // D5/D6/R14: la persistencia es la cara durable de un mensaje que
            // TODAVIA NO SALIO. Antes solo se persistia cuando el canal estaba
            // caido en ese instante, y un mensaje que esperaba turno detras de
            // una cabeza en vuelo se quedaba solo en memoria: al recrear el
            // nucleo, el sucesor rehidrataba la cabeza pero NO a los que
            // esperaban, y la cola se perdia en silencio. La condicion correcta
            // es "no hay wire", no "el transporte esta offline".
            if (lastWireByMessageId[stored.messageId] == null) {
                persistQueued(stored)
            }
            transition(stored.messageId, MessageState.QUEUED, null)
        }
        if (sendQueue.head(peer) != stored.messageId) {
            // R2: NO es cabeza. Espera su turno; el ACK/EXPIRED del anterior la
            // promueve. Ningun temporizador corto la adelanta.
            return Result.success(Unit)
        }
        ensureHeadRetry(peer, stored.messageId)
        return pump(peer)
    }

    /**
     * Reintenta UN mensaje ya enviado, por el camino que le corresponde.
     *
     * Contrato estricto: reenvia el MISMO wire. Un mensaje que nunca salio no
     * tiene wire y este metodo NO lo cifra por la puerta de atras: devuelve
     * `NO_SESSION`. La primera transmision de un `QUEUED` es cosa de [pump], no
     * de este metodo.
     *
     * R2: si el mensaje esta en una cola y NO es su cabeza, no se transmite
     * nada. El temporizador del que se creia cabeza no manda sobre la puerta.
     */
    @Synchronized
    override fun retry(messageId: MessageId): Result<Unit> {
        val message = messageStore.get(messageId).getOrNull()
            ?: return Result.failure(NoSuchElementException("Message $messageId not found"))

        if (message.state != MessageState.SENT && message.state != MessageState.QUEUED) {
            return Result.success(Unit)
        }

        val peer = sendQueue.peerOf(messageId) ?: return retryDirect(message)
        if (sendQueue.head(peer) != messageId) return Result.success(Unit)

        val secure = secureOutbound ?: return retryLegacy(message)
        return retrySecure(message, secure)
    }

    /**
     * Reintenta EXPLICITAMENTE un mensaje que ya murio (`EXPIRED`/`FAILED`).
     *
     * ## POR QUE ES UNA ACCION DEL USUARIO Y NO UNA RESURRECCION AUTOMATICA
     *
     * Un mensaje que agoto sus reintentos conserva su FILA como evidencia
     * (`EXPIRED/TIMEOUT`), pero sale de la cola del par para que la cabeza ceda
     * el turno (R4/R13). Sin una accion explicita, la unica salida seria
     * reescribir a mano el estado o perder el mensaje. Esta es esa accion, y
     * solo existe para terminales: un mensaje vivo no necesita que se le
     * "reintente".
     *
     * ## QUE SE CONSERVA Y QUE SE RECALCULA
     *
     * Se conserva lo que identifica al mensaje: `messageId` y `timestamp`
     * ORIGINALES. Eso mantiene el orden de creacion (la reinscripcion usa la
     * misma marca) y permite que el receptor DEDUPLIQUE por `(messageId, from)`
     * y re-emita su `ACK` (F2). Se borra el wire cacheado: el frame viejo ya no
     * sirve, y el mensaje vuelve a cifrarse UNA vez. No se toca ninguna clave
     * de establecimiento: no hay PREKEY ni re-PREKEY en este camino.
     *
     * @return fallo si el mensaje no existe o no es terminal. Un no-op
     *   silencioso es exactamente el defecto que este cambio cierra.
     */
    @Synchronized
    override fun resend(messageId: MessageId): Result<Unit> {
        val message = messageStore.get(messageId).getOrNull()
            ?: return Result.failure(NoSuchElementException("Message $messageId not found"))
        if (message.state != MessageState.EXPIRED && message.state != MessageState.FAILED) {
            return Result.failure(
                IllegalStateException("solo se reenvia un mensaje terminal, y $messageId esta ${message.state}"),
            )
        }
        retryManager.cancelRetry(messageId)
        lastWireByMessageId.remove(messageId)
        inFlight.remove(messageId)
        // El motivo viejo deja de aplicar: el mensaje vuelve a estar en cola,
        // no "fallido". Si el nuevo intento falla, escribira su propio codigo.
        transition(messageId, MessageState.QUEUED, null)
        persistQueued(message)
        sendQueue.enqueue(messageId, message.to, message.timestamp)

        val peer = message.to
        if (sendQueue.head(peer) != messageId) {
            // Otro mensaje anterior sigue siendo cabeza. El reenvio espera su
            // turno, como cualquier otro: adelantarlo seria reordenar.
            return Result.success(Unit)
        }
        ensureHeadRetry(peer, messageId)
        return pump(peer)
    }

    /**
     * Descarta un mensaje terminal por decision EXPLICITA del usuario.
     *
     * Es la unica forma de que una fila desaparezca: la expiracion y el rechazo
     * dejan tombstone; el silencio nunca borra. Por eso exige que el mensaje ya
     * sea terminal: borrar un `QUEUED`/`SENT` seria tirar un mensaje que aun
     * podia salir, disfrazado de accion de usuario.
     */
    @Synchronized
    override fun discard(messageId: MessageId): Result<Unit> {
        val message = messageStore.get(messageId).getOrNull()
            ?: return Result.failure(NoSuchElementException("Message $messageId not found"))
        if (message.state != MessageState.EXPIRED && message.state != MessageState.FAILED) {
            return Result.failure(
                IllegalStateException("solo se descarta un mensaje terminal, y $messageId esta ${message.state}"),
            )
        }
        retryManager.cancelRetry(messageId)
        lastWireByMessageId.remove(messageId)
        inFlight.remove(messageId)
        val peer = sendQueue.peerOf(messageId)
        sendQueue.discard(messageId)
        offlineQueue.remove(messageId)
        if (peer != null) {
            headRetryScheduled.remove(peer)
            promoteNext(peer)
        }
        return messageStore.delete(messageId)
    }

    /**
     * D3 — abandona lo que estaba EN VUELO al cerrar este nucleo.
     *
     * Un `SENT` significa "el socket local acepto los bytes", no "el par lo
     * recibio y lo confirmo". Al cerrar el nucleo esa espera ya no puede
     * terminar (no hay temporizador ni ACK posible), y dejarla como `SENT` para
     * siempre seria un OUT eterno que el usuario no puede accionar. Se convierte
     * cada uno en `EXPIRED/TIMEOUT`: la FILA se conserva (tombstone visible y
     * reenviable) y sale de las dos colas.
     *
     * Los que esperaban turno (`QUEUED`) NO se tocan: el sucesor los rehidrata
     * desde el `OfflineQueue`. La cabeza nueva se promociona de forma
     * ESTRUCTURAL (queda como `head`) pero no se transmite ni se le programa
     * temporizador: este emisor se esta apagando y el que manda es el sucesor.
     *
     * @return cuantos mensajes en vuelo se convirtieron en tombstone.
     */
    @Synchronized
    internal fun abandonInFlight(): Int {
        val enVuelo = inFlight.toList()
        val pares = enVuelo.mapNotNull { sendQueue.peerOf(it) }.distinct()
        enVuelo.forEach { messageId ->
            retryManager.cancelRetry(messageId)
            lastWireByMessageId.remove(messageId)
            inFlight.remove(messageId)
            transition(messageId, MessageState.EXPIRED, FailureCode.TIMEOUT)
            sendQueue.discard(messageId)
            offlineQueue.remove(messageId)
        }
        pares.forEach { peer ->
            headRetryScheduled.remove(peer)
            sendQueue.head(peer)?.let { retryManager.cancelRetry(it) }
        }
        return enVuelo.size
    }

    /**
     * Cancela el mensaje: ni sale, ni se reintenta, ni resucita al rehidratar.
     *
     * Sale tambien de la cola persistida a proposito: dejar ahi un mensaje
     * cancelado haria que el proximo arranque lo tratara como pendiente y lo
     * enviara sin que nadie lo haya pedido.
     */
    @Synchronized
    override fun cancel(messageId: MessageId): Result<Unit> {
        retryManager.cancelRetry(messageId)
        lastWireByMessageId.remove(messageId)
        inFlight.remove(messageId)
        val peer = sendQueue.peerOf(messageId)
        if (peer != null) {
            val wasHead = sendQueue.head(peer) == messageId
            sendQueue.discard(messageId)
            offlineQueue.remove(messageId)
            if (wasHead) {
                headRetryScheduled.remove(peer)
                promoteNext(peer)
            }
        }
        transition(messageId, MessageState.FAILED, FailureCode.INTERNAL_ERROR)
        return Result.success(Unit)
    }

    // ===================================================================
    // Senales: ACK, vencimiento, presencia
    // ===================================================================

    /**
     * El `ACK` de [messageId] ha llegado AUTENTICADO: el wire ya no sirve y la
     * cabeza AVANZA.
     *
     * ## POR QUE EL WIRE SE LIMPIA AQUI Y SOLO AQUI (ademas de la muerte)
     *
     * `lastWireByMessageId` existe para reenviar el MISMO wire en un reintento.
     * Un wire deja de servir en dos momentos: cuando el `ACK` confirma que llego,
     * y cuando el mensaje muere ([failSecure]/[cancel]). Limpiar en cualquier
     * otro punto rompe la idempotencia.
     *
     * ## POR QUE EL `OfflineQueue` SE BORRA AQUI Y NO AL DRENAR (D6)
     *
     * Antes se borraba al vaciar la cola. Un mensaje transmitido pero SIN ACK
     * quedaba fuera de la persistencia, de modo que un cierre del proceso lo
     * perdia en silencio. El ACK es la unica prueba de que ya no hay que
     * enviarlo; hasta entonces, la entrada se conserva.
     *
     * Que el disparador sea un `ACK` AUTENTICADO —y no cualquier byte— lo
     * garantiza `AckManagerImpl`: solo dispara sus handlers tras verificar la
     * firma. Este metodo no vuelve a comprobarlo porque no puede: no tiene las
     * claves ni el mensaje.
     */
    @Synchronized
    fun onAckConfirmed(messageId: MessageId) {
        lastWireByMessageId.remove(messageId)
        inFlight.remove(messageId)
        retryManager.cancelRetry(messageId)

        val peer = sendQueue.peerOf(messageId) ?: return
        if (sendQueue.head(peer) == messageId) sendQueue.advance(peer) else sendQueue.discard(messageId)
        offlineQueue.remove(messageId)
        headRetryScheduled.remove(peer)
        promoteNext(peer)
    }

    /**
     * R4 — vencio el intento [attempt] de [messageId].
     *
     * Si es la cabeza (lo unico que tiene temporizador mientras espera), se
     * reintenta y se reprograma; al pasarse del maximo, `EXPIRED` y la
     * siguiente ocupa su sitio: la cabeza agotada NO bloquea al par (D4).
     *
     * Si el mensaje ya no es cabeza, su temporizador es un HUERFANO (lo dejo
     * atras un ACK) y se ignora: reprogramarlo seria darle al nuevo cabeza un
     * temporizador que no le toca.
     */
    @Synchronized
    fun onRetryExpired(messageId: MessageId, attempt: Int) {
        val peer = sendQueue.peerOf(messageId)
        if (peer == null) {
            // Fuera de la cola ordenada: camino heredado para quien usa el
            // emisor sin pasar por `send` (API directa de reintento).
            val nextAttempt = attempt + 1
            if (nextAttempt > retryManager.maxRetries()) {
                transition(messageId, MessageState.EXPIRED, FailureCode.TIMEOUT)
                return
            }
            retry(messageId)
            retryManager.scheduleRetry(messageId, nextAttempt)
            return
        }
        if (sendQueue.head(peer) != messageId) return

        val nextAttempt = attempt + 1
        if (nextAttempt > retryManager.maxRetries()) {
            expireHead(messageId, peer)
            return
        }
        val message = messageStore.get(messageId).getOrNull()
        if (message == null) {
            expireHead(messageId, peer)
            return
        }
        inFlight.remove(messageId)
        transmit(message)
        retryManager.scheduleRetry(messageId, nextAttempt)
    }

    /**
     * R8 — el par ha vuelto (presencia del rele). DESPIERTA la cabeza; no drena.
     *
     * Antes este metodo recorria `offlineQueue.peekAll()` y llamaba a `send` por
     * mensaje: ese drenado avido es exactamente el defecto que este cambio
     * quita. Aqui no se recorre nada, no se decide nada y no se encola nada
     * nuevo: la cabeza del par es la unica que puede transmitir, y el resto
     * espera su turno.
     */
    @Synchronized
    fun onPeerOnline(peerId: IdentityId) {
        wakeHead(peerId)
    }

    /**
     * D8 — el binding del par ha llegado a `CONNECTED`.
     *
     * Es la senal buena para despertar: `sendData` exige `CONNECTED` y el
     * binding lo conduce el driver DESPUES de que el nucleo se suscriba. Un
     * `PEER_ONLINE` del rele puede llegar antes; despertar por el canal real
     * evita el intento en falso.
     */
    @Synchronized
    fun onPeerConnected(peerId: IdentityId) {
        wakeHead(peerId)
    }

    /**
     * D6 — rehidrata la cola ordenada desde la persistencia.
     *
     * Admite `QUEUED` y BARRE del `OfflineQueue` lo que ya no es pendiente
     * (`SENT` sin wire tras un reinicio, `DELIVERED`, ...), porque la app nunca
     * llama a `expire`. No transmite aqui: al arrancar puede NO haber sesion
     * todavia, y un `NO_SESSION` terminal mataria mensajes que solo esperaban a
     * que se estableciera. La primera presencia/`CONNECTED` los despierta.
     */
    @Synchronized
    internal fun rehydrate(): RehydrateReport {
        val queued = offlineQueue.peekAll().getOrNull().orEmpty()
        return sendQueue.rehydrate(
            queued = queued,
            stateOf = { id -> messageStore.get(id).getOrNull()?.state },
            onStale = { message -> offlineQueue.remove(message.messageId) },
        )
    }

    fun registerStateHandler(handler: (MessageId, MessageState, FailureCode?) -> Unit) {
        stateHandlers.add(handler)
    }

    /** D3 — un nucleo cerrado no puede notificar a nadie. */
    internal fun clearStateHandlers() {
        stateHandlers.clear()
    }

    /**
     * Cuantos wires hay cacheados ahora mismo.
     *
     * Existe por una razon que no es de comodidad: sin un contador observable,
     * "el mapa se limpia tras el `ACK`" es una garantia sobre estado interno que
     * ningun test puede pinzar, y una garantia que no se puede pinzar es una
     * garantia que se rompe en silencio. Con esto, un test afirma el 0 y el
     * crecimiento sin limite deja de poder esconderse.
     */
    internal fun cachedWireCount(): Int = lastWireByMessageId.size

    /** Cuantos mensajes esperan en el par. Observabilidad y tests. */
    internal fun queueSize(peer: IdentityId): Int = sendQueue.size(peer)

    /** La cabeza actual del par, o `null`. Observabilidad y tests. */
    internal fun headOf(peer: IdentityId): MessageId? = sendQueue.head(peer)

    // ===================================================================
    // La cabeza: despertarla y transmitirla
    // ===================================================================

    private fun wakeHead(peer: IdentityId) {
        val head = sendQueue.head(peer) ?: return
        ensureHeadRetry(peer, head)
        pump(peer)
    }

    /**
     * Despierta a la cabeza del par si —y solo si— puede volar ahora.
     *
     * Un mensaje que ya salio y espera ACK (`inFlight`) NO se reenvia por el
     * despertar: de eso se encarga su reintento. Sin esta guarda, un
     * `PEER_ONLINE` seguido de `CONNECTED` pondria dos frames del mismo mensaje
     * en el cable, y la idempotencia del receptor estaria tapando un doble
     * envio en vez de un reenvio legitimo.
     */
    private fun pump(peer: IdentityId): Result<Unit> {
        val head = sendQueue.head(peer) ?: return Result.success(Unit)
        if (inFlight.contains(head)) return Result.success(Unit)
        val message = messageStore.get(head).getOrNull()
        if (message == null) {
            discardSilently(head, peer)
            return promoteNext(peer)
        }
        when (message.state) {
            MessageState.FAILED,
            MessageState.EXPIRED,
            MessageState.DELIVERED,
            -> {
                discardSilently(head, peer)
                return promoteNext(peer)
            }
            else -> Unit
        }
        if (!transport.isOnline()) {
            // D5: si el canal se fue entre la inscripcion y el turno, el mensaje
            // pasa a ESPERAR y por tanto se persiste aqui, que es el unico
            // instante en que de verdad espera.
            persistQueued(message)
            transition(head, MessageState.QUEUED, null)
            return Result.success(Unit)
        }
        transition(head, MessageState.SENDING, null)
        return transmit(message)
    }

    /**
     * La cabeza transmite: EL UNICO punto donde se cifra o se reenvia.
     *
     * Cifrado: si ya hay wire, se reenvia EL MISMO (`retransmit`); si no, se
     * cifra una vez. Es la unica forma de que R5 y el ratchet convivan.
     */
    private fun transmit(stored: Message): Result<Unit> {
        val secure = secureOutbound ?: return transmitLegacy(stored)
        val cached = lastWireByMessageId[stored.messageId]
        val outcome = if (cached != null) secure.retransmit(stored, cached) else secure.send(stored)
        return applySecureOutcome(stored, outcome)
    }

    /**
     * Traduce el resultado cifrado a estado, y decide si el rechazo es terminal.
     *
     * D9: `TRANSPORT_REJECTED` NO mata el mensaje. El binding puede no estar aun
     * `CONNECTED`; el wire ya existe (viaja en el rechazo) y el reintento lo
     * reenvia sin re-cifrar. `NO_SESSION`/`NOT_AUTHENTICATED` (y el resto) SI
     * son terminales: reintentar no crea una sesion ni una autoridad.
     */
    private fun applySecureOutcome(stored: Message, outcome: SecureSendOutcome): Result<Unit> {
        return when (outcome) {
            is SecureSendOutcome.Encrypted -> {
                lastWireByMessageId[stored.messageId] = outcome.wire
                inFlight.add(stored.messageId)
                transition(stored.messageId, MessageState.SENT, null)
                ackManager.expectAck(stored.messageId)
                Result.success(Unit)
            }
            is SecureSendOutcome.Rejected -> when (outcome.reason) {
                SecureSendReject.TRANSPORT_REJECTED -> {
                    // El ratchet ya produjo el frame: se conserva para que el
                    // reintento no queme otra clave de la cadena.
                    if (outcome.wire.isNotEmpty()) {
                        lastWireByMessageId[stored.messageId] = outcome.wire
                    }
                    inFlight.remove(stored.messageId)
                    // D5: un rechazo de TRANSPORTE es el mensaje pasando a
                    // esperar. Se persiste para sobrevivir al proceso; el borrado
                    // sigue diferido al ACK (D6).
                    persistQueued(stored)
                    transition(stored.messageId, MessageState.QUEUED, null)
                    Result.success(Unit)
                }
                else -> {
                    inFlight.remove(stored.messageId)
                    val failure = failSecure(stored.messageId, outcome.reason, outcome.failureCode)
                    terminate(stored.messageId, stored.to)
                    failure
                }
            }
        }
    }

    /**
     * Camino legado (EN CLARO, solo si no se inyecto `SecureOutbound`).
     *
     * Mismo contrato de cola: si el `Transport` rechaza, el mensaje vuelve a
     * `QUEUED` y sigue siendo cabeza, no se pierde.
     */
    private fun transmitLegacy(stored: Message): Result<Unit> {
        val encoded = encoder.encodeMessage(stored).getOrElse { return Result.failure(it) }
        return if (transport.isOnline()) {
            val sendResult = transport.send(encoded, stored.to)
            if (sendResult.isSuccess) {
                inFlight.add(stored.messageId)
                transition(stored.messageId, MessageState.SENT, null)
                ackManager.expectAck(stored.messageId)
                Result.success(Unit)
            } else {
                persistQueued(stored)
                transition(stored.messageId, MessageState.QUEUED, null)
                Result.success(Unit)
            }
        } else {
            persistQueued(stored)
            transition(stored.messageId, MessageState.QUEUED, null)
            Result.success(Unit)
        }
    }

    /**
     * Reintento cifrado: el MISMO wire, o un fallo explicito.
     *
     * `MAX_RESEND` es un checkpoint cerrado, y la razon de que esto no cifre de
     * nuevo es la misma: un frame distinto, con otro `N` y otra clave de
     * mensaje, es un mensaje NUEVO. El receptor lo descifraria dos veces y el
     * emisor quemaria dos claves de la cadena por un solo reintento.
     *
     * Si no hay wire cacheado, no hay reintento posible. La alternativa
     * --re-cifrar el sobre con el mismo `messageId`-- parece inocua y no lo es:
     * produce un frame que el receptor no puede distinguir de un mensaje
     * legitimo nuevo y, de paso, avanza el ratchet. Ante la duda el mensaje se
     * queda como esta y se avisa con un fallo, que es informacion; fingir que
     * salio no lo es.
     */
    private fun retrySecure(message: Message, secure: SecureOutbound): Result<Unit> {
        val wire = lastWireByMessageId[message.messageId]
            ?: return Result.failure(
                SecureSendRejectedException(SecureSendReject.NO_SESSION),
            )
        return applySecureOutcome(message, secure.retransmit(message, wire))
    }

    /** Reintento del camino legado: re-codifica, que ahi no hay ratchet que quemar. */
    private fun retryLegacy(message: Message): Result<Unit> {
        val encoded = encoder.encodeMessage(message).getOrElse { return Result.failure(it) }
        return if (transport.isOnline()) {
            val sendResult = transport.send(encoded, message.to)
            if (sendResult.isSuccess) {
                inFlight.add(message.messageId)
                transition(message.messageId, MessageState.SENT, null)
                Result.success(Unit)
            } else {
                transition(message.messageId, MessageState.QUEUED, null)
                Result.success(Unit)
            }
        } else {
            Result.failure(Exception("Offline"))
        }
    }

    /**
     * Reintento de un mensaje que NO esta en la cola ordenada (API directa).
     *
     * Es el comportamiento heredado de `retry` y existe para quien llama al
     * emisor sin pasar por `send` (tests del wire, consumidores del API). La
     * cola ordenada no lo gobierna porque no lo conoce.
     */
    private fun retryDirect(message: Message): Result<Unit> {
        val secure = secureOutbound ?: return retryLegacy(message)
        return retrySecure(message, secure)
    }

    // ===================================================================
    // Promocion de la cabeza y muerte de un mensaje
    // ===================================================================

    private fun promoteNext(peer: IdentityId): Result<Unit> {
        val next = sendQueue.head(peer)
        if (next == null) {
            headRetryScheduled.remove(peer)
            return Result.success(Unit)
        }
        ensureHeadRetry(peer, next)
        return pump(peer)
    }

    /**
     * Programa el techo de la cabeza UNA vez.
     *
     * Reprogramar el intento 1 en cada despertar haria que la cabeza no agotara
     * nunca sus intentos: su cota de 155 s dejaria de existir y bloquearia al
     * par para siempre, que es justo lo que R4 prohibe.
     */
    private fun ensureHeadRetry(peer: IdentityId, head: MessageId) {
        if (headRetryScheduled[peer] == head) return
        headRetryScheduled[peer] = head
        retryManager.scheduleRetry(head, 1)
    }

    /** R4: la cabeza agoto sus intentos. `EXPIRED`, fuera, y la siguiente entra. */
    private fun expireHead(messageId: MessageId, peer: IdentityId) {
        retryManager.cancelRetry(messageId)
        lastWireByMessageId.remove(messageId)
        inFlight.remove(messageId)
        transition(messageId, MessageState.EXPIRED, FailureCode.TIMEOUT)
        sendQueue.discard(messageId)
        offlineQueue.remove(messageId)
        headRetryScheduled.remove(peer)
        promoteNext(peer)
    }

    /**
     * Rechazo terminal: la cabeza cae y el par no se queda parado detras.
     *
     * La FILA del mensaje conserva `FAILED` con su codigo: es la evidencia
     * durable y la unica pieza que el usuario puede ver y reenviar (R13). Lo que
     * se retira es su entrada de `OfflineQueue`, porque esa cola es la cara
     * durable de lo que ESPERA y un mensaje terminal ya no espera. Dejarla ahi
     * era el resto inconsistente entre `expireHead` (que la borraba) y este
     * camino (que la conservaba).
     */
    private fun terminate(messageId: MessageId, peer: IdentityId) {
        retryManager.cancelRetry(messageId)
        inFlight.remove(messageId)
        sendQueue.discard(messageId)
        offlineQueue.remove(messageId)
        headRetryScheduled.remove(peer)
        promoteNext(peer)
    }

    private fun discardSilently(messageId: MessageId, peer: IdentityId) {
        retryManager.cancelRetry(messageId)
        inFlight.remove(messageId)
        lastWireByMessageId.remove(messageId)
        sendQueue.discard(messageId)
        offlineQueue.remove(messageId)
        headRetryScheduled.remove(peer)
    }

    // ===================================================================
    // Detalles
    // ===================================================================

    /**
     * Rechazo terminal: estado `FAILED`, sin `Transport.send`.
     *
     * Devuelve `Result.failure` a proposito, para que `KeyMessageCoreImpl` no
     * lo reporte como enviado. El estado del store es la verdad observable y ya
     * queda escrito antes de que este metodo devuelva.
     */
    private fun failSecure(
        messageId: MessageId,
        reason: SecureSendReject,
        failureCode: FailureCode,
    ): Result<Unit> {
        // El wire cacheado era de un intento que ya no vale: si el mensaje
        // llegara a salir por el camino legado, ese seria el frame que el
        // receptor veria, y no el de este intento.
        lastWireByMessageId.remove(messageId)
        transition(messageId, MessageState.FAILED, failureCode)
        return Result.failure(SecureSendRejectedException(reason))
    }

    /** Escritura de estado + notificacion, en el mismo sitio para no divergir. */
    private fun transition(
        messageId: MessageId,
        state: MessageState,
        failureCode: FailureCode?,
    ): Result<Unit> {
        val written = messageStore.updateState(messageId, state, failureCode)
        stateHandlers.forEach { it(messageId, state, failureCode) }
        return written
    }

    /**
     * Persiste el mensaje como pendiente, sin duplicar.
     *
     * `OfflineQueue.enqueue` no es idempotente por contrato (la implementacion
     * de Room inserta con REPLACE por clave, la de memoria anade al final), asi
     * que la comprobacion vive aqui: una sola entrada por `messageId`.
     */
    private fun persistQueued(message: Message) {
        val already = offlineQueue.peekAll().getOrNull().orEmpty()
            .any { it.messageId == message.messageId }
        if (!already) offlineQueue.enqueue(message)
    }
}
