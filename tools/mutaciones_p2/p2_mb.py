#!/usr/bin/env python3
"""
M-B — `NodeAuthenticated.authorizesPeerSession()` devuelve `true` siempre.

Ignora el estado VIVO del `AuthSession`. Es la mutacion del lado del NODO: si
fuera cierta, un `AuthSession` en FAILED autorizaria la sesion 1:1 y el caso de
nodo se habria relajado en silencio al cambiar el guard.

Debe fallar MSG-07 (`SecureMessagingSessionTest`) y K1-02 / K6-03
(`PeerTransportBindingTest`), que construyen el contexto con la sesion viva y la
ROMPEN despues: con M-B, el contexto seguiria autorizando.
"""

import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

from _comun_p2 import F_PEER_AUTHORITY, aplicar, main  # noqa: E402

VIEJO = """        override fun authorizesPeerSession(): Boolean =
            authSession.state == AuthSessionState.AUTHENTICATED
"""

NUEVO = """        override fun authorizesPeerSession(): Boolean = true
"""


def fn():
    aplicar(F_PEER_AUTHORITY, [(VIEJO, NUEVO, 1)])


if __name__ == "__main__":
    main([F_PEER_AUTHORITY], fn)