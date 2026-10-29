#!/bin/sh
# ---------------------------------------------------------------------------
# Guardia de SECRETOS del despliegue TURN.
#
# Falla si:
#  1. la plantilla lleva un `static-auth-secret`/`user` con valor real (solo se
#     admiten los placeholders __TURN_*__);
#  2. el ejemplo de entorno lleva valores que no sean los de "cambia esto";
#  3. algun modulo de produccion del cliente hardcodea una URL ICE.
#
# Se ejecuta desde cualquier sitio: resuelve el repo desde su propia ruta.
# Uso: deploy/coturn/check-no-secrets.sh
# ---------------------------------------------------------------------------
set -eu

AQUI=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
RAIZ=$(CDPATH= cd -- "$AQUI/../.." && pwd)
FALLOS=0

fallo() {
  echo "FALLO: $*" >&2
  FALLOS=1
}

# 1. Plantilla: los unicos valores admitidos son los placeholders.
REALES=$(grep -nE '^(static-auth-secret|user)=' "$AQUI/turnserver.conf" 2>/dev/null \
  | grep -vE '__TURN_(SECRET|USER|CREDENTIAL)__' || true)
if [ -n "$REALES" ]; then
  fallo "turnserver.conf lleva un valor concreto:"
  printf '%s\n' "$REALES" >&2
fi

# 2. El ejemplo no puede llevar secretos "de verdad".
if grep -nE '^(TURN_CREDENTIAL|TURN_SECRET)=' "$AQUI/turn.env.example" 2>/dev/null \
  | grep -vE '=cambia-esto' >/dev/null; then
  fallo "turn.env.example lleva valores que no son de ejemplo"
fi

# 3. Ningun cliente hardcodea una URL ICE.
LITERALES=$(grep -rnE '"(stun|stuns|turn|turns):[^"$]' \
  "$RAIZ/km-core/src/main" \
  "$RAIZ/km-transport/src/main" \
  "$RAIZ/km-webrtc/src/main" \
  "$RAIZ/km-desktop/src/main" 2>/dev/null || true)
if [ -n "$LITERALES" ]; then
  fallo "URL ICE hardcodeada en produccion:"
  printf '%s\n' "$LITERALES" >&2
fi

if [ "$FALLOS" -ne 0 ]; then
  exit 1
fi
echo "OK: sin secretos en deploy/coturn ni URLs ICE hardcodeadas"
