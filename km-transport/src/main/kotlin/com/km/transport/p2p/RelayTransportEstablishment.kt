package com.km.transport.p2p

import com.km.model.IdentityId
import com.km.node.EstablishmentState
import com.km.node.Medium
import com.km.node.TransportBackend
import com.km.node.TransportError
import com.km.node.TransportEstablishment
import com.km.node.TransportResult

/**
 * El relay como ULTIMO eslabon de una [com.km.node.TransportChain].
 *
 * ## Que significa "establecer" para el relay
 *
 * El relay no negocia: no hay SDP, ni ICE, ni DTLS. Su establecimiento es que
 * el par este PRESENTE y que el binding del motor acepte el par. Por eso
 * [negotiate] no hace nada y [awaitReady] vuelve a preguntar presencia: es el
 * unico hecho que puede convertir "el medio existe" en "el medio entrega".
 *
 * ## Desviacion consciente del design
 *
 * El design describe este paso como `sendOffer("")`/`receiveAnswer("")`, que
 * son metodos de `PeerTransportManager` y exigen un `PeerContext`. La cadena
 * corre el establecimiento FUERA del manager, y pedirle un `PeerContext` aqui
 * obligaria a que el coordinador inventara autoridad. Se usa el contrato de
 * [TransportBackend] —que es lo unico que la cadena conoce— y se declara.
 *
 * ## Presencia
 *
 * [present] se inyecta para poder probar los dos desenlaces sin socket. En
 * produccion es `JvmRelayTransport::isPeerPresent`.
 */
class RelayTransportEstablishment(
    private val backend: TransportBackend,
    private val present: (IdentityId) -> Boolean = { true },
) : TransportEstablishment {

    override val transportName: String = Medium.RELAY.linkName

    override var state: EstablishmentState = EstablishmentState.CREATED
        private set

    private var bound = false
    private var local: IdentityId? = null
    private var remote: IdentityId? = null

    override fun initialize(): TransportResult<Unit> {
        val r = backend.initialize()
        state = if (r.isSuccess) EstablishmentState.CREATED else EstablishmentState.FAILED
        return r
    }

    override fun createBinding(localIdentity: IdentityId, remotePeerId: IdentityId): TransportResult<Unit> {
        local = localIdentity
        remote = remotePeerId
        if (!present(remotePeerId)) {
            state = EstablishmentState.FAILED
            return TransportResult.Failure(
                TransportError.PEER_NOT_ONLINE_AT_RELAY,
                "${remotePeerId.value.take(8)}… no esta presente en el rele",
            )
        }
        val r = backend.onCreateBinding(localIdentity, remotePeerId)
        state = if (r.isSuccess) EstablishmentState.NEGOTIATING else EstablishmentState.FAILED
        bound = r.isSuccess
        return r
    }

    /** El relay no negocia: el exito es seguir en curso, nunca `READY`. */
    override fun negotiate(): TransportResult<Unit> {
        if (state != EstablishmentState.NEGOTIATING) {
            state = EstablishmentState.FAILED
            return TransportResult.Failure(
                TransportError.INVALID_STATE_TRANSITION,
                "negotiate sin binding: ${state}",
            )
        }
        return TransportResult.Success(Unit)
    }

    override fun awaitReady(timeoutMs: Long): TransportResult<Unit> {
        if (!bound) {
            state = EstablishmentState.FAILED
            return TransportResult.Failure(TransportError.BINDING_NOT_FOUND, "sin binding de relay")
        }
        // La presencia es la condicion terminal del relay. `timeoutMs` se
        // ignora porque la consulta es LOCAL: el motor mantiene la lista de
        // presentes con los PEER_ONLINE del rele; esperar aqui no la cambiaria.
        val par = remote
        if (par != null && !present(par)) {
            state = EstablishmentState.FAILED
            return TransportResult.Failure(
                TransportError.PEER_NOT_ONLINE_AT_RELAY,
                "${par.value.take(8)}… dejo de estar presente",
            )
        }
        state = EstablishmentState.READY
        return TransportResult.Success(Unit)
    }

    override fun abort() {
        val l = local
        val r = remote
        if (bound && l != null && r != null) {
            // `abort` vive en el camino de error: un fallo de limpieza no puede
            // propagarse ni dejar a medias a la cadena.
            runCatching { backend.onCloseBinding(l, r) }
        }
        state = EstablishmentState.CLOSED
        bound = false
    }

    /** Solo para tests: el par que se capturo en `createBinding`. */
    internal fun boundPeer(): IdentityId? = remote
}
