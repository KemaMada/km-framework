#!/usr/bin/env python3
"""
p5_m0_1.py — M0.1 (implementador, tarea 0.1): borrar `secureSessionRegistry` de
`CoreFactory.create`.

GARANTIA ATACADA: "no fallback a texto plano". Sin el registro, km-core no
construye `SecureOutboundImpl` y el envio sale EN CLARO por el camino legado.

La mutacion COMPILA: se quita una linea de argumento con nombre, no se borra
ningun simbolo. Debe dejar en rojo `SecureSendRejectionTest` (cero bytes por los
DOS caminos, y estado FAILED). Se comenta la linea en vez de borrarla para no
alterar la estructura del `when` de argumentos.
"""

import os
import sys

sys.path.insert(0, os.path.join(
    os.path.dirname(os.path.dirname(os.path.abspath(__file__))),
    "mutaciones_p2",
))

from _comun_p2 import main, aplicar  # noqa: E402

F = "km-desktop/src/main/kotlin/com/km/desktop/core/CoreFactory.kt"

VIEJO = """            secureSessionRegistry = registry,"""

NUEVO = """            // M0.1: el registro NO se inyecta -> SecureOutboundImpl no existe ->
            // el envio degrada al camino legado en claro.
            // secureSessionRegistry = registry,"""


def fn():
    aplicar(F, [(VIEJO, NUEVO, 1)])


if __name__ == "__main__":
    main([F], fn)
