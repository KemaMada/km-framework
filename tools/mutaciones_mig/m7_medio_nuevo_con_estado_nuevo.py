#!/usr/bin/env python3
"""
M7 — el medio ganador se crea CON ESTADO NUEVO.

Cuando una ronda de establecimiento concede la autoridad, la cadena ADEMAS le
reabre el binding al medio ganador (`onCreateBinding`). Es decir: el medio
nuevo no hereda el contexto autenticado que ya tenia el binding, se le abre
otro. En un protocolo de transporte esto es una reatacion encubierta: el
`PeerContext` autenticado es uno, y el medio nuevo cree que arranca de cero.

POR QUE ESTE SITIO: `TransportChain.establish()` es la UNICA operacion de
km-core donde ocurre "el medio nuevo". Es la migracion de 3Q.5.3. No hay ningun
otro gancho de produccion al que colgar "el transporte nuevo".

PROPIEDAD QUE DEBE ROMPER: P-5, el medio nuevo se monta sobre el estado
criptografico que ya tenia, no sobre uno recien creado.
"""
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from _mig import main, aplicar

FICHERO = "km-core/src/main/kotlin/com/km/node/TransportChain.kt"

VIEJO = """                selected = est
                return EstablishmentResult.Ready(attempt, attempts.toList())"""

NUEVO = """                selected = est
                // MUTACION M7: el medio ganador se trae con estado NUEVO. Se le
                // reabre el binding en vez de heredar el contexto ya autenticado.
                foldAll("onCreateBinding") { it.onCreateBinding(localIdentity, remotePeerId) }
                return EstablishmentResult.Ready(attempt, attempts.toList())"""

main(FICHERO, lambda: aplicar(FICHERO, [(VIEJO, NUEVO, 1)]))
