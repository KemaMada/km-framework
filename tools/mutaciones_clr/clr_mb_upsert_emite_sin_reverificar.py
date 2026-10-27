#!/usr/bin/env python3
"""
clr_mb_upsert_emite_sin_reverificar.py — M-B: el libro EMITE aunque el sobre
NO verifique.

GARANTIA ATACADA: R3 — "Un upsert que NO verifica MUST lanzar como hoy (codigo
via rejectionCodeOf), NO emitir y NO alterar all()".

FORMA DEL ATAQUE
================
El registro deja de REVERIFICAR lo que le llega: guarda el objeto tal cual. Con
ello, un contacto con el sobre ROTO entra en el libro y EMITE, y la pantalla
pinta un contacto que nadie ha verificado. Es la forma que hace inutilizable la
unica puerta de verificacion que tiene el registro.

La mutacion COMPILA: se cambia la fuente de `verificado`, no se borra ningun
simbolo ni se cambia la estructura. Debe dejar en rojo
`InMemoryContactBookTest`, en concreto el caso del sobre roto.
"""

import os
import sys

sys.path.insert(0, os.path.join(
    os.path.dirname(os.path.dirname(os.path.abspath(__file__))),
    "mutaciones_p2",
))

from _comun_p2 import main, aplicar  # noqa: E402

F = "app/src/main/java/com/example/keymessage/contact/ContactBook.kt"

VIEJO = """        val verificado = ContactImporter.import(contact.bundleText)
        val guardado = contact.copy(
            identityRoot = verificado.identityRoot,
            subjectDeviceId = verificado.subjectDeviceId,
            subjectSigningKey = verificado.subjectSigningKey,
        )
"""

NUEVO = """        // M-B: se guarda SIN reverificar. Un sobre roto entra y EMITE, y el
        // registro deja de ser la puerta unica de verificacion.
        val verificado = contact
        val guardado = contact
"""


def fn():
    aplicar(F, [(VIEJO, NUEVO, 1)])


if __name__ == "__main__":
    main([F], fn)
