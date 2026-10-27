#!/usr/bin/env python3
"""
p6_m1_retencion_sin_tope.py — M1: la retencion de PREKEYs se queda SIN TOPE.

GARANTIA ATACADA: R8 — "Un PREKEY de par desconocido MUST retenerse con TTL y
tope acotados".

FORMA DEL ATAQUE
================
Se anula la EXPULSION por tope: el `while` que saca al mas antiguo cuando el
mapa se llena deja de ejecutarse. La retencion sigue teniendo TTL (el TTL lo
mide otro test), pero el numero de emisores retenidos no tiene limite: un par
desconocido puede hacer crecer el mapa sin limite, que es exactamente la
superficie de memoria direccionable por un tercero que el tope existia para
cerrar.

La mutacion COMPILA: se cambia la CONDICION del `while`, no se borra ningun
simbolo ni se cambia la estructura. Debe dejar en rojo
`ContactExchangeCoordinatorTest`, en concreto el test del tope de 16.
"""

import os
import sys

sys.path.insert(0, os.path.join(
    os.path.dirname(os.path.dirname(os.path.abspath(__file__))),
    "mutaciones_p2",
))

from _comun_p2 import main, aplicar  # noqa: E402

F = "app/src/main/java/com/example/keymessage/contact/ContactExchangeCoordinator.kt"

VIEJO = """            while (retenidos.size > maxRetenidos) {"""

NUEVO = """            // M1: la retencion se queda SIN TOPE. `size` nunca llega a
            // Int.MAX_VALUE, asi que la expulsion no ocurre nunca.
            while (retenidos.size > Int.MAX_VALUE) {"""


def fn():
    aplicar(F, [(VIEJO, NUEVO, 1)])


if __name__ == "__main__":
    main([F], fn)
