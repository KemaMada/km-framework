#!/usr/bin/env python3
"""
`mutprobe.py` con el directorio de resultados de km-webrtc.

No es un arnés nuevo: IMPORTA el de la FASE 3 y le cambia UNA cosa, el
directorio donde se buscan los XML. Todo lo demás — el `rm -rf` previo, el unico
patrón `--tests`, la exigencia de un XML NUEVO, el `INVALIDA(sin XML)`, el
`exit` y el conteo — es el mismo código, para que comparar una campaña de
km-core con otra de km-webrtc no sea comparar dos arnes distintos.
"""
import os
import sys

RAIZ = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
sys.path.insert(0, os.path.join(RAIZ, "tools"))

import mutprobe  # noqa: E402

mutprobe.RESULTS = os.path.join(RAIZ, "km-webrtc", "build", "test-results", "test")

if __name__ == "__main__":
    sys.exit(mutprobe.main())
