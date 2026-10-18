#!/usr/bin/env python3
"""
p4_m2_b.py — M2-b (spec R-2) = LA MUTACION ANTI-CLOBBER, la M6 del orquestador.

La spec dice, literalmente: "asignar `eventCallbacks = TransportEventCallbacks()`
fresco en vez de componer con `.copy()`". Eso es lo que hace aqui.

## POR QUE SE ESCRIBIO DOS VECES, Y QUE ENSENO EL PRIMER INTENTO

La primera version de este script hacia
`TransportEventCallbacks(onDataReceived = { ... })`: asignacion fresca PERO con
el cable puesto. Resultado medido: **NO DETECTADA**, 0 fallos, exit 0.

El motivo es que la composicion viva dentro del propio lambda (`previo.
onDataReceived?.invoke(...)` se evalua cuando llega el byte), de modo que poner
un `onDataReceived` nuevo que ademas invoca al previo produce EXACTAMENTE el
mismo comportamiento observable. La mutacion no era una mutacion: era el mismo
codigo con mas ruido.

Eso no es un detalle de este arnés: es el motivo por el que la mutacion tiene
que escribirse contra la SEMANTICA que se quiere atacar —"reasignar borra lo que
habia"— y no contra la forma que uno se_imagina que tiene el error. Una
mutacion que no cambia nada es la forma mas cara de no medir nada.

## POR QUE ESTA ES LA MUTACION MAS IMPORTANTE DE LA FASE

Si pasara, un consumidor dejaria de recibir lo que envia **sin excepcion y sin
log**: el byte se perderia en el silencio de un `var` reasignado. Y el
`previo` se deja LEIDO para que no se confunda con M2-a (desinstalar el cable):
aqui el codigo SI se conecta, se conecta a nada.

Debe dejar en rojo:
  - MW-01 (el cable propio queda anulado -> 0 entregas)
  - MW-02 (el slot previo registrado ANTES del manager deja de recibir el byte)
"""

import os
import sys

sys.path.insert(0, os.path.join(
    os.path.dirname(os.path.dirname(os.path.abspath(__file__))),
    "mutaciones_p2",
))

from _comun_p2 import main, aplicar  # noqa: E402

F_MANAGER = "km-core/src/main/kotlin/com/km/node/PeerTransportManager.kt"

VIEJO = """        val previo = transportBackend.eventCallbacks
        transportBackend.eventCallbacks = previo.copy(
            onDataReceived = { remotePeerId, data ->
                // Primero el que ya estaba: componer es una GARANTIA, no un
                // reemplazo. Si el lambda previo es `null` —el caso normal—
                // esta llamada no hace nada y no hay que preguntarse por ello.
                previo.onDataReceived?.invoke(remotePeerId, data)
                // Despues los handlers, con el MISMO contrato que el eco de
                // [sendData]: `(remotePeerId, data)`, en orden de registro. El
                // orden importa: la sesion se suscribio en su `init` y el
                // puente al instalarse, y el puente necesita ver el plaintext
                // que la sesion acaba de producir.
                dataHandlers.forEach { it(remotePeerId, data) }
            },
        )"""

NUEVO = """        // MUTACION M2-b: asignacion fresca en vez de composicion. Borra el
        // cable propio Y todo lo que hubiera puesto otro antes.
        @Suppress("UNUSED_VARIABLE")
        val previo = transportBackend.eventCallbacks
        transportBackend.eventCallbacks = TransportEventCallbacks()"""


def fn():
    aplicar(F_MANAGER, [(VIEJO, NUEVO, 1)])


if __name__ == "__main__":
    main([F_MANAGER], fn)