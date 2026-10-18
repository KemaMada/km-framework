#!/usr/bin/env python3
"""
p4_probe.py — arnes de medicion de mutaciones para `p4-relay-backend`.

Es ADITIVO y NO toca `tools/mutprobe.py`: ese arnes tiene tres campanas
cerradas y se prohibe cambiar su semantica. Este existe porque las mutaciones de
p4 apuntan a TRES modulos distintos (`km-core`, `km-transport`, `km-desktop`) y
`mutprobe.py` solo mide `km-core`.

Las mismas garantias que `mutprobe.py`, por las mismas razones (un arnes que
miente es peor que ninguno):

  1. `rm -rf` del directorio de resultados ANTES de cada ejecucion.
  2. Un UNICO patron `--tests` por invocacion; se rechaza si contiene comas.
  3. Se exige un XML NUEVO: su mtime tiene que ser posterior a un testigo
     escrito justo antes de lanzar Gradle.
  4. Sin XML nuevo -> `INVALIDA(sin XML)`, que NO es "mutacion no detectada".
  5. Se registra el `exit` real de Gradle y el numero de tests.

Uso:
    export KM_P2_BACKUP_DIR=/tmp/opencode/p4-backups
    tools/mutaciones_p4/p4_probe.py --label m1a --module :km-transport:test \
        --results km-transport/build/test-results/test \
        --pattern 'com.km.transport.RelayBackendFaceTest' \
        --mutate tools/mutaciones_p4/p4_m1_a.py \
        --revert tools/mutaciones_p4/p4_m1_a.py
"""

import argparse
import glob
import hashlib
import os
import re
import subprocess
import sys
import time

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))


def parse_xml(path):
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
        contenido = fh.read()
    # `<failure` puede caer en la linea siguiente a `<testcase ...>`, asi que se
    # mira el bloque completo del caso y no el resto de la linea.
    for fm in re.finditer(r'<testcase\b[^>]*?\bname="([^"]*)"[^>]*>(.*?)</testcase>', contenido, re.S):
        if "<failure" in fm.group(2) or "<error" in fm.group(2):
            fallos.append(fm.group(1))
    return {
        "tests": attr("tests"),
        "failures": attr("failures"),
        "errors": attr("errors"),
        "skipped": attr("skipped"),
        "name": re.search(r'\bname="([^"]*)"', tag).group(1) if re.search(r'\bname="([^"]*)"', tag) else "?",
        "fallos": fallos,
    }


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--label", required=True)
    ap.add_argument("--pattern", required=True)
    ap.add_argument("--module", required=True, help="tarea gradle, p.ej. :km-transport:test")
    ap.add_argument("--results", required=True, help="directorio de XML (relativo a la raiz o absoluto)")
    ap.add_argument("--mutate", default=None)
    ap.add_argument("--revert", default=None)
    ap.add_argument("--java-home", default="/usr/lib/jvm/java-27-openjdk")
    ap.add_argument("--expect-xml", default=None)
    args = ap.parse_args()

    if "," in args.pattern:
        print("RECHAZADO: un solo patron --tests por ejecucion", file=sys.stderr)
        return 2

    results = args.results if os.path.isabs(args.results) else os.path.join(ROOT, args.results)
    subprocess.run(["rm", "-rf", results], check=True)

    if args.mutate:
        r = subprocess.run([sys.executable, args.mutate, "apply"], cwd=ROOT)
        if r.returncode != 0:
            print("ABORTADO: la mutacion no se pudo aplicar (exit %d)" % r.returncode, file=sys.stderr)
            return 3

    os.makedirs(results, exist_ok=True)
    testigo = os.path.join(results, ".testigo")
    with open(testigo, "w") as fh:
        fh.write(str(time.time()))
    t0 = time.time()

    cmd = [
        "./gradlew",
        "-Dorg.gradle.java.home=" + args.java_home,
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

    xmls = [p for p in sorted(glob.glob(os.path.join(results, "*.xml")))
            if os.stat(p).st_mtime >= os.stat(testigo).st_mtime]
    os.remove(testigo)

    print("=" * 72)
    print("ETIQUETA : %s" % args.label)
    print("MODULO   : %s" % args.module)
    print("PATRON   : %s" % args.pattern)
    print("EXIT     : %d" % exit_code)
    print("XML NUEVOS: %d" % len(xmls))

    if not xmls:
        print("RESULTADO: INVALIDA(sin XML) — no se midio nada; esto NO es 'mutacion no detectada'")
        print("---- gradle (ultimas lineas) ----")
        print("\n".join((proc.stdout + proc.stderr).strip().splitlines()[-25:]))
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
