#!/usr/bin/env python3
"""
verify_attack_messageid.py — ATAQUE PROPIO DEL VERIFICADOR sobre E2E-01.

PREGUNTA DEL VERIFICADOR
========================
"¿Puede el `messageId` no coincidir?" El E2E afirma, en la linea 323:

    assertEquals(creado.messageId, entregado.messageId, ...)

Este ataque comprueba que esa afirmacion tiene DIENTES: que si el `messageId`
que viaja dentro del sobre cifrado no es el que el emisor escribio, el E2E lo ve.

COMO SE ATACA
=============
`MessageEnvelopeCodec.uuidFromBytes` reconstruye el `MessageId` a partir de los
16 bytes que van en el sobre. Se muta para que devuelva un UUID VALIDO pero
DISTINTO —los dos longs invertidos a nivel de bits—.

Por que esto y no romper la AEAD:
  - El frame cifrado sigue intacto: el header de 44 B y la autenticacion no
    cambian, luego `BinarySecureFrameCodec.decode` sigue dando `MESSAGE` y el
    `wire` sigue siendo > 44 B. Las aserciones 4.a/6 del E2E quedan VERDES: el
    byte SI llega y SI es un SecureFrame.
  - La entrega tambien ocurre: `delivered == 1`, `rejectedFrames == 0`. El
    unico hecho que cambia es el `messageId`.

Es decir: es el ataque MAXIMAMENTE localizado a la pregunta. Si el E2E solo
mirara "llego algo", seguiria verde; solo la asercion de identidad lo pinza.

La mutacion COMPILA: `bytes` se sigue leyendo, `MessageId`, `UUID` y `ByteBuffer`
siguen usandose, y no se introduce ningun simbolo inexistente.
"""

import os
import sys

sys.path.insert(0, os.path.join(
    os.path.dirname(os.path.dirname(os.path.abspath(__file__))),
    "mutaciones_p2",
))

from _comun_p2 import main, aplicar  # noqa: E402

F_ENV = "km-core/src/main/kotlin/com/km/messaging/MessageEnvelope.kt"

VIEJO = """        val bb = ByteBuffer.wrap(bytes)
        return MessageId(UUID(bb.long, bb.long))"""

NUEVO = """        val bb = ByteBuffer.wrap(bytes)
        // ATAQUE DEL VERIFICADOR: el id que se reconstruye NO es el que el
        // emisor escribio (los dos longs invertidos a nivel de bits). Sigue
        // siendo un UUID valido, el frame sigue descifrando y entregando; lo
        // unico que cambia es la identidad del mensaje.
        return MessageId(UUID(bb.long.inv(), bb.long.inv()))"""


def fn():
    aplicar(F_ENV, [(VIEJO, NUEVO, 1)])


if __name__ == "__main__":
    main([F_ENV], fn)
