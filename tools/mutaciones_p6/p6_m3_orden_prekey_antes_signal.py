#!/usr/bin/env python3
"""
p6_m3_orden_prekey_antes_signal.py — M3: el PREKEY sale ANTES del SIGNAL.

GARANTIA ATACADA: R4 — "Tras un escaneo valido, el emisor MUST enviar su bundle
por `contact_exchange` ANTES del PREKEY".

FORMA DEL ATAQUE
================
Se invierte el orden de la secuencia del emisor: la sesion se abre y el PREKEY
se entrega ANTES de que el portador propio salga por `SIGNAL/contact_exchange`.

Por que esto es un fallo REAL y no un detalle de estilo: el frame PREKEY no
transporta el bundle del emisor (cabecera fija de 44 B) y `km-core` exige el
bundle verificado del emisor para aceptarlo (KM-0006 §7.3). Con este orden, el
receptor recibe un PREKEY de un par que todavia no conoce, no lo puede aceptar
y el establecimiento se queda colgado. Es el fallo que el cambio arregla.

La mutacion COMPILA: se mueve la llamada, no se borra ningun simbolo. Debe
dejar en rojo `ContactBootstrapTest`, en concreto el test del orden y el de
"si el intercambio NO se entrega NO se abre sesion".
"""

import os
import sys

sys.path.insert(0, os.path.join(
    os.path.dirname(os.path.dirname(os.path.abspath(__file__))),
    "mutaciones_p2",
))

from _comun_p2 import main, aplicar  # noqa: E402

F = "app/src/main/java/com/example/keymessage/contact/ContactBootstrap.kt"

VIEJO = """        val veredicto = enviarPortador(portador)"""

NUEVO = """        // M3: la sesion (y su PREKEY) se abren ANTES de publicar el bundle.
        val previo = abrirSesionYEntregar()
        val veredicto = enviarPortador(portador)"""


VIEJO2 = """        return abrirSesionYEntregar()
    }"""

NUEVO2 = """        return previo
    }"""


def fn():
    aplicar(F, [(VIEJO, NUEVO, 1), (VIEJO2, NUEVO2, 1)])


if __name__ == "__main__":
    main([F], fn)
