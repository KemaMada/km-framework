#!/usr/bin/env python3
"""
Lector de NOMBRES de test de los XML de Gradle, para la tabla de mutaciones.

POR QUE EXISTE, Y NO SE ARREGLA `mutprobe.py`
---------------------------------------------

`tools/mutprobe.py` decide el veredicto leyendo los ATRIBUTOS de `<testsuite>`,
que son correctos: `tests`, `failures`, `errors`. Ese veredicto no se toca.

Lo que si esta roto es el LISTADO de nombres de los tests que fallan, en
`parse_xml`:

  1. La expresion `<testcase\\b[^>]*name="([^"]*)"` es codiciosa, luego hace
     backtracking hasta el ULTIMO `name="` de la etiqueta, que es `classname`.
     Resultado: imprime `com.km.node.PeerAuthorityTest` en vez de
     `PA-03 ContactVerified verified rechaza...`.
  2. `<failure ...>` va en su PROPIA linea en los XML de Gradle, y el codigo
     comprueba solo el resto de la MISMA linea, luego nunca lo encuentra.

Para la evidencia de p2 hace falta el nombre real de cada test que fallo: una
tabla que dice "falla 1" sin decir cual, no distingue "detecto la mutacion" de
"se rompio por otra cosa". Este script lo lee bien. NO se modifica `mutprobe.py`
a proposito: es infraestructura de tres campanas ya cerradas, y tocarla a
media fase mezclaria sus resultados con los de esta.

Uso:
    python3 tools/mutaciones_p2/fallos_p2.py            # todos los XML nuevos
    python3 tools/mutaciones_p2/fallos_p2.py --xml <ruta>
"""

import argparse
import glob
import os
import re
import sys

RAIZ = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
RESULTS = os.path.join(RAIZ, "km-core", "build", "test-results", "test")


def names_de_clase(ruta):
    """(total, [(nombre, kind, mensaje), ...]) de un XML de clase."""
    with open(ruta, encoding="utf-8", errors="replace") as fh:
        texto = fh.read()
    cab = re.search(r"<testsuite\b[^>]*>", texto)
    if not cab:
        return None, []

    def attr(nombre):
        mm = re.search(r'\b%s="(\d+)"' % nombre, cab.group(0))
        return int(mm.group(1)) if mm else 0

    total = attr("tests")

    # `[^>]*?` NO codicioso + `(?<!class)` para que no se confunda con
    # `classname`. El cuerpo se busca despues del cierre de la etiqueta, que es
    # donde el XML de Gradle pone `<failure>` / `<error>`.
    fallos = []
    for m in re.finditer(r'<testcase\b[^>]*?(?<!class)\bname="([^"]*)"[^>]*?(/?)>', texto, re.S):
        nombre = m.group(1)
        if m.group(2) == "/":
            continue
        cuerpo = texto[m.end(): m.end() + 4000]
        fm = re.search(r"<(failure|error)\b[^>]*?(?:message=\"([^\"]*)\")?", cuerpo)
        if fm:
            fallos.append((nombre, fm.group(1), (fm.group(2) or "")[:110]))
    return total, fallos


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--xml", default=None)
    args = ap.parse_args()

    rutas = [args.xml] if args.xml else sorted(glob.glob(os.path.join(RESULTS, "*.xml")))
    if not rutas:
        print("INVALIDA(sin XML): no hay resultados que leer", file=sys.stderr)
        return 4

    tot = fallos = 0
    for r in rutas:
        total, fs = names_de_clase(r)
        if total is None:
            print("XML ilegible: %s" % os.path.basename(r))
            continue
        tot += total
        for nombre, kind, msg in fs:
            fallos += 1
            print("  FALLO[%s] %s" % (kind, nombre))
            if msg:
                print("          %s" % msg.replace("&#10;", " ").strip())
    print("TOTALES: tests=%d  tests-con-fallo=%d  clases=%d" % (tot, fallos, len(rutas)))
    return 0


if __name__ == "__main__":
    sys.exit(main())