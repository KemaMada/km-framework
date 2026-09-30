#!/usr/bin/env python3
"""
M10 — CONSUMIR DOS VECES el pending inbound.

`SymmetricRatchet.previewReceive()` se LEE la clave retenida en lugar de
RETIRARLA. La clave es de un solo uso por construccion, y la retirada ES la
semantica de "usar": sin ella, el mismo frame pendiente se puede abrir dos
veces con el mismo resultado, y la posicion del ratchet no se consume.

PROPIEDAD QUE DEBE ROMPER: atomicidad y semantica de recepcion. Abrir un
frame CONSUME su posicion; un segundo intento tiene que ser un rechazo por
replay, y no lo sera.
"""
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from _mig import main, aplicar

FICHERO = "km-core/src/main/kotlin/com/km/ratchet/SymmetricRatchet.kt"

VIEJO = """        skipped.remove(key)?.let { messageKey ->
            return ReceiveOutcome.FromSkipped(RatchetStep(messageKey, messageNumber))
        }"""

NUEVO = """        // MUTACION M10: la clave retenida se LEE pero no se CONSUME. Es de un
        // solo uso, y la retirada es justamente la semantica de "usar": sin
        // ella el mismo pendiente se abre dos veces.
        skipped[key]?.let { messageKey ->
            return ReceiveOutcome.FromSkipped(RatchetStep(messageKey, messageNumber))
        }"""

main(FICHERO, lambda: aplicar(FICHERO, [(VIEJO, NUEVO, 1)]))
