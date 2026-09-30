package com.km.node

import com.km.model.IdentityId
import java.util.concurrent.ConcurrentHashMap

/**
 * Envoltura de DATOS opacos reenviados por el relay (3Q.3).
 *
 * El relay reenvia estos bytes sin interpretarlos. El `payload` es un
 * `SecureFrame` cifrado: el relay no tiene la clave de sesion ni de ratchet, y
 * no deberia poder tenerla. Por eso el tipo es deliberadamente opaco y no
 * modela mensajes, conversaciones, estados de entrega ni nada que el relay
 * pudiera interpretar.
 *
 * Lo que el relay SI hace es lo mismo que con la senalizacion: verificar que
 * `from` coincide con la identidad autenticada de la sesion, y rutear a un
 * destinatario que este conectado.
 */
class RelayDataEnvelope(
    val from: IdentityId,
    val to: IdentityId,
    val payload: ByteArray,
    val timestamp: Long,
) {
    /**
     * `equals` por CONTENIDO.
     *
     * Una `data class` con `ByteArray` compararia por identidad de referencia y
     * dos envoltorios con los mismos bytes seriam distintos. Es el mismo
     * problema que `ChainIdentifier` en 3O.2, y aqui silenciaria deduplicacion
     * y comparaciones en los tests.
     */
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is RelayDataEnvelope) return false
        return from == other.from &&
            to == other.to &&
            timestamp == other.timestamp &&
            payload.contentEquals(other.payload)
    }

    override fun hashCode(): Int {
        var result = from.hashCode()
        result = 31 * result + to.hashCode()
        result = 31 * result + timestamp.hashCode()
        result = 31 * result + payload.contentHashCode()
        return result
    }

    override fun toString(): String =
        "RelayDataEnvelope(from=${from.value.take(8)}…, to=${to.value.take(8)}…, payload=${payload.size}B)"
}

/**
 * Transporte REAL por relay (3Q.3).
 *
 * Reenvia bytes opacos entre dos peers autenticados. No negocia criptografia,
 * no ve plaintext y no sabe de ratchets: es un tubo con control de acceso.
 *
 * A diferencia de WebRTC, aqui no hay oferta/answer ni ICE: el relay ya conoce
 * a ambos lados porque los autentico. Esa es su ventaja (funciona tras NAT) y
 * su coste (el relay ve metadatos de trafico y tiempo, aunque no contenido).
 */
class RealRelayTransport(
    override val name: String = "relay",
    private val server: RelayServer,
    private val localIdentity: IdentityId,
    private val now: () -> Long = System::currentTimeMillis,
) : TransportBackend {

    override var eventCallbacks: TransportEventCallbacks = TransportEventCallbacks()

    /** Bytes recibidos del relay, ya verificados por el servidor. */
    private val dataHandlers = ConcurrentHashMap<IdentityId, (ByteArray) -> Unit>()

    override fun initialize(): TransportResult<Unit> {
        val r = server.registerDataHandler(localIdentity) { envelope ->
            dataHandlers[envelope.from]?.invoke(envelope.payload)
        }
        return r.toTransportResult("registro del handler de datos")
    }

    override fun onCreateBinding(localIdentity: IdentityId, remotePeerId: IdentityId): TransportResult<Unit> {
        if (!server.isPeerOnline(remotePeerId)) {
            return TransportResult.Failure(
                TransportError.PEER_NOT_ONLINE_AT_RELAY,
                "${remotePeerId.value.take(8)}… no esta conectado al relay",
            )
        }
        return TransportResult.Success(Unit)
    }

    override fun onLocalSdp(localIdentity: IdentityId, remotePeerId: IdentityId, sdp: String): TransportResult<Unit> =
        // El relay no participa en la negociacion: no hay SDP local que aplicar.
        TransportResult.Success(Unit)

    override fun onRemoteSdp(localIdentity: IdentityId, remotePeerId: IdentityId, sdp: String): TransportResult<Unit> =
        TransportResult.Success(Unit)

    override fun onIceCandidate(localIdentity: IdentityId, remotePeerId: IdentityId, candidate: String): TransportResult<Unit> =
        TransportResult.Success(Unit)

    /**
     * Envia bytes opacos al peer a traves del relay.
     *
     * El relay verifica `from` contra la sesion autenticada; un `from`
     * suplantado se rechaza ALLI, no aqui. Este metodo no necesita volver a
     * comprobar lo que el servidor ya garantiza.
     */
    override fun sendData(remotePeerId: IdentityId, data: ByteArray): TransportResult<Unit> {
        val r = server.deliverData(
            localIdentity,
            RelayDataEnvelope(localIdentity, remotePeerId, data.copyOf(), now()),
        )
        return r.toTransportResult("envio por relay")
    }

    override fun onCloseBinding(localIdentity: IdentityId, remotePeerId: IdentityId): TransportResult<Unit> {
        dataHandlers.remove(remotePeerId)
        return TransportResult.Success(Unit)
    }

    override fun shutdown(): TransportResult<Unit> {
        dataHandlers.clear()
        server.unregisterDataHandler(localIdentity)
        return TransportResult.Success(Unit)
    }

    /** Registra quien recibe los bytes de un peer concreto. */
    fun onDataFrom(remotePeerId: IdentityId, handler: (ByteArray) -> Unit) {
        dataHandlers[remotePeerId] = handler
    }

    /** Identidad local con la que este transporte se autentica ante el relay. */
    fun localIdentityId(): IdentityId = localIdentity

    /**
     * Si el peer indicado esta conectado al relay.
     *
     * Es una CONSULTA, no una afirmacion del transporte. La decision de
     * convertirla en `READY` es de [RealRelayEstablishment], no de aqui: el
     * backend informa del estado del medio y la capa de establecimiento
     * decide que significa.
     */
    fun serverPeerOnline(remotePeerId: IdentityId): Boolean = server.isPeerOnline(remotePeerId)
}

/**
 * Traduce un resultado del relay al vocabulario de transporte.
 *
 * La correspondencia importa: un fallo del relay debe seguir siendo
 * clasificado correctamente para que `permitsFallback` signifique lo mismo en
 * las dos capas.
 */
private fun RelayServerResult<Unit>.toTransportResult(op: String): TransportResult<Unit> = when (this) {
    is RelayServerResult.Success -> TransportResult.Success(Unit)
    is RelayServerResult.Failure -> TransportResult.Failure(
        when (error) {
            // El destinatario no esta: es el MEDIO el que no puede alcanzarlo.
            RelayServerError.PEER_NOT_ONLINE, RelayServerError.SESSION_NOT_FOUND,
            RelayServerError.SESSION_CLOSED -> TransportError.PEER_NOT_ONLINE_AT_RELAY
            // Identidad: nunca justifies cambiar de medio.
            RelayServerError.SPOOFING_DETECTED, RelayServerError.AUTH_FAILED -> TransportError.PEER_ID_MISMATCH
            RelayServerError.INVALID_MESSAGE -> TransportError.INVALID_FRAME_AT_RELAY
            else -> TransportError.BACKEND_ERROR
        },
        "$op: $message",
    )
}

/**
 * Establecimiento REAL por relay (3Q.4 aplicado a un medio real).
 *
 * No hay negotiation que hacer: el relay ya autentico a ambos lados. La
 * condicion `READY` se cumple cuando el destinatario esta conectado al relay.
 * Es el equivalente real de "el DataChannel abrio" en WebRTC: sin el, no hay
 * por donde enviar.
 */
class RealRelayEstablishment(
    private val transport: RealRelayTransport,
    private val remotePeerId: IdentityId,
) : TransportEstablishment {

    override val transportName: String get() = transport.name

    override var state: EstablishmentState = EstablishmentState.CREATED
        private set

    override fun initialize(): TransportResult<Unit> = transport.initialize()

    override fun createBinding(localIdentity: IdentityId, remotePeerId: IdentityId): TransportResult<Unit> =
        transport.onCreateBinding(localIdentity, remotePeerId)

    override fun negotiate(): TransportResult<Unit> {
        // No hay offer/answer: el relay conoce a ambos. Se marca NEGOTIATING
        // para que el estado refleje que aun falta la condicion terminal.
        state = EstablishmentState.NEGOTIATING
        return TransportResult.Success(Unit)
    }

    /**
     * `READY` cuando el peer destino esta en linea. El relay no "dice" que
     * esta conectado: la capa de establecimiento lo COMPRUEBA y decide.
     */
    override fun awaitReady(timeoutMs: Long): TransportResult<Unit> {
        val online = transport.serverPeerOnline(remotePeerId)
        if (online) {
            state = EstablishmentState.READY
            return TransportResult.Success(Unit)
        }
        state = EstablishmentState.FAILED
        return TransportResult.Failure(
            TransportError.PEER_NOT_ONLINE_AT_RELAY,
            "${remotePeerId.value.take(8)}… no esta conectado al relay",
        )
    }

    override fun abort() {
        transport.onCloseBinding(transport.localIdentityId(), remotePeerId)
        state = EstablishmentState.CLOSED
    }
}
