#!/usr/bin/env python3
"""
p4_m7_b.py — M7-b (spec): BYPASS MANUAL CON EL CABLE VIVO. En
`ManagedWebRtcPeer.wireTransportEvents` se duplica el despacho del byte: el
cable del manager (`previo.onDataReceived`) se invoca DOS veces, que es lo que
ocurria cuando cada test ponia su propio puente
`onTransportData -> session.onTransportBytes` ADEMAS del cable del manager (R-7.b).

POR QUE ESTA MUTACION
=====================
Con el cable del manager vivo, anadir un segundo puente manual entrega el mismo
byte dos veces a la sesion. El primero descifra; el segundo es un REPLAY que el
ratchet rechaza, y ese rechazo es ruido (`rejectedFrames`) que contamina la
cuenta. Es el defecto que la tarea 4.1 retiro de `SecureMessagingOverWebRtcTest`
al dejar de ser necesario.

La mutacion COMPILA: es una llamada duplicada a un lambda ya existente.

Debe dejar en rojo:
  - `SecureMessagingOverWebRtcTest` (doble entrega: el segundo drenaje no
    encuentra plaintext y sube `rejectedFrames`, o el conteo de entrega varia)
"""

import os
import sys

sys.path.insert(0, os.path.join(
    os.path.dirname(os.path.dirname(os.path.abspath(__file__))),
    "mutaciones_p2",
))

from _comun_p2 import main, aplicar  # noqa: E402

F_PEER = "km-webrtc/src/main/kotlin/com/km/webrtc/ManagedWebRtcPeer.kt"

VIEJO = """                previo.onDataReceived?.invoke(fromPeerId, data)
                // Solo datos del peer autenticado. Un canal de otro par"""

NUEVO = """                previo.onDataReceived?.invoke(fromPeerId, data)
                // MUTACION M7-b: BYPASS MANUAL CON EL CABLE VIVO. El mismo byte
                // vuelve a pasar por el cable del manager: doble entrega.
                previo.onDataReceived?.invoke(fromPeerId, data)
                // Solo datos del peer autenticado. Un canal de otro par"""


def fn():
    aplicar(F_PEER, [(VIEJO, NUEVO, 1)])


if __name__ == "__main__":
    main([F_PEER], fn)
