package com.km.messaging

import com.km.auth.AuthSessionState
import com.km.model.IdentityId
import com.km.node.PeerContext
import com.km.node.PeerTransportManager
import com.km.node.PeerTransportState
import com.km.frame.SecureRatchetProtocol

/**
 * Sesion de mensajeria segura: la tercera y ultima capa.
 *
 * <pre>
 *   plaintext
 *      │
 *      ▼
 *   SecureRatchetProtocol   (ratchet + SecureFrame)     [única que ve plaintext]
 *      │
 *      ▼
 *   PeerTransportManager    (transporta bytes opacos)    [km-core]
 *      │
 *      ▼
 *   P2P / Relay / Tor                               [implementaciones]
 * </pre>
 *
 * IGNORANCIA DEL TRANSPORTE (requisito normativo):
 * Esta clase NO contiene ninguna comprobacion del tipo
 * `if (transport is WebRtcTransport)`. Solo invoca el API de
 * [PeerTransportManager], que es igual para todos los transportes. Por eso
 * puede probarse contra `FakeTransportBackend` sin WebRTC, relay ni red.
 *
 * SEPARACION DE RESPONSABILIDADES:
 * - Decide si es correcto enviar: exige `PeerContext` AUTHENTICATED y binding
 *   CONNECTED. Esa decision es de km-core.
 * - Produce y consume plaintext: eso es de [SecureRatchetProtocol].
 * - No sabe ni le importa de donde vienen los bytes.
 */
class SecureMessagingSession(
    /** Identidad local: la que posee esta sesion. */
    val localIdentity: IdentityId,
    /** Contexto autenticado del par remoto. Fuente UNICA del remotePeerId. */
    val peerContext: PeerContext,
    private val protocol: SecureRatchetProtocol,
    private val transport: PeerTransportManager,
) {
    /** remotePeerId autoritativo, tomado del PeerContext. */
    val remotePeerId: IdentityId get() = peerContext.remotePeerId

    /** Bytes recibidos del transporte que aun no se han descifrado. */
    private val inbox = ArrayDeque<ByteArray>()

    /** Plaintext entregado a la aplicacion. */
    private val outbox = ArrayDeque<ByteArray>()

    private val dataHandlers = mutableListOf<(ByteArray) -> Unit>()

    @Volatile
    private var closed = false

    init {
        // El transporte entrega bytes opacos; la sesion decide si son plaintext.
        //
        // FILTRAR POR PAR NO ES UNA OPTIMIZACION, ES AISLAMIENTO. El manager
        // multiplexa: `sendData` notifica a TODOS los handlers con la identidad
        // del par. Sin el filtro, el frame de B entra en la sesion de A, intenta
        // descifrarlo, y lo que hace el ratchet con un frame que no es suyo ya
        // esta medido en `TransportMigrationTest`: abrir una epoch que no debia
        // y generar una clave de mas. Estado de dos pares mezclado no es "un
        // mensaje perdido": es criptografia corrompida.
        transport.onData { from, data ->
            if (from == remotePeerId) onTransportBytes(data)
        }
    }

    /**
     * Envia un mensaje.
     *
     * El orden es normativo: primero se cifra, y solo despues se entrega al
     * transporte. Si el cifrado falla, no se consume estado del ratchet ni se
     * envia nada.
     */
    fun send(plaintext: ByteArray): SendResult {
        if (closed) return SendResult.Rejected(SendReject.CLOSED)
        // 1. El manager exige PeerContext AUTHENTICATED al abrir el binding.
        if (peerContext.authSession.state != AuthSessionState.AUTHENTICATED) {
            return SendResult.Rejected(SendReject.NOT_AUTHENTICATED)
        }
        // 2. El ratchet produce los bytes. Si falla, no se toca el transporte.
        val wire = try {
            protocol.encrypt(plaintext)
        } catch (e: Exception) {
            return SendResult.Rejected(SendReject.ENCRYPTION_FAILED)
        }
        // 3. Solo ahora se entrega al transporte.
        val result = transport.sendData(localIdentity, peerContext, wire)
        return if (result.isSuccess) SendResult.Ok(wire.size, wire)
        else SendResult.Rejected(SendReject.TRANSPORT_REJECTED)
    }

    /**
     * Reenvia bytes YA CIFRADOS, sin volver a cifrar.
     *
     * Es el unico camino honesto de reintento, y la razon es `MAX_RESEND` /
     * KM-0004: re-cifrar el mismo sobre produce un frame DISTINTO, con otro `N`
     * y otra clave de mensaje. El `messageId` del sobre seguiria siendo el
     * mismo —va DENTRO del plaintext cifrado—, asi que el receptor lo
     * deduplicaria por identidad; pero habria tenido que DESCIFRARLO dos veces
     * y el emisor habria quemado dos mensajes de la cadena del ratchet por un
     * solo reintento. Un reintento que consume estado criptografico no es un
     * reintento: es un mensaje nuevo disfrazado.
     *
     * Lo que NO hace, y es lo que la hace segura:
     *
     * - **no llama a `protocol.encrypt`**, luego no toca el ratchet;
     * - no inventa plaintext ni re-decodifica el sobre;
     * - pasa por las MISMAS guardas que [send] —cerrada, `AUTHENTICATED` y
     *   binding que el manager ya exigio—, con lo que un reintento no puede
     *   abrir una puerta que el envio original no tenia.
     */
    fun sendTranscript(wire: ByteArray): SendResult {
        if (closed) return SendResult.Rejected(SendReject.CLOSED)
        if (peerContext.authSession.state != AuthSessionState.AUTHENTICATED) {
            return SendResult.Rejected(SendReject.NOT_AUTHENTICATED)
        }
        val result = transport.sendData(localIdentity, peerContext, wire)
        return if (result.isSuccess) SendResult.Ok(wire.size, wire)
        else SendResult.Rejected(SendReject.TRANSPORT_REJECTED)
    }

    /**
     * Recibe bytes del transporte y los descifra.
     *
     * Un frame que no descifra NUNCA llega a la aplicacion.
     */
    fun receive(wire: ByteArray): ReceiveResult {
        if (closed) return ReceiveResult.Rejected(ReceiveReject.CLOSED)
        val result = protocol.decrypt(wire)
        return when (result) {
            is SecureRatchetProtocol.DecryptResult.Ok ->
                ReceiveResult.Plaintext(result.plaintext, result.fromSkipped)
            is SecureRatchetProtocol.DecryptResult.Rejected ->
                ReceiveResult.Rejected(ReceiveReject.RATCHET_REJECTED)
            is SecureRatchetProtocol.DecryptResult.Unauthenticated ->
                ReceiveResult.Rejected(ReceiveReject.NOT_AUTHENTICATED_FRAME)
        }
    }

    /** Bytes recibidos del transporte pendientes de descifrar. */
    fun pendingInboundCount(): Int = inbox.size

    /** Plaintext listo para la aplicacion. */
    fun drainOutbound(): List<ByteArray> = outbox.toList().also { outbox.clear() }

    /** Registra un handler de plaintext. */
    fun onPlaintext(handler: (ByteArray) -> Unit) {
        dataHandlers.add(handler)
    }

    /** Estado del binding segun el manager (la autoridad de km-core). */
    fun bindingState(): PeerTransportState? =
        transport.getBinding(localIdentity, remotePeerId)?.state

    /**
     * El gestor de transporte, de solo lectura.
     *
     * Se expone para observabilidad y pruebas (inspeccion del wire, routing
     * en memoria). La sesion no lo modifica ni consulta su tipo: el
     * aislamiento de transporte se verifica en `MSG-11`.
     */
    val transportManager: PeerTransportManager get() = transport

    /** Huella del estado del ratchet, para verificar atomicidad. */
    fun ratchetFingerprint(): ByteArray = protocol.stateFingerprint()

    /** Cierra la sesion. No reinicia el ratchet. */
    fun close() {
        closed = true
        dataHandlers.clear()
        inbox.clear()
        outbox.clear()
    }

    val isClosed: Boolean get() = closed

    // ------------------------------------------------------------------

    /**
     * Entrada real de bytes desde el transporte.
     *
     * Es el unico camino por el que un frame puede convertirse en plaintext
     * para la aplicacion. Un frame que no descifra se descarta aqui.
     */
    fun onTransportBytes(data: ByteArray) {
        if (closed) return
        inbox.addLast(data.copyOf())
        when (val result = receive(data)) {
            is ReceiveResult.Plaintext -> {
                outbox.addLast(result.bytes)
                dataHandlers.forEach { it(result.bytes) }
            }
            is ReceiveResult.Rejected -> Unit
        }
    }
}

/** Resultado de [SecureMessagingSession.send]. */
sealed class SendResult {
    /**
     * Los bytes SALIERON, y aqui estan.
     *
     * [wire] es exactamente el `ByteArray` que se entrego al transporte, no una
     * re-codificacion: es lo que hace posible [SecureMessagingSession.sendTranscript]
     * sin volver a cifrar. [bytes] sigue siendo el tamano, porque es el dato que
     * el resto del framework ya consumia y no tiene por que cambiar.
     *
     * `equals` compara el contenido del wire y no su identidad, para que dos
     * envios del mismo contenido sean el mismo resultado. Sin eso, comparar dos
     * `Ok` mediria el allocator.
     */
    data class Ok(val bytes: Int, val wire: ByteArray = ByteArray(0)) : SendResult() {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is Ok) return false
            return bytes == other.bytes && wire.contentEquals(other.wire)
        }

        override fun hashCode(): Int = 31 * bytes + wire.contentHashCode()
    }

    data class Rejected(val reason: SendReject) : SendResult()
}

/** Motivos de rechazo al enviar. */
enum class SendReject {
    NOT_AUTHENTICATED,
    ENCRYPTION_FAILED,
    TRANSPORT_REJECTED,
    CLOSED,
}

/** Resultado de recibir. */
sealed class ReceiveResult {
    /** Texto plano autentico. */
    class Plaintext(val bytes: ByteArray, val fromSkipped: Boolean) : ReceiveResult() {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is Plaintext) return false
            return bytes.contentEquals(other.bytes) && fromSkipped == other.fromSkipped
        }

        override fun hashCode(): Int = 31 * bytes.contentHashCode() + fromSkipped.hashCode()
    }

    /** El frame fue rechazado y NO se entrega a la aplicacion. */
    data class Rejected(val reason: ReceiveReject) : ReceiveResult()
}

/** Motivos de rechazo al recibir. */
enum class ReceiveReject {
    /** El ratchet rechazo el frame (replay, salto, epoch). */
    RATCHET_REJECTED,

    /** El frame no autentico: header o ciphertext alterados. */
    NOT_AUTHENTICATED_FRAME,

    CLOSED,
}
