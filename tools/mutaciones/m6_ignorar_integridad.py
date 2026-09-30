#!/usr/bin/env python3
"""M6 — ignorar la verificacion de INTEGRIDAD del medio.

El medio deja de DECLARAR el dano: un hueco que no es un fichero pasa a
contestar `null`, que es decir "el medio esta vacio". Y eso es una AFIRMACION
sobre el estado hecha por un medio danado, que es justo lo que la invariante de
la fase prohibe.

Ojo con el reparto: el CHECKSUM es del codec, no del medio (MEDIO-05 lo mide).
La parte que le toca al MEDIO es la DECLARACION del dano, que es lo que se
muta aqui.
"""
import sys, os
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from _comun import main, aplicar

VIEJO_A = """        if (!f.isFile) {
            throw TransmitStoreFailure(TransmitStage.READ_BACK, "el hueco '$nombre' del medio esta danado: no es un fichero")
        }"""
NUEVO_A = """        if (!f.isFile) {
            return null
        }"""

VIEJO_B = """            if (f.exists() && !f.isFile) {
                throw TransmitStoreFailure(stage, "el hueco '$nombre' del medio esta danado: no es un fichero")
            }"""
NUEVO_B = """            if (false) {
                throw TransmitStoreFailure(stage, "el hueco '$nombre' del medio esta danado: no es un fichero")
            }"""

main(lambda: aplicar([(VIEJO_A, NUEVO_A, 1), (VIEJO_B, NUEVO_B, 1)]))
