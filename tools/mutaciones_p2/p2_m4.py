#!/usr/bin/env python3
"""
M-4 — `PeerContext.forContact` indexa por `subjectDeviceId` en vez de por la raiz.

R-4 exige que la sesion se indexe por el `identityId` de la RAIZ, que es el
valor que el propio bundle declara y verifica. Esta mutacion cambia la clave a
la del DISPOSITIVO, que es el otro `IdentityId` que el documento lleva.

Debe fallar CS-01, CS-04 y CS-05. Medido, no deducido: los tres leen el
`remotePeerId` derivado como CLAVE DE REGISTRO y por eso no lo encuentran cuando
la clave pasa a ser el `subjectDeviceId`.

La version anterior de este docstring afirmaba que CS-05 ("dos raices distintas
daban claves distintas") DEBIA seguir verde porque los dispositivos de Bob y de
Carol tambien son distintos. Eso era verdad de un test que no existia: el CS-05
antiguo comparaba dos `remotePeerId` sueltos y no consultaba el registro, luego
esa asercion era vacuamente cierta bajo esta mutacion —el fallo de un test
falso, no una garantia. CS-05 se reescribio para usar el `SecureSessionRegistry`
real (`find(raiz)`), y ahora cae aqui, en `find(bob.root.identityId) == null`.
Que `registro.size()` siga siendo 2 no lo salva: dos claves de dispositivo
distintas tambien son dos entradas.
"""

import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

from _comun_p2 import F_PEER_CONTEXT, aplicar, main  # noqa: E402

VIEJO = """            val rootIdentityId = KmIds.identityId(bundle.identityRoot)
            return PeerContext(
                remotePeerId = IdentityId(rootIdentityId.toHexString()),"""

NUEVO = """            // MUTACION M-4: la clave pasa a ser la del DISPOSITIVO
            val rootIdentityId = bundle.subjectDeviceId
            return PeerContext(
                remotePeerId = IdentityId(rootIdentityId.toHexString()),"""


def fn():
    aplicar(F_PEER_CONTEXT, [(VIEJO, NUEVO, 1)])


if __name__ == "__main__":
    main([F_PEER_CONTEXT], fn)