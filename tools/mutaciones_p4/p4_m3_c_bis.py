#!/usr/bin/env python3
"""
p4_m3_c_bis.py — M3-c-bis: el conductor deja de comprobar que el par que le
avisa es el que su `PeerContext` declara.

POR QUE ESTA MUTACION Y NO LA DE `p4_m3_c.py`
==============================================
`p4_m3_c.py` quita el `markConnected` (la otra mitad de M3-c: el ciclo se
recorre pero no se cierra). Esta quita la GUARDA DE PAR:

    if (remotePeerId != peerContext.remotePeerId) return

Sin ella, un `PEER_ONLINE` o un aviso de binding de CUALQUIER par —un tercero
presente en el rele, un contacto que ni siquiera es parte de esta sesion—
conduciria el enlace de este par a `CONNECTED`. El conductor esta declarado hacia
un `PeerContext` concreto y esa declaracion es su unica autoridad: aceptarla en
blanco convierte un driver de enlace en algo que enlaza con quien sea.

Es un fallo SILENCIOSO por construccion: el manager no puede notarlo, porque su
`createBinding`/`sendOffer`/`receiveAnswer`/`markConnected` exigen todos que el
`remotePeerId` sea el del `PeerContext`, luego un par equivocado acaba
desembocando en un enlace "correcto" hacia el par DECLARADO pero iniciado por la
presencia de otro. Nada lanza, nada falla: solo se atribuye una presencia ajena.

La mutacion COMPILA: `remotePeerId` sigue usandose en `forPeer`, en las llamadas
al manager y en `conectados.add`, y el `if` desaparece sin dejar ningun simbolo
sin referenciar.

Debe dejar en rojo:
  - PC-05 (la presencia de un par ajeno NO conecta el enlace de otro)
"""

import os
import sys

sys.path.insert(0, os.path.join(
    os.path.dirname(os.path.dirname(os.path.abspath(__file__))),
    "mutaciones_p2",
))

from _comun_p2 import main, aplicar  # noqa: E402

F_DRIVER = "km-transport/src/main/kotlin/com/km/transport/RelayPeerChannelDriver.kt"

VIEJO = """        if (remotePeerId != peerContext.remotePeerId) return
"""

NUEVO = """        // MUTACION M3-c-bis: la guarda de par desaparece. Cualquier presencia
        // o cualquier aviso de binding abre el enlace DECLARADO, aunque venga
        // de un par ajeno.
"""


def fn():
    aplicar(F_DRIVER, [(VIEJO, NUEVO, 1)])


if __name__ == "__main__":
    main([F_DRIVER], fn)
