#!/usr/bin/env python3
"""
M-5 — borrar la verificacion de `VerifiedContactBundle.verified`.

R-5 dice que un bundle invalido NUNCA produce autoridad. Esta mutacion quita
exactamente la llamada que lo hacia cierto: `ContactBundle.verifyBundle`. Todo lo
demas —el constructor privado, las fabricas de `PeerAuthority`— sigue igual, que
es el punto: la garantia de R-5 no la sostenia el tipo por si solo, la sostenia
esta llamada.

Debe fallar:
  - `PeerAuthorityTest` PA-03 (firma alterada) y PA-04 (raiz inesperada), que
    esperan `KmIdVerificationException`;
  - los dos tests PREEXISTENTES de `VerifiedContactBundleTest` que ya lo
    comprobaban, para que se vea que no es un test nuevo que solo sabe lo que
    su propio cambio hizo.
"""

import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

from _comun_p2 import F_VERIFIED_BUNDLE, aplicar, main  # noqa: E402

VIEJO = """            ContactBundle.verifyBundle(bundle, expectedIdentityRoot)
            return VerifiedContactBundle(bundle)
"""

NUEVO = """            // MUTACION M-5: verificacion suprimida
            return VerifiedContactBundle(bundle)
"""


def fn():
    aplicar(F_VERIFIED_BUNDLE, [(VIEJO, NUEVO, 1)])


if __name__ == "__main__":
    main([F_VERIFIED_BUNDLE], fn)