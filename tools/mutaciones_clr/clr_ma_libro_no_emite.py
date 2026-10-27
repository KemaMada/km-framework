#!/usr/bin/env python3
"""
clr_ma_libro_no_emite.py — M-A: el libro de contactos NO EMITE.

GARANTIA ATACADA: R1/R2 — "ContactBook MUST exponer flujo HOT (StateFlow) que
emita el estado PRESENTE al suscribirse" y "toda mutacion emite".

FORMA DEL ATAQUE
================
El estado del libro deja de ser la fuente de verdad de la pantalla: `all()` y
`find()` siguen funcionando (el mapa de verdad esta aparte), pero el FLUJO
`contacts` no se actualiza NUNCA. Es la forma exacta del defecto D1 (obs #326):
la lista de la pantalla se queda congelada porque NADIE le notifica, y el
contacto auto-registrado no aparece hasta que el usuario navega.

La mutacion COMPILA: se cambia DONDE se guarda el estado, no se borra ningun
simbolo. Debe dejar en rojo `InMemoryContactBookTest`: los casos que afirman
que el flujo trae el estado presente, que cada mutacion emite y que la ultima
emision es la union de las raices.
"""

import os
import sys

sys.path.insert(0, os.path.join(
    os.path.dirname(os.path.dirname(os.path.abspath(__file__))),
    "mutaciones_p2",
))

from _comun_p2 import main, aplicar  # noqa: E402

F = "app/src/main/java/com/example/keymessage/contact/ContactBook.kt"

# 1. El estado del flujo deja de ser la fuente de verdad: `all()` y `find()`
#    leen un mapa aparte, y el flujo se queda congelado para siempre.
VIEJO_ESTADO = """    private val _contacts = MutableStateFlow<List<VerifiedContact>>(emptyList())

    override val contacts: StateFlow<List<VerifiedContact>> = _contacts.asStateFlow()

    override fun all(): List<VerifiedContact> = _contacts.value

    override fun find(identityRoot: IdentityId): VerifiedContact? =
        _contacts.value.firstOrNull { it.identityRoot == identityRoot }
"""

NUEVO_ESTADO = """    // M-A: el libro NO EMITE. `all()` y `find()` siguen funcionando (el mapa
    // de verdad esta aparte), pero el FLUJO no se actualiza NUNCA: la pantalla
    // se queda con la foto que se tomo al suscribirse.
    private val porRaiz = LinkedHashMap<IdentityId, VerifiedContact>()

    private val _contacts = MutableStateFlow<List<VerifiedContact>>(emptyList())

    override val contacts: StateFlow<List<VerifiedContact>> = _contacts.asStateFlow()

    override fun all(): List<VerifiedContact> = porRaiz.values.toList()

    override fun find(identityRoot: IdentityId): VerifiedContact? = porRaiz[identityRoot]
"""

# 2. `upsert` escribe en el mapa, no en el flujo.
VIEJO_UPSERT = """        _contacts.update { actual ->
            val i = actual.indexOfFirst { it.identityRoot == verificado.identityRoot }
            // Reemplazar conserva la POSICION (el contacto no se va al final);
            // un contacto NUEVO se anade al final, que es el orden de
            // importacion que `all()` promete.
            if (i >= 0) actual.toMutableList().apply { this[i] = guardado } else actual + guardado
        }
"""

NUEVO_UPSERT = """        // M-A: la escritura va al mapa, y el flujo NO se toca.
        porRaiz[verificado.identityRoot] = guardado
"""

# 3. `remove` tambien.
VIEJO_REMOVE = """        _contacts.update { actual -> actual.filterNot { it.identityRoot == identityRoot } }
"""

NUEVO_REMOVE = """        // M-A: el borrado va al mapa, y el flujo NO se toca.
        porRaiz.remove(identityRoot)
"""


def fn():
    aplicar(F, [
        (VIEJO_ESTADO, NUEVO_ESTADO, 1),
        (VIEJO_UPSERT, NUEVO_UPSERT, 1),
        (VIEJO_REMOVE, NUEVO_REMOVE, 1),
    ])


if __name__ == "__main__":
    main([F], fn)
