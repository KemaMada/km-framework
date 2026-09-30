#!/bin/bash
# El arnés de la campaña de mutaciones de `MIG-*`, en una sola llamada por
# mutación. Cuatro pasos, y el orden es el invariante:
#
#   1. CON MUTACIÓN  — mide si los 11 tests `MIG-*` la detectan, y en la misma
#      corrida mide la PRUEBA DE CONDICIÓN, que tiene que salir VERDE: si el
#      código mutado no hace lo que la mutación dice, la medición no vale.
#   2. CONTROL NEGATIVO — revierte y vuelve a medir SOLO la prueba de condición.
#      Tiene que salir ROJA: una prueba que sale verde con y sin mutación no
#      prueba nada, y esa es la forma barata defalsear un detector.
#   3. `git status` sobre `src/main` tiene que salir VACÍO.
#
# Un solo patrón `--tests` por invocación. Nunca una lista por comas.
set -u
M="$1"
SCRIPT="tools/mutaciones_mig/${M}.py"

echo "############################## ${M} — CON MUTACIÓN"
python3 tools/mutprobe.py --label "${M}-con-mutacion" \
    --pattern 'com.km.messaging.Transport*' \
    --mutate "$SCRIPT" --revert "$SCRIPT"
echo "HARNESS_CON_MUTACION=$?"
echo "---- qué tests detectaron (leído del XML NUEVO que el arnés ya validó) ----"
python3 tools/mutaciones_mig/fallos_xml.py km-core

echo "############################## ${M} — CONTROL NEGATIVO (revertida)"
python3 tools/mutprobe.py --label "${M}-control" \
    --pattern 'com.km.messaging.TransportMigrationProbe'
echo "HARNESS_CONTROL=$?"

echo "############################## ${M} — PRODUCCIÓN"
git status --short -- km-core/src/main km-webrtc/src/main
echo "(vacío arriba = producción idéntica a HEAD)"
