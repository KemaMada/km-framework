#!/usr/bin/env python3
"""M2 — eliminar el `force(true)` del CANAL de escritura.

Quita la sincronizacion del FICHERO y deja la del DIRECTORIO, que es lo que
hace la mutacion invisible a una busqueda de tokens: `force(true)` sigue
apareciendo en `sync()`, asi que el token no desaparece.
"""
import sys, os
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from _comun import main, aplicar

VIEJO = """                val buffer = ByteBuffer.wrap(bytes)
                while (buffer.hasRemaining()) canal.write(buffer)
                canal.force(true)
            }"""

NUEVO = """                val buffer = ByteBuffer.wrap(bytes)
                while (buffer.hasRemaining()) canal.write(buffer)
            }"""

main(lambda: aplicar([(VIEJO, NUEVO, 1)]))
