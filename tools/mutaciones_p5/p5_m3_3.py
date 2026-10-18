#!/usr/bin/env python3
"""
p5_m3_3.py — M3.3 (implementador, tarea 3.3): el envio degrada al camino legado.

GARANTIA ATACADA: "no fallback a texto plano" en el SEND.
Forma: se inyecta un registro NULO explicito en `CoreFactory`, de modo que el
nucleo no construye `SecureOutboundImpl` (misma condicion que M0.1) pero MEDIDA
por otro test: `DesktopSendCiphertextTest`, que exige FAILED + cero bytes y, con
la mutacion, ve SENT y el texto saliendo en claro.

Se escribe `secureSessionRegistry = null` en vez de borrar la linea: mantiene la
estructura y evita cualquier riesgo de que el compilador infiera un tipo distinto.
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

NUEVO = """            secureSessionRegistry = null,"""


def fn():
    aplicar(F, [(VIEJO, NUEVO, 1)])


if __name__ == "__main__":
    main([F], fn)
