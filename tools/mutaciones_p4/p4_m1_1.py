#!/usr/bin/env python3
"""
p4_m1_1.py — M de la tarea 1.1: `LegacyInboundFrame.equals` pasa a ser
IDENTIDAD (comparacion por REFERENCIA) en vez de igualdad por contenido.

Por que esta y no "anadir el messageId a la igualdad": esa otra mutacion se
intento PRIMERO y resulto ser un NO-OP, y el arnes lo dijo con claridad
(`NO DETECTADA`). La razon es que `messageId` es DERIVADO de `encoded`, luego
`encoded == other.encoded` ya implica `messageId == other.messageId`: la
comparacion anadida no cambia un solo caso. Una mutacion que no cambia nada es
la forma mas cara de no medir nada, y por eso esta lo dice en su propio modulo.

La mutacion REALISTA es la de referencia, y es la clasica cuando el sobre
envuelve un `ByteArray` (aqui `Message.payload`): la tentacion de comparar
identidad para no recorrer los bytes. Con ella, dos marcos del MISMO byte dejan
de ser iguales, y un `Set` o un `distinct` de marcos trataria una reentrega
como un byte nuevo.

`hashCode` se deja coherente con la nueva `equals` (identidad del objeto) para
que la mutacion no falle por una asercion distinta de la que persigue.

Debe fallar LF-01.
"""

import os
import sys

sys.path.insert(0, os.path.join(
    os.path.dirname(os.path.dirname(os.path.abspath(__file__))),
    "mutaciones_p2",
))

from _comun_p2 import main, aplicar  # noqa: E402

F_FRAME = "km-core/src/main/kotlin/com/km/node/LegacyInboundFrame.kt"

VIEJO = """    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is LegacyInboundFrame) return false
        return from == other.from && encoded == other.encoded
    }

    /** Hash coherente con [equals]: el de `from` y el de [encoded]. */
    override fun hashCode(): Int = 31 * from.hashCode() + encoded.hashCode()"""

NUEVO = """    // MUTACION: igualdad por REFERENCIA. Dos marcos del mismo byte dejan de
    // ser iguales, y el hash pasa a ser identidad para que la clase no sea
    // incoherente consigo misma.
    override fun equals(other: Any?): Boolean = this === other

    override fun hashCode(): Int = System.identityHashCode(this)"""


def fn():
    aplicar(F_FRAME, [(VIEJO, NUEVO, 1)])


if __name__ == "__main__":
    main([F_FRAME], fn)