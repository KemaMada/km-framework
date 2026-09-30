#!/usr/bin/env python3
"""
M8 — el medio RETIRADO sigue entregando.

`TransportChain.acceptsInbound()` deja de preguntar por la seleccion vigente
y responde "si, acepto" a cualquiera que este en la lista configurada. Un P2P
retirado sigue pudiendo inyectar datos en la sesion, indistinguible de un
atacante: el receptor no tiene forma de saber de donde vinieron.

PROPIEDAD QUE DEBE ROMPER: aislamiento del transporte antiguo. Un medio perdedor
que siguiera entregando seria un segundo camino oculto hacia la sesion.
"""
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from _mig import main, aplicar

FICHERO = "km-core/src/main/kotlin/com/km/node/TransportChain.kt"

VIEJO = """    fun acceptsInbound(transport: String): Boolean {
        val est = selected ?: return false
        return est.transportName == transport && est.state == EstablishmentState.READY
    }"""

NUEVO = """    fun acceptsInbound(transport: String): Boolean {
        // MUTACION M8: la retirada no se consulta. Todo lo que esta en la lista
        // configurada acepta datos,y tambien a los medios perdedores.
        return transports.any { it.name == transport }
    }"""

main(FICHERO, lambda: aplicar(FICHERO, [(VIEJO, NUEVO, 1)]))
