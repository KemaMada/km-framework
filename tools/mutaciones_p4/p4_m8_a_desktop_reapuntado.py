#!/usr/bin/env python3
"""
p4_m8_a_desktop_reapuntado.py — M8-a (bis): el escritorio vuelve a una COPIA local del motor.

`p4_m8_desktop_dup.py` prueba que la guardia R-8.a detecta la EXISTENCIA de un
segundo cliente de wire. Esta prueba la otra mitad: que `DesktopMain` no este
compilado contra esa copia.

POR QUE NO BASTA CON LA MUTACION ANTERIOR
=========================================
Anhadir `com.km.desktop.JvmRelayTransport` al arbol NO cambia el POOL DE
CONSTANTES de `DesktopMainKt`: el `import` sigue resuelve a `com.km.transport`.
La guardia de bytecode se quedaria en verde, y eso seria correcto —no se ha
movido nada— pero no demonstraria que la asercion tiene dientes.

Aqui la copia se ADJUNTA y `DesktopMain` se REAPUNTA a ella: el escritorio vuelve
a compilar contra un cliente de wire que no es el de `:km-transport`. Con eso las
DOS aserciones de `DesktopWireEngineReuseTest` deben ponerse rojas:

  - `R-8a no hay un segundo cliente de wire en com-km-desktop`  (la clase existe)
  - `R-8a el punto de entrada del escritorio referencia al motor del modulo hermano`
    (el pool de `DesktopMainKt` dice `com/km/desktop/JvmRelayTransport`)

Y los dos controles deben seguir en VERDE: si un control se puso rojo, la
guardia esta midiendo otra cosa.

La copia es una FACHADA que delega en el motor del modulo hermano. No es el
motor duplicado entero —copiar 1090 lineas dentro del repositorio para tirar de
ellas al terminar no aporta nada a la prueba—: es el caso estructuralmente
equivalente que la guardia tiene que rejectar, que es "el escritorio compila
contra una clase suya".
"""

import os
import sys

sys.path.insert(0, os.path.join(
    os.path.dirname(os.path.dirname(os.path.abspath(__file__))),
    "mutaciones_p2",
))

from _comun_p2 import main, aplicar  # noqa: E402

F_COPIA = "km-desktop/src/main/kotlin/com/km/desktop/CopiaLocalDelMotorMutada.kt"
F_MAIN = "km-desktop/src/main/kotlin/com/km/desktop/DesktopMain.kt"

COPIA = '''package com.km.desktop

import com.km.model.IdentityId
import com.km.model.RelayExpiredNotice
import com.km.model.StoredReceipt
import com.km.protocol.Transport
import com.km.transport.RelayStatus

/**
 * MUTACION M8-a-bis: un cliente de wire LOCAL en el escritorio.
 *
 * Compila porque delega en el motor del modulo hermano, pero el escritorio
 * queda compilado contra ESTA clase. Es exactamente lo que R-8.a prohibe: dos
 * clientes de wire, dos protocolos y dos `MAX_RESEND`.
 */
class JvmRelayTransport(
    private val inner: com.km.transport.JvmRelayTransport,
) : Transport by inner {

    constructor(
        identity: IdentityId,
        ed25519PublicKey: ByteArray,
        ed25519PrivateKey: ByteArray,
        endpoint: String = DEFAULT_ENDPOINT,
        relayIdentity: IdentityId? = null,
        log: (String) -> Unit = {},
    ) : this(com.km.transport.JvmRelayTransport(
        identity = identity,
        ed25519PublicKey = ed25519PublicKey,
        ed25519PrivateKey = ed25519PrivateKey,
        endpoint = endpoint,
        relayIdentity = relayIdentity,
        log = log,
    ))

    val name: String get() = inner.name

    fun status(): RelayStatus = inner.status()
    fun close() = inner.close()
    fun setStoredHandler(handler: (StoredReceipt) -> Unit) = inner.setStoredHandler(handler)
    fun setRelayExpiredHandler(handler: (RelayExpiredNotice) -> Unit) = inner.setRelayExpiredHandler(handler)

    companion object {
        const val DEFAULT_ENDPOINT: String = com.km.transport.JvmRelayTransport.DEFAULT_ENDPOINT
    }
}
'''

IMPORT_VIEJO = """import com.km.transport.JvmRelayTransport
import com.km.transport.RelayDataCodec"""

IMPORT_NUEVO = """import com.km.desktop.JvmRelayTransport
import com.km.transport.RelayDataCodec"""


def fn():
    # El fichero nuevo no lo respalda `asegurar()`: se crea y se borra. El
    # `main` de la comun lo revierte y valida md5 sobre lo que SI tiene copia.
    ruta = os.path.join(
        os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__)))),
        F_COPIA,
    )
    with open(ruta, "w", encoding="utf-8") as fh:
        fh.write(COPIA)
    aplicar(F_MAIN, [(IMPORT_VIEJO, IMPORT_NUEVO, 1)])


if __name__ == "__main__":
    main([F_MAIN], fn)
