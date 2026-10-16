#!/usr/bin/env python3
"""
M-3 — borrar `require(announcement.nodeId == remotePeerId)` de
`NodeAuthenticated.of`.

R-3: la autoridad de nodo debe seguir exigiendo que el anuncio sea DEL PAR. Sin
este `require`, un anuncio firmado por Charlie vale para un `remotePeerId` de
Bob, y el contexto pasa a afirmar algo que nadie ha verificado.

Debe fallar:
  - `PeerAuthorityTest` PA-02;
  - `IntegrationAudit3ITest` F2-02, que ya comprobaba esa coherencia a traves de
    `PeerContext.authenticated`.
"""

import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

from _comun_p2 import F_PEER_AUTHORITY, aplicar, main  # noqa: E402

VIEJO = """                require(announcement.identity.nodeId == remotePeerId) {
                    "announcement.nodeId (${announcement.identity.nodeId}) debe coincidir con remotePeerId ($remotePeerId)"
                }
"""

NUEVO = """                // MUTACION M-3: coherencia anuncio/remotePeerId suprimida
"""


def fn():
    aplicar(F_PEER_AUTHORITY, [(VIEJO, NUEVO, 1)])


if __name__ == "__main__":
    main([F_PEER_AUTHORITY], fn)