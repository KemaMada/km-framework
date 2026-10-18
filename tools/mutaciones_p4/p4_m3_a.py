#!/usr/bin/env python3
"""
p4_m3_a.py — M3-a (spec): el filtro por par de `SecureMessagingSession` se
elimina y CUALQUIER byte entrante entra en la sesion.

POR QUE ESTA MUTACION
=====================
`SecureMessagingSession.kt:70` hace `if (from == remotePeerId) onTransportBytes(data)`.
Sin ese filtro, el frame de OTRO par entra en esta sesion e intenta descifrarse;
lo que hace el ratchet con un frame ajeno ya esta medido en
`TransportMigrationTest`: abre una epoch que no debia y genera una clave de mas.
"Estado de dos pares mezclado no es un mensaje perdido: es criptografia
corrompida" (KDoc del propio fichero).

La mutacion COMPILA: se quita la condicion, la llamada queda. `from` sigue
usandose? No: Kotlin avisa de parametro no usado solo si es un nombre nuevo; aqui
`from` es la lambda del `onData`, y se deja el parametro con `_` implicito no
existe: se conserva `from` en la firma para no romper el tipo del lambda. Como
`onData` espera `(IdentityId, ByteArray) -> Unit`, dejar `from` sin usar produce
un warning, no un error: COMPILA.

Debe dejar en rojo:
  - MW-05 (el frame de A NO lo descifra la sesion de B -> `delivered == 0`,
    `unknownPeerFrames == 1`): sin filtro, la sesion de B intentaria usar el frame
    de A y el contador `unknownPeerFrames` no subiria igual.
"""

import os
import sys

sys.path.insert(0, os.path.join(
    os.path.dirname(os.path.dirname(os.path.abspath(__file__))),
    "mutaciones_p2",
))

from _comun_p2 import main, aplicar  # noqa: E402

F_SESION = "km-core/src/main/kotlin/com/km/messaging/SecureMessagingSession.kt"

VIEJO = """        transport.onData { from, data ->
            if (from == remotePeerId) onTransportBytes(data)
        }"""

NUEVO = """        // MUTACION M3-a: sin filtro por par. Cualquier byte entrante intenta
        // descifrarse contra esta sesion, mezclando estado de dos pares.
        @Suppress("UNUSED_PARAMETER")
        transport.onData { from, data ->
            onTransportBytes(data)
        }"""


def fn():
    aplicar(F_SESION, [(VIEJO, NUEVO, 1)])


if __name__ == "__main__":
    main([F_SESION], fn)
