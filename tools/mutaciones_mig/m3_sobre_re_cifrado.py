#!/usr/bin/env python3
"""
M3 — RE-CIFRAR / re-encolar el sobre al cambiar de medio.

`TransportChain.sendData` deja de entregar al eslabon los bytes que le dio la
sesion: le entrega esos bytes MAS el nombre del medio. El sobre se REGENERA en
cada salto, asi que los mismos bytes de la sesion salen distintos por cada
medio, y lo que sale por el medio nuevo ya no es el frame queProdujo el
ratchet.

POR QUE ESTE SITIO Y NO EL DEL CIFRADO REAL: km-core no conserva el plaintext
detras de `SecureMessagingSession.send()` —no hay a quien re-cifrar—, y la
cadena no tiene AEAD. La condicion que la tabla nombra es `ciphertext_pre ==
ciphertext_post`, y esta es la unica mutacion que la rompe por el camino de
produccion que la exercise: los bytes que salen del ratchet y los que recibe
el medio dejan de ser los mismos.

PROPIEDAD QUE DEBE ROMPER: `ciphertext_pre == ciphertext_post`.
"""
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from _mig import main, aplicar

FICHERO = "km-core/src/main/kotlin/com/km/node/TransportChain.kt"

VIEJO = """            val result = try {
                transport.sendData(remotePeerId, data)
            } catch (e: Throwable) {"""

NUEVO = """            val result = try {
                // MUTACION M3: el sobre se REGENERA en cada salto. El eslabon
                // recibe los bytes del ratchet MAS su propio nombre, asi que el
                // mismo frame sale distinto por cada medio y lo que llega al
                // extremo remoto no es lo que salio del ratchet.
                transport.sendData(remotePeerId, data + transport.name.toByteArray())
            } catch (e: Throwable) {"""

main(FICHERO, lambda: aplicar(FICHERO, [(VIEJO, NUEVO, 1)]))
