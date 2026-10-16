#!/usr/bin/env python3
"""
M-G4 — borrar el `require` de autoridad del `init` de `PeerTransportBinding`.

Cierra la cobertura DIRECTA de G4 (`PeerTransportBinding.kt:61`), que antes de
p2 no la tenia: `PeerTransportBindingTest` no construia nunca un
`PeerTransportBinding(...)` y `createBinding` filtra antes, luego ese `require`
no se ejecutaba en ninguna corrida de la suite.

Debe fallar K1-07 (construccion directa con una sesion caida) y, al mismo
tiempo, K1-02 y K6-03 deben SEGUIR VERDES: `createBinding` filtra antes y
devuelve `PEER_CONTEXT_NOT_AUTHENTICATED`. Eso es defensa en profundidad, y que
K1-02 no caiga es lo que demuestra que los dos guards son sitios distintos y no
el mismo leer dos veces.
"""

import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

from _comun_p2 import F_BINDING, aplicar, main  # noqa: E402

VIEJO = """    init {
        require(peerContext.authority.authorizesPeerSession()) {
            "la autoridad del PeerContext debe autorizar la sesion de par para crear binding"
        }
    }
"""

NUEVO = """    init {
        // MUTACION M-G4: el invariante de autoridad del binding, suprimido
    }
"""


def fn():
    aplicar(F_BINDING, [(VIEJO, NUEVO, 1)])


if __name__ == "__main__":
    main([F_BINDING], fn)