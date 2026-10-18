#!/usr/bin/env python3
"""
p4_m8_desktop_dup.py — M8-desktop: la copia del motor VUELVE a `km-desktop`.

POR QUE ESTA MUTACION
=====================
CRITICAL #1 del verify de p4: R-8.a (UN solo cliente de wire) estaba FAILING
porque `km-desktop` conservaba `JvmRelayTransport.kt` y `RelayDataCodec.kt`
byte a byte a HEAD y `DesktopMain` usaba la copia LOCAL. El arreglo elimina la
copia y hace que el escritorio consuma `:km-transport` POR ARTEFACTO.

La forma de comprobar que la guardia `DesktopWireEngineReuseTest` tiene dientes
es devolver la copia. No se copian las 1090 lineas del motor: la guardia solo
mira la EXISTENCIA de la clase `com.km.desktop.JvmRelayTransport`, y un
sustituto minimo con ese nombre es exactamente lo que esa existencia significa
en el classpath. El fichero COMPILA (es una clase de un paquete valido).

Debe dejar en rojo:
  - `DesktopWireEngineReuseTest` `R-8a no hay un segundo cliente de wire en
    com-km-desktop`

El fichero se CREA y se BORRA: no existia en el estado que se mide, luego el
"revert" es la ausencia, no un md5. `p4_probe.py --revert` lo elimina.
"""

import os
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

F_COPIA = "km-desktop/src/main/kotlin/com/km/desktop/JvmRelayTransport.kt"

CLASE = """package com.km.desktop

/**
 * MUTACION M8-desktop: segundo cliente de wire en km-desktop.
 *
 * Es un sustituto minimo con el MISMO nombre y paquete que la copia que el
 * CRITICAL #1 elimino. La guardia R-8.a mira la existencia de la clase en el
 * classpath, no sus lineas.
 */
class JvmRelayTransport(val identity: String)
"""


def ruta():
    return os.path.join(ROOT, F_COPIA)


def apply():
    os.makedirs(os.path.dirname(ruta()), exist_ok=True)
    with open(ruta(), "w", encoding="utf-8") as fh:
        fh.write(CLASE)
    print("MUTACION M8-desktop: copia del motor reanadida en km-desktop")


def revert():
    if os.path.exists(ruta()):
        os.remove(ruta())
    print("REVERTIDO M8-desktop: existe=%s" % os.path.exists(ruta()))


if __name__ == "__main__":
    if len(sys.argv) != 2 or sys.argv[1] not in ("apply", "revert"):
        raise SystemExit("uso: %s apply|revert" % sys.argv[0])
    if sys.argv[1] == "apply":
        apply()
    else:
        revert()
