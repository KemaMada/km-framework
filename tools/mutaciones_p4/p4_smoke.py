#!/usr/bin/env python3
"""
HUMO del arnes de `p4-relay-backend` (tarea 0.1).

QUE MIDE
========
Que el arnes corre y MIDE: sin `--mutate` da `NO DETECTADA`, y con `--mutate`
da `DETECTADA` con XML NUEVO. Un cambio de comentario daria `NO DETECTADA` en los
dos casos y la aceptacion de 0.1 no probaria nada: probaria que Gradle arranca,
no que el arnes detecta.

La mutacion es COMPORTAMENTAL y del resumen del motor: `FakeTransportBackend.
sendData` pasa a devolver `Failure(BACKEND_NOT_READY)`, que es exactamente la
forma que toma la cara `TransportBackend` cuando el medio no esta listo. Como
`FakeTransportBackend` es el backend POR DEFECTO de `PeerTransportManager`, todo
camino que envie por el se rompe al instante.

QUE NO USA
==========
Git, ni para revertir ni para validar: se apoya en `_comun_p2` (sin tocarlo)
con `KM_P2_BACKUP_DIR=/tmp/opencode/p4-backups`, porque el manifiesto vive
DENTRO de ese directorio y asi no colisiona con el de la campana de p2.

Uso:

    export KM_P2_BACKUP_DIR=/tmp/opencode/p4-backups
    tools/mutprobe.py --label p4_humo_sin --pattern 'com.km.messaging.SecureMessagingSessionTest'
    tools/mutprobe.py --label p4_humo_con --pattern 'com.km.messaging.SecureMessagingSessionTest' \
        --mutate tools/mutaciones_p4/p4_smoke.py --revert tools/mutaciones_p4/p4_smoke.py
    tools/mutaciones_p2/_comun_p2.py --verify km-core/src/main/kotlin/com/km/node/FakeTransportBackend.kt
"""

import os
import sys

sys.path.insert(0, os.path.join(
    os.path.dirname(os.path.dirname(os.path.abspath(__file__))),
    "mutaciones_p2",
))

from _comun_p2 import main, aplicar  # noqa: E402

F_FAKE = "km-core/src/main/kotlin/com/km/node/FakeTransportBackend.kt"

VIEJO = """    override fun sendData(remotePeerId: IdentityId, data: ByteArray): TransportResult<Unit> =
        TransportResult.Success(Unit)
"""

NUEVO = """    override fun sendData(remotePeerId: IdentityId, data: ByteArray): TransportResult<Unit> =
        TransportResult.Failure(TransportError.BACKEND_NOT_READY, "MUTACION DE HUMO: el fake ya no esta listo")
"""


def fn():
    aplicar(F_FAKE, [(VIEJO, NUEVO, 1)])


if __name__ == "__main__":
    main([F_FAKE], fn)