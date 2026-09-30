#!/usr/bin/env python3
"""
M5 — VACIAR la bandeja `PendingInbound` al componer la unidad.

`SecureTransmitJournal.persistir()` sigue haciendo write-ahead, sigue
verificando y sigue confirmando: la unidad es valida, con su estado y su
envio. Lo que no viaja es la bandeja de frames entrantes sin usar. Es el
defecto mas peligroso de la lista porque NO se ve como un fallo: la unidad se
escribe, se relee, coincide con la compuesta y se confirma.

PROPIEDAD QUE DEBE ROMPER: D-5, la bandeja viaja en la MISMA unidad atomica
que el estado y el envio, con sus bytes y su orden.
"""
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from _mig import main, aplicar

FICHERO = "km-core/src/main/kotlin/com/km/transmit/SecureTransmitJournal.kt"

VIEJO = """        val unidad = Km52Unit(foto, tabla, outbound, inbox.bloque())"""

NUEVO = """        // MUTACION M5: la bandeja no viaja. La unidad sigue siendo valida, con
        // su estado y su envio: el defecto no se ve como un fallo.
        val unidad = Km52Unit(foto, tabla, outbound, emptyList())"""

main(FICHERO, lambda: aplicar(FICHERO, [(VIEJO, NUEVO, 1)]))
