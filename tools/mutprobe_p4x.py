#!/usr/bin/env python3
"""Parseador de resultados JUnit XML por modulo. Uso: mutprobe_p4x.py [modulo ...]"""
import sys, glob, os, time, xml.etree.ElementTree as ET

ROOT = os.path.dirname(os.path.abspath(__file__))
BASE = os.path.dirname(ROOT)

def parse(mod):
    d = os.path.join(BASE, mod, "build", "test-results", "test")
    files = sorted(glob.glob(os.path.join(d, "TEST-*.xml")))
    if not files:
        print("%-14s INVALIDA(sin XML)" % mod)
        return None
    tests = fails = errs = skips = classes = 0
    oldest = None
    names = []
    for f in files:
        try:
            r = ET.parse(f).getroot()
        except Exception as e:
            print("XML ilegible", f, e); continue
        classes += 1
        t = int(r.get("tests", 0)); tests += t
        fails += int(r.get("failures", 0))
        errs += int(r.get("errors", 0))
        skips += int(r.get("skipped", 0))
        age = time.time() - os.path.getmtime(f)
        oldest = age if oldest is None else max(oldest, age)
        for tc in r.iter("testcase"):
            for bad in list(tc.findall("failure")) + list(tc.findall("error")):
                names.append("%s.%s :: %s" % (tc.get("classname"), tc.get("name"),
                                              (bad.get("message") or "")[:110]))
    print("%-14s %5d tests  %d fallos  %d errores  %d skipped  %d clases  xml_mas_viejo=%.1fs"
          % (mod, tests, fails, errs, skips, classes, oldest or -1))
    for n in names:
        print("   ROJA: " + n)
    return (tests, fails, errs, skips)

mods = sys.argv[1:] or ["km-core", "km-transport", "km-desktop", "km-daemon", "km-webrtc"]
tot = 0
t0 = time.time()
for m in mods:
    r = parse(m)
    if r:
        tot += r[0]
print("TOTAL tests = %d  (parseado en %.1fs)" % (tot, time.time() - t0))
