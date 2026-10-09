# km-desktop

Cliente TUI de KeyMessage: Kotlin/JVM, un solo binario, sin interfaz gráfica.
Habla el dialecto **KM-WIRE-RELAY** con el relé desplegado en
`wss://keymessage.xyz/km`.

Es un **consumidor** de `km-core`. No reimplementa criptografía de protocolo, no
aloja lógica del ratchet ni del `SecureFrame`, y no conoce el cliente Android:
todo lo que sabe de protocolo lo pide a `km-core` por sus interfaces públicas.

> **`km-desktop` vive dentro del repositorio del framework**, en
> `km-desktop/`. **No hay un repositorio de GitHub aparte para el cliente de
> escritorio**: se clona el repo del framework y se compila este módulo.

---

## Requisitos

### Para compilar

| Requisito | Versión | Cómo se selecciona |
|-----------|---------|--------------------|
| JDK para **ejecutar** Gradle | **27** | `JAVA_HOME`, o `-Dorg.gradle.java.home=<ruta>` en la invocación. |
| Gradle | **9.5.0** | `gradle/wrapper/gradle-wrapper.properties` de la raíz del mono-repo. **Solo el wrapper**: `./gradlew`. No hay que instalar nada. |
| Toolchain de **compilación** | **11** | `jvmToolchain(11)`. El build lo descarga solo si la máquina no lo tiene (`foojay-resolver-convention`). |
| Espacio libre | **~5 GB** | Cachés de Gradle (`~/.gradle`) + salidas de build. |
| Red de salida | HTTPS | `services.gradle.org`, Maven Central, Google Maven. |
| Android SDK | — | **No hace falta.** |

```sh
# Opción A: exportar el JDK una vez por terminal
export JAVA_HOME=/usr/lib/jvm/java-27-openjdk
./gradlew :km-core:publishToMavenLocal

# Opción B: pasar el JDK solo en esa invocación
./gradlew -Dorg.gradle.java.home=/usr/lib/jvm/java-27-openjdk \
          :km-core:publishToMavenLocal
```

El JDK que **ejecuta** Gradle (27) y el toolchain que **produce** el bytecode
(11) son dos requisitos distintos. El jar que sale corre sobre un JRE 11 o
superior; el 27 solo hace falta para lanzar el build.

`gradle.properties` **no** fija `org.gradle.java.home`, y es a propósito: es una
ruta absoluta que no viaja entre máquinas. Para no pasar `-D` en cada comando,
ponla en `~/.gradle/gradle.properties`, que tiene precedencia.

### Para ejecutar

| Requisito | Detalle |
|-----------|---------|
| JRE | **11 o superior.** El jar es bytecode Java 11. |
| Terminal | Cualquiera. Sin terminal, `Ansi` desactiva el color solo. `NO_COLOR` y `TERM=dumb` también. |
| Red | Alcanzabilidad del relé. Ver abajo. |
| Privilegios | **Ninguno.** |

### Red y puertos

| Qué | Detalle |
|-----|---------|
| Salida **443/TCP** | Contra el relé desplegado, `wss://keymessage.xyz/km`, que es el endpoint por defecto. |
| **8080/TCP** | Donde escucha un `km-daemon` propio (`listenPort` por defecto 8080). Con `--relay ws://<host>:8080/km` basta salida local, sin nada más. |
| Salida HTTPS | `services.gradle.org`, `repo.maven.apache.org`, `dl.google.com`, solo para el build. |
| Privilegios | **Ninguno.** No hay puertos privilegiados, no hay root. |

**Pruebas entre varias máquinas.** Lo cómodo es **Tailscale**: el `km-daemon` en
una máquina, la TUI en otra apuntando a la dirección `100.x` del relé, y nada
que abrir en el router. Alternativamente, el relé tiene que ser alcanzable
públicamente, lo que implica un `listenHost` expuesto y el firewall de esa
máquina dejando pasar el puerto. No hace falta ningún privilegio especial en
ningún caso.

---

## Inicio rápido

```sh
# 1. Clonar el repo del framework. km-desktop va dentro, en km-desktop/.
git clone https://github.com/KemaMada/km-framework.git
cd KeyMessage

# 2. Publicar km-core en mavenLocal. NO es opcional: este módulo lo consume como
#    artefacto publicado (com.km:km-core:0.1.0-SNAPSHOT), no como proyecto del
#    build. Sin este paso, el paso 3 no resuelve la dependencia.
./gradlew -Dorg.gradle.java.home=/usr/lib/jvm/java-27-openjdk \
          :km-core:publishToMavenLocal

# 3. Compilar el fat jar de un solo fichero
./gradlew -Dorg.gradle.java.home=/usr/lib/jvm/java-27-openjdk \
          :km-desktop:shadowJar

# 4. Arrancar la TUI. Sin argumentos usa el relé desplegado.
java -jar km-desktop/build/libs/km-desktop.jar
```

Contra un relé propio, en vez del desplegado:

```sh
# En una terminal: el relé
./gradlew -Dorg.gradle.java.home=/usr/lib/jvm/java-27-openjdk \
          :km-daemon:installDist
./km-daemon/build/install/km-daemon/bin/km-daemon

# En otra: la TUI apuntada a él
java -jar km-desktop/build/libs/km-desktop.jar --relay ws://127.0.0.1:8080/km
```

El `km-daemon` **no enruta por ruta**: acepta el handshake WebSocket en
cualquier path, así que `/km` y `/ws` funcionan igual. Y `--relay` acepta
cualquier esquema que Ktor resuelva, `ws://` incluido.

Al arrancar por primera vez la TUI no encuentra identidad: **crea una** y pide
una frase de paso nueva, dos veces, para confirmar. En los arranques siguientes
pide la que la abre. La identidad es lo que los contactos necesitan para poder
escribirte, así que ese paso no es opcional en la práctica.

El directorio de claves de peers (`peers.json`) **tampoco existe al principio**,
y la TUI avisa por `stderr`:

```
sin claves de peers registradas (.../peers.json); los ACK entrantes no se podran autenticar. usa 'peer <identityIdHex> <claveBase64>'
```

Eso no es un error: sin la clave de firma de un peer, sus `ACK` llegan y se
descartan sin verificar. El comando `peer` es lo que la resuelve. Ver
[Bootstrap de contactos](#bootstrap-de-contactos--diseñado-no-implementado).

---

## Construir

`km-core` se consume como **artefacto publicado**, no como proyecto del build.
Por eso el primer paso es publicarlo:

```bash
# desde la raiz del mono-repo
./gradlew :km-core:publishToMavenLocal

# luego, el cliente
./gradlew :km-desktop:shadowJar
```

El resultado es un único jar con todo dentro:

```
km-desktop/build/libs/km-desktop.jar
java -jar km-desktop/build/libs/km-desktop.jar
```

El módulo también se compila **aislado**, sin el resto del mono-repo, gracias a
su propio `settings.gradle.kts`:

```bash
# Con un Gradle 9.5.0 en el PATH, no con el wrapper: este settings.gradle.kts
# no incluye los otros módulos, así que ./gradlew desde la raíz no aplica.
cd km-desktop && gradle shadowJar
```

Lo mismo vale para `installDist`, que deja un launcher con su classpath:

```bash
cd km-desktop && gradle installDist
./km-desktop/build/install/km-desktop/bin/km-desktop --relay ws://127.0.0.1:8080/km
```

Las versiones de plugin de ese `settings.gradle.kts` son las mismas que el
catálogo de la raíz. Si divergieran, Gradle fallaría al incluir el módulo en el
build integrado por conflicto de plugin.

## Ejecutar

```bash
java -jar km-desktop/build/libs/km-desktop.jar
# o, durante desarrollo
./gradlew :km-desktop:run
# o, instalado
./km-desktop/build/install/km-desktop/bin/km-desktop
```

Al arrancar, si no hay identidad, la crea y pide una frase de paso nueva
(dos veces, para confirmar). Si ya existe, pide la que la abre.

### Opciones

Argumentos de línea de comandos. Son pocos a propósito: esto es una TUI, no un
CLI, y lo que se usa a menudo son los comandos de dentro de la sesión.

| Opción | Efecto |
|---|---|
| `--relay <endpoint>` | Relé alternativo. También se lee de `KM_RELAY_URL`. Sin él, `wss://keymessage.xyz/km`. |
| `--relay-identity <64hex>` | Identidad del relé que se anuncia en el `AUTH_REQUEST`. También se lee de `KM_RELAY_IDENTITY`. |
| `--keystore <ruta>` | Ruta del almacén de identidad. Por defecto, `$XDG_CONFIG_HOME/keymessage/identity.kmkey`, o `~/.config/keymessage/identity.kmkey`. |
| `--peers <ruta>` | Ruta del directorio de claves de firma de los peers, del que sale el resolver que autentica los `ACK`. Por defecto `peers.json`, **junto** al almacén de identidad. |
| `--reset` | Borra el almacén **y** el directorio de peers antes de arrancar. |
| `--verbose` | Traza del transporte por **stderr**. |
| `-h`, `--help` | Ayuda, sin arrancar. |

Un `identityId` mal escrito falla **al parsear**, con un mensaje de línea de
comandos, no veinte segundos después con un `AUTH_FAIL`. Un argumento
desconocido también: sale con código `2`.

### Variables de entorno

| Variable | Efecto |
|---|---|
| `KM_KEYSTORE_PASSPHRASE` | Frase de paso, sin preguntar. Comodidad con riesgo: queda en el entorno del proceso y a menudo en la historia del shell. Cuando está puesta, la TUI lo **avisa**. |
| `KM_RELAY_URL` | Endpoint por defecto. |
| `KM_RELAY_IDENTITY` | `identityId` del relé que se anuncia. |
| `NO_COLOR` | Desactiva el color, igual que `TERM=dumb` o una salida que no sea terminal. |

## Comandos

Se escriben dentro de la sesión, en el prompt `km>`.

| Comando | Qué hace |
|---|---|
| `connect [endpoint]` | Abre el WebSocket y hace el handshake `AUTH_REQUEST` → `AUTH_CHALLENGE` → `AUTH_RESPONSE` → `AUTH_OK`. Sin argumento usa `wss://keymessage.xyz/km`. |
| `identity` | `identityId`, huellas de las claves públicas y ruta del almacén. |
| `status` | Estado del transporte, `sessionId`, relé, intento y cuenta atrás de reconexión, último error, peers vistos y cuántas claves de firma hay registradas. |
| `send <identityIdHex> <texto>` | Crea y envía un mensaje por la API de `KeyMessageCore`. El `identityId` se valida por forma: 64 hex. |
| `peer <identityIdHex> <claveBase64>` | Registra la clave de firma de un peer y la guarda en `peers.json`. Es lo que permite autenticar sus `ACK`. Rechaza una clave que no derive ese `identityId`. |
| `help` | Ayuda. |
| `quit` | Desconecta y sale. |

Alias: `conectar`, `enviar`, `id`, `q`, `exit`, `?`, `h`.

---

## Arquitectura

```
┌──────────────────────────────────────────────────────────────┐
│  DesktopMain.kt — JLine                                     │
│    Command.parse  →  DesktopTui  →  Session                  │
│    (no sabe nada de criptografía)                            │
└───────────────┬──────────────────────────────────────────────┘
                │  inyecta transporte + identidad
                ▼
┌──────────────────────────────────────────────────────────────┐
│  km-core  (artefacto, no submodule)                          │
│    KeyMessageCoreImpl ─ SenderImpl ─ ReceiverImpl ─ AckMgr   │
│    JsonEncoder / JsonDecoder      RelayControlCodec          │
│    Ed25519 · X25519 · TranscriptBuilder · Base64Url          │
└───────────────┬──────────────────────────┬───────────────────┘
                │ implementa RelayTransport│
                ▼                          │  (nunca al revés)
┌──────────────────────────────────────────┴───────────────────┐
│  km-desktop                                                  │
│    JvmRelayTransport  ── KM-WIRE-RELAY sobre Ktor WS ──┐     │
│    RelayDataCodec     ── MESSAGE / ACK (secciones 10/11)│    │
│    IdentityKeystore   ── Argon2id + AES-256-GCM          │    │
│    Ansi               ── secuencias SGR                   │    │
└──────────────────────────────────────────────────────────┘    │
                                    │                            │
                                    ▼                            │
                    wss://keymessage.xyz/km  →  km-daemon ◄─────┘
```

### `PeerKeyDirectory` — el almacén de `peers.json`

Del que sale el `peerPublicKeyResolver` que `KeyMessageCoreImpl` le pasa a
`AckManagerImpl`. Sin él, el resolver por defecto es `{ null }`,
`AckManagerImpl` no puede verificar ninguna firma y devuelve `success` sin
procesar: el `ACK` llega, y el emisor se queda esperando uno que ya tiene.

- **Qué guarda**: pares `identityId` → clave Ed25519 **de firma** pública, en
  Base64 estándar, en un JSON plano. **Ningún nombre.** `peers.json` es un
  directorio de claves, no una lista de contactos.
- **La coherencia se recalcula, no se copia**: al registrar y también al leer,
  `identityId = SHA-256("KM-ID-IDENTITY" || clave)` se vuelve a derivar con
  `KmIds.identityId` y se compara. Un registro incoherente se rechaza y se cuenta
  en `skipped`. Aceptar uno sería autenticar un `ACK` firmado por una clave ajena
  a la identidad que dice emitirlo.
- **Un registro ilegible no tumba el arranque**: se salta y se cuenta. Un
  `peers.json` corrupto deja de autenticar esos peers, que es el comportamiento
  documentado, y no impide arrancar un cliente que sí podría entregar mensajes.
- **`--reset` lo borra también**, junto al almacén. Dejarlo crearía un problema
  peor: un `identityId` nuevo se confirmaría contra un `ACK` firmado por una clave
  que el usuario ya no tiene, y el fallo aparecería como un fallo de protocolo y
  no de arranque.

No es criptografía y no implementa nada de KM-ID-0001: es el almacén de la mitad
**pública** de una relación que ya se estableció en otro sitio. Ver
[Bootstrap de contactos](#bootstrap-de-contactos--diseñado-no-implementado) para
lo que se sabe y lo que no.

### `IdentityKeystore` — almacén cifrado de identidad

- **Ruta**: `$XDG_CONFIG_HOME/keymessage/identity.kmkey`, o
  `~/.config/keymessage/identity.kmkey` si `XDG_CONFIG_HOME` no está. El
  directorio queda en `700` y el archivo en `600` cuando el sistema de ficheros
  tiene permisos POSIX.
- **KDF**: Argon2id v1.3, 3 iteraciones, 64 MiB, **4 carriles** — la segunda
  opción recomendada de RFC 9106 §4 (`t=3`, `p=4`, `m=2¹⁶`). Sal de 16 bytes
  por archivo, nunca reutilizada.
- **Compatibilidad al subir el perfil**: leer un almacén **no** usa el perfil por
  defecto, usa el bloque `kdf` de la cabecera de ese archivo. Un almacén
  escrito antes del cambio (`p=1`) sigue abriéndose, sin migración y sin versión
  nueva: el coste vive en el archivo, no en una constante. Subir el perfil solo
  cambia lo que se escribe a partir de ahora.
- **Cifrado**: AES-256-GCM, clave de 32 bytes derivada del KDF, IV de 12 bytes
  por archivo, tag de 128 bits.
- **Formato**: un objeto JSON con los parámetros del KDF y el IV **en claro**
  (sin ellos no se puede derivar la clave) y la carga en Base64 estándar. Los
  parámetros del KDF van **también** dentro del texto plano, que sí está
  autenticado por el tag: si alguien edita la cabecera para cambiar el coste, la
  carga ya no descifra.
- **Frase de paso**: `KM_KEYSTORE_PASSPHRASE` o prompt enmascarado de JLine.
- **Frase incorrecta** y **archivo alterado** producen el mismo error, porque el
  tag GCM no distingue los dos casos sin un valor de control que delate el
  fallo. No se adivina: se dice lo mismo.

Contiene el `IdentityId` y los pares **Ed25519** (firma) y **X25519**
(acuerdo). El `identityId` se **re-deriva** al abrir y se rechaza si no
coincide con la clave pública guardada.

### `JvmRelayTransport` — el dialecto sobre el cable

Implementa `com.km.node.RelayTransport`. El ciclo de vida del socket corre en su
propio `CoroutineScope`; `connect()` es síncrono porque `Transport` lo declara
así, y espera al `AUTH_OK` con un límite de 20 s.

| Frame | Dirección | Dónde se implementa |
|---|---|---|
| `AUTH_REQUEST` | cliente → relé | aquí |
| `AUTH_CHALLENGE` | relé → cliente | aquí |
| `AUTH_RESPONSE` | cliente → relé | aquí, firmando con `Ed25519` de `km-core` el transcript de 152 B de `TranscriptBuilder` |
| `AUTH_OK` / `AUTH_FAIL` | relé → cliente | aquí |
| `MESSAGE` | ambos | `RelayDataCodec`, campo `data` en Base64 estándar |
| `ACK` | ambos | `RelayDataCodec`; el relé **no** lo reenvía |
| `PING` / `PONG` | ambos | aquí, correlacionados por `originalMessageId` |
| `PEER_ONLINE` / `PEER_OFFLINE` | relé → cliente | aquí |
| `STORED` / `RELAY_EXPIRED` | relé → cliente | `JsonRelayControlCodec` de `km-core` |
| `RELAY_ERROR` | relé → cliente | `JsonRelayControlCodec` de `km-core` |

**`responderIdentityId`.** El `AUTH_REQUEST` lo lleva (§9.2, obligatorio en la
tabla), pero el `km-daemon` desplegado rechaza con
`AUTH_FAIL RELAY_IDENTITY_MISMATCH` cualquier valor **informado** que no sea el
suyo, y una cadena vacía cuenta como informado. Por eso el campo se **omite**
cuando el cliente todavía no conoce la identidad del relé, y en cuanto llega el
`AUTH_CHALLENGE` su `responderIdentityId` queda fijada: a partir de ahí todas
las reconexiones se anuncian. `--relay-identity` permite fijarla desde el
principio.

**Reconexión**: backoff exponencial desde 1 s hasta 30 s (el tope de KM-WIRE-RELAY
§18.1, I17) con jitter completo. El jitter no es decorativo: sin él, N clientes
que caen a la vez reconectan a la vez.

**Dos niveles de ping** (§12.3): el `pingInterval` de Ktor es el keepalive de
RFC 6455; el `PING`/`PONG` JSON es de aplicación. Los dos coexisten y ninguno
sustituye al otro.

### Dos dialects, y por qué se traducen

`KeyMessageCore` habla el vocabulario de `km-core`: su `messageHandler` espera
`{messageId, from, to, payload, timestamp}` y su `ackHandler` espera el `Ack` de
`JsonDecoder`. El relé habla el suyo, donde el campo de carga se llama `data`
(KM-WIRE-RELAY §10.1, y la divergencia sigue abierta en P5).

`RelayDataCodec` traduce de uno a otro en los dos sentidos. No es un
detalle: mezclar los dos vocabularios convertiría una divergencia documentada en
un fallo silencioso en producción.

---

## Estado y límites honestos

Lo que **no** está hecho, y conviene no dar por hecho:

- **La carga del `Message` no está cifrada en este camino.** Es la limitación más
  importante de la lista. `KeyMessageCoreImpl` **firma** el `Message` que produce
  (`SenderImpl`), pero `Km52` y `SecureFrame` **no están cableados** a este
  transporte. Lo que viaja por el cable es exactamente lo que `km-core` produce
  hoy, sin nada más. Las primitivas criptográficas existen y están testeadas en
  `km-core` (`SecureFrame`, `DoubleRatchetSession`, `X3dh`); lo que falta es la
  conexión. **Firmar no es cifrar**, y con este camino abierto un intermediario lee
  el texto en claro.
- **No hay verificación de `serverSignature`.** El transcript de 120 B necesita el
  `serverNonce`, y la tabla de campos de `AUTH_OK` (§9.6) no lo lista, así que la
  firma llega sin material verificable. Es la divergencia P6 del propio
  documento: el cliente Android tampoco la verifica. Se comprueba la **forma**
  del `sessionId` (40 hex), no su firma.
- **La autenticación de los `ACK` depende de que registres al peer.** Sin su clave
  de firma en `peers.json`, `AckManagerImpl` recibe la clave por defecto
  (`{ null }`) y devuelve `success` sin procesar: el `ACK` llega y el emisor no se
  entera. El `stderr` del arranque lo avisa, y `status` distingue «no hay peers en
  línea» de «no hay claves de firma», porque son dos fallos distintos.
- **`STORED` y `RELAY_EXPIRED` no se emiten todavía.** El codec está y el cliente
  los maneja; la lógica que los dispararía sigue sin implementar en el relé
  (§13.3, P1).
- **La reconexión automática no se ha podido provocar contra el relé real.** El
  backoff sí está cubierto por tests deterministas y el camino del `AUTH_REQUEST`
  con `responderIdentityId` fijado está ejercitado, pero nadie ha tumbado el
  socket del relé para ver el ciclo completo.
- **El E2E no cruza el cliente Android.** `RelayAckEndToEndTest` levanta el
  `km-daemon` real y conecta dos `JvmRelayTransport` reales, porque el
  `RelayClient` de Android es código de otro repositorio y no se puede levantar
  desde aquí. La pata Android se mide aparte, en su propio repositorio.
- **Store-and-forward no existe** y **no hay soporte de Tor**. Ni una cosa ni la
  otra están empezadas; los RFC las declaran fuera de alcance.

Lo que **sí** funciona de extremo a extremo, y es lo que sostiene el resto:

| Capacidad | Cómo está comprobada |
|-----------|----------------------|
| Handshake `AUTH_REQUEST` → `AUTH_CHALLENGE` → `AUTH_RESPONSE` → `AUTH_OK`, con `sessionId` de 40 hex | Ejecución real contra el relé desplegado, y `DesktopAckAuthenticationTest` |
| `MESSAGE` con su `messageId` de aplicación, movido y re-codificado por el relé, recibido por el destinatario con ese mismo identificador | `RelayAckEndToEndTest`, con el `km-daemon` real en proceso |
| `ACK` reenviado **verbatim** por el relé, sin que se entienda, y **autenticado** en el emisor contra la clave del peer, que confirma `M` | `RelayAckEndToEndTest`, y `AckForwardingTest` en `km-daemon` |
| Cero amplificación: en todo ese camino no aparece ni un `MESSAGE` de más | `RelayAckEndToEndTest` |

## Comprobado contra el relé desplegado

No es una compilación en verde: es una ejecución real contra
`wss://keymessage.xyz/km` desde el `shadowJar`.

```
km> connect
conexion: AUTHENTICATING
conexion: ONLINE
conectado  sesion f24730aa2fa9d350fdc07fbd51eb9a5f8c2040f5

km> status
  conexion     ONLINE
  sessionId    f24730aa2fa9d350fdc07fbd51eb9a5f8c2040f5
  rele         e365dd1cac805d1a…
```

El handshake `AUTH_REQUEST` → `AUTH_CHALLENGE` → `AUTH_RESPONSE` → `AUTH_OK`
completa, y el `sessionId` que devuelve el relé es el que espera
`AuthVerifier.isValidHex40`.

Esa ejecución encontró **dos defectos reales** que ya están corregidos y
cubiertos por tests:

1. Anunciar `responderIdentityId: ""` hace que el relé responda
   `AUTH_FAIL RELAY_IDENTITY_MISMATCH`: el campo vacío cuenta como informado.
2. En `AUTH_OK`, `identityId` es el **iniciador** y `responderIdentityId` es el
   **relé**. Leer el primero guardaba como identidad del relé la del propio
   cliente; no fallaba en el primer arranque, sino en la **primera
   reconexión**, que se anunciaba a sí misma.

La prueba negativa del pin también está hecha: con
`--relay-identity 0000…0000` el relé rechaza la conexión, lo que confirma que el
campo se envía de verdad y no se ignora.

Lo que **no** se ha probado contra el relé: `PEER_ONLINE`, store-and-forward y
la reconexión automática.

El envío de `MESSAGE` entre dos peers **sí** está probado, pero no contra el
relé desplegado: `RelayAckEndToEndTest` lo cierra con el `km-daemon` real
levantado en proceso y dos `JvmRelayTransport` reales, que es la única forma de
afirmar el recorrido completo sin depender de que el relé desplegado esté vivo.

---

## Modelo de roles — DISEÑADO, con una parte implementada

> **Estado: DISEÑADO.** El diseño es que no hay servidor privilegiado; lo que hay
> hoy es un subconjunto. Nada de lo que sigue es una afirmación sobre el código
> actual salvo donde se marca.

El modelo previsto es que **cualquier cliente de escritorio puede actuar como nodo
de señalización y como relé**, y que el rol es por sesión, no por identidad. No
hay un servidor al que haya que pedirle permiso ni una lista de operadores.

| Rol | Qué hace | Dónde vive |
|-----|----------|------------|
| **Cliente** | Envía y recibe mensajes. Rol por defecto de esta TUI. | `km-desktop`, en marcha |
| **Nodo de señalización** | Aloja el intercambio de SDP/ICE para que dos peers abran la `PeerConnection`. | `km-daemon`, con `signalingEnabled: true` |
| **Relay** | Mueve `MESSAGE` y `ACK` entre peers que no se alcanzan, y los mantiene en línea aunque el otro esté dormido. | `km-daemon`, con `relayEnabled: true` |

Los dos últimos roles son **el mismo proceso**: `km-daemon` es a la vez nodo de
señalización y relé, con los dos caminos encendibles por separado. Eso es
justamente lo que hace que «subir» un cliente a relay sea arrancar un proceso, y
no desplegar un servicio aparte: **cualquiera que clone este repositorio puede ser
el relé** para sus propios contactos.

El **móvil es un cliente fino** y en este diseño no es imprescindible. No aloja
lógica de protocolo: implementa las interfaces de almacenamiento de `km-core`
sobre Room y los contratos de transporte sobre WebSocket, y todo lo
criptográfico lo pide a `km-core`. Puede subir a signalling/relé más adelante, y
el diseño no depende de que eso ocurra.

Qué hay hoy:

| Pieza | Estado |
|-------|--------|
| Cliente de escritorio que se autentica, envía, recibe y confirma `ACK` contra un relé | **IMPLEMENTADO** |
| `km-daemon` con los tres caminos del dialecto: datos, señalización y control | **IMPLEMENTADO** |
| Señalización SDP/ICE en el cable | **DISEÑADO.** Existe como extensión explícita con `kind` ∈ {`sdp_offer`, `sdp_answer`, `ice_candidate`, `contact_exchange`}; el catálogo normativo no define un tipo `SIGNAL` y esa divergencia está abierta |
| La TUI **subiendo** a relay sin un proceso aparte | **NO EMPEZADO** |
| Móvil como relay | **NO EMPEZADO**, y fuera del camino crítico |

---

## Bootstrap de contactos — DISEÑADO, no implementado

> **Estado: DISEÑADO.** No hay comando, opción ni formato de fichero que haga
> esto hoy, salvo el comando `peer`. Está aquí para que quede escrito antes de que
> se implemente, y para que nadie asuma que ya existe.

Un protocolo sin servidor de descubrimiento necesita una manera de que dos
personas se queden con la clave pública de la otra. El diseño tiene dos caminos.

### El camino completo: un fichero `.km`

Un contacto es un fichero, no un enlace. La primera vez, el cliente exporta su
propio contacto; el otro lo recibe por el medio que sea (correo, pendrive,
mensaje) y lo importa.

- **Contenido**: un `ContactBundle` **firmado** (`com.km.identity.ContactBundle`,
  implementado en `km-core`) más un nombre visible, para que la lista de contactos
  no muestre 64 hex.
- El bundle lleva un snapshot firmado del `DeviceRoster` y de las prekeys
  vigentes, así que es **autónomo**: permite iniciar la relación sin ningún
  servicio. Es un bootstrap, no una fuente de verdad.
- El mismo contenido criptográfico sirve para el fichero y para el QR. **No hay
  dos protocolos distintos**, solo dos formas de transportar lo mismo.
- El bundle está sujeto al **invariante de binding**: no puede afirmar que
  pertenece al dispositivo X mientras lleva las claves de Y.

Lo que un `.km` **no** hace es cifrar el mensaje. Solo da la clave pública con la
que luego se establece la sesión (X3DH + Double Ratchet), y esa parte del camino
todavía **no está cableada al transporte** (ver
[Estado y límites honestos](#estado-y-límites-honestos)).

### El camino reducido: una clave pública a pelo

Para un contacto técnico, o para probar, importar una clave Ed25519 desnuda es
suficiente — y si no viene nombre, **el nombre por defecto es `user`**. Es
deliberado: un contacto sin nombre es un dato que existe, y `user` es mejor que
una cadena vacía o que un `identityId` hexadecimal.

Esto tiene una consecuencia práctica y es a propósito: **importar una clave a
pelo no establece ninguna relación criptográfica**. Sirve para poder *responder* a
alguien que ya te tiene a ti, y para autenticar sus `ACK`. Para escribirle la
primera vez hace falta un `ContactBundle` con prekeys.

### El QR: se muestra, no se escanea

El escritorio **muestra** el QR de su contacto y **no tiene escáner**. Es diseño,
no una carencia pendiente: un escritorio no tiene cámara que ofrecer, así que el
QR es una **salida**. Se pasa el móvil por delante de la pantalla del PC, o se
lee a mano. El escaneo es del cliente móvil, que sí tiene cámara.

Qué hay hoy de todo esto:

| Pieza | Estado |
|-------|--------|
| `ContactBundle` en `km-core`: estructura, firma, invariante de binding, vectores dorados | **IMPLEMENTADO** |
| `PeerKeyDirectory.registerFromContactBundle`, que somete la clave del bundle a la misma comprobación que una clave a pelo | **IMPLEMENTADO** en la librería, pero **no expuesto** por ninguna opción de CLI ni comando de TUI |
| Comando `peer <identityIdHex> <claveBase64>`: el camino reducido, y **sin nombre** | **IMPLEMENTADO** |
| Nombre visible por contacto | **NO EMPEZADO.** `peers.json` guarda `identityId` y `publicKey`, nada más |
| Fichero `.km`, importación y exportación | **NO EMPEZADO** |
| Nombre por defecto `user` al importar una clave a pelo | **NO EMPEZADO** |
| QR de contacto en el escritorio | **NO EMPEZADO**; el escaneo, por diseño, tampoco |
| QR de identidad en Android | **IMPLEMENTADO**, en el repositorio del cliente |

---

## Desviaciones del diseño

Dos, ambas deliberadas y documentadas en el sitio donde se toman:

1. **`kansi` → `Ansi`.** No está publicado en ningún repositorio accesible:
   `com.github.ajalt.kansi:kansi-jvm` devuelve 404 en Maven Central, el grupo
   `com.github.ajalt` de Central solo publica `clikt`, `colormath`, `mordant` y
   `timberkt`, el repositorio GitHub `ajalt/kansi` devuelve 404 y JitPack exige
   token para un repositorio que no existe. Declararla haría que `shadowJar` no
   compilase. Lo sustituye `Ansi`, 40 líneas de secuencias SGR con apagado
   automático.

2. **`argon2-jvm` → BouncyCastle.** El POM de `de.mkammerer:argon2-jvm:2.11`
   declara **LGPL-3.0**. Este repositorio es MIT, verifica la licencia de cada
   dependencia en su POM y copia el texto a `THIRD-PARTY-LICENSES`; meter una
   LGPL con JNI nativo para ocho plataformas sería una obligación de distribución
   que el proyecto no tiene. BouncyCastle es MIT, ya es dependencia del proyecto
   (`libs.bcprov`, misma versión que `km-core`), e implementa **el mismo**
   Argon2id de RFC 9106 en Java puro: sin nativa, sin extracción a temporal, y
   portable.

Ambas quedan como desviaciones a resolver por quien decida, no como decisiones
definitivas.

## Dependencias

| Dependencia | Versión | Licencia | Para qué |
|---|---|---|---|
| `com.km:km-core` | 0.1.0-SNAPSHOT | MIT | framework: core, crypto, codec, modelo |
| `com.fasterxml.jackson.module:jackson-module-kotlin` | 2.15.2 | Apache-2.0 | la ejecuta `JsonRelayControlCodec` de `km-core` |
| `org.bouncycastle:bcprov-jdk18on` | 1.86 | MIT | Argon2id del almacén cifrado |
| `io.ktor:ktor-client-core` / `-cio` / `-websockets` | 3.1.3 | Apache-2.0 | transporte WebSocket |
| `org.jline:jline` | 3.30.17 | BSD-3-Clause / EPL-2.0 | terminal y edición de línea |
| `org.jetbrains.kotlinx:kotlinx-serialization-json` | 1.8.1 | Apache-2.0 | JSON del cable (solo API de árbol) |
| `org.jetbrains.kotlinx:kotlinx-coroutines-core` | 1.10.2 | Apache-2.0 | ciclo de vida del socket |
| `org.slf4j:slf4j-simple` | 2.0.17 | MIT | binding de SLF4J |

`kotlinx-serialization` se usa **solo por su API de árbol**
(`buildJsonObject`, `Json.parseToJsonElement`), así que el módulo no necesita el
plugin del compilador de serialization y no arrastra un cuarto plugin.

El binding de SLF4J escribe en **stderr** y a nivel `warn`: stdout es el prompt
de la TUI, y una línea de log ahí parte la edición de la línea que el usuario
está escribiendo. La traza fina se activa con `--verbose`, que escribe por
stderr explícitamente.

## Licencia

MIT, como el resto del repositorio.
