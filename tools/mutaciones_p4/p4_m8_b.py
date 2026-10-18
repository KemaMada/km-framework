#!/usr/bin/env python3
"""
p4_m8_b.py — M8-b (spec): km-core declarado como `project(":km-core")` en vez de
por ARTEFACTO.

## EL ESCENARIO QUE LA SPEC DESCRIBE

La spec dice que la mezcla de las dos formas mete km-core DOS veces en el mismo
grafo y Gradle produce `Duplicate class`. La tarea 1.4 eligio el artefacto por
ese motivo, y el KDoc de `km-transport/build.gradle.kts` lo repite.

## LO QUE SE MIDIO, Y EL HALLAZGO

Se probaron DOS formas, y NINGUNA reproduce `Duplicate class` en este repositorio:

  (a) `api(project(":km-core"))` en `km-transport/build.gradle.kts`.
      Resultado: `:km-transport:test` verde. km-transport aislado no arrastra
      km-core dos veces; km-desktop consume km-transport POR ARTEFACTO, asi que
      el POM publicado —que sigue apuntando a `com.km:km-core`— es lo que entra
      en el grafo de km-desktop. Cambiar el build del PROYECTO km-transport no
      cambia el POM ya publicado.

  (b) `implementation(project(":km-core"))` en `km-desktop/build.gradle.kts`,
      conservando `com.km:km-transport` por artefacto.
      Resultado: `:km-desktop:compileKotlin` BUILD SUCCESSFUL. **Gradle sustituye
      el modulo externo `com.km:km-core` por el proyecto local homonimo**
      (module substitution): las dos formas colapsan en UNA sola entrada y no
      hay duplicado que detectar.

## CONCLUSION HONESTA

`Duplicate class` NO es reproducible aqui porque Gradle de-duplica por
coordenadas. La afirmacion del KDoc describe el riesgo de un consumidor EXTERNO
que no comparta el composite build (un proyecto fuera de este repo que declare
`project(":km-core")` no compilaria, pero eso no es medible desde dentro). Se
declara como NO DETECTADA con la evidencia de las dos corridas, en vez de fingir
una mutacion que no rompe nada.

La mutacion COMPILA: `project(":km-core")` es sintaxis valida.
"""

import os
import sys

sys.path.insert(0, os.path.join(
    os.path.dirname(os.path.dirname(os.path.abspath(__file__))),
    "mutaciones_p2",
))

from _comun_p2 import main, aplicar  # noqa: E402

F_BUILD = "km-transport/build.gradle.kts"

VIEJO = """    api("com.km:km-core:0.1.0-SNAPSHOT")"""

NUEVO = """    // MUTACION M8-b: km-core entra como PROYECTO en vez de por artefacto.
    api(project(":km-core"))"""


def fn():
    aplicar(F_BUILD, [(VIEJO, NUEVO, 1)])


if __name__ == "__main__":
    main([F_BUILD], fn)
