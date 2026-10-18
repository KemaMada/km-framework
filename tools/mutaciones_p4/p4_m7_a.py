#!/usr/bin/env python3
"""
p4_m7_a.py — M7-a (spec): el motor REESCRITO al moverlo. `onAck` deja de
entregar el `ACK` al `ackHandler`, con el mismo nombre y la misma forma pero otro
comportamiento.

POR QUE ESTA MUTACION
=====================
La tarea 1.5 es un MOVIMIENTO PURO de `km-desktop` a `km-transport`: si al mover
alguien "arregla de paso" la logica, el movimiento deja de ser puro y el motor
nuevo se comporta distinto. La mutacion mas peligrosa de un refactor de
movimiento no es un error que reviente: es un cambio sutil que compila y muda el
comportamiento. Aqui el `ACK` deja de llegar al emisor, que nunca pasa a
`DELIVERED` y `awaitState` expira.

La mutacion COMPILA: `codec.toCoreAckBytes(ack)` se sigue llamando para no dejar
un simbolo muerto, pero su resultado no se entrega.

Debe dejar en rojo:
  - `RelayAckEndToEndTest` (el emisor NO confirma M: se queda sin `DELIVERED`)
"""

import os
import sys

sys.path.insert(0, os.path.join(
    os.path.dirname(os.path.dirname(os.path.abspath(__file__))),
    "mutaciones_p2",
))

from _comun_p2 import main, aplicar  # noqa: E402

F_MOTOR = "km-transport/src/main/kotlin/com/km/transport/JvmRelayTransport.kt"

VIEJO = """        codec.toCoreAckBytes(ack)?.let { ackHandler(it) }
    }"""

NUEVO = """        // MUTACION M7-a: reescritura al mover. El ACK se decodifica pero NO se
        // entrega: el emisor nunca confirma.
        @Suppress("UNUSED_EXPRESSION")
        codec.toCoreAckBytes(ack)
    }"""


def fn():
    aplicar(F_MOTOR, [(VIEJO, NUEVO, 1)])


if __name__ == "__main__":
    main([F_MOTOR], fn)
