#!/usr/bin/env python3
"""
p4_m1_b.py — M1-b (spec): el codec PIERDE el `from` al despachar y todo byte
remoto entra como si viniera de la identidad LOCAL.

POR QUE ASI Y NO "cambiar el codec"
===================================
`RealRelayTransport.initialize()` despacha
`eventCallbacks.onDataReceived?.invoke(envelope.from, envelope.payload)`. El
`from` es la identidad AUTENTICADA del emisor en el rele, y es el unico dato con
el que el receptor puede decidir si el byte es de su par. Si se pasa la
identidad LOCAL en su lugar, el filtro `from == remotePeerId` de
`SecureMessagingSession` descarta TODO frame remoto y el receptor nunca descifra
nada. El emisor, en cambio, recibe su propio eco como si fuera del par.

La mutacion COMPILA: `localIdentity` ya es un campo del constructor, asi que no
hace falta inventar un simbolo.

Debe dejar en rojo:
  - R3-13 (receptor `delivered == 1`) y, con el, E2E-01
  - LR-02, si el `from` que llega al compositor de pares deja de ser el emisor
    (aqui se ataca el despacho de `RealRelayTransport`; LR-02 cubre el motor ya
    movido, y por eso tambien queda como prediccion, no como garantia)
"""

import os
import sys

sys.path.insert(0, os.path.join(
    os.path.dirname(os.path.dirname(os.path.abspath(__file__))),
    "mutaciones_p2",
))

from _comun_p2 import main, aplicar  # noqa: E402

F_REAL = "km-core/src/main/kotlin/com/km/node/RealRelayTransport.kt"

VIEJO = """            eventCallbacks.onDataReceived?.invoke(envelope.from, envelope.payload)
            dataHandlers[envelope.from]?.invoke(envelope.payload)"""

NUEVO = """            // MUTACION M1-b: el `from` autenticado se pierde y se pasa el
            // LOCAL. El receptor filtrara `from == remotePeerId` y descartara
            // todo; el emisor se ve su propio byte como si fuera del par.
            eventCallbacks.onDataReceived?.invoke(localIdentity, envelope.payload)
            dataHandlers[envelope.from]?.invoke(envelope.payload)"""


def fn():
    aplicar(F_REAL, [(VIEJO, NUEVO, 1)])


if __name__ == "__main__":
    main([F_REAL], fn)
