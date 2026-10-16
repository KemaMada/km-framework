#!/usr/bin/env python3
"""
M-1 — restaurar el guard viejo `peerContext.authSession?.state != AUTHENTICATED`
en `SecureMessagingSession.send` y en `sendTranscript`.

COMPILA porque `authSession` es nullable desde 2.1, que es justo lo que hace
posible esta mutacion: antes de p2 el campo no lo era y este texto no se
podria ni escribir.

Y es la que demuestra por que el guard de R-1 TIENE que cambiar: con el guard
viejo, un `ContactVerified` no puede enviar porque no tiene `AuthSession` en
`AUTHENTICATED` —`authSession` es `null`—, luego el camino de contacto queda
cerrado por el guard, no por la criptografia.

Debe fallar CS-02 y CS-03 de `ContactSessionE2ETest`.
"""

import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

from _comun_p2 import F_MENSAJERIA, aplicar, main  # noqa: E402

VIEJO_SEND = """        if (!peerContext.authority.authorizesPeerSession()) {
            return SendResult.Rejected(SendReject.NOT_AUTHENTICATED)
        }
        // 2. El ratchet produce los bytes."""

NUEVO_SEND = """        // MUTACION M-1: vuelve el guard por estado de AuthSession
        if (peerContext.authSession?.state != com.km.auth.AuthSessionState.AUTHENTICATED) {
            return SendResult.Rejected(SendReject.NOT_AUTHENTICATED)
        }
        // 2. El ratchet produce los bytes."""

VIEJO_TRANSCRIPT = """        if (!peerContext.authority.authorizesPeerSession()) {
            return SendResult.Rejected(SendReject.NOT_AUTHENTICATED)
        }
        val result = transport.sendData(localIdentity, peerContext, wire)"""

NUEVO_TRANSCRIPT = """        // MUTACION M-1: vuelve el guard por estado de AuthSession
        if (peerContext.authSession?.state != com.km.auth.AuthSessionState.AUTHENTICATED) {
            return SendResult.Rejected(SendReject.NOT_AUTHENTICATED)
        }
        val result = transport.sendData(localIdentity, peerContext, wire)"""


def fn():
    aplicar(F_MENSAJERIA, [(VIEJO_SEND, NUEVO_SEND, 1), (VIEJO_TRANSCRIPT, NUEVO_TRANSCRIPT, 1)])


if __name__ == "__main__":
    main([F_MENSAJERIA], fn)