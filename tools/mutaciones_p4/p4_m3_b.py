#!/usr/bin/env python3
"""
p4_m3_b.py — M3-b (spec): el `from` del despacho de pares se vuelve CONSTANTE
en el motor movido (`JvmRelayTransport.onMessage`).

POR QUE AQUI Y NO EN `RealRelayTransport`
=========================================
M1-b ataca el `from` en el backend de prueba de km-core (un solo camino). M3-b
ataca el MISMO defecto en el MOTOR real —el que corre en produccion por socket—
para demostrar que el motor nuevo tambien lleva el `from` autenticado y que el
test de routing lo pinza. Se pasa `identity` (la identidad LOCAL del motor) en
lugar de `inbound.from`: el compositor de pares recibe el byte etiquetado con
quien lo recibe, y el filtro `from == remotePeerId` de la sesion lo descarta.

La mutacion COMPILA: `identity` es un campo del motor y su tipo es `IdentityId`,
igual que `inbound.from`.

Debe dejar en rojo:
  - R3-13 (receptor `delivered == 1`, y su companion de `from`)
  - LR-02 (el compositor recibe el `from` correcto)
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

NUEVO = """        // MUTACION M3-b: `from` CONSTANTE (la identidad local). El byte
        // llega etiquetado con quien lo recibe, no con quien lo mando.
        compositor(identity, inbound.payload)
    }"""


def fn():
    aplicar(F_MOTOR, [(VIEJO, NUEVO, 1)])


if __name__ == "__main__":
    main([F_MOTOR], fn)
