#!/usr/bin/env python3
"""
p4_m6_spec.py — M6(spec): `km-core` gana una dependencia de RED
(`io.ktor:ktor-client-cio`) y se mide QUE DETECTA EL GUARD.

## QUE CABE ESPERAR, Y POR QUE ESTA ESCRITO ANTES DE MEDIR

El guard de km-core (`KmCoreNoWebRtcDependencyTest`) comprueba UNA frontera:
`km-core` no depende de `km-webrtc` (fuente, build script y classpath). NO
comprueba "km-core sin dependencias de red": para R-6 la evidencia es
DECLARADA —`git diff km-core/build.gradle.kts` = 0—, no un test.

Por eso esta mutacion es la que el orquestador exigio medir en vez de fingir:
si anadimos `ktor-client-cio` y el guard NO falla, queda DEMOSTRADO que el guard
solo cubre km-webrtc y que R-6 no tiene guardia. El resultado honesto de M6
puede ser, por tanto, `NO DETECTADA` —y eso es un HALLAZGO, no un fallo de la
mutacion—. Si el guard SI fallara, seria porque alguien lo generalizo a red, y
entonces el guard cubriria mas de lo que su KDoc dice.

La mutacion COMPILA: `ktor-client-cio` es una coordenada valida y km-core la
resuelve como `implementation`.

Objetivo declarado: `KmCoreNoWebRtcDependencyTest` (guard de km-core).
"""

import os
import sys

sys.path.insert(0, os.path.join(
    os.path.dirname(os.path.dirname(os.path.abspath(__file__))),
    "mutaciones_p2",
))

from _comun_p2 import main, aplicar  # noqa: E402

F_BUILD = "km-core/build.gradle.kts"

VIEJO = """    implementation(libs.bcprov)

    testImplementation(kotlin("test"))"""

NUEVO = """    implementation(libs.bcprov)

    // MUTACION M6(spec): km-core gana una dependencia de RED. Si el guard de
    // km-webrtc NO falla con esto, queda demostrado que R-6 no tiene guardia.
    implementation("io.ktor:ktor-client-cio:3.1.3")

    testImplementation(kotlin("test"))"""


def fn():
    aplicar(F_BUILD, [(VIEJO, NUEVO, 1)])


if __name__ == "__main__":
    main([F_BUILD], fn)
