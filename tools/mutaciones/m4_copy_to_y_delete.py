#!/usr/bin/env python3
"""M4 — volver a `copyTo` + `delete` en la PROMOCION.

Reemplaza el renombrado por una copia que SUSTITUYE y un borrado del origen.
Es la unica mutacion de la campana que deja una ventana con el hueco confirmado
a medias, y por eso es la que exige DOS detectores: uno estructural y otro
conductual.
"""
import sys, os
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from _comun import main, aplicar

VIEJO = """        try {
            Files.move(
                pendiente.toPath(),
                File(dir, CONFIRMADA).toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
        } catch (e: Exception) {"""

NUEVO = """        try {
            pendiente.copyTo(File(dir, CONFIRMADA), overwrite = true)
            pendiente.delete()
        } catch (e: Exception) {"""

main(lambda: aplicar([(VIEJO, NUEVO, 1)]))
