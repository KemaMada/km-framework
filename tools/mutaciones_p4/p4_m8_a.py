#!/usr/bin/env python3
"""
p4_m8_a.py — M8-a (spec): MOTOR DUPLICADO. Un segundo fichero de
`km-transport/src/main` pasa a usar `com.km.crypto` —lo que ocurre si alguien
copia el motor en vez de moverlo—, y el guard `RelayTransportCryptoSeparationTest`
(tarea 5.3) tiene que verlo.

POR QUE ESTA MUTACION
=====================
La separacion cripto/transporte de `:km-transport` solo vale si UN SOLO fichero
—el del AUTH— toca `com.km.crypto`. El guard afirma `soloElAuthUsaCrypto ==
[JvmRelayTransport.kt]`. Si la duplicacion del motor deja un segundo fichero con
`com.km.crypto`, esa afirmacion deja de cumplirse: es exactamente la cobertura
que 5.3 existe para dar. La mutacion la comprueba con dientes.

La mutacion COMPILA: se anade un `import` de un tipo que existe
(`com.km.crypto.Ed25519`). Un `import` sin usar produce warning, no error.

Debe dejar en rojo:
  - `RelayTransportCryptoSeparationTest`:
      * `produccionLimpia` (el fichero no es el de la excepcion y trae crypto)
      * `soloElAuthUsaCrypto` (aparecen DOS ficheros, no uno)
"""

import os
import sys

sys.path.insert(0, os.path.join(
    os.path.dirname(os.path.dirname(os.path.abspath(__file__))),
    "mutaciones_p2",
))

from _comun_p2 import main, aplicar  # noqa: E402

F_CODEC = "km-transport/src/main/kotlin/com/km/transport/RelayDataCodec.kt"

VIEJO = """import com.km.codec.JsonDecoder
import com.km.codec.JsonEncoder"""

NUEVO = """import com.km.codec.JsonDecoder
import com.km.codec.JsonEncoder
// MUTACION M8-a: MOTOR DUPLICADO. Un segundo fichero del transporte toca
// `com.km.crypto`; el guard de 5.3 debe verlo.
import com.km.crypto.Ed25519"""


def fn():
    aplicar(F_CODEC, [(VIEJO, NUEVO, 1)])


if __name__ == "__main__":
    main([F_CODEC], fn)
