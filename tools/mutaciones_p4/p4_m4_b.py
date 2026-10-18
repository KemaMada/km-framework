#!/usr/bin/env python3
"""
p4_m4_b.py — M4-b (spec): `SecureFrameSpec.HEADER_LENGTH` pasa de 44 a 43.

POR QUE ESTA MUTACION
=====================
`O5-18 el header tiene 44 bytes` afirma `assertEquals(44, SecureFrameSpec.HEADER_LENGTH)`.
Es la guardia del LAYOUT congelado: el checkpoint v1 mide 44 bytes y un cambio a
43 no es un numero, es un wire format distinto que ningun receptor v1 leeria.

La mutacion COMPILA: es un literal. No se toca ni el codec ni el protector; se
ataca la CONSTANTE que el test pinza, que es justo lo que la mutacion pretende
demostrar que esta cubierto.

Debe dejar en rojo:
  - O5-18 `el header tiene 44 bytes`
"""

import os
import sys

sys.path.insert(0, os.path.join(
    os.path.dirname(os.path.dirname(os.path.abspath(__file__))),
    "mutaciones_p2",
))

from _comun_p2 import main, aplicar  # noqa: E402

F_SPEC = "km-core/src/main/kotlin/com/km/frame/SecureFrame.kt"

VIEJO = "    const val HEADER_LENGTH: Int = 44"
NUEVO = "    // MUTACION M4-b: el header cambia de tamano.\n    const val HEADER_LENGTH: Int = 43"


def fn():
    aplicar(F_SPEC, [(VIEJO, NUEVO, 1)])


if __name__ == "__main__":
    main([F_SPEC], fn)
