#!/usr/bin/env python3
"""
Lista los tests que FALLARON en un XML de JUnit, con la primera linea del
mensaje.

`mutprobe.py` cuenta fallos pero no los nombra, y "nº de detectores" sin los
nombres no localizable nada: hay que poder decir QUALES de los once `MIG-*`
detectaron cada mutacion. No sustituye al arnés: solo lee el XML que el arnés
ya ha producido y cuya NUEVAIDAD el arnés ya ha comprobado.
"""
import glob
import os
import re
import sys
import xml.etree.ElementTree as ET

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))


def main():
    base = sys.argv[1] if len(sys.argv) > 1 else "km-core"
    patron = sys.argv[2] if len(sys.argv) > 2 else ""
    for p in sorted(glob.glob(os.path.join(ROOT, base, "build", "test-results", "test", "*.xml"))):
        raiz = ET.parse(p).getroot()
        for tc in raiz.iter("testcase"):
            for hijo in list(tc):
                if hijo.tag in ("failure", "error"):
                    msg = (hijo.get("message") or "").splitlines()
                    primera = msg[0][:190] if msg else ""
                    tipo = hijo.get("type") or ""
                    print(
                        "  FALLO %s | %s | %s%s"
                        % (
                            os.path.basename(p).replace("TEST-", "").replace(".xml", ""),
                            tc.get("name"),
                            tipo,
                            (" :: " + primera) if primera else "",
                        )
                    )


if __name__ == "__main__":
    main()
