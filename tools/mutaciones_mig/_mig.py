#!/usr/bin/env python3
"""
Infraestructura comun de la campana de mutaciones sobre `MIG-*` (3Q.5.3 FASE 4).

Molde: `tools/mutaciones/_comun.py` de la FASE 3. Las dos diferencias son
deliberadas:

  1. El fichero a mutar se declara POR SCRIPT, no en el modulo comun, porque
     esta campana reparte las mutaciones entre cuatro ficheros de produccion
     distintos y `_comun.py` tiene el suyo fijo.
  2. `aplicar()` exige que el texto a mutar aparezca EXACTAMENTE las veces
     esperadas y ABORTA si no. Un `replace` a pelo sobre un texto que no esta
     donde se espera produce una mutacion que no es la que dice ser: es la
     forma mas barata de mentir en una campana de mutaciones.

La revertir es `git checkout -- <fichero>` NUNCA una edicion a mano. Deshacer a
mano es como se cuela un resto de mutacion en la siguiente corrida, y esa
exactamente es la mentira que ya se ha contado en este proyecto.
"""

import io
import os
import subprocess
import sys


def raiz():
    return os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))


def leer(fichero):
    with io.open(fichero, encoding="utf-8") as fh:
        return fh.read()


def escribir(fichero, s):
    with io.open(fichero, "w", encoding="utf-8") as fh:
        fh.write(s)


def original(fichero):
    return subprocess.run(
        ["git", "show", "HEAD:" + fichero],
        cwd=raiz(),
        check=True,
        capture_output=True,
        text=True,
    ).stdout


def aplicar(fichero, reemplazos):
    """reemplazos = [(viejo, nuevo, veces_esperadas), ...]"""
    s = leer(fichero)
    for viejo, nuevo, esperadas in reemplazos:
        n = s.count(viejo)
        if n != esperadas:
            raise SystemExit(
                "ABORTADO: el texto a mutar aparece %d veces y se esperaban %d.\n"
                "Si no esta donde se esperaba, la mutacion seria OTRA cosa:\n---\n%s\n---"
                % (n, esperadas, viejo)
            )
        s = s.replace(viejo, nuevo)
    escribir(fichero, s)
    mostrar_diff(fichero)


def revertir(fichero):
    subprocess.run(["git", "checkout", "--", fichero], cwd=raiz(), check=True)
    if leer(fichero) != original(fichero):
        raise SystemExit("ABORTADO: el checkout no ha restaurado el fichero")


def mostrar_diff(fichero):
    d = subprocess.run(
        ["git", "diff", "--stat", "--", fichero],
        cwd=raiz(),
        check=True,
        capture_output=True,
        text=True,
    ).stdout.strip()
    print("---- la mutacion que se ha aplicado ----")
    print(d if d else "VACIO: la mutacion NO ha cambiado nada")


def main(fichero, fn):
    if len(sys.argv) != 2 or sys.argv[1] not in ("apply", "revert"):
        raise SystemExit("uso: %s apply|revert" % sys.argv[0])
    if sys.argv[1] == "apply":
        fn()
    else:
        revertir(fichero)
