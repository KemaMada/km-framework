#!/usr/bin/env python3
"""
fallos_p4.py — recuento de tests por modulo leyendo los XML de `build/test-results`.

Distingue `INVALIDA(sin XML)` de "no hay fallos": un objetivo inexistente o un
`compileTestKotlin` fallido dejan el directorio de resultados vacio o con XML de
una corrida ANTERIOR, y ambos casos se reportan como lo que son.

Uso:
    tools/mutaciones_p4/fallos_p4.py --results km-core/build/test-results/test
    tools/mutaciones_p4/fallos_p4.py --results km-core/build/test-results/test --list-failures
"""

import argparse
import glob
import os
import re
import sys
import time


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
        "name": (re.search(r'\bname="([^"]*)"', tag) or [None, "?"])[1] if re.search(r'\bname="([^"]*)"', tag) else "?",
        "fallos": fallos,
        "mtime": os.stat(path).st_mtime,
    }


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--results", required=True)
    ap.add_argument("--list-failures", action="store_true")
    ap.add_argument("--expect", default=None, help="numero de tests esperado; si no coincide -> MISMATCH")
    ap.add_argument("--since", type=float, default=None, help="ignorar XML con mtime anterior")
    args = ap.parse_args()

    if not os.path.isdir(args.results):
        print("RESULTADO: INVALIDA(sin XML) — el directorio %s no existe" % args.results)
        return 4

    xmls = sorted(glob.glob(os.path.join(args.results, "*.xml")))
    if args.since is not None:
        xmls = [p for p in xmls if os.stat(p).st_mtime >= args.since]
    if not xmls:
        print("RESULTADO: INVALIDA(sin XML) — 0 XML en %s" % args.results)
        return 4

    total = {"tests": 0, "failures": 0, "errors": 0, "skipped": 0}
    fallos = []
    for p in xmls:
        d = parse_xml(p)
        if d is None:
            print("RESULTADO: INVALIDA(xml ilegible: %s)" % os.path.basename(p))
            return 4
        for k in total:
            total[k] += d[k]
        fallos.extend("%s#%s" % (os.path.basename(p)[:-4], f) for f in d["fallos"])

    newest = max(parse_xml(p)["mtime"] for p in xmls)
    print("DIR      : %s" % args.results)
    print("CLASES   : %d" % len(xmls))
    print("TOTALES  : tests=%d failures=%d errors=%d skipped=%d"
          % (total["tests"], total["failures"], total["errors"], total["skipped"]))
    print("XML mtime mas reciente: %.0f (%s)"
          % (newest, time.strftime("%H:%M:%S", time.localtime(newest))))
    if args.list_failures and fallos:
        for f in fallos:
            print("  FALLO: %s" % f)

    if args.expect is not None and str(total["tests"]) != args.expect:
        print("RESULTADO: MISMATCH — esperado %s tests, medidos %d" % (args.expect, total["tests"]))
        return 5

    print("RESULTADO: OK")
    return 0


if __name__ == "__main__":
    sys.exit(main())