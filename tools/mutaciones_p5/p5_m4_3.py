#!/usr/bin/env python3
"""
p5_m4_3.py — M4.3 (implementador, tarea 4.3): el default del bootstrap lleva IP
literal incrustada.

GARANTIA ATACADA: "sin IPs incrustadas; bootstrap configurable".
Forma: `BootstrapConfig.DEFAULT` deja de LEER `JvmRelayTransport.DEFAULT_ENDPOINT`
y devuelve una lista con una IP literal. El default del motor es un NOMBRE de host
(no cuenta como defecto); sustituirlo por una IP incrustada si.

La mutacion COMPILA: se cambia el cuerpo de la `val`. Debe dejar en rojo
`NoHardcodedEndpointTest` (las dos guardias: IP literal y URI con host literal).
"""

import os
import sys

sys.path.insert(0, os.path.join(
    os.path.dirname(os.path.dirname(os.path.abspath(__file__))),
    "mutaciones_p2",
))

from _comun_p2 import main, aplicar  # noqa: E402

F = "km-desktop/src/main/kotlin/com/km/desktop/config/BootstrapConfig.kt"

VIEJO = """    val DEFAULT: List<String> get() = listOf(JvmRelayTransport.DEFAULT_ENDPOINT)"""

NUEVO = """    // M4.3: IP literal incrustada en el binario.
    val DEFAULT: List<String> get() = listOf("ws://13.140.155.230:3001/ws")"""


def fn():
    aplicar(F, [(VIEJO, NUEVO, 1)])


if __name__ == "__main__":
    main([F], fn)
