#!/usr/bin/env python3
"""
p4_m4_a.py — M4-a (spec): un byte del HEADER (que va dentro del AAD) deja de
estar autenticado porque el AAD se construye SIN los campos del ratchet.

POR QUE ESTA MUTACION Y NO "cambiar el tag"
===========================================
El checkpoint v1 del wire format exige que el AAD cubra los 44 bytes del header:
`O6-04` altera `messageNumber` y espera que el descifrado FALLE, `O6-05` hace lo
mismo con `previousChainLength` y `O6-06` con `dhPublicKey`. Si el AAD deja de
incluir esos campos —se rellenan con ceros— el tag (calculado sobre el AAD sin
alterar) sigue cuadrando y un frame TAMPERED se ACEPTA. Eso es exactamente "el
frame alterado NO se rechaza", que es lo que ataca esta mutacion.

La mutacion COMPILA: los offsets y el largo de 44 bytes se conservan (para no
romper `O6-20`), lo unico que cambia es que los campos del header se escriben
como cero en vez de con su valor real. `ratchetHeader` sigue usandose en el
parametro (la firma no cambia); el cuerpo simplemente ignora sus valores.

Debe dejar en rojo (la autenticacion del header deja de funcionar):
  - O6-04 `messageNumber esta autenticado`
  - O6-05 `PN esta autenticado`
  - O6-06 `dhPublicKey esta autenticado`
"""

import os
import sys

sys.path.insert(0, os.path.join(
    os.path.dirname(os.path.dirname(os.path.abspath(__file__))),
    "mutaciones_p2",
))

from _comun_p2 import main, aplicar  # noqa: E402

F_CODEC = "km-core/src/main/kotlin/com/km/frame/SecureFrameCodec.kt"

VIEJO = """        System.arraycopy(
            ratchetHeader.dhPublicKey, 0,
            out, SecureFrameSpec.DH_PUBLIC_KEY_OFFSET,
            SecureFrameSpec.DH_PUBLIC_KEY_LENGTH,
        )
        writeUint32(out, SecureFrameSpec.PREVIOUS_CHAIN_LENGTH_OFFSET, ratchetHeader.previousChainLength)
        writeUint32(out, SecureFrameSpec.MESSAGE_NUMBER_OFFSET, ratchetHeader.messageNumber)
        return out
    }"""

NUEVO = """        // MUTACION M4-a: los campos del ratchet NO entran en el AAD. El largo
        // sigue siendo 44, pero los bytes van a cero: alterar messageNumber/PN o
        // dhPublicKey deja el AAD igual y el tag sigue cuadrando.
        @Suppress("UNUSED_EXPRESSION")
        ratchetHeader
        writeUint32(out, SecureFrameSpec.PREVIOUS_CHAIN_LENGTH_OFFSET, 0u)
        writeUint32(out, SecureFrameSpec.MESSAGE_NUMBER_OFFSET, 0u)
        return out
    }"""


def fn():
    aplicar(F_CODEC, [(VIEJO, NUEVO, 1)])


if __name__ == "__main__":
    main([F_CODEC], fn)
