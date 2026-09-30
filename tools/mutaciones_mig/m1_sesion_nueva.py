#!/usr/bin/env python3
"""
M1 — la migracion FABRICA una sesion NUEVA en vez de restaurar la misma.

`SecureTransmitJournal.restaurar()` entrega a la sesion un estado recien
inventado: raiz nueva, DH propio nuevo, contadores a cero y sin cadenas de
recepcion. El proceso que vuelve a arrancar continua con una sesion que no es
la que persistio.

PROPIEDAD QUE DEBE ROMPER: identidad y continuidad de sesion. La sesion que
migra tiene que ser LA MISMA, y aqui es otra: otro estado, otra clave, otras
claves retenidas.
"""
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from _mig import main, aplicar

FICHERO = "km-core/src/main/kotlin/com/km/transmit/SecureTransmitJournal.kt"

VIEJO = """        session.restore(unidad.snapshot)
"""

NUEVO = """        // MUTACION M1: no se restaura LA MISMA sesion. Se fabrica una NUEVA:
        // raiz nueva, DH propio nuevo, contadores a cero y sin cadenas de
        // recepcion. La bandeja y el envio se rehidratan igual, que es lo que
        // hace el defecto INDETECTABLE a ojo: parece una restauracion.
        session.restore(
            DoubleRatchetSnapshot(
                rootKey = ByteArray(32) { ((it * 7 + 3) and 0xFF).toByte() },
                dhSelf = com.km.crypto.DerivedX25519KeyPair.derive(
                    ByteArray(32) { ((it * 11 + 5) and 0xFF).toByte() },
                    com.km.crypto.provider.BcX25519(),
                ),
                dhRemote = null,
                sendMessageNumber = 0u,
                previousChainLength = 0u,
                sendChain = SymmetricRatchetSnapshot(
                    sendChainKey = ByteArray(32) { ((it + 2) and 0xFF).toByte() },
                    sendMessageNumber = 0u,
                    receiveChainKey = ByteArray(32) { ((it + 4) and 0xFF).toByte() },
                    receiveMessageNumber = 0u,
                    skipped = emptyMap(),
                ),
                receiveChains = emptyList(),
            ),
        )
"""

main(FICHERO, lambda: aplicar(FICHERO, [(VIEJO, NUEVO, 1)]))
