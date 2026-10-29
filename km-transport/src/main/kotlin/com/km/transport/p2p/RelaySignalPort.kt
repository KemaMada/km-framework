package com.km.transport.p2p

import com.km.model.IdentityId
import com.km.node.PeerSignalPort
import com.km.transport.DecodedSignal
import com.km.transport.JvmRelayTransport
import com.km.transport.RelayDataCodec

/**
 * [PeerSignalPort] sobre el motor del relay.
 *
 * ## Que hace y que NO hace
 *
 * Traduce el contrato agnostico del medio (`sendSignal(kind, sdp, candidate)`)
 * a la `SIGNAL` del dialecto, y las senales entrantes del motor al mismo
 * contrato. NO reintenta, NO decide roles y NO conoce SDP: la politica vive en
 * el coordinador, y la codificacion en [RelayDataCodec].
 *
 * ## Por que `sendSignal` devuelve `false` y no lanza
 *
 * El puerto lo declara asi: un fallo de senalizacion es un DATO para el
 * coordinador (fallback a relay), no una excepcion que deba tumbar el
 * establecimiento. El motor devuelve `Result`; aqui se aplana.
 */
class RelaySignalPort(
    private val transport: JvmRelayTransport,
) : PeerSignalPort {

    override fun sendSignal(to: IdentityId, kind: String, sdp: String?, candidate: String?): Boolean =
        transport.sendSignal(
            to = to,
            kind = kind,
            // El portador de contacto no viaja por este puerto: su `kind` es
            // `contact_exchange` y lo emite `RelayClientStack`. Un puerto P2P
            // que pudiera inyectarlo tendria dos duenos del mismo frame.
            contactBundle = null,
            sdp = sdp,
            candidate = candidate,
        ).isSuccess

    /**
     * Reenvia las senales entrantes de SDP/ICE.
     *
     * Los otros `kind` (`contact_exchange`) y los `UnknownKind`/`Invalid` se
     * ignoran aqui: no son de este puerto y el motor ya los registro con su
     * codigo estable.
     */
    override fun onSignal(
        handler: (from: IdentityId, kind: String, sdp: String?, candidate: String?) -> Unit,
    ): () -> Unit {
        // Se envuelve para poder quitar EXACTAMENTE este consumidor: el motor
        // guarda la lambda que se le pasa, y una lambda nueva no seria igual.
        val bridge: (DecodedSignal) -> Unit = { decoded ->
            if (decoded is DecodedSignal.Known) {
                when (decoded.kind) {
                    RelayDataCodec.KIND_SDP_OFFER, RelayDataCodec.KIND_SDP_ANSWER ->
                        handler(decoded.from, decoded.kind, decoded.text, null)
                    RelayDataCodec.KIND_ICE_CANDIDATE ->
                        handler(decoded.from, decoded.kind, null, decoded.text)
                    else -> Unit
                }
            }
        }
        transport.onSignal(bridge)
        return { transport.removeSignalHandler(bridge) }
    }
}
