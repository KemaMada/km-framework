#!/usr/bin/env python3
"""
p4_m5_a.py — M5-a (spec): ENTREGA SIN SESION. Un `SecureFrame` que llega de un
par SIN sesion establecida se cuenta como ENTREGADO en vez de como
`unknownPeerFrames`.

POR QUE ESTA MUTACION
=====================
`SecureInbound.classifyOnly` existe para que "me llego un frame cifrado de
alguien con quien no he establecido nada" sea un HECHO CONTABLE. Si en vez de
contar el frame ajeno se cuenta como entregado, la aplicacion cree que recibio
un mensaje que en realidad nadie pudo descifrar: es la falsa sensacion de
entrega, el mismo defecto que
`LegacyInboundObservation` cierra por el lado en claro.

La mutacion COMPILA: se intercambian las dos operaciones sobre los dos
`AtomicInteger`, que ya estan declarados.

Debe dejar en rojo:
  - MW-05 (`delivered == 0` pasa a 1, y `unknownPeerFrames == 1` pasa a 0)
"""

import os
import sys

sys.path.insert(0, os.path.join(
    os.path.dirname(os.path.dirname(os.path.abspath(__file__))),
    "mutaciones_p2",
))

from _comun_p2 import main, aplicar  # noqa: E402

F_INBOUND = "km-core/src/main/kotlin/com/km/messaging/SecureInbound.kt"

VIEJO = """        if (registry.find(from) == null) unknownPeerFrames.incrementAndGet()
    }"""

NUEVO = """        // MUTACION M5-a: ENTREGA SIN SESION. El frame de un par sin sesion se
        // cuenta como entregado en lugar de como desconocido.
        if (registry.find(from) == null) delivered.incrementAndGet()
    }"""


def fn():
    aplicar(F_INBOUND, [(VIEJO, NUEVO, 1)])


if __name__ == "__main__":
    main([F_INBOUND], fn)
