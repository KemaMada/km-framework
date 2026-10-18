package com.km.node

import com.km.model.IdentityId
import java.util.concurrent.ConcurrentHashMap

/**
 * Clase de un fallo de transporte, segun QUE se rompio (3Q.1).
 *
 * La distincion no es cosmetica: determina si tiene sentido reintentar la
 * MISMA operacion sobre un transporte distinto. Un fallo de red justifies
 * cambiar de medio; un fallo de autenticacion o de formato no, y hacerlo
 * unicamente enmascara el problema original.
 *
 *  TRANSPORT  el MEDIO fallo. Otro medio puede funcionar.
 *             Unico caso que justifica fallback (P2P -> Relay -> Tor).
 *             Ejemplo: el DataChannel no estaba OPEN.
 *
 *  SECURITY    la IDENTIDAD fallo. Reintentar por otro medio no cambia el
 *             hecho de que no sabemos con quien hablamos, y el fallback
 *             podria enmascarar un intento de suplantacion.
 *             Ejemplo: el peer presento un publicKey distinto al vinculado.
 *
 *  PROTOCOL    los DATOS o la NEGOCIACION son invalidos. Los mismos bytes
 *             produciran el mismo fallo en cualquier transporte.
 *             Ejemplo: SDP malformada.
 *
 *  STATE       el ESTADO LOCAL impide la operacion. Reintentar no lo cambia;
 *             quien debe resolverlo es el manager o la cadena, no el medio.
 *             Ejemplo: transicion de estado no permitida.
 */
enum class TransportErrorCategory {
    TRANSPORT,
    SECURITY,
    PROTOCOL,
    STATE,
}

/**
 * Error codes for PeerTransportManager operations.
 *
 * Cada constante declara su [category], y de ella se deriva [permitsFallback].
 * La tabla es el contrato que permitira a `TransportChain` (3Q.2) decidir si
 * debe pasar al siguiente transporte o propagar el fallo hacia arriba.
 */
enum class TransportError(val category: TransportErrorCategory) {
    // --- SEGURIDAD: nunca justifican fallback ---
    PEER_CONTEXT_NOT_AUTHENTICATED(TransportErrorCategory.SECURITY),
    SESSION_MISMATCH(TransportErrorCategory.SECURITY),
    PEER_ID_MISMATCH(TransportErrorCategory.SECURITY),
    NEGOTIATION_HASH_MISMATCH(TransportErrorCategory.SECURITY),

    // --- PROTOCOLO: los mismos bytes fallaran igual en otro medio ---
    INVALID_SDP(TransportErrorCategory.PROTOCOL),
    INVALID_ICE_CANDIDATE(TransportErrorCategory.PROTOCOL),
    INVALID_FRAME_AT_RELAY(TransportErrorCategory.PROTOCOL),

    // --- ESTADO: reintentar no cambia el estado ---
    BINDING_ALREADY_EXISTS(TransportErrorCategory.STATE),
    INVALID_STATE_TRANSITION(TransportErrorCategory.STATE),

    // --- TRANSPORTE: el medio fallo, otro medio puede funcionar ---
    BINDING_NOT_FOUND(TransportErrorCategory.TRANSPORT),
    BINDING_CLOSED(TransportErrorCategory.TRANSPORT),
    BINDING_FAILED(TransportErrorCategory.TRANSPORT),

    // --- TRANSPORTE: fallo reportado por el backend ---
    // El destino no esta donde este medio podria alcanzarlo. Es un fallo del
    // MEDIO, no del protocolo: por eso autorizan fallback hacia otro.
    PEER_NOT_ONLINE_AT_RELAY(TransportErrorCategory.TRANSPORT),
    BACKEND_NOT_READY(TransportErrorCategory.TRANSPORT),
    BACKEND_SEND_FAILED(TransportErrorCategory.TRANSPORT),
    BACKEND_ERROR(TransportErrorCategory.TRANSPORT),
    ;

    /**
     * Si la misma operacion puede reintentarse sobre un transporte distinto.
     *
     * Es la unica pregunta que `TransportChain` necesita responder para
     * decidir entre probar el siguiente medio y propagar el fallo.
     *
     * Deliberadamente restrictiva: solo los fallos del MEDIO la permiten.
     */
    val permitsFallback: Boolean
        get() = category == TransportErrorCategory.TRANSPORT
}

/**
 * Result type for transport operations.
 */
sealed class TransportResult<out T> {
    data class Success<T>(val value: T) : TransportResult<T>()
    data class Failure(val error: TransportError, val message: String) : TransportResult<Nothing>()

    val isSuccess: Boolean get() = this is Success
    val isFailure: Boolean get() = this is Failure
    fun getOrThrow(): T = when (this) {
        is Success -> value
        is Failure -> throw IllegalStateException("$error: $message")
    }
}

/**
 * Gestor de bindings de transporte peer-to-peer.
 *
 * INVARIANTES DE SEGURIDAD (3K.5):
 * 1. Solo PeerContext AUTHENTICATED puede crear binding.
 * 2. remotePeerId del binding es el remotePeerId del PeerContext, inmutable.
 * 3. Un solo binding activo por (localIdentity, remotePeerId).
 * 4. SDP/ICE solo validos si pertenecen al remotePeerId del binding.
 * 5. Re-auth del peer invalida el binding existente.
 * 6. negotiationHash vinculado al binding via PeerContext.
 *
 * La clave de almacenamiento es (localIdentity, remotePeerId), donde
 * localIdentity es la identidad LOCAL (quien crea el binding)
 * y remotePeerId es la identidad del PEER REMOTO.
 *
 * El gestor delega las operaciones de transporte real a [TransportBackend].
 * Seguridad e identidad permanecen exclusivamente en el manager.
 *
 * @param transportBackend backend de transporte (default: [FakeTransportBackend]).
 * @param now fuente de tiempo.
 */
class PeerTransportManager(
    private val transportBackend: TransportBackend = FakeTransportBackend(),
    private val now: () -> Long = System::currentTimeMillis,
) {
    init {
        transportBackend.initialize()
    }

    // Bindings activos: (localIdentity, remotePeerId) -> PeerTransportBinding
    private val bindings = ConcurrentHashMap<Pair<IdentityId, IdentityId>, PeerTransportBinding>()

    // Handlers
    private val stateChangeHandlers = java.util.concurrent.CopyOnWriteArrayList<(IdentityId, PeerTransportState) -> Unit>()
    private val dataHandlers = java.util.concurrent.CopyOnWriteArrayList<(IdentityId, ByteArray) -> Unit>()

    /**
     * A4.2 — Observadores del ciclo de vida del ENLACE, en booleanos.
     *
     * A diferencia de [onStateChange] (que reporta el estado del binding con la
     * nomenclatura del manager), esta lista habla el idioma del TRANSPORTE:
     * "hay enlace" / "se cayo". Es lo que necesita [ChainSupervisor] para sus
     * triggers (ICE DROPPED/FAILED), y vive aqui y no en el supervisor porque es
     * el manager quien observa las transiciones del binding.
     *
     * Lista vacia por defecto: con nadie suscrito, el manager se comporta
     * EXACTAMENTE igual que antes.
     */
    private val connectionStateListeners =
        java.util.concurrent.CopyOnWriteArrayList<(IdentityId, Boolean) -> Unit>()

    /**
     * B2 — EL CABLE DE RECEPCION (p4).
     *
     * ## QUE ESTA ROTO Y QUE ESTO ARREGLA
     *
     * Hasta p4, este gestor no tenia NI UNA referencia a
     * [TransportBackend.eventCallbacks]. Los `dataHandlers` —la lista a la que
     * se suscriben las sesiones y el puente de recepcion— solo se disparaban
     * desde [sendData], o sea desde el camino de SALIDA. Con un transporte de
     * red, un byte remoto entraba por `onDataReceived` y ahi se quedaba: en
     * produccion ningun byte remoto llegaba jamas a ningun nucleo. El E2E solo
     * se podia demostrar inyectando el eco del propio emisor.
     *
     * ## POR QUE ESTE `init` VA DESPUES DE `dataHandlers`
     *
     * El lambda lee [dataHandlers] en el MOMENTO DEL EVENTO, no al construirlo,
     * asi que el orden no le afecta. Va aqui igualmente, y no en el `init` de
     * arriba, por legibilidad: se lee justo debajo de la lista que despacha. Si
     * alguien lo sube, el comportamiento no cambia y por eso no hay un test que
     * lo prohiva —lo que si hay es un test que lo EXIGE, MW-01.
     *
     * ## POR QUE SE COMPONE Y NO SE SUSTITUYE
     *
     * `TransportEventCallbacks` es un `data class` de slots `null`, y
     * `eventCallbacks` es un `var` con semantica de ASIGNACION. Asignar un
     * `TransportEventCallbacks()` fresco borra todo lo que habia: un
     * `onIceCandidateGenerated` puesto por otro, el `onTransportError` que
     * consulta `ChainSupervisor`, o el cable de otro manager. Por eso aqui se
     * ENCADENA: el lambda anterior se invoca primero y despues se avisa a los
     * `dataHandlers`. Sustituir seria el fallo silencioso de D-B.
     *
     * ## POR QUE NO HAY UN GATE POR BINDING ACTIVO
     *
     * Porque el aislamiento por par YA EXISTE en las dos capas que deben
     * tenerlo, y duplicarlo aqui perderia los contadores: `SecureInbound`
     * cuenta `unknownPeerFrames` y `legacyBytesOnPeerChannel` en un handler
     * global del manager, y un gate aqui —que es el MISMO sitio por donde
     * pasan esos bytes— filtraria antes de que el contador los viera.
     *
     * Las dos capas que ya lo hacen:
     *  1. `SecureMessagingSession` filtra `from == remotePeerId` antes de tocar
     *     el ratchet.
     *  2. `SecureInbound` filtra otra vez y CLASIFICA lo que no es suyo.
     *
     * Ademas el gate seria redundante para el eco y ciego para el caso real:
     * [sendData] ya exige binding activo y `CONNECTED` antes de notificar, de
     * modo que al eco nunca le faltaria el binding; y un byte que entra por
     * `onDataReceived` sin binding no tiene un par al que atribuirse, asi que
     * el gate no tendria nada que decidir.
     *
     * ## LO QUE ESTO NO TOCA
     *
     * Cero referencias a `com.km.crypto`, `com.km.ratchet`, `com.km.frame` o
     * `com.km.x3dh`: el guard `ChainCryptoSeparationArchTest` escanea este
     * fichero y lo comprueba.
     */
    private fun cablearRecepcion() {
        val previo = transportBackend.eventCallbacks
        transportBackend.eventCallbacks = previo.copy(
            onDataReceived = { remotePeerId, data ->
                // Primero el que ya estaba: componer es una GARANTIA, no un
                // reemplazo. Si el lambda previo es `null` —el caso normal—
                // esta llamada no hace nada y no hay que preguntarse por ello.
                previo.onDataReceived?.invoke(remotePeerId, data)
                // Despues los handlers, con el MISMO contrato que el eco de
                // [sendData]: `(remotePeerId, data)`, en orden de registro. El
                // orden importa: la sesion se suscribio en su `init` y el
                // puente al instalarse, y el puente necesita ver el plaintext
                // que la sesion acaba de producir.
                dataHandlers.forEach { it(remotePeerId, data) }
            },
        )
    }

    init {
        cablearRecepcion()
    }

    // ===================================================================
    // Gestion de bindings
    // ===================================================================

    /**
     * Crea un binding para un PeerContext autenticado.
     *
     * @param localIdentity identidad LOCAL (quien crea el binding).
     * @param peerContext contexto del peer autenticado.
     */
    fun createBinding(
        localIdentity: IdentityId,
        peerContext: PeerContext,
    ): TransportResult<PeerTransportBinding> {
        // La AUTORIDAD del par es lo que abre el enlace. Para un nodo eso
        // significa un `AuthSession` en `AUTHENTICATED` vivo; para un contacto,
        // un bundle verificado. Lo que NO significa es "el par es un nodo": esa
        // pregunta no se hace aqui.
        if (!peerContext.authority.authorizesPeerSession()) {
            return TransportResult.Failure(
                TransportError.PEER_CONTEXT_NOT_AUTHENTICATED,
                "la autoridad del par no autoriza la sesion: ${peerContext.authority}"
            )
        }
        val remotePeerId = peerContext.remotePeerId
        val key = localIdentity to remotePeerId
        val existing = bindings[key]
        if (existing != null && existing.isActive) {
            return TransportResult.Failure(
                TransportError.BINDING_ALREADY_EXISTS,
                "ya existe binding activo para ($localIdentity, $remotePeerId)"
            )
        }
        val binding = PeerTransportBinding(peerContext, PeerTransportState.NEW, now())
        bindings[key] = binding
        transportBackend.onCreateBinding(localIdentity, remotePeerId)
        return TransportResult.Success(binding)
    }

    fun getBinding(localIdentity: IdentityId, remotePeerId: IdentityId): PeerTransportBinding? =
        bindings[localIdentity to remotePeerId]

    /**
     * El backend con el que se construyo este manager. Solo lectura.
     *
     * p4: lo necesita `RelayClientStack.compose` para EXIGIR que el backend
     * del manager sea el mismo motor que el de la pila. Sin este accessor habia
     * que comparar por identidad con algo que el manager no expone, y la
     * comprobacion se degradaria a comparar por una clave deducida —que es como
     * una pila acaba con dos motores sin que nadie lo note.
     */
    fun backend(): TransportBackend = transportBackend

    fun getBindingForPeerContext(localIdentity: IdentityId, peerContext: PeerContext): PeerTransportBinding? =
        bindings[localIdentity to peerContext.remotePeerId]

    fun activeBindings(): List<PeerTransportBinding> = bindings.values.filter { it.isActive }

    fun bindingsForLocalIdentity(localIdentity: IdentityId): List<PeerTransportBinding> =
        bindings.filterKeys { (lid, _) -> lid == localIdentity }.values.toList()

    // ===================================================================
    // SDP
    // ===================================================================

    fun sendOffer(
        localIdentity: IdentityId,
        peerContext: PeerContext,
        offerSdp: String,
        remotePeerId: IdentityId,
    ): TransportResult<PeerTransportBinding> {
        if (remotePeerId != peerContext.remotePeerId) {
            return TransportResult.Failure(
                TransportError.PEER_ID_MISMATCH,
                "remotePeerId $remotePeerId != peerContext.remotePeerId ${peerContext.remotePeerId}"
            )
        }
        val key = localIdentity to peerContext.remotePeerId
        val binding = getActiveBinding(key) ?: return TransportResult.Failure(
            TransportError.BINDING_NOT_FOUND, "no hay binding activo para ($localIdentity, ${peerContext.remotePeerId})"
        )
        val updated = binding.withOffer(offerSdp, now())
        bindings[key] = updated
        transportBackend.onLocalSdp(localIdentity, peerContext.remotePeerId, offerSdp)
        notifyStateChange(peerContext.remotePeerId, updated.state)
        return TransportResult.Success(updated)
    }

    fun receiveAnswer(
        localIdentity: IdentityId,
        remotePeerId: IdentityId,
        answerSdp: String,
    ): TransportResult<PeerTransportBinding> {
        val key = localIdentity to remotePeerId
        val binding = getActiveBinding(key) ?: return TransportResult.Failure(
            TransportError.BINDING_NOT_FOUND, "no hay binding activo para ($localIdentity, $remotePeerId)"
        )
        if (remotePeerId != binding.peerContext.remotePeerId) {
            return TransportResult.Failure(
                TransportError.PEER_ID_MISMATCH,
                "answer from $remotePeerId != binding remotePeerId ${binding.peerContext.remotePeerId}"
            )
        }
        return try {
            val updated = binding.withAnswer(answerSdp, now())
            bindings[key] = updated
            transportBackend.onRemoteSdp(localIdentity, remotePeerId, answerSdp)
            notifyStateChange(remotePeerId, updated.state)
            TransportResult.Success(updated)
        } catch (e: IllegalArgumentException) {
            TransportResult.Failure(
                TransportError.INVALID_STATE_TRANSITION,
                "estado invalido para recibir answer: ${binding.state}"
            )
        }
    }

    // ===================================================================
    // ICE
    // ===================================================================

    fun addIceCandidate(
        localIdentity: IdentityId,
        remotePeerId: IdentityId,
        candidate: String,
    ): TransportResult<PeerTransportBinding> {
        val key = localIdentity to remotePeerId
        val binding = getActiveBinding(key) ?: return TransportResult.Failure(
            TransportError.BINDING_NOT_FOUND, "no hay binding activo para ($localIdentity, $remotePeerId)"
        )
        if (remotePeerId != binding.peerContext.remotePeerId) {
            return TransportResult.Failure(
                TransportError.PEER_ID_MISMATCH,
                "ICE from $remotePeerId != binding remotePeerId ${binding.peerContext.remotePeerId}"
            )
        }
        return try {
            val updated = binding.withIceCandidate(candidate)
            bindings[key] = updated
            transportBackend.onIceCandidate(localIdentity, remotePeerId, candidate)
            TransportResult.Success(updated)
        } catch (e: IllegalArgumentException) {
            TransportResult.Failure(
                TransportError.INVALID_STATE_TRANSITION,
                "estado invalido para recibir ICE: ${binding.state}"
            )
        }
    }

    // ===================================================================
    // DataChannel
    // ===================================================================

    fun markConnected(localIdentity: IdentityId, peerContext: PeerContext): TransportResult<PeerTransportBinding> {
        val key = localIdentity to peerContext.remotePeerId
        val binding = getActiveBinding(key) ?: return TransportResult.Failure(
            TransportError.BINDING_NOT_FOUND, "no hay binding activo"
        )
        val updated = binding.withConnected()
        bindings[key] = updated
        notifyStateChange(peerContext.remotePeerId, PeerTransportState.CONNECTED)
        notifyConnectionState(peerContext.remotePeerId, connected = true)
        return TransportResult.Success(updated)
    }

    /**
     * Envia datos al peer a traves del backend de transporte.
     *
     * ORDEN (3Q.1): el backend envia PRIMERO. Solo si el medio acepto los
     * bytes se contabilizan y se notifica a los handlers.
     *
     * Antes se notificaba antes de enviar, lo que producia dos mentiras:
     * - un envio fallido figuraba como enviado (contadores inflados);
     * - un fallo del medio se reportaba como `Success`, impidiendo que una
     *   cadena de transportes distinguiera "este medio no sirve" de "listo".
     *
     * El resultado del backend se PROPAGA tal cual, incluida su categoria,
     * de modo que el llamante puede consultar [TransportError.permitsFallback].
     */
    fun sendData(localIdentity: IdentityId, peerContext: PeerContext, data: ByteArray): TransportResult<Unit> {
        val key = localIdentity to peerContext.remotePeerId
        val binding = getActiveBinding(key)
            ?: return TransportResult.Failure(TransportError.BINDING_NOT_FOUND, "no hay binding activo")
        if (binding.state != PeerTransportState.CONNECTED) {
            return TransportResult.Failure(
                TransportError.INVALID_STATE_TRANSITION,
                "solo CONNECTED puede enviar datos, estado: ${binding.state}"
            )
        }
        when (val backend = transportBackend.sendData(peerContext.remotePeerId, data)) {
            is TransportResult.Failure -> return backend
            is TransportResult.Success -> Unit
        }
        // Los bytes salieron del nodo: ahora si se contabilizan y se notifican.
        val updated = binding.copy(dataReceived = binding.dataReceived + data.size)
        bindings[key] = updated
        dataHandlers.forEach { it(peerContext.remotePeerId, data) }
        return TransportResult.Success(Unit)
    }

    // ===================================================================
    // Ciclo de vida
    // ===================================================================

    fun closeBinding(localIdentity: IdentityId, remotePeerId: IdentityId): TransportResult<Unit> {
        val key = localIdentity to remotePeerId
        val binding = bindings[key] ?: return TransportResult.Failure(
            TransportError.BINDING_NOT_FOUND, "no hay binding para ($localIdentity, $remotePeerId)"
        )
        bindings[key] = binding.close()
        transportBackend.onCloseBinding(localIdentity, remotePeerId)
        notifyStateChange(remotePeerId, PeerTransportState.CLOSED)
        notifyConnectionState(remotePeerId, connected = false)
        return TransportResult.Success(Unit)
    }

    fun failBinding(localIdentity: IdentityId, remotePeerId: IdentityId, reason: String = ""): TransportResult<Unit> {
        val key = localIdentity to remotePeerId
        val binding = bindings[key] ?: return TransportResult.Failure(
            TransportError.BINDING_NOT_FOUND, "no hay binding para ($localIdentity, $remotePeerId): $reason"
        )
        bindings[key] = binding.fail()
        transportBackend.onCloseBinding(localIdentity, remotePeerId)
        notifyStateChange(remotePeerId, PeerTransportState.FAILED)
        notifyConnectionState(remotePeerId, connected = false)
        return TransportResult.Success(Unit)
    }

    fun invalidateAllForLocalIdentity(localIdentity: IdentityId) {
        bindings.filterKeys { (lid, _) -> lid == localIdentity }.keys.toList().forEach { (lid, pid) ->
            closeBinding(lid, pid)
        }
    }

    fun invalidateAllForPeerId(remotePeerId: IdentityId) {
        bindings.filterKeys { (_, pid) -> pid == remotePeerId }.keys.toList().forEach { (lid, pid) ->
            closeBinding(lid, pid)
        }
    }

    // ===================================================================
    // Handlers
    // ===================================================================

    fun onStateChange(handler: (IdentityId, PeerTransportState) -> Unit) {
        stateChangeHandlers.add(handler)
    }

    /**
     * A4.2 — Suscribe un observador del enlace en booleanos (`true` = hay
     * enlace, `false` = cayo o se cerro). Complementa a [onStateChange]: es la
     * señal que consume [ChainSupervisor] para re-intentar tier-1.
     *
     * @return handle CANCELABLE. Al cancelarlo, la lista de observadores queda
     *   como estaba y el manager se comporta igual que sin el: es lo que exige
     *   que desconectar el cableado de produccion no deje manejadores vivos.
     */
    fun onConnectionStateChange(handler: (IdentityId, Boolean) -> Unit): ChainSubscription {
        connectionStateListeners.add(handler)
        return ChainSubscription { connectionStateListeners.remove(handler) }
    }

    fun onData(handler: (IdentityId, ByteArray) -> Unit) {
        dataHandlers.add(handler)
    }

    fun clear() {
        bindings.clear()
        transportBackend.shutdown()
    }

    // ===================================================================
    // Helpers
    // ===================================================================

    private fun getActiveBinding(key: Pair<IdentityId, IdentityId>): PeerTransportBinding? {
        val binding = bindings[key] ?: return null
        return if (binding.isActive) binding else null
    }

    private fun notifyStateChange(remotePeerId: IdentityId, state: PeerTransportState) {
        stateChangeHandlers.forEach { it(remotePeerId, state) }
    }

    private fun notifyConnectionState(remotePeerId: IdentityId, connected: Boolean) {
        connectionStateListeners.forEach { it(remotePeerId, connected) }
    }
}