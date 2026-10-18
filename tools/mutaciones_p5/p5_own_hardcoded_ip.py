#!/usr/bin/env python3
"""
p5_own_hardcoded_ip.py — ATAQUE PROPIO DEL VERIFICADOR (no esta en la tabla del
implementador).

GARANTIA ATACADA: "sin IPs incrustadas; bootstrap configurable y multiple".

FORMA DEL ATAQUE, DISTINTA DE M4.3
==================================
El implementador (M4.3) incrusto una IP en el DEFAULT de configuracion
(`BootstrapConfig.DEFAULT` / el default del motor). Aqui se ataca OTRO mecanismo:
un fallback de ARRANQUE en `DesktopOptions.parse`: si no llega `--relay`, en vez
de leer del entorno/default del motor, el parser inventa un rele con IP literal.

Es un defecto distinto —una constante de arranque, no un default de config— y
prueba que la guardia `NoHardcodedEndpointTest` no depende de QUE fichero ni de
QUE forma tenga la IP: mira el POOL DE CONSTANTES de TODO el paquete de
produccion, y una IP que llega al binario es una IP.

La mutacion COMPILA: se anade una rama con una constante String. No se renombra
ni se borra ningun simbolo (eso dejaria la prueba FALSEADA por error de
compilacion, el modo de fallo ya sufrido en este proyecto).

Debe dejar en rojo:
  - 4.3 cero IPs literales en el escritorio  (NoHardcodedEndpointTest)
  - 4.3 cero URIs de red con HOST LITERAL en el escritorio (el mismo fichero)
"""

import os
import sys

sys.path.insert(0, os.path.join(
    os.path.dirname(os.path.dirname(os.path.abspath(__file__))),
    "mutaciones_p2",
))

from _comun_p2 import main, aplicar  # noqa: E402

F_MAIN = "km-desktop/src/main/kotlin/com/km/desktop/DesktopMain.kt"

VIEJO = """            val bootstraps = BootstrapConfig.resolve(cli = bootstrapsDeLinea)
            val ordered = if (relayDeLinea != null) listOf(relayDeLinea) + bootstraps else bootstraps"""

NUEVO = """            // ATAQUE PROPIO DEL VERIFICADOR: fallback de arranque con IP literal.
            // El destino queda incrustado en el binario y no se puede cambiar sin
            // recompilar, que es exactamente lo que la guardia prohibe.
            val relayConIp = relayDeLinea ?: "ws://203.0.113.77:3001/ws"
            val bootstraps = BootstrapConfig.resolve(cli = bootstrapsDeLinea)
            val ordered = listOf(relayConIp) + bootstraps"""


def fn():
    aplicar(F_MAIN, [(VIEJO, NUEVO, 1)])


if __name__ == "__main__":
    main([F_MAIN], fn)
