#!/usr/bin/env python3
"""
M-6 — borrar `require(authSession.state == AUTHENTICATED)` de
`NodeAuthenticated.of`.

R-3: la autoridad de nodo exige el handshake COMPLETO. Sin el `require`, un
`AuthSession` en IDLE vale exactamente igual que uno autenticado, y el contexto
afirma una autenticacion que no ha ocurrido.

Debe fallar:
  - `PeerAuthorityTest` PA-01;
  - `IntegrationAudit3ITest` F2-01 y `EndToEndRuntimeTest` L1-03, que ya lo
    comprobaban a traves de `PeerContext.authenticated`.
"""

import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

from _comun_p2 import F_PEER_AUTHORITY, aplicar, main  # noqa: E402

VIEJO = """                require(authSession.state == AuthSessionState.AUTHENTICATED) {
                    "PeerContext solo se puede crear desde una sesion AUTHENTICATED (actual: ${authSession.state})"
                }
"""

NUEVO = """                // MUTACION M-6: exigencia de estado vivo suprimida
"""


def fn():
    aplicar(F_PEER_AUTHORITY, [(VIEJO, NUEVO, 1)])


if __name__ == "__main__":
    main([F_PEER_AUTHORITY], fn)