#!/usr/bin/env python3
"""
M2 — restaurar el snapshot pero REGENERAR el par DH propio.

`DoubleRatchetSession.restore()` sobrescribe todo lo que trae la foto, pero la
mitad que no se puede regenerar —el ESCALAR PRIVADO— se tira y se genera una
nueva. La foto restaurada es coherente consigo misma (la publica se deriva de
la nueva privada) y por eso `snapshot()` no protesta: el estado es NUEVO y
valido, no corrupto. Solo deja de ser la sesion que se persistio.

PROPIEDAD QUE DEBE ROMPER: continuidad criptografica. Y es el caso que
`MIG-08` demuestra por construccion: dos mitades con la MISMA clave publica y
distinto escalar no pueden entenderse nunca.
"""
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from _mig import main, aplicar

FICHERO = "km-core/src/main/kotlin/com/km/ratchet/DoubleRatchetSession.kt"

VIEJO = """        dhSelf = s.dhSelf.toKeyPair()
"""

NUEVO = """        dhSelf = x25519.generateKeyPair()
"""

main(FICHERO, lambda: aplicar(FICHERO, [(VIEJO, NUEVO, 1)]))
