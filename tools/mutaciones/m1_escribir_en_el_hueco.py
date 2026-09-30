#!/usr/bin/env python3
"""M1 — escribir DIRECTAMENTE en `pendiente.bin`, sin temporal.

Es el defecto que abre la clase: un `writeBytes` dentro del hueco trunca el
fichero destino ANTES de escribir, asi que un corte convierte una escritura a
medias en una unidad aparentemente entera.
"""
import sys, os
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from _comun import main, aplicar

VIEJO = """        val temporal = File(dir, PENDIENTE_TMP)
        try {
            FileChannel.open(
                temporal.toPath(),
                StandardOpenOption.CREATE,
                StandardOpenOption.WRITE,
                StandardOpenOption.TRUNCATE_EXISTING,
            ).use { canal ->
                val buffer = ByteBuffer.wrap(bytes)
                while (buffer.hasRemaining()) canal.write(buffer)
                canal.force(true)
            }
            Files.move(
                temporal.toPath(),
                File(dir, PENDIENTE).toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
        } catch (e: TransmitStoreFailure) {"""

NUEVO = """        val temporal = File(dir, PENDIENTE)
        try {
            FileChannel.open(
                temporal.toPath(),
                StandardOpenOption.CREATE,
                StandardOpenOption.WRITE,
                StandardOpenOption.TRUNCATE_EXISTING,
            ).use { canal ->
                val buffer = ByteBuffer.wrap(bytes)
                while (buffer.hasRemaining()) canal.write(buffer)
                canal.force(true)
            }
        } catch (e: TransmitStoreFailure) {"""

main(lambda: aplicar([(VIEJO, NUEVO, 1)]))
