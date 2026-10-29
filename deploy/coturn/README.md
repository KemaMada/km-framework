# coturn para KeyMessage

STUN/TURN del tier P2P de **escritorio**. No lo usa Android en este cambio:
la app sigue fuera de alcance y no se modifica.

## Que hay aqui

| Fichero | Para que |
|---|---|
| `turnserver.conf` | PLANTILLA sin secretos. `entrypoint.sh` la renderiza. |
| `entrypoint.sh` | Exige el entorno, renderiza en `/run` (0600) y arranca. |
| `docker-compose.yml` | `coturn/coturn:4.6.2-r4`, `network_mode: host`. |
| `turn.env.example` | Forma del fichero de entorno (valores falsos). |
| `keymessage-turn.service` | Unidad systemd (requiere root para instalarla). |

Puertos por defecto de esta plantilla: **3479/udp+tcp** y relay
**55000-55999/udp**. Se eligen asi para convivir con un coturn nativo en
3478/5349; un cliente se configura por entorno y el numero no forma parte del
protocolo. Para usar los estandar, cambia `listening-port` y el rango.

## Despliegue

    sudo install -d -m 0755 /opt/keymessage
    sudo install -d -m 0755 /etc/keymessage
    sudo install -m 0600 /dev/null /etc/keymessage/turn.env
    sudoedit /etc/keymessage/turn.env        # rellenar desde turn.env.example
    sudo install -m 0644 deploy/coturn/keymessage-turn.service /etc/systemd/system/
    sudo systemctl daemon-reload
    sudo systemctl enable --now keymessage-turn

Sin systemd (o sin root) se puede levantar a mano desde este directorio:

    docker run -d --name km-coturn --restart unless-stopped --network host \
      -v "$PWD/turnserver.conf:/etc/coturn/turnserver.conf.template:ro" \
      -v "$PWD/entrypoint.sh:/opt/keymessage/entrypoint.sh:ro" \
      -v /ruta/turn.env:/run/keymessage-turn.env:ro \
      --entrypoint /bin/sh coturn/coturn:4.6.2-r4 /opt/keymessage/entrypoint.sh

`docker compose` no esta disponible en todos los hosts; el `docker run` de
arriba es equivalente.

## Firewall

Docker **no** abre el firewall del host. Si el puerto no responde desde fuera,
falta abrirlo (requiere root; no se puede hacer desde una sesion sin sudo):

    # ufw
    sudo ufw allow 3479/udp
    sudo ufw allow 3479/tcp
    sudo ufw allow 55000:55999/udp

    # firewalld
    sudo firewall-cmd --permanent --add-port=3479/udp
    sudo firewall-cmd --permanent --add-port=3479/tcp
    sudo firewall-cmd --permanent --add-port=55000-55999/udp
    sudo firewall-cmd --reload

    # nftables (ejemplo)
    sudo nft add rule inet filter input udp dport 3479 accept
    sudo nft add rule inet filter input tcp dport 3479 accept
    sudo nft add rule inet filter input udp dport 55000-55999 accept

En un proveedor cloud hay ademas un firewall externo (security group): abrir
los mismos puertos alli. **Sin eso no hay verificacion externa posible.**

## TLS (`turns:`)

Solo es viable con un certificado real y su clave legibles por el contenedor.
Con Let's Encrypt:

    sudo install -d -m 0755 /etc/keymessage/tls
    sudo cp /etc/letsencrypt/live/<dominio>/fullchain.pem /etc/keymessage/tls/
    sudo cp /etc/letsencrypt/live/<dominio>/privkey.pem   /etc/keymessage/tls/
    sudo chmod 0640 /etc/keymessage/tls/privkey.pem
    # descomentar tls-listening-port/cert/pkey en turnserver.conf
    # montar /etc/keymessage/tls:/etc/keymessage/tls:ro en el compose
    # abrir el puerto TLS (5350/tcp por defecto)

Puerto TLS: la plantilla usa **5350/tcp** para no chocar con un coturn nativo.
En redes que filtran UDP (o el rango de relay) conviene exponerlo en **443/tcp**,
que casi nunca esta bloqueado; el cliente lo selecciona con
`turns:turn.example.org:443?transport=tcp` (ver `docs/developer/p2p.md`). Para
escuchar en 443:

- el proceso necesita privilegio de puerto bajo (`CAP_NET_BIND_SERVICE` o
  `user: "0:0"` en el compose), o
- un proxy/balanceador TLS delante (nginx/HAProxy) que termine TLS y reenvie a
  5350. En ese caso el certificado lo presenta el proxy, no coturn.

443/tcp suele estar ocupado por el servidor web del host: comprobar antes con
`ss -ltnp | grep :443`. `turns:` es **opcional**: sin certificado no se activa.

Sin certificado NO se activa `tls-listening-port`: coturn no arranca y el fallo
se ve en `docker logs` como un error de PEM.

## Verificacion

Las credenciales son EFIMERAS. Se derivan del secreto con el mismo calculo que
hace el cliente (`IceConfig.restCredential`):

    SECRET="$(sed -n 's/^TURN_SECRET=//p' /etc/keymessage/turn.env)"
    TURN_USER="$(($(date +%s) + 3600)):kmdesktop"
    TURN_CREDENTIAL="$(printf '%s' "$TURN_USER" | openssl dgst -sha1 -hmac "$SECRET" -binary | base64)"

Desde FUERA del host (no vale `localhost`):

    turnutils_stunclient -p 3479 13.140.155.230
    turnutils_uclient -T -u "$TURN_USER" -w "$TURN_CREDENTIAL" \
        -p 3479 13.140.155.230 13.140.155.230
    # con TLS:
    turnutils_uclient -S -T -u "$TURN_USER" -w "$TURN_CREDENTIAL" \
        -p 5350 13.140.155.230 13.140.155.230

`uclient` tiene que mostrar un candidato `relay` asignado. `stunclient` solo
prueba STUN: que responda NO demuestra que TURN asigne relay.

La verificacion INTERNA (mismo host, `127.0.0.1`) prueba la AUTENTICACION sin
depender del firewall:

    timeout 15 turnutils_uclient -T -u "$TURN_USER" -w "$TURN_CREDENTIAL" \
        -p 3479 127.0.0.1 127.0.0.1

Si sale `Cannot find credentials of user <...>`, la credencial no se derivo con
el secreto vigente o el contenedor no recarg'o `TURN_SECRET` (reiniciar). NO
anadas `user=` al fichero: con `static-auth-secret` coturn lo ignora.

Alternativa sin `turnutils`: un binding request STUN crudo por UDP (ver
`docs/developer/p2p.md`), que tambien distingue "el puerto responde" de "el
firewall lo descarta".

## Lo que este despliegue NO hace

- No abre puertos del host ni del proveedor: eso exige root o consola cloud.
- No reinicia nginx ni toca el relay WebSocket.
- No rota credenciales solo: hay que reiniciar el contenedor y actualizar los
  escritorios.
