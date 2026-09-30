#!/usr/bin/env python3
"""M3 — eliminar `ATOMIC_MOVE` de las DOS publicaciones.

El `rename` sigue siendo un `rename` en Linux, asi que esta mutacion NO cambia
el inodo: solo cambia la garantia declarada. Es la mutacion que solo se puede
ver mirando el codigo.
"""
import sys, os
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from _comun import main, aplicar

VIEJO = """                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,"""

NUEVO = """                StandardCopyOption.REPLACE_EXISTING,"""

main(lambda: aplicar([(VIEJO, NUEVO, 2)]))
