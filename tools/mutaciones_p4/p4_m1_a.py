#!/usr/bin/env python3
"""
p4_m1_a.py — M1-a (spec): la cara `TransportBackend.sendData` del motor se
convierte en un NO-OP que devuelve exito.

POR QUE ESTA MUTACION Y NO OTRA
===============================
`sendData` es el unico punto por el que el manager cree que un byte sale hacia
la red. Si devuelve `Success` sin escribir nada, el manager anota el eco local
(el `dataHandlers` de `sendData:338` SI se dispara) y todo el camino de salida
"parece" funcionar sin que nada haya llegado al rele. Es el fallo silencioso
exacto que la doble fachada debe impedir: exito sin entrega.

La mutacion COMPILA: se sustituye el cuerpo por una devolucion de exito. No se
borra el metodo ni su firma —borrarlo daria un error de compilacion y el test
quedaria falseado, que es el modo de fallo ya sufrido en este proyecto—.

Debe dejar en rojo:
  - RB-01 (`sendData` escribe un frame MESSAGE con los bytes intactos)
  - E2E-01 (el E2E por socket real deja de entregar en B)
"""

import os
import sys

sys.path.insert(0, os.path.join(
    os.path.dirname(os.path.dirname(os.path.abspath(__file__))),
    "mutaciones_p2",
))

from _comun_p2 import main, aplicar  # noqa: E402

F_MOTOR = "km-transport/src/main/kotlin/com/km/transport/JvmRelayTransport.kt"

VIEJO = """        return try {
            send(data, remotePeerId)
            TransportResult.Success(Unit)
        } catch (e: Exception) {
            TransportResult.Failure(TransportError.BACKEND_SEND_FAILED, e.message ?: e::class.java.simpleName)
        }
    }"""

NUEVO = """        // MUTACION M1-a: exito sin escribir nada. El eco local del manager
        // sigue disparandose (`sendData` del manager notifica a sus handlers),
        // de modo que el fallo es SILENCIOSO hacia arriba.
        return TransportResult.Success(Unit)
    }"""


def fn():
    aplicar(F_MOTOR, [(VIEJO, NUEVO, 1)])


if __name__ == "__main__":
    main([F_MOTOR], fn)
