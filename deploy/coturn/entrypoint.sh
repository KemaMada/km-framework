#!/bin/sh
# ---------------------------------------------------------------------------
# Entrypoint de KeyMessage TURN.
#
# QUE HACE
#  1. Exige las variables del fichero de entorno. Sin ellas NO arranca: una
#     plantilla con placeholders es peor que un fallo, porque parece desplegada.
#  2. Renderiza la configuracion a /run (tmpfs del contenedor) con permisos 600.
#  3. Arranca coturn en primer plano.
#
# POR QUE NO SE USAN VARIABLES DE `docker run -e`
#  `docker inspect` muestra el entorno del contenedor. El fichero se MONTA
#  (0600 en el host) y se lee aqui, de modo que la credencial no queda en la
#  configuracion del contenedor ni en `docker inspect`.
# ---------------------------------------------------------------------------
set -eu

ENV_FILE="${KM_TURN_ENV_FILE:-/run/keymessage-turn.env}"
TEMPLATE="${KM_TURN_TEMPLATE:-/etc/coturn/turnserver.conf.template}"
# `/run` no es escribible cuando el contenedor corre con el uid del dueno del
# fichero de entorno (que es lo que permite mantenerlo en 0600). `/tmp` es
# privado del contenedor y no se publica.
OUT="${KM_TURN_CONF:-/tmp/turnserver.conf}"

if [ ! -r "$ENV_FILE" ]; then
  echo "FALLO: no se puede leer el fichero de entorno ($ENV_FILE)." >&2
  echo "       Debe existir en el host en 0600 y montarse de solo lectura." >&2
  exit 78
fi

# shellcheck disable=SC1090
set -a
. "$ENV_FILE"
set +a

: "${TURN_REALM:?FALTA TURN_REALM en el fichero de entorno}"
: "${TURN_SECRET:?FALTA TURN_SECRET: sin el no hay autenticacion TURN}"

# Render sin `envsubst` (no esta en la imagen): `sed` con delimitador `|`.
# El secreto generado es base64/base64url, sin `|`; si se rota con un valor que
# lo contenga, el arranque falla en vez de escribir una configuracion a medias.
render() {
  sed \
    -e "s|__TURN_REALM__|${TURN_REALM}|g" \
    -e "s|__TURN_SECRET__|${TURN_SECRET}|g" \
    "$TEMPLATE" > "$OUT"
}

case "${TURN_REALM}${TURN_SECRET}" in
  *"|"*) echo "FALLO: un valor del entorno contiene '|'; no se puede renderizar." >&2; exit 78 ;;
esac

render
chmod 600 "$OUT"

# `-c` con la configuracion renderizada. El pidfile vacio evita escribir en /.
exec turnserver -c "$OUT" --pidfile=
