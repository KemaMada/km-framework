#!/usr/bin/env python3
"""
p6_m2_verificacion_saltada.py — M2: la verificacion de la RAIZ se SALTA.

GARANTIA ATACADA: R6 — "Un `contact_exchange` de par desconocido MUST
auto-registrar el contacto por la MISMA verificacion del escaneo manual y MUST
rechazarse si la raiz != `from`".

FORMA DEL ATAQUE
================
La comparacion entre la raiz del bundle y la identidad del emisor deja de
DISPARAR. El bundle sigue verificando (la firma cuadra), pero se acepta de
QUIEN SEA: un par que publica el bundle de otro se queda con su contacto
registrado, y el victimario aparece en la lista de un tercero.

No se borra el `if`: se deja su cuerpo, pero la condicion nunca es cierta. Asi
el diff muestra EXACTAMENTE lo que se ha anulado, y el rechazo con su codigo
estable (`BUNDLE_ROOT_MISMATCH`) sigue ahi, esperando a que alguien lo vuelva
a connecting.

La mutacion COMPILA: `if (false)` es codigo valido en Kotlin (avisa, no
prohíbe). Debe dejar en rojo `InboundContactRegistrarTest`, en concreto el
test del codigo `BUNDLE_ROOT_MISMATCH` y el de "un bundle de otro par NO se
registra bajo el emisor".
"""

import os
import sys

sys.path.insert(0, os.path.join(
    os.path.dirname(os.path.dirname(os.path.abspath(__file__))),
    "mutaciones_p2",
))

from _comun_p2 import main, aplicar  # noqa: E402

F = "app/src/main/java/com/example/keymessage/contact/InboundContactRegistrar.kt"

VIEJO = """        if (contact.identityRoot.value != from.value) {"""

NUEVO = """        // M2: la verificacion de la RAIZ se SALTA: la condicion nunca es
        // cierta, asi que el bundle se acepta de quien sea.
        if (false) {"""


def fn():
    aplicar(F, [(VIEJO, NUEVO, 1)])


if __name__ == "__main__":
    main([F], fn)
