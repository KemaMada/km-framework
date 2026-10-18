#!/usr/bin/env python3
"""
p4_m5_c.py — M5-c (spec): el RECHAZO/CLASIFICACION no se cuenta: los dos
contadores del observador global (`unknownPeerFrames` y
`legacyBytesOnPeerChannel`) se dejan de incrementar.

POR QUE ESTA MUTACION
=====================
Los dos contadores son la UNICA forma de que "llego algo que no es mio" o "llego
un byte que no es un frame" dejen de ser un SILENCIO. Son el espejo de `delivered`:
sin ellos, un frame ajeno y un frame que nunca llego son indistinguibles. En vez
de reventar, el sistema simplemente no dice nada: el fallo mas caro de todos
porque se lee como "todo bien".

La mutacion COMPILA: se sustituye el cuerpo de `classifyOnly` por codigo que
mantiene las referencias vivas y no incrementa nada.

Debe dejar en rojo:
  - MW-05 (`unknownPeerFrames == 1` pasa a 0)
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
        // MUTACION M5-c: los contadores de clasificacion NO se mueven. Un frame
        // ajeno y un byte no-frame pasan en silencio.
        @Suppress("UNUSED_EXPRESSION")
        isSecureFrameWire(wire)
        @Suppress("UNUSED_EXPRESSION")
        registry.find(from)
    }"""


def fn():
    aplicar(F_INBOUND, [(VIEJO, NUEVO, 1)])


if __name__ == "__main__":
    main([F_INBOUND], fn)
