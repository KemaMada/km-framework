#!/usr/bin/env python3
"""
p4_m5_d.py — M5-d (spec): el rele SE REAPUNTA AL EMISOR. El fan-out de
`MessageRouter.deliverData` entrega el `MESSAGE` a `envelope.from` en vez de a
`envelope.to`.

POR QUE ESTA MUTACION, Y POR QUE AQUI Y NO EN UN TEST
=====================================================
LE-01 pinza que el cliente NO recibe lo que envia. Lo que se pinzaba antes era
un doble de test; la tarea 4.3 exige que se pinze con el RELE REAL y sin tocar
`MessageRouter` para el eco. La forma de comprobar que la asercion tiene dientes
es mutar la RUTA REAL —`deliverData` entrega a `envelope.to`, linea 338— y ver
que LE-01 se pone rojo. La mutacion es TEMPORAL: el arnes la revierte por md5.

`MessageRouter.kt` no esta en los respaldos de p4 todavia; `aplicar` lo respalda
la primera vez que corre esta mutacion (`asegurar`), asi que la copia existe
antes de tocar nada y el revert es verificable.

La mutacion COMPILA: `envelope.from` es del mismo tipo que `envelope.to`.

Debe dejar en rojo:
  - LE-01 (el emisor recibe su propio mensaje: `deliveredToAlice == 0` pasa a 1)
"""

import os
import sys

sys.path.insert(0, os.path.join(
    os.path.dirname(os.path.dirname(os.path.abspath(__file__))),
    "mutaciones_p2",
))

from _comun_p2 import main, aplicar  # noqa: E402

F_ROUTER = "km-daemon/src/main/kotlin/com/km/daemon/server/MessageRouter.kt"

VIEJO = """        val wire = codec.message(envelope.from, envelope.to, envelope.payload)
        withConnection(envelope.to) { it.send(wire) }"""

NUEVO = """        // MUTACION M5-d: el rele se reapunta al EMISOR. El cliente recibe su
        // propio mensaje ("hub", no fan-out).
        val wire = codec.message(envelope.from, envelope.to, envelope.payload)
        withConnection(envelope.from) { it.send(wire) }"""


def fn():
    aplicar(F_ROUTER, [(VIEJO, NUEVO, 1)])


if __name__ == "__main__":
    main([F_ROUTER], fn)
