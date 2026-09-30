#!/usr/bin/env python3
"""
Nombres y motivo de cada fallo del XML indicado.

Se usa un parser de XML y NO una expresion regular: con regex, un `<testcase
/>` auto-cerrado hace que el fallo se atribuya al test ANTERIOR, que es
exactamente el tipo de atribucion equivocada que no se puede permitirse en una
campana de mutaciones.
"""
import glob
import html
import sys
import xml.etree.ElementTree as ET

pat = sys.argv[1] if len(sys.argv) > 1 else "*"
total = fallos = 0
for p in sorted(glob.glob("km-core/build/test-results/test/TEST-%s.xml" % pat)):
    raiz = ET.parse(p).getroot()
    for tc in raiz.iter("testcase"):
        total += 1
        hijos = [h for h in tc if h.tag in ("failure", "error")]
        if not hijos:
            continue
        fallos += 1
        msg = html.unescape(hijos[0].get("message") or "")
        primera = msg.splitlines()[0] if msg.strip() else "(sin mensaje)"
        print("  FALLO %-72s %s" % (tc.get("name", "?")[:72], primera[:160]))
print("  [%d tests, %d fallos]" % (total, fallos))