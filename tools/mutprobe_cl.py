#!/usr/bin/env python3
"""
mutprobe_cl.py — arnes de medicion de mutaciones de la fase APPLY de
`contact-list-refresh`.

POR QUE ESTE ARNES ES TAN PARANOICO (heredado de mutprobe.py / mutprobe_p5.py /
mutprobe_p6.py)
=========================================================================
En este proyecto un arnes de mutation ya afirmo haber ejecutado cosas que no
habia ejecutado. La causa siempre fue la misma: se aceptaba un XML de una corrida
ANTERIOR como si fuera el resultado de la actual. Aqui eso se vuelve imposible
por construccion:

  1. `rm -rf` del directorio `test-results` del modulo ANTES de cada ejecucion.
  2. Un UNICO patron `--tests` por ejecucion (nunca una lista por comas).
  3. Se exige que exista un XML NUEVO (mtime posterior a un testigo escrito justo
     antes de lanzar Gradle).
  4. Sin XML nuevo -> `INVALIDA(sin XML)`: eso NO es "mutacion no detectada", es
     "no se midio nada".
  5. Se registra el `exit` real de Gradle, el numero de tests y los fallos.
  6. Si la compilacion falla, el veredicto es `INVALIDA`: una mutacion que no
     COMPILA falsea la prueba y no mide nada.

DIFERENCIA CON `mutprobe_p6.py`: el directorio de RESPALDO es el suyo propio
(`/tmp/opencode/clr-apply-backups`), porque una campana de mutaciones que
comparte directorio de respaldo con otra puede restaurar el fichero equivocado y
medir sobre otra cosa.

Uso:
    tools/mutprobe_cl.py --label ma --pattern 'com.example.keymessage.contact.InMemoryContactBookTest' \
        --mutate tools/mutaciones_clr/clr_ma_libro_no_emite.py
"""

import argparse
import glob
import os
import re
import subprocess
import sys
import time

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
RESULTS = os.path.join(ROOT, "app", "build", "test-results", "testDebugUnitTest")
JAVA_HOME = "/usr/lib/jvm/java-27-openjdk"

# El respaldo de los ficheros mutados vive FUERA del repositorio, y `separado`
# del de p6: una campana que comparte directorio de respaldo con otra puede
# restaurar el fichero equivocado.
BACKUP_DIR = os.environ.setdefault("KM_P2_BACKUP_DIR", "/tmp/opencode/clr-apply-backups")


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
        texto = fh.read()
    # Gradle pone el `<failure>` en la linea SIGUIENTE al `<testcase>`, asi que
    # hay que mirar el bloque entero y no solo el resto de la linea. Y hay que
    # distinguir los DOS formas: `<testcase .../>` (pasa, vacio) y
    # `<testcase ...>...</testcase>` (puede fallar). Si se ignorase la forma
    # auto-cerrada, el cuerpo de un caso que PASA se comeria el del siguiente
    # y el fallo se atribuiria al caso equivocado.
    for fm in re.finditer(
        r'<testcase\b[^>]*?\bname="([^"]*)"[^>]*?(?:/>|>(.*?)</testcase>)', texto, re.S
    ):
        cuerpo = fm.group(2) or ""
        if "<failure" in cuerpo or "<error" in cuerpo:
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
    ap.add_argument("--mutate", default=None)
    ap.add_argument("--revert", default=None)
    args = ap.parse_args()

    if "," in args.pattern:
        print("RECHAZADO: un solo patron --tests por ejecucion, sin lista por comas", file=sys.stderr)
        return 2

    # El entorno lo heredan los scripts de mutacion: `_comun_p2.py` lee el
    # directorio de respaldo al IMPORTAR, y este es el momento de fijarlo.
    os.environ["KM_P2_BACKUP_DIR"] = BACKUP_DIR

    subprocess.run(["rm", "-rf", RESULTS], check=True)

    if args.mutate:
        r = subprocess.run([sys.executable, args.mutate, "apply"], cwd=ROOT)
        if r.returncode != 0:
            print("ABORTADO: la mutacion no se pudo aplicar (exit %d)" % r.returncode, file=sys.stderr)
            return 3

    testigo = os.path.join(RESULTS, ".testigo")
    os.makedirs(RESULTS, exist_ok=True)
    with open(testigo, "w") as fh:
        fh.write(str(time.time()))
    t0 = time.time()

    cmd = [
        "./gradlew",
        "-p", "app",
        "-Dorg.gradle.java.home=" + JAVA_HOME,
        ":testDebugUnitTest",
        "--tests", args.pattern,
        "--rerun-tasks",
        "--console=plain",
    ]
    proc = subprocess.run(cmd, cwd=ROOT, capture_output=True, text=True)
    exit_code = proc.returncode
    dur = time.time() - t0

    if args.revert:
        subprocess.run([sys.executable, args.revert, "revert"], cwd=ROOT)

    xmls = [p for p in sorted(glob.glob(os.path.join(RESULTS, "*.xml")))
            if os.stat(p).st_mtime >= os.stat(testigo).st_mtime]
    if os.path.exists(testigo):
        os.remove(testigo)

    print("=" * 72)
    print("ETIQUETA : %s" % args.label)
    print("PATRON   : %s" % args.pattern)
    print("EXIT     : %d" % exit_code)
    print("XML NUEVOS: %d" % len(xmls))

    if not xmls:
        salida = proc.stdout + proc.stderr
        compile_failed = "compileDebugUnitTestKotlin" in salida and (
            "error:" in salida or "e: " in salida
        )
        print("RESULTADO: INVALIDA(sin XML)%s" %
              (" [posible fallo de COMPILACION: la prueba queda FALSEADA]" if compile_failed else ""))
        tail = "\n".join(salida.strip().splitlines()[-30:])
        print("---- gradle (ultimas lineas) ----")
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

    veredicto = "DETECTADA" if (total["failures"] + total["errors"]) > 0 else "NO DETECTADA"
    print("RESULTADO: %s" % veredicto)
    return 0 if veredicto == "DETECTADA" else 1


if __name__ == "__main__":
    sys.exit(main())
