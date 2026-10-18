#!/usr/bin/env python3
"""
p4_own_b2_tx_a.py — ATAQUE PROPIO al lado TRANSPORTE del cierre de B2.

El `onMessage` del motor entrega el frame opaco al compositor de pares con
`compositor(inbound.from, inbound.payload)`. El `from` es la identidad
AUTENTICADA por el rele, y es el unico dato con el que el receptor decide si el
byte es de su par. Si el motor lo sustituye por su PROPIA identidad local, el
filtro `from == remotePeerId` de `SecureMessagingSession` descarta TODO frame
remoto y el receptor nunca descifra nada, en silencio.

POR QUE ESTA MUTACION Y NO OTRA
===============================
El verify de p4 dejo declarado que el routing del transporte no tenia NINGUN
test: esta mutacion dejaba la suite km-transport en verde. Ahora
`RelayBackendFaceTest` (RB-01/RB-02) y `LegacyAndPeerChannelRoutingTest` (LR-02)
afirman el `from` con el emisor, y esta mutacion las debe poner en rojo.

La mutacion COMPILA: `identity` es un parametro del constructor del motor.

Debe dejar en rojo:
  - `RelayBackendFaceTest` RB-01 y RB-02 (`from == aliceId` pasa a `bobId`)
  - `LegacyAndPeerChannelRoutingTest` LR-02
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

NUEVO = """        // MUTACION OWN-B2-TX-A: el `from` autenticado se pierde y se entrega la
        // identidad LOCAL del motor. El receptor filtrara `from == remotePeerId`
        // y descartara todo; el emisor se ve su propio byte como si fuera del par.
        compositor(identity, inbound.payload)
    }"""


def fn():
    aplicar(F_MOTOR, [(VIEJO, NUEVO, 1)])


if __name__ == "__main__":
    main([F_MOTOR], fn)
