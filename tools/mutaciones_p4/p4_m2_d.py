#!/usr/bin/env python3
"""
p4_m2_d.py — M2-d (spec): la CLASIFICACION del `onMessage` del motor se
desactiva: todo va al camino legado (`alCaminoLegado`), nunca a la cara de par.

POR QUE ESTA MUTACION
=====================
`onMessage` hace UNA pregunta —"puedo leer esta carga como mi `Message`?"— y de
ella salen DOS caminos: legado (compositor de frames o `messageHandler`) y par
(`onDataReceived`). Si se fuerza la rama legada siempre, un `SecureFrame` que
llega al motor se envuelve como `Message` de km-core y se entrega por el camino
en claro: el receptor nunca descifra, `delivered == 0`. Es el defecto de D-B: la
doble fachada colapsada en una sola.

La mutacion COMPILA: se sustituye el cuerpo de `onMessage` por la llamada al
camino legado, que ya existe y tiene la firma correcta. No se borra la funcion.

Debe dejar en rojo:
  - LR-02 (SecureFrame con compositor -> `onDataReceived`, no `messageHandler`)
  - E2E-01 (el frame cifrado por socket real ya no se entrega en B)
"""

import os
import sys

sys.path.insert(0, os.path.join(
    os.path.dirname(os.path.dirname(os.path.abspath(__file__))),
    "mutaciones_p2",
))

from _comun_p2 import main, aplicar  # noqa: E402

F_MOTOR = "km-transport/src/main/kotlin/com/km/transport/JvmRelayTransport.kt"

VIEJO = """        val comoLegado = codec.applicationMessageId(inbound.payload) != null
        if (comoLegado) {"""

NUEVO = """        // MUTACION M2-d: clasificacion DESACTIVADA. Todo se trata como legado,
        // con lo que un SecureFrame acaba entregado por el camino en claro.
        val comoLegado = true
        if (comoLegado) {"""


def fn():
    aplicar(F_MOTOR, [(VIEJO, NUEVO, 1)])


if __name__ == "__main__":
    main([F_MOTOR], fn)
