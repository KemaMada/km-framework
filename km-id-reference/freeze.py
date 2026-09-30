#!/usr/bin/env python3
"""
Congelado de km-id-reference
============================

Escribe `FROZEN.sha256`, un inventario de checksums que cubre TANTO los
artefactos generados COMO el codigo que los produce.

Por que cubre el codigo y no solo los fixtures: un cambio en `kce.py` que
alterase el orden de las claves no moveria ningun hash de fixture si estos
se regeneraran despues. Congelar tambien el codigo es lo que hace que
"la referencia no ha cambiado" sea una afirmacion verificable y no una
promesa.

Uso:
    python3 freeze.py            # escribe FROZEN.sha256
    python3 freeze.py --check    # verifica sin escribir (sale 1 si difiere)
"""

from __future__ import annotations

import hashlib
import os
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
FROZEN = os.path.join(HERE, "FROZEN.sha256")

# El propio freeze.py y el checksum no se inventarian a si mismos.
EXCLUDE = {"FROZEN.sha256", "freeze.py", "__pycache__"}
CODE = [
    "constants.py",
    "contact.py",
    "device_link.py",
    "ed25519.py",
    "errors.py",
    "fingerprints.py",
    "generate.py",
    "identity.py",
    "kce.py",
    "rotation.py",
    "roster.py",
    "schemas.py",
    "selfcheck.py",
    "verification.py",
]


def inventory() -> dict[str, str]:
    out: dict[str, str] = {}

    for name in CODE:
        p = os.path.join(HERE, name)
        if not os.path.exists(p):
            raise FileNotFoundError(f"falta el modulo {name}")
        out[f"src/{name}"] = hashlib.sha256(open(p, "rb").read()).hexdigest()

    for sub in ("vectors", "invalid"):
        base = os.path.join(HERE, sub)
        for root, _dirs, files in os.walk(base):
            for fn in sorted(files):
                if fn in EXCLUDE or fn.endswith(".pyc"):
                    continue
                p = os.path.join(root, fn)
                rel = os.path.relpath(p, HERE)
                out[rel] = hashlib.sha256(open(p, "rb").read()).hexdigest()

    out["MANIFEST.json"] = hashlib.sha256(
        open(os.path.join(HERE, "MANIFEST.json"), "rb").read()
    ).hexdigest()
    return dict(sorted(out.items()))


def render(items: dict[str, str]) -> str:
    lines = [
        "# km-id-reference FROZEN",
        "# Checksums del codigo generador Y de los artefactos generados.",
        "# Cubrir el codigo es lo que hace verificable la afirmación de que la",
        "# referencia no ha cambiado: un cambio de ordenacion en kce.py no movería",
        "# ningun hash de fixture si estos se regeneraran despues.",
        "# Verificar con: python3 freeze.py --check",
    ]
    for path, digest in items.items():
        lines.append(f"{digest}  {path}")
    return "\n".join(lines) + "\n"


def main() -> int:
    check = "--check" in sys.argv
    items = inventory()
    body = render(items)

    if not check:
        with open(FROZEN, "w", encoding="utf-8") as f:
            f.write(body)
        print(f"Escritos {len(items)} checksums en FROZEN.sha256")
        return 0

    if not os.path.exists(FROZEN):
        print("FALLO: no existe FROZEN.sha256; ejecuta python3 freeze.py")
        return 1

    frozen = {}
    with open(FROZEN, encoding="utf-8") as f:
        for line in f:
            if line.startswith("#") or not line.strip():
                continue
            digest, _, path = line.partition("  ")
            # Sin strip(), la ruta arrastraria el salto de linea y NINGUNA
            # entrada coincidiria con el inventario recalculado.
            frozen[path.strip()] = digest.strip()

    changed = [p for p in frozen if p not in items or items[p] != frozen[p]]
    added = [p for p in items if p not in frozen]

    for p in added:
        print(f"NUEVO   {p}")
    for p in changed:
        state = "MODIFICADO" if p in items else "ELIMINADO"
        print(f"{state} {p}")

    if changed or added:
        print(f"\nLa referencia DIFIERE del congelado ({len(changed)} cambiados, "
              f"{len(added)} nuevos).")
        print("Si el cambio es intencionado, regenera y vuelve a congelar:")
        print("    python3 generate.py && python3 selfcheck.py && python3 freeze.py")
        return 1

    print(f"OK: la referencia coincide con el congelado ({len(items)} ficheros).")
    return 0


if __name__ == "__main__":
    sys.exit(main())
