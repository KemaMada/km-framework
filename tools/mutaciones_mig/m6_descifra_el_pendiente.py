#!/usr/bin/env python3
"""
M6 — DESCIFRAR el pendiente durante la restauracion.

`SecureTransmitJournal.restaurar()` rehidrata la bandeja y acto seguido "abre"
cada frame pendiente para comprobar que es recuperable. Abrir un frame que
venia de una clave RETENIDA la CONSUME: `SymmetricRatchet.previewReceive`
hace `skipped.remove(key)`, y el propio codigo avisa de que esa retirada no se
deshace aunque el descifrado falle.

El resultado es la forma mas cruel de esta lista de propiedades: la bandeja
sobrevive CON SUS BYTES, y a la vez es IRRECUPERABLE. Nada de lo que se
persiste se ha reescrito, asi que cualquier prueba que mire "los bytes del
pendiente" ve verde mientras el mensaje ya no se puede abrir jamas.

PROPIEDAD QUE DEBE ROMPER: preservacion byte a byte del pendiente — y, con
ella, la recuperabilidad de D-5.
"""
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from _mig import main, aplicar

FICHERO = "km-core/src/main/kotlin/com/km/transmit/SecureTransmitJournal.kt"

VIEJO = """        inbox = PendingInbox.rehidratada(unidad.pendingInbound)
        return unidad.outbound"""

NUEVO = """        inbox = PendingInbox.rehidratada(unidad.pendingInbound)
        // MUTACION M6: la restauracion DESCIFRA el pendiente "para comprobar que
        // es recuperable". Abrirlo consume la clave retenida que lo abria, y esa
        // retirada no se deshace: la bandeja conserva sus bytes y ya no abre.
        run {
            val comprobador = com.km.frame.SecureRatchetProtocol(
                session,
                com.km.frame.SecureFrameProtector(
                    com.km.crypto.provider.BcChaCha20Poly1305(),
                    com.km.crypto.provider.BcHkdfSha256(),
                ),
            )
            for (entrada in inbox.entradas()) {
                runCatching { comprobador.decrypt(entrada.wireFrame) }
            }
        }
        return unidad.outbound"""

main(FICHERO, lambda: aplicar(FICHERO, [(VIEJO, NUEVO, 1)]))
