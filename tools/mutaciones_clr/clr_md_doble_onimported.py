#!/usr/bin/env python3
"""
clr_md_doble_onimported.py — M-D: el evento "importado" se dispara DOS veces.

GARANTIA ATACADA: R7 — "MUST quedar EXACTAMENTE UNO [dueno] y unico EN EL
TIEMPO... logcat UNA linea [SIGNAL] entregado por par (hoy >1)" (obs #326, D2).

FORMA DEL ATAQUE
================
El `ImportContactViewModel` llama a `onImported` DOS veces por importacion. Con
el dueno unico, eso es exactamente lo que relanzaba el intercambio de contactos
con el contacto anterior: dos entregas de `[SIGNAL]` por par.

La mutacion COMPILA: se duplica una llamada, no se borra ningun simbolo. Debe
dejar en rojo `ImportContactViewModelTest`, en concreto el caso del import
EXITOSO, que afirma EXACTAMENTE una vez.

Esta mutacion es la que demuestra que ese test NO es vacio: sin ella, "una vez"
seria una afirmacion sin dientes.
"""

import os
import sys

sys.path.insert(0, os.path.join(
    os.path.dirname(os.path.dirname(os.path.abspath(__file__))),
    "mutaciones_p2",
))

from _comun_p2 import main, aplicar  # noqa: E402

F = "app/src/main/java/com/example/keymessage/ui/ConversationViewModel.kt"

VIEJO = """                onSuccess = { contact ->
                    val navResult = runCatching { onImported(contact) }
"""

NUEVO = """                onSuccess = { contact ->
                    val navResult = runCatching { onImported(contact) }
                    // M-D: el evento se dispara DOS veces: dos entregas de
                    // [SIGNAL] por par, que es el defecto D2.
                    runCatching { onImported(contact) }
"""


def fn():
    aplicar(F, [(VIEJO, NUEVO, 1)])


if __name__ == "__main__":
    main([F], fn)
