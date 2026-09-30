#!/usr/bin/env python3
"""M5 — aceptar una LECTURA PARCIAL.

Una sola llamada a `read()` y se devuelve lo que haya cabido, sin comprobar que
se ha leido todo. Es el error clasico de un lector, y convierte una unidad
entera en una unidad a medias que el codec rechazara: un fallo fisico
convertido en un rechazo nuevo.
"""
import sys, os
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from _comun import main, aplicar

VIEJO = """        return try {
            f.readBytes()
        } catch (e: Exception) {"""

NUEVO = """        return try {
            val destino = ByteArray(4096)
            val leidos = f.inputStream().use { it.read(destino) }
            destino.copyOf(maxOf(leidos, 0))
        } catch (e: Exception) {"""

main(lambda: aplicar([(VIEJO, NUEVO, 1)]))
