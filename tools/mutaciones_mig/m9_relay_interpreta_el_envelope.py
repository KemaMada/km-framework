#!/usr/bin/env python3
"""
M9 (sitio km-core) — el relay de PRODUCCIÓN interpreta el envelope.

`RealRelayTransport.sendData()` lee la cabecera de 4 B del SecureFrame
(version / tipo / longitud), decide que no es suya y la CONSUME: entrega solo
el cuerpo. Es exactamente lo que hace un relay que interpreta el envelope en
vez de moverlo, y es la forma más barata de que esa interpretación se note: el
sobre que sale ya no es el sobre que entró.

POR QUE ESTE SITIO Y NO `RelayServiceImpl`: `RelayServiceImpl` es la capa de
KM-0004 y este checkpoint no la toca. `RealRelayTransport` es el transporte de
km-core que mete el envelope en el camino del relay, y es el punto más cercano
a la frontera D-4 que se puede mutar sin abrir una capa prohibida.

POR QUE D-4 EN km-webrtc NO TIENE SITIO: allí el relay solo transporta
señalización SDP/ICE, y el envelope de aplicación va por el DataChannel, sin
pasar por el relay. "Hacer que el relay interprete el envelope" no es un defecto
que se pueda introducir ahí: es lo contrario de lo que la arquitectura dice.
"""
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from _mig import main, aplicar

FICHERO = "km-core/src/main/kotlin/com/km/node/RealRelayTransport.kt"

VIEJO = """    override fun sendData(remotePeerId: IdentityId, data: ByteArray): TransportResult<Unit> {
        val r = server.deliverData(
            localIdentity,
            RelayDataEnvelope(localIdentity, remotePeerId, data.copyOf(), now()),
        )
        return r.toTransportResult("envio por relay")
    }"""

NUEVO = """    override fun sendData(remotePeerId: IdentityId, data: ByteArray): TransportResult<Unit> {
        // MUTACION M9: el relay INTERPRETA el envelope. Lee la cabecera de 4 B
        // (version / tipo / longitud), decide que no es suya y la CONSUME, asi
        // que entrega solo el cuerpo. Un relay opaco no lee la cabecera: mueve
        // lo que le dieron, entero y sin mirar.
        val cuerpo = if (data.size > 4) data.copyOfRange(4, data.size) else data
        val r = server.deliverData(
            localIdentity,
            RelayDataEnvelope(localIdentity, remotePeerId, cuerpo, now()),
        )
        return r.toTransportResult("envio por relay")
    }"""

main(FICHERO, lambda: aplicar(FICHERO, [(VIEJO, NUEVO, 1)]))
