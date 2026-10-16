#!/usr/bin/env python3
"""
HUMO del arnes de `p2-contact-sessions` (tarea 0.1).

Mutacion TRIVIAL pero REAL: en `SecureMessagingSession.send`, el rechazo por
falta de autoridad pasa a notificarse como `CLOSED` en vez de
`NOT_AUTHENTICATED`.

Por que tiene que ser COMPORTAMENTAL y no un cambio de comentario: el criterio
de aceptacion de 0.1 es que `mutprobe.py --label <x>` sin `--mutate` de
`NO DETECTADA` y con `--mutate` de `DETECTADA` con XML nuevo. Un cambio de
comentario daria `NO DETECTADA` en los dos casos y la aceptacion no probaria
nada: mediria que el arnes corre, no que mide. Aqui lo detectado es MSG-07,
que es justo el guard que 2.1 va a cambiar.

El otro requisito de 0.1 —"aplica y revierte un cambio trivial y deja el
fichero byte a byte igual"— lo sostiene `_comun_p2.revertir`, que restaura
desde la copia de `/tmp/opencode/p2-backups` y valida el md5 resultante.
"""

import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

from _comun_p2 import FICHERO_HUMO, aplicar, main  # noqa: E402

VIEJO = """            return SendResult.Rejected(SendReject.NOT_AUTHENTICATED)
        }
        // 2. El ratchet produce los bytes. Si falla, no se toca el transporte.
"""

NUEVO = """            return SendResult.Rejected(SendReject.CLOSED)
        }
        // 2. El ratchet produce los bytes. Si falla, no se toca el transporte.
"""


def fn():
    aplicar(FICHERO_HUMO, [(VIEJO, NUEVO, 1)])


if __name__ == "__main__":
    main([FICHERO_HUMO], fn)