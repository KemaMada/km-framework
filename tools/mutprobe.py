#!/usr/bin/env python3
"""
mutprobe.py — arnes de medicion de mutaciones para la campaña de 3Q.5.3 FASE 3.

POR QUE ESTE ARNES ES TAN PARANOICO
====================================
En este proyecto un arnés de mutation ya afirmó haber ejecutado cosas que no
había ejecutado. La causa siempre fue la misma: se aceptaba un XML de una
corrida ANTERIOR como si fuera el resultado de la actual. Aquí eso es
imposible por construcción:

  1. `rm -rf` del directorio de resultados ANTES de cada ejecución.
  2. Un UNICO patrón `--tests` por ejecución (nunca una lista por comas), y se
     rechaza si el patrón contiene una coma.
  3. Se exige que exista un XML NUEVO: la marca de tiempo del XML tiene que ser
     posterior a un testigo escrito justo antes de lanzar Gradle, y el nombre del
     XML tiene que terminar en el nombre de la clase que se pidió.
  4. Si no hay XML nuevo → `INVALIDA(sin XML)`. Eso NO es "mutación no
     detectada": es "no se midió nada", y se reporta como tal.
  5. Se registra el `exit` real de Gradle y el número de tests de la corrida.

Uso:
    tools/mutprobe.py --label baseline --pattern 'com.keymessage.core.transmit.FileTransmitUnitStoreTest'
    tools/mutprobe.py --label mut1 --pattern 'com.keymessage.core.transmit.FileTransmitUnitStoreTest' \
        --mutate tools/mutaciones/m1_escribir_en_el_hueco.py
"""

import argparse
import glob
import hashlib
import os
import re
import subprocess
import sys
import time

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
RESULTS = os.path.join(ROOT, "km-core", "build", "test-results", "test")
JAVA_HOME = "/usr/lib/jvm/java-27-openjdk"


def parse_xml(path):
    """Conteo de la cabecera <testsuite> y nombres de los casos que fallan."""
    with open(path, "r", encoding="utf-8", errors="replace") as fh:
        head = fh.read(4096)
    m = re.search(r"<testsuite\b[^>]*>", head)
    if not m:
        return None
    tag = m.group(0)

    def attr(name):
        mm = re.search(r'\b%s="(\d+)"' % name, tag)
        return int(mm.group(1)) if mm else 0

    fallos = []
    with open(path, "r", encoding="utf-8", errors="replace") as fh:
        for line in fh:
            for fm in re.finditer(r'<testcase\b[^>]*name="([^"]*)"[^>]*>', line):
                rest = line[fm.end():]
                if "<failure" in rest or "<error" in rest:
                    fallos.append(fm.group(1))
    return {
        "tests": attr("tests"),
        "failures": attr("failures"),
        "errors": attr("errors"),
        "skipped": attr("skipped"),
        "name": re.search(r'\bname="([^"]*)"', tag).group(1) if re.search(r'\bname="([^"]*)"', tag) else "?",
        "fallos": fallos,
    }


def fingerprint():
    """Huella de los XML existentes, para distinguir lo NUEVO de lo viejo."""
    out = {}
    for p in sorted(glob.glob(os.path.join(RESULTS, "*.xml"))):
        st = os.stat(p)
        with open(p, "rb") as fh:
            out[os.path.basename(p)] = (st.st_mtime, hashlib.sha1(fh.read()).hexdigest())
    return out


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--label", required=True)
    ap.add_argument("--pattern", required=True)
    ap.add_argument("--mutate", default=None, help="script python que aplica la mutación")
    ap.add_argument("--revert", default=None, help="script python que revierte la mutación")
    ap.add_argument("--module", default="km-core:test", help="tarea gradle (por defecto :km-core:test)")
    ap.add_argument("--expect-xml", default=None, help="sufijo del nombre del XML que debe aparecer")
    args = ap.parse_args()

    if "," in args.pattern:
        print("RECHAZADO: un solo patrón --tests por ejecución, sin lista por comas", file=sys.stderr)
        return 2

    subprocess.run(["rm", "-rf", RESULTS], check=True)

    if args.mutate:
        r = subprocess.run([sys.executable, args.mutate, "apply"], cwd=ROOT)
        if r.returncode != 0:
            print("ABORTADO: la mutación no se pudo aplicar (exit %d)" % r.returncode, file=sys.stderr)
            return 3

    testigo = os.path.join(RESULTS, ".testigo")
    os.makedirs(RESULTS, exist_ok=True)
    with open(testigo, "w") as fh:
        fh.write(str(time.time()))
    t0 = time.time()

    cmd = [
        "./gradlew",
        "-Dorg.gradle.java.home=" + JAVA_HOME,
        args.module,
        "--tests",
        args.pattern,
        "--rerun-tasks",
    ]
    proc = subprocess.run(cmd, cwd=ROOT, capture_output=True, text=True)
    exit_code = proc.returncode
    dur = time.time() - t0

    if args.revert:
        subprocess.run([sys.executable, args.revert, "revert"], cwd=ROOT)

    xmls = [p for p in sorted(glob.glob(os.path.join(RESULTS, "*.xml")))
            if os.stat(p).st_mtime >= os.stat(testigo).st_mtime]
    os.remove(testigo)

    print("=" * 72)
    print("ETIQUETA : %s" % args.label)
    print("PATRON   : %s" % args.pattern)
    print("EXIT     : %d" % exit_code)
    print("XML NUEVOS: %d" % len(xmls))

    if not xmls:
        print("RESULTADO: INVALIDA(sin XML) — no se midió nada; esto NO es 'mutación no detectada'")
        tail = "\n".join((proc.stdout + proc.stderr).strip().splitlines()[-25:])
        print("---- gradle (últimas líneas) ----")
        print(tail)
        return 4

    total = {"tests": 0, "failures": 0, "errors": 0, "skipped": 0}
    for p in xmls:
        d = parse_xml(p)
        if d is None:
            print("RESULTADO: INVALIDA(xml ilegible: %s)" % os.path.basename(p))
            return 4
        for k in total:
            total[k] += d[k]
        print("  - %s  tests=%d failures=%d errors=%d skipped=%d"
              % (os.path.basename(p), d["tests"], d["failures"], d["errors"], d["skipped"]))
        for f in d["fallos"]:
            print("      FALLO: %s" % f)
    print("TOTALES  : tests=%d failures=%d errors=%d skipped=%d (%.1fs)"
          % (total["tests"], total["failures"], total["errors"], total["skipped"], dur))

    if args.expect_xml:
        if not any(os.path.basename(p) == args.expect_xml for p in xmls):
            print("RESULTADO: INVALIDA — se esperaba el XML '%s' y no aparecio" % args.expect_xml)
            return 4

    veredicto = "DETECTADA" if (total["failures"] + total["errors"]) > 0 else "NO DETECTADA"
    print("RESULTADO: %s" % veredicto)
    return 0 if veredicto == "DETECTADA" else 1


if __name__ == "__main__":
    sys.exit(main())