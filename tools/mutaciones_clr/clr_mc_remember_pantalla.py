#!/usr/bin/env python3
"""
clr_mc_remember_pantalla.py — M-C: la lista vuelve a congelarse con la clave
de la pantalla.

GARANTIA ATACADA: R5 — "La UI MUST observar el flujo del libro
(collectAsStateWithLifecycle) y mapear a Contact en funcion PURA" (obs #326, D1:
`remember(pantalla) { container.contactBook.all()... }`).

FORMA DEL ATAQUE
================
La UI vuelve a leer el libro con una FOTOGRAFIA memorizada por la pantalla: el
`remember` solo se recalcula al cambiar de pestana, asi que el contacto
auto-registrado por el `contact_exchange` no aparece hasta que el usuario
navega a CONFIG y vuelve. Es el defecto D1 exacto, reintroducido.

La mutacion COMPILA: se cambia el `remember` por el de antes, que es codigo
valido. Debe dejar en rojo `ContactListReactiveGuardTest`, que mira la forma.
"""

import os
import sys

sys.path.insert(0, os.path.join(
    os.path.dirname(os.path.dirname(os.path.abspath(__file__))),
    "mutaciones_p2",
))

from _comun_p2 import main, aplicar  # noqa: E402

F = "app/src/main/java/com/example/keymessage/ui/MainActivity.kt"

VIEJO = """    val contactosDelLibro by container.contactBook.contacts.collectAsStateWithLifecycle()
    val contactos = remember(contactosDelLibro) { mapearContactos(contactosDelLibro) }
"""

NUEVO = """    // M-C: la lista vuelve a congelarse con la clave de la pantalla.
    val contactos = remember(pantalla) {
        container.contactBook.all().map { Contact(id = it.identityRoot.value, name = it.fingerprint) }
    }
"""


def fn():
    aplicar(F, [(VIEJO, NUEVO, 1)])


if __name__ == "__main__":
    main([F], fn)
