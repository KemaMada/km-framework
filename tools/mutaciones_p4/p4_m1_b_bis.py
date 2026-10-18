#!/usr/bin/env python3
"""
p4_m1_b_bis.py — M1-b-bis: el motor movido a km-transport pierde el `from`
AUTENTICADO por el rele al despachar un byte opaco a la cara de par.

POR QUE OTRA MUTACION Y NO REUSAR `p4_m1_b.py`
===============================================
`p4_m1_b.py` ataca `km-core/src/main/.../RealRelayTransport.kt`, que es el doble
de PROCESO: sigue siendo el camino de km-core, pero NO es el motor que habla por
socket. El motor movido a `km-transport` tiene SU PROPIO despacho en
`JvmRelayTransport.onMessage`, y ese es el que usan `RB-01` y `E2E-01`. Sin esta
mutacion, "el `from` se pierde" solo estaria medido en un camino que el E2E no
usa.

LA MUTACION
============
    compositor(inbound.from, inbound.payload)
    compositor(identity,        inbound.payload)     <-- MUTACION

`identity` es el campo del constructor del motor, asi que no hay que inventar
ningun simbolo y la mutacion compila. El receptor recibe el byte con la
identidad LOCAL en vez de con el emisor, y ahi lo para el filtro de
`SecureMessagingSession` (`from == remotePeerId`) y el de `SecureInbound`.

Debe dejar en rojo:
  - E2E-01 (el receptor deja de entregar: `delivered == 0`,
    `unknownPeerFrames == 1` en vez de `delivered == 1`)
  - RB-02, si el compositor recibe la identidad local
"""

import os
import sys

sys.path.insert(0, os.path.join(
    os.path.dirname(os.path.dirname(os.path.abspath(__file__))),
    "mutaciones_p2",
))

from _comun_p2 import main, aplicar  # noqa: E402

F_MOTOR = "km-transport/src/main/kotlin/com/km/transport/JvmRelayTransport.kt"

VIEJO = """        compositor(inbound.from, inbound.payload)
    }"""

NUEVO = """        // MUTACION M1-b-bis: el `from` AUTENTICADO por el rele se pierde y se
        // entrega la identidad LOCAL. Todo par remoto queda filtrado por
        // `from == remotePeerId` y el receptor no descifra nada.
        compositor(identity, inbound.payload)
    }"""


def fn():
    aplicar(F_MOTOR, [(VIEJO, NUEVO, 1)])


if __name__ == "__main__":
    main([F_MOTOR], fn)
