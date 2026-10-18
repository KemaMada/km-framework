#!/usr/bin/env python3
"""
p4_m2_a.py — M2-a (spec R-2): el cable de recepcion del manager DESAPARECE.

Se elimina la composicion de `eventCallbacks` en el `init` de
`PeerTransportManager`. Como el resto del `init` se queda, el fichero COMPILA:
lo que se quita es una llamada, no un simbolo. Un simbolo inexistente impediria
compilar y el test quedaria falseado, que es el modo de fallo que ya se ha
sufrido en este proyecto.

Debe dejar en rojo:
  - MW-01 (llegada, descifrado, `delivered == 1`)
  - MW-05 (`delivered == 0` y `unknownPeerFrames == 1`: sin cable no hay ni una
    cosa ni la otra)
  - MW-06 (`legacyBytesOnPeerChannel == 1`)
Y MW-04 (eco local) debe SEGUIR VERDE: el eco viene de `sendData`, no del cable.
"""

import os
import sys

sys.path.insert(0, os.path.join(
    os.path.dirname(os.path.dirname(os.path.abspath(__file__))),
    "mutaciones_p2",
))

from _comun_p2 import main, aplicar  # noqa: E402

F_MANAGER = "km-core/src/main/kotlin/com/km/node/PeerTransportManager.kt"

VIEJO = """    init {
        cablearRecepcion()
    }"""

NUEVO = """    // MUTACION M2-a: el cable de recepcion no se instala.
    init {
        // cablearRecepcion() desactivado a proposito
    }"""


def fn():
    aplicar(F_MANAGER, [(VIEJO, NUEVO, 1)])


if __name__ == "__main__":
    main([F_MANAGER], fn)