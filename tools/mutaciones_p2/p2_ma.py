#!/usr/bin/env python3
"""
M-A — `ContactVerified.authorizesPeerSession()` devuelve `false`.

Es la mutacion del lado del CONTACTO, y la direccion contraria a M-B. Con ella,
un contacto deja de poder abrir ni usar su sesion 1:1.

Debe fallar `ContactSessionE2ETest` entero: CS-01 (la autoridad abre sesion),
CS-02 (el contacto envia y el otro extremo descifra) y CS-03 (`sendTranscript`
pasa el mismo guard). Que fallen las TRES es lo que demuestra que el camino de
contacto atraviesa ESE guard y que el test no mira el codigo fuente.
"""

import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

from _comun_p2 import F_PEER_AUTHORITY, aplicar, main  # noqa: E402

VIEJO = """        override fun authorizesPeerSession(): Boolean = true
"""

NUEVO = """        override fun authorizesPeerSession(): Boolean = false
"""


def fn():
    aplicar(F_PEER_AUTHORITY, [(VIEJO, NUEVO, 1)])


if __name__ == "__main__":
    main([F_PEER_AUTHORITY], fn)