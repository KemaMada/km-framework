#!/usr/bin/env python3
"""
M4 — alterar `N` y `PN` durante la restauracion (y por tanto durante la
migracion).

`DoubleRatchetSession.restore()` deja de copiar los contadores tal cual: los
incrementa. El estado restaurado sigue siendo COHERENTE consigo mismo (el `Ns`
del campo y el de la cadena de envio se mueven juntos, que es lo que exige la
`require` de `DoubleRatchetSnapshot`), asi que nada se queja al construirlo: lo
unico que ha cambiado es que la sesion que vuelve a arrancar esta mas
adelantada de lo que estaba.

PROPIEDAD QUE DEBE ROMPER: el estado del ratchet intacto. `N` no es
decorativo: va DENTRO del frame (`SecureFrameSpec.MESSAGE_NUMBER_OFFSET`), asi
que un `N` corrido produce un frame que el receptor ve como un salto que nadie
marcó.
"""
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from _mig import main, aplicar

FICHERO = "km-core/src/main/kotlin/com/km/ratchet/DoubleRatchetSession.kt"

VIEJO = """        sendMessageNumber = s.sendMessageNumber
        previousChainLength = s.previousChainLength
        sendChain = ratchetFrom(s.sendChain)"""

NUEVO = """        sendMessageNumber = s.sendMessageNumber + 1u
        previousChainLength = s.previousChainLength + 1u
        sendChain = ratchetFrom(s.sendChain)"""

main(FICHERO, lambda: aplicar(FICHERO, [(VIEJO, NUEVO, 1)]))
