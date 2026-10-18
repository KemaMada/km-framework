#!/usr/bin/env python3
"""
p4_m3_c.py — M3-c (spec): el driver recorre `OFFER_SENT`/`ANSWER_RECEIVED` pero
NUNCA llama a `markConnected`.

POR QUE ESTA MUTACION
=====================
`RelayPeerChannelDriver` existe porque `PeerTransportBinding.withConnected` exige
haber pasado por `OFFER_SENT`/`ANSWER_RECEIVED`/`NEGOTIATING` antes de admitir
`CONNECTED`. La secuencia del driver es `createBinding -> sendOffer ->
receiveAnswer -> markConnected`. Si se quita el `markConnected`, el binding se
queda en `ANSWER_RECEIVED` y `manager.sendData` —que exige `CONNECTED`— devuelve
`INVALID_STATE_TRANSITION`.

La mutacion COMPILA: `conectados` solo deja de actualizarse; la variable
`remotePeerId` se sigue usando en `forPeer`/`intentarConectar`, y el `if` que
observaba el exito del `markConnected` se convierte en un bloque vacio documentado.
No se borra ninguna funcion ni simbolo.

Debe dejar en rojo:
  - PC-01 (presencia-antes-de-binding alcanza CONNECTED)
  - PC-02 (binding-antes-de-presencia tambien)
"""

import os
import sys

sys.path.insert(0, os.path.join(
    os.path.dirname(os.path.dirname(os.path.abspath(__file__))),
    "mutaciones_p2",
))

from _comun_p2 import main, aplicar  # noqa: E402

F_DRIVER = "km-transport/src/main/kotlin/com/km/transport/RelayPeerChannelDriver.kt"

VIEJO = """        if (manager.markConnected(localIdentity, peerContext).isSuccess) {
            conectados.add(remotePeerId)
        }
    }"""

NUEVO = """        // MUTACION M3-c: el ciclo de estados se recorre pero NO se cierra:
        // `markConnected` desaparece y el binding se queda en ANSWER_RECEIVED.
        // `sendData` del manager devolvera INVALID_STATE_TRANSITION.
        manager.receiveAnswer(localIdentity, remotePeerId, SDP_VACIO)
    }"""


def fn():
    aplicar(F_DRIVER, [(VIEJO, NUEVO, 1)])


if __name__ == "__main__":
    main([F_DRIVER], fn)
