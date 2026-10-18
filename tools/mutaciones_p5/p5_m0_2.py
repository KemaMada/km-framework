#!/usr/bin/env python3
"""
p5_m0_2.py — M0.2 (implementador, tarea 0.2): `RelayProbe` cuenta LLAMADAS en
vez de BYTES.

GARANTIA ATACADA: la instrumentacion que sostiene "cero bytes". Si el doble
cuenta llamadas, un `sendData` de cero bytes y uno de doscientos son el mismo
evento, y un "no salio nada" que si salio pasaria.

La mutacion COMPILA: se cambia la expresion de `bytesSalientes`. Debe dejar en
rojo `RelayProbeSelfTest`.
"""

import os
import sys

sys.path.insert(0, os.path.join(
    os.path.dirname(os.path.dirname(os.path.abspath(__file__))),
    "mutaciones_p2",
))

from _comun_p2 import main, aplicar  # noqa: E402

F = "km-desktop/src/test/kotlin/com/km/desktop/support/RelayProbe.kt"

VIEJO = """    val bytesSalientes: Int get() = enviados.sumOf { it.second.size }"""

NUEVO = """    // M0.2: cuenta LLAMADAS, no bytes. Un sendData vacio cuenta igual que uno lleno.
    val bytesSalientes: Int get() = llamadas"""


def fn():
    aplicar(F, [(VIEJO, NUEVO, 1)])


if __name__ == "__main__":
    main([F], fn)
