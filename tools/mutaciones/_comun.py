#!/usr/bin/env python3
"""
Infraestructura comun de la campana de mutaciones de 3Q.5.3 FASE 3.

Cada mutacion se aplica sobre UN fichero de produccion y se revierte con
`git checkout --`, que es fiable y verificable. El script NO intenta deshacer
el cambio a mano: deshacerlo a mano es como se cuela un resto de mutacion en
la siguiente corrida, y esa es exactamente la mentira que ya se ha contado en
este proyecto.
"""

import io
import os
import subprocess
import sys

FICHERO = "km-core/src/main/kotlin/com/km/transmit/FileTransmitUnitStore.kt"


def leer():
    with io.open(FICHERO, encoding="utf-8") as fh:
        return fh.read()


def escribir(s):
    with io.open(FICHERO, "w", encoding="utf-8") as fh:
        fh.write(s)


def aplicar(reemplazos):
    """reemplazos = [(viejo, nuevo, veces_esperadas), ...]"""
    s = leer()
    for viejo, nuevo, esperadas in reemplazos:
        n = s.count(viejo)
        if n != esperadas:
            raise SystemExit(
                "ABORTADO: el texto a mutar aparece %d veces y se esperaban %d.\n"
                "Si no esta donde se esperaba, la mutacion seria OTRA cosa:\n---\n%s\n---" % (n, esperadas, viejo)
            )
        s = s.replace(viejo, nuevo)
    escribir(s)
    mostrar_diff()


def revertir():
    subprocess.run(["git", "checkout", "--", FICHERO], cwd=raiz(), check=True)
    if leer() != original():
        raise SystemExit("ABORTADO: el checkout no ha restaurado el fichero")


def original():
    return subprocess.run(
        ["git", "show", "HEAD:" + FICHERO], cwd=raiz(), check=True, capture_output=True, text=True
    ).stdout


def raiz():
    return os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))


def mostrar_diff():
    d = subprocess.run(
        ["git", "diff", "--stat", "--", FICHERO], cwd=raiz(), check=True, capture_output=True, text=True
    ).stdout.strip()
    print("---- la mutacion que se ha aplicado ----")
    print(d if d else "VACIO: la mutacion NO ha cambiado nada")


def main(fn):
    if len(sys.argv) != 2 or sys.argv[1] not in ("apply", "revert"):
        raise SystemExit("uso: %s apply|revert" % sys.argv[0])
    if sys.argv[1] == "apply":
        fn()
    else:
        revertir()