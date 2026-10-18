#!/usr/bin/env python3
"""
p4_m5_b.py — M5-b (spec): un byte que NO es `SecureFrame` se trata COMO
`SecureFrame`: la clasificacion D-1 se desactiva en el observador global.

POR QUE ESTA MUTACION
=====================
`classifyOnly` distingue por el primer byte (D-1): si no empieza por la version
de `SecureFrame` v1, es un byte de otra ruta y sube
`legacyBytesOnPeerChannel`. Si se trata TODO como frame, un texto legible cuenta
como frame ajeno y `legacyBytesOnPeerChannel` se queda en 0: el camino en claro
deja de ser distinguible de un frame, que es exactamente la confusion que D-1
existe para evitar.

La mutacion COMPILA: `isSecureFrameWire(wire)` se sigue llamando para no quedar
con un simbolo muerto, pero su resultado se ignora a proposito con un
`@Suppress`; la rama que sube `legacyBytesOnPeerChannel` se elimina.

Debe dejar en rojo:
  - MW-06 (`legacyBytesOnPeerChannel == 1` pasa a 0)
"""

import os
import sys

sys.path.insert(0, os.path.join(
    os.path.dirname(os.path.dirname(os.path.abspath(__file__))),
    "mutaciones_p2",
))

from _comun_p2 import main, aplicar  # noqa: E402

F_INBOUND = "km-core/src/main/kotlin/com/km/messaging/SecureInbound.kt"

VIEJO = """    private fun classifyOnly(from: IdentityId, wire: ByteArray) {
        if (!isSecureFrameWire(wire)) {
            legacyBytesOnPeerChannel.incrementAndGet()
            return
        }
        if (registry.find(from) == null) unknownPeerFrames.incrementAndGet()
    }"""

NUEVO = """    private fun classifyOnly(from: IdentityId, wire: ByteArray) {
        // MUTACION M5-b: TODO byte se trata como SecureFrame. Un texto legible
        // deja de contar como byte del canal de par.
        @Suppress("UNUSED_EXPRESSION")
        isSecureFrameWire(wire)
        if (registry.find(from) == null) unknownPeerFrames.incrementAndGet()
    }"""


def fn():
    aplicar(F_INBOUND, [(VIEJO, NUEVO, 1)])


if __name__ == "__main__":
    main([F_INBOUND], fn)
