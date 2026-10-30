# P2P (WebRTC + coturn): escritorio y Android

Documento del tier P2P en los DOS clientes. Desde `android-p2p`, el movil
tambien hace P2P con el mismo `ChainedTransport` y el mismo coordinador; lo
unico especifico de plataforma es el backend nativo (`dev.onvoid.webrtc` en
escritorio, `io.github.webrtc-sdk` en Android).

## Que hay y que no

Hay:

- `km-core` puro: `ChainedTransport`, `P2pProvider`, `PeerSignalPort`,
  `IceConfig`, `P2pMode`/`P2pPlan` (la politica es UNA, compartida), motivos
  estables.
- `km-transport`: composicion `[P2P, relay]` por par, coordinador con
  presupuesto de 8 s, reintento armado con jitter y puerto de senalizacion por
  SIGNAL.
- `km-webrtc`: `RealWebRtcTransport` con `iceServers` reales y
  `WebRtcP2pProvider` sobre `ManagedWebRtcPeer` (escritorio).
- `km-webrtc-android`: `AndroidWebRtcBackend` + `AndroidP2pProvider` sobre
  `org.webrtc`; una `PeerConnectionFactory` por proceso, DataChannel unico.
- `km-desktop` y `app`: leen el modo, componen y declaran el medio.

No hay (ni se promete):

- ACK por P2P. **El ACK viaja SIEMPRE por el relay**; el medio de datos no
  cambia la correlacion (`originalMessageId`) ni la sesion cifrada.
- Seleccion manual de ruta por contacto, multi-dispositivo, store-and-forward,
  Tor/.onion, federacion.
- Filtro de candidatos ICE. Un candidato `host`/`srflx` puede revelar la IP
  local o publica del par. `auto` sin TURN **no intenta** P2P precisamente por
  esto; `on` lo declara.
- Verificacion de traversal en CI (ver abajo).

## Android

- El modo se lee de preferencias (`km_p2p`): `mode`, `turn`, `user`, `secret`
  (TURN REST). El binario arranca en `off`.
- `auto` exige TURN, igual que escritorio.
- El backend se inicializa perezosamente en el primer intento; `dispose` al
  cerrar la sesion.
- Solo DataChannel: sin permisos de camara ni microfono.
- El APK de release incluye `arm64-v8a` + `armeabi-v7a`; el **debug** anade
  `x86_64` para el emulador.
- `DependencyOriginTest` prohibe `dev.onvoid` siempre y permite `org.webrtc`
  solo si `com.km:km-webrtc-android` esta en el classpath.
- Reintentos: el ANSWERER se re-arma cada ~1 s y el OFFERER cada ~5 s, con
  jitter. Sin eso, dos sesiones que se establecen con 20 s de diferencia no
  coinciden nunca en una ventana de 8 s.

## Modos: `KM_DESKTOP_P2P`

| Valor | Efecto |
|---|---|
| `off` (defecto) | Relay-only, byte a byte. **No** se construye `RealWebRtcTransport` ni ninguna cadena, y `KM_ICE_*` **no se parsea** (una variable invalida no rompe el rollback). |
| `auto` | P2P solo con un **servidor TURN utilizable** (`turn:`/`turns:` con credenciales). Sin ICE: relay con `p2p_no_ice`. Con STUN solo: relay con `p2p_no_turn`. |
| `on` | Exige P2P. Sin ICE falla con `p2p_on_no_ice`; con STUN solo falla con `p2p_on_no_turn` (exit 2). |

Compatibilidad: `true/1/yes/enabled` equivalen a `auto`; `false/0/no/disabled`
a `off`. Un valor desconocido cae a `off`: un typo no enciende la red P2P.
`KmFlags.DESKTOP_P2P` conserva su definicion booleana; el tri-estado vive en
`km-desktop/.../net/P2pMode.kt`.

## Configuracion ICE (solo entorno)

| Variable | Contenido |
|---|---|
| `KM_ICE_STUN_URLS` | Lista por comas, esquemas `stun:`/`stuns:`. |
| `KM_ICE_TURN_URLS` | Lista por comas, esquemas `turn:`/`turns:`. |
| `KM_ICE_TURN_SECRET` | Secreto compartido con coturn (`static-auth-secret`). Preferido. Deriva una credencial efimera. **Nunca** aparece en logs, barra ni export. |
| `KM_ICE_TURN_USERNAME` | Etiqueta del usuario cuando se usa el secreto (`kmdesktop`). Con credencial estatica, el usuario tal cual. |
| `KM_ICE_TURN_CREDENTIAL` | Credencial estatica (alternativa al secreto). **Nunca** aparece en logs, barra ni export. |
| `KM_ICE_TURN_TTL_SECONDS` | Vida de la credencial efimera. Default `86400` (24 h). |

### Credenciales efimeras (preferido)

El servidor coturn usa `use-auth-secret` + `static-auth-secret`: **no hay
usuarios estaticos**. El cliente deriva, en cada arranque:

    username   = "<expiryUnix>:<etiqueta>"
    credential = base64(HMAC-SHA1(secreto, username))

`IceConfig` guarda solo el resultado; el secreto no se retiene ni se imprime.
Rotar el secreto invalida las credenciales viejas sin tocar el cliente mas alla
del entorno.

Ejemplo:

    export KM_DESKTOP_P2P=auto
    export KM_ICE_STUN_URLS="stun:13.140.155.230:3479"
    export KM_ICE_TURN_URLS="turn:13.140.155.230:3479?transport=udp"
    export KM_ICE_TURN_USERNAME="kmdesktop"
    export KM_ICE_TURN_SECRET="<mismo valor que TURN_SECRET en el servidor>"
    ./km

### Redes que bloquean UDP: `turns:` sobre 443 (opcional)

Requiere certificado y clave del dominio en el servidor (ver
`deploy/coturn/README.md`, seccion TLS). Sin certificado NO se activa:

    export KM_ICE_TURN_URLS="turn:turn.example.org:3479?transport=udp,turns:turn.example.org:443?transport=tcp"

`turns:` va por TCP/TLS, que atraviesa redes que descartan UDP; el puerto 443 se
elige porque casi nunca esta filtrado. Es **opcional**: si el certificado no
esta desplegado, la URL falla y el cliente sigue teniendo `turn:` por UDP.

### Credencial estatica (alternativa, menos preferida)

Sin `KM_ICE_TURN_SECRET`, `IceConfig` acepta el par
`KM_ICE_TURN_USERNAME`/`KM_ICE_TURN_CREDENTIAL`. El servidor tendria que usar
`user=` y **quitar** `static-auth-secret` (las dos cosas juntas no autentican).
Tradeoff: una credencial de larga vida en cada escritorio, sin vencimiento ni
revocacion por cliente; rotarla obliga a distribuir el valor nuevo a todos.

Sin TURN, `auto` **no intenta** P2P: un STUN solo descubre la IP publica, y
usar candidatos `host`/`srflx` filtraria la IP del escritorio sin garantizar
traversal. `on` tampoco: si el operador pide P2P obligatorio y solo hay STUN,
el arranque falla (exit 2) en vez de exponer la IP.

`IceConfig` rechaza `http(s)` y cualquier esquema que no sea `stun(s)`/`turn(s)`;
una URL invalida es un error de configuracion (exit 2), no un fallback silencioso.
`toString()` y `redactedUrls()` quitan userinfo y credencial.

## coturn

Ver `deploy/coturn/README.md`. Resumen:

- Imagen `coturn/coturn:4.6.2-r4`, `network_mode: host`.
- Puertos de la plantilla: **3479/udp+tcp**, relay **55000-55999/udp** (para
  convivir con un coturn nativo en 3478/5349).
- `use-auth-secret` + `static-auth-secret`: **solo credenciales efimeras**. Un
  `user=` estatico se ignora (fue la causa de `Cannot find credentials` en la
  verificacion de #487) y ya no se escribe.
- El secreto sale de un fichero de entorno **0600**, montado en el contenedor y
  **fuera del repo**; el cliente recibe el MISMO valor por
  `KM_ICE_TURN_SECRET`.
- `no-cli`, `no-multicast-peers`, `denied-peer-ip` de rangos privados,
  `user-quota`/`total-quota`.

### Verificacion

La autenticacion se prueba DENTRO del host; el firewall solo afecta a la
verificacion externa:

    SECRET="$(sed -n 's/^TURN_SECRET=//p' /etc/keymessage/turn.env)"
    U="$(($(date +%s) + 3600)):kmdesktop"
    C="$(printf '%s' "$U" | openssl dgst -sha1 -hmac "$SECRET" -binary | base64)"
    turnutils_uclient -T -u "$U" -w "$C" -p 3479 127.0.0.1 127.0.0.1

Desde una maquina FUERA del host:

    turnutils_stunclient -p 3479 13.140.155.230
    turnutils_uclient -T -u "$U" -w "$C" \
        -p 3479 13.140.155.230 13.140.155.230

`stunclient` solo demuestra que el puerto responde a STUN. **Que responda no
demuestra que TURN asigne relay**: eso lo dice `uclient` con un candidato
`relay`.

Si el puerto no responde desde fuera, el firewall del host o del proveedor lo
esta descartando. Abrirlo exige root/consola cloud; el comando exacto esta en
`deploy/coturn/README.md` (por ejemplo
`sudo ufw allow 3479/udp`, `sudo ufw allow 3479/tcp`,
`sudo ufw allow 55000:55999/udp`).

## Presupuesto y fallback

El presupuesto P2P es de **8 s TOTALES** por intento: desde que empieza la
ronda (binding + negociacion) hasta que el DataChannel esta `OPEN`. La cadena
toma un unico deadline con un reloj inyectado y reparte lo que queda entre
`negotiate` y `awaitReady`; un offerer lento no puede sumar dos esperas de 8 s.
Cuando vence, el relay es el medio de datos si el fallo autoriza fallback
(SECURITY/PROTOCOL/STATE no cambian de medio). El reintento P2P no ocurre antes
de 60 s por par, y el establecimiento cerrado **da de baja** su suscripcion de
SIGNAL: no quedan handlers de intentos viejos procesando senales de un par
cerrado.

## Observabilidad

El glifo `P2P[o|~|x|?]` **no cambia de significado**: sigue siendo el enlace AL
RELAY. El medio de DATOS se dice aparte:

- barra: `via P2P`, `via relay`, `via relay (sin ICE)`, `via relay (sin TURN)`;
- DIAGNOSTICO: `p2p modo=... motivo=... medio=... ice=<urls redactadas>`;
- log: canal `[RELAY]` al arrancar (`p2p modo=... motivo=... ice=...`) y en cada
  cambio de medio (`medio peer=... medio=... motivo=...`). No se crea un canal
  nuevo porque el escritorio tiene ocho canales fijados por su spec.

Motivos estables (no traducidos, no derivados de mensajes de libwebrtc):

| Codigo | Significado |
|---|---|
| `p2p_off` | Modo `off`: no se intento. |
| `p2p_no_ice` | `auto` sin configuracion ICE. |
| `p2p_no_turn` | `auto` con ICE pero sin TURN utilizable: no se intenta (STUN solo filtraria la IP). |
| `p2p_on_no_ice` | `on` sin configuracion ICE: error de arranque. |
| `p2p_on_no_turn` | `on` con STUN solo: error de arranque (exit 2). |
| `p2p_ready` | P2P establecido (DataChannel `OPEN`). |
| `p2p_timeout` | No completo en 8 s **totales** (negociacion incluida). |
| `p2p_failed` | Fallo de medio que autoriza fallback. |
| `p2p_closed` | Enlace cerrado (par ausente o cierre local). |
| `p2p_in_flight` / `p2p_backoff` / `p2p_no_chain` | No se intento por single-flight, backoff de 60 s o falta de cadena. |

El badge declara el medio de datos y **no** afirma entrega ni que el ACK viaje
por ahi.

## CI

`.github/workflows/ci.yml`:

1. JDK 27 (Temurin) y toolchain 11 aprovisionado por foojay.
2. `publishToMavenLocal` en este orden: `km-core` -> `km-webrtc` ->
   `km-transport`.
3. `test` de `km-core`, `km-transport`, `km-webrtc` y `km-desktop`.
4. Nativo `dev.onvoid.webrtc` linux-x86_64 segun `os.arch`.

**CI NO prueba traversal real**: no hay TURN publico ni dos redes distintas en
el runner. Los contratos usan backends falsos. El test de ICE real esta gateado
por `KM_ICE_IT=1` y se salta por defecto:

    KM_ICE_IT=1 KM_ICE_STUN_URLS=... ./gradlew :km-webrtc:test --tests '*IceIt*'

Una regresion en `km-webrtc` (p. ej. volver a `iceServers = emptyList()`) si
pone el job rojo.

## Rollout y rollback

1. Desplegar coturn y abrir puertos.
2. Probar con un escritorio: `KM_DESKTOP_P2P=auto` + ICE. La barra dice
   `via P2P` solo con DataChannel abierto; si dice `via relay`, mirar el motivo
   en DIAGNOSTICO.
3. Adoptar `auto` como modo de despliegue.

Rollback: `KM_DESKTOP_P2P=off` y reiniciar. No se reconstruye nada, no se migra
ningun dato y los frames son los mismos. Apagar coturn por separado. El campo
SIGNAL con `sdp`/`candidate` es aditivo: un cliente anterior lo ignora con
`SIGNAL_UNKNOWN_KIND` y la conexion sigue.

## Invariante criptografico

Cambiar de medio **no** crea sesion, **no** ejecuta X3DH, **no** re-cifra y
**no** cambia `messageId`, frame ni timestamp. El relay sigue viendo metadatos
y disponibilidad; el ACK sigue siendo suyo. Los checkpoints de
`SecureFrameCodec`, `SecureRatchetProtocol`, `Kce` y `ContactBundle` no cambian
en este cambio.
