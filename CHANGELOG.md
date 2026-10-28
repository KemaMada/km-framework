# Changelog

Todas las novedades de km-framework.

El formato sigue [Keep a Changelog](https://keepachangelog.com/es-ES/1.1.0/) y
el versionado sigue [SemVer](https://semver.org/lang/es/).

Este proyecto **está por debajo de 1.0 y no publica versiones todavía**. No hay
`maven-publish` aplicado en los módulos, así que no existe un artefacto del que
depender. Las entradas de abajo describen el estado del código, no de un
artefacto publicado.

## [No publicado]

### Añadido — `km-desktop`: ratón (clic izquierdo y rueda) en la TUI

Change B (`desktop-tui-mouse`). Solo toca `km-desktop` y documentación: el wire,
`SecureFrame` v1, el ratchet, X3DH, HMAC, `km-core`, `km-transport`, `app/` y los
lanzadores no cambian. El ratón **no pinta nada nuevo**: los 21 goldens `f2-*`
siguen idénticos byte a byte, que es el gate de la fase.

- **`--mouse auto|on|off`** (defecto `auto`) y **`--wheel-rows 1..10`** (defecto
  3). `auto` activa el tracking solo con TUI viable y terminal con soporte; `off`
  lo apaga y devuelve la **selección nativa**; `on` con `--accessible`/`--no-tui`
  o sin soporte es **error de uso** (exit 2). `--help` y `?` declaran el valor
  efectivo (`ON`/`OFF`).
- **Hit-map por frame.** El renderer devuelve `RenderedFrame(lines, hitMap)`; el
  compositor emite `HitRegion(Rect, MouseIntent)` en el MISMO bucle que pinta y
  `at()` gana por orden de pintado. El overlay tapa el fondo (`Dismiss` a pantalla
  completa más los botones); el reducer nunca ve coordenadas.
- **Clic** press+release en la **misma** región: ficha de INICIO abre chat,
  pestaña cambia de raíz, burbuja/log mueven el foco, botón de diálogo elige, pie
  ejecuta su acción (copiar, `.km`, texto, exportar…). Arrastre y botón
  derecho/medio no actúan.
- **Rueda** = `ScrollBy(±wheelRows)` sobre la lista bajo el cursor, con
  `ensureVisible`/`followEnd`; fuera de lista, con overlay o con el foco en un
  campo de texto no mueve.
- **Motion descartado** (cierre de W1): `MouseCodec` devuelve `null` para todo
  evento con el bit 32 (hover/arrastre) y para un release sin botón. `?1000` no
  los pide, pero un multiplexor que los reenvíe no puede convertirlos en clic.
  Press+release en la misma región sigue siendo un clic.
- **Tracking** `?1000h`/`?1006h` al entrar y `?1000l`/`?1006l` **antes** de
  `?25h?1049l` en toda salida (normal, EOF, SIGINT, excepción); idempotente.
  `UnlockGate` no cambia.
- **Pruebas**: `MouseCodec` (press/release/rueda/4 dígitos/truncado/basura,
  motion y release sin botón, y regresión de flechas/Alt), `ClickTracker`,
  `MouseRouter` (clic, arrastre con motion y release suelto), hit-map de frames
  reales de las 7 pantallas, exhaustividad región→tecla, reducer de `Pointer` y
  bytes/orden del tracking. Suite fresca JDK27: **694 tests, 0 fallos**.
  Controles negativos compilados y restaurados por MD5: `at()` con `firstOrNull`,
  rueda sin `ScrollBy` y `exit()` sin OFF.

**Fuera de alcance (declarado)**: hover, doble clic, arrastre, redimensionar por
arrastre, menús contextuales, toque, selección propia y verificación en pty
real/Windows/tmux (manual: tmux `set -g mouse on`; conhost puede no entregar SGR).

### Corregido — `km-desktop`: QR centrado sin banda blanca y listas de ancho completo

Correcciones visuales reportadas por el usuario en una terminal real, sin tocar
protocolo, wire, crypto ni `km-core`/`km-transport`/`app/`.

- **El QR ya no se pega a la izquierda ni pinta una barra blanca.** Medido antes
  del arreglo en frames reales: a 100x50 el código empezaba en la columna 0 y las
  43 filas del QR ocupaban **100 celdas** (85 de código + 15 de relleno blanco);
  a 120x50 y 160x50 eran 120 y 160. La causa era doble: la rejilla rellenaba el
  resto de la fila con el estilo del QR y `ScreenWriter` emitía el `ESC[K` de fin
  de línea **dentro** de ese estilo. Ahora el código va centrado (x=7 a 100x50,
  x=17 a 120x50, x=37 a 160x50), el blanco cubre exactamente sus 85 celdas —con
  margen de silencio— y ningún `ESC[K` se emite con el fondo blanco activo. Las
  43 filas siguen enteras y las tres bandas (85x43 sin marco, 89x47 con marco,
  100x50 con bandas) se conservan.
- **INICIO, CONVERSACION y DIAGNOSTICO son de ancho completo.** El `maxWidth` de
  88/75/60 centrado dejaba la pantalla flotando en el medio (16 columnas de aire
  a cada lado a 120). Ahora el contenido ocupa 118 columnas con una celda de
  padding; el centrado queda solo para los formularios (FRASE, RESTAURAR) y los
  diálogos. INICIO es una lista de chats: buscador, cabecera `CONTACTOS n` con
  regla, y una ficha por contacto (presencia, alias, último mensaje truncado,
  hora, `S`/`K`) seguida de un separador fino de ancho completo; el vacío,
  cargando y error se pintan dentro del área. CONVERSACION alinea las burbujas
  por autor (`[...]` a la derecha, `{...}` a la izquierda) con hora y etiqueta
  (`OUT`/`ENTREGADO`/`EXPIRADO`) fuera del marco, y el compositor va enmarcado y
  a todo el ancho. La marca de la cabecera pasa a `KEYMESSAGE` y el pie muestra
  las teclas de la pantalla, como la barra inferior de la app.
- **Pruebas:** suite de `:km-desktop` en **610** tests (baseline 605 + 5 nuevos:
  centrado del QR, celdas fuera del QR en estilo por defecto, ningún `ESC[K` con
  el estilo del QR activo, lista de INICIO de ancho completo y alineación de la
  conversación). Los 21 goldens F2 se regeneraron porque el aspecto cambia a
  propósito.

### Cambiado — `km-desktop`: rediseño de la TUI (bandas, pestañas, scroll y diff por filas)

La TUI de pantallas se reescribe **por dentro** sin tocar el protocolo. No cambian
`km-core`, `km-transport`, `app/`, el wire, `SecureFrame` v1 (44 B), el ratchet,
X3DH ni el HMAC: cambia cómo se decide la pintura y cuántos bytes se escriben.

**Añadido** (`km-desktop/src/main/kotlin/com/km/desktop/ui/`):

- `layout/` — `Rect`, `Frame` y `Layout`: bandas cabecera (2) / cuerpo / estado /
  pie, ancho completo en las listas (INICIO, CONVERSACION, DIAGNOSTICO) y
  `maxWidth` 60 centrado solo en los formularios, padding 1, `Chrome.NONE` para
  el QR a pantalla completa. `Scroll` puro (`ensure`, `window`, indicador
  `[n/m]`), sin guardar geometría en el estado.
- `render/` — `Grid` de celdas con ancho exacto (el saneo de `Sanitizer` es la
  única puerta de texto), `FrameComposer`, `OverlayWindow` para la ayuda
  ventaneada.
- `theme/` — roles semánticos, paletas por capacidad (truecolor / 256 / 16 / MONO)
  y alfabeto ASCII. `shell/Sgr` es la única capa que traduce rol a SGR; MONO no
  emite ninguno.
- `screen/` — las siete pantallas sobre el kernel. El QR conserva su contraste
  fijo `30;47` y su geometría 85x43 / 89x47.
- **Pestañas raíz** INICIO / FRASE / DIAGNOSTICO (`Alt-1..3`, `Tab` hasta la barra,
  `Enter`); cambiar de pestaña **no borra** borradores. **Scroll** por lista
  (`PgUp`/`PgDn`/`Home`/`End`) con `[n/m]` textual y `[N nuevos ↓]` cuando llega
  un mensaje con el hilo subido.
- **Barra de estado** (identidad corta, relé, presencia del par, contadores S/K,
  aviso VOLÁTIL), **toasts** con caducidad inyectada por `Tick` y spinner solo
  con efectos en vuelo. Nunca se pinta «ONLINE» del par ni una entrega que no
  tenga ACK verificado.
- **Diálogos modales** con foco atrapado y backdrop en color; las acciones
  destructivas arrancan en «No», de modo que un `Enter` reflejo no borra nada.
- `--theme mono`: capacidad de color mínima explícita, sin SGR. `--ascii` y
  `NO_COLOR` siguen apagando el color.
- **Diff por filas** en `ScreenWriter`: un frame idéntico escribe **0 bytes**; un
  cambio escribe solo las filas afectadas (`ESC[<n>;1H` + fila + `ESC[K`),
  comparando texto, estilo, foco y paleta. Resize, cambio de alto, tema o
  capacidad, `--ascii` y entrar o salir de la pantalla alternativa fuerzan frame
  completo. El single-writer y la restauración del terminal no cambian.

**Retirado**: `ShellRendererLegacy`, `TruncationPolicy.LEGACY`/`fit()` y su test
de paridad. El recorte central conservaba cabeza y cola y escondía justo la fila
enfocada; lo sustituye la ventana de scroll.

**Pruebas**: la suite de `:km-desktop` queda en 21 goldens F2 (7 pantallas × 3
tamaños), propiedades de `ensureVisible`, reducer puro, tests de bytes del diff
(idéntico 0 B, una fila posicionada, resize completo, estilo solo) y un test de
coste con 500 mensajes que cuenta filas leídas y bytes escritos (no
milisegundos). Los goldens son la baseline del aspecto nuevo, no un oráculo de
paridad F1: esa paridad se demostró en M2 con los md5 del `apply-progress`.

**Límites declarados de ese change**: **no había ratón** todavía (lo añade
`desktop-tui-mouse`, arriba), **no
hay temas configurables** más allá de `color`/`mono`, **no hay persistencia** de
contactos ni mensajes, en MONO el backdrop de los diálogos no se pinta y el QR
baja de chrome con el tamaño (85x43..88x46 sin marco, 89x47 con marco compacto,
100x50 con bandas completas) antes que recortarlo.

### Añadido — `km-desktop`: TUI de pantallas, lanzadores `./km` y `km.cmd`

`km-desktop` deja de ser solo un REPL de líneas. El modo por defecto es una TUI
de pantallas keyboard-first, con el mismo modelo puro para los tres modos
(`--tui`, `--accessible`, `--no-tui`). No se toca el wire, ni `SecureFrame` v1
(44 B), ni el ratchet, ni X3DH, ni HMAC: el shell **orquesta y pinta**, no habla
protocolo.

**Añadido** (`km-desktop/src/main/kotlin/com/km/desktop/`):

- `ui/` — modelo inmutable (`ShellState`, `ShellAction`, `ShellEffect`), pila de
  navegación pura (`ScreenStack`), keymap, `ShellReducer` sin IO, `ShellRenderer`
  con rejilla de celdas de ancho exacto, `Sanitizer` (ESC/C0/C1/CSI/OSC/bidi),
  `QrRenderer` (zxing-core, ISO-8859-1, margen 4, corrección L) y `Osc52`.
- `shell/` — `JLineShell` y `ScreenWriter` como **único escritor** del terminal;
  pantalla alternativa, `SIGWINCH`, restauración en salida normal, EOF, SIGINT y
  excepción. `LineModeShell` conserva el REPL de G1.
- `client/` — servicios sin UI de contacto, chat, recuperación, diagnóstico y
  export. `RecoveryService` revela/copia la frase solo tras confirmación y
  restaura con `IdentityKeystore.writeFromSeed` (solo identidad).
- `platform/` — `ConfigPaths` (XDG/HOME en POSIX, APPDATA/USERPROFILE en Windows)
  y `OwnerPermissions` (700/600 POSIX, ACL de propietario en Windows con aviso si
  no hay soporte). Sin rutas POSIX hardcodeadas y sin shell-out.

**Pantallas**: desbloqueo (passphrase enmascarada, identidad antes de red),
INICIO/contactos con aviso de lista VOLÁTIL, IMPORTAR (texto, `.km` ISO-8859-1
hasta 64 KiB, ver mi QR), COMPARTIR (QR de salida, OSC 52, `.km`), CONVERSACIÓN
(`[OUT]` solo bytes aceptados, `[ENTREGADO]` solo ACK verificado, `[EXPIRADO
motivo]` con `R`/`D`), FRASE (oculta, reveal confirmado, nunca en logs) y
DIAGNÓSTICO/LOGS con filtro, copia y export redactado.

**Añadido en la raíz**:

- `km` — lanzador POSIX (LF, ejecutable). Si falta
  `km-desktop/build/libs/km-desktop.jar`, publica `km-core` y `km-transport` en
  `mavenLocal` y ejecuta `:km-desktop:shadowJar`; después lanza `exec java -jar`
  con los argumentos intactos y el mismo código de salida. El build escribe en
  stderr. `KM_REBUILD=1` fuerza el paso de build aunque el jar exista (Gradle decide qué recompilar).
- `km.cmd` — equivalente batch (CRLF) para Windows 10+.

**Corregido (CRITICAL-1 de la verificación #446)**: a 85x43 —el viewport mínimo
del QR— la pantalla COMPARTIR metía el QR en la caja y lo recortaba a 81 columnas,
sustituyendo además 5 filas por `… (5 líneas)`: un QR corrupto y sin aviso. Ahora,
cuando el QR cabe en el viewport pero no en el marco, se pinta **sin caja** (las 43
filas intactas); desde 89x47 vuelve el marco con título y barra de acciones, y por
debajo de 85x43 se declara el mínimo y se ofrece texto/`.km`. Sin cambios en el
portador, el wire ni la criptografía.

**Guardas**: `DesktopIsAThinClientTest` cubre ahora `Deflater`/`Inflater`,
`ContactBundle` y `Kce` —además de `Cipher`/`KeyPairGenerator`/`Signature`/`Mac`—
y exige que `ui/` no referencie crypto, portador ni `com/km/transport/`.
`TuiSingleWriterTest` pinza un solo escritor y cero `PrintWriter` en `ui/` y
`client/`. `DesktopCarrierIsSharedTest` sigue siendo la guarda específica del
codec del portador.

**Documentación corregida**: el README de la raíz y el de `km-desktop` afirmaban
que la carga viajaba **en claro** y ubicaban `JvmRelayTransport` dentro de
`km-desktop`. Las dos afirmaciones eran falsas desde p5/p4: el texto viaja en
`SecureFrame` y el motor vive en `:km-transport`. También se corrigen los límites
reales: contactos y mensajes **solo en memoria**, sin store-and-forward, sin
failover de endpoint en caliente, el escritorio **no escanea** QR y la TUI **no
está auditada**.

**Límites declarados**: `km.cmd`, las ACL de Windows y la TUI en un `pty` real no
se han ejecutado en este repositorio; se revisan por lectura y tests puros. Los
contactos siguen pendientes de `persist-contact-book`, que no está aplicado.

### Añadido — `km-desktop`: el escritorio se cablea al framework y deja de enviar en claro

`km-desktop` era, hasta ahora, un cliente de relé que sabia hablar el dialecto
pero no sabia **cifrar**. `DesktopMain` construia `KeyMessageCoreImpl` sin
`secureSessionRegistry` ni `peerTransportManager`, que es exactamente la
combinación con la que `km-core` documenta que el envío sale **EN CLARO** por el
camino legado. El comando `send` de la TUI funcionaba, y funcionaba en claro: la
puerta no estaba abierta con llave, es que no estaba.

**Añadido** (`km-desktop/src/main/kotlin/com/km/desktop/`):

- `core/CoreFactory.kt` — construye el nucleo con `secureSessionRegistry`
  presente y `allowLegacyCleartextReceive = false`, sin parametros que puedan
  abrir la puerta.
- `net/RelayStackFactory.kt` — compone `RelayClientStack.compose(motor,
  PeerTransportManager(motor))`. **No hay cliente de relé propio**: el motor sigue
  viniendo de `:km-transport`.
- `net/EndpointSequence.kt` — failover ordenado con `BootstrapAllFailed(tried)`.
- `config/BootstrapConfig.kt` — `--bootstrap` repetible, `KM_BOOTSTRAPS` (plural)
  y `KM_BOOTSTRAP_URL` (singular), con el default **leído** de
  `JvmRelayTransport.DEFAULT_ENDPOINT`.
- `contact/ContactImporter.kt` — `ContactBundle.decodeAndVerify` como ÚNICA
  entrada, con `InMemoryContactBook` que reverifica al guardar.
- `session/SessionCoordinator.kt` — `initiate`/`accept` de
  `SecureSessionEstablishment` sobre `PeerContext.forContact`, con `registry.install`.
- `util/DiagLog.kt` — ocho canales (`[BOOTSTRAP] [RELAY] [AUTH] [CONTACT]
  [SESSION] [SEND] [RECV] [INTEGRITY]`), `LogSink` inyectable y
  `StreamDiagSink` a **stderr** porque stdout es el prompt de la TUI.

**Modificado**: `DesktopMain.kt` usa la pila (`Session` guarda `RelayClientStack`,
no el motor suelto), da de alta el camino legado con `stack.attachLegacy()`
explícitamente, suscribe `registry.onInstall` para abrir el driver de par de
cada sesión, expone `sessionPeers()` en la línea de estado y distingue en
`send` entre `SENT` y el rechazo por falta de sesión. `PeerKeyDirectory` gana
`registerFromVerifiedBundle`, que guarda la `subjectSigningKey` del bundle
**verificado** indexada por la raíz.

**Decisiones que quedan escritas**:

- **Sin IPs incrustadas.** La guarda `NoHardcodedEndpointTest` lee el **bytecode**
  de producción (no el fuente: un grep falla por la redacción de un comentario) y
  exige cero IPv4 y cero `ws|wss|stun|turn` con host **literal**. Un nombre de
  host sí se admite: es el default configurable.
- **`Cipher` sí aparece, en `IdentityKeystore` y solo ahí.** El cifrado en reposo
  del almacén de identidad es un checkpoint cerrado y km-core no tiene almacén
  que cifrar. La guardia lo nombra como excepción explícita y falla si se extiende.
- **`MessageDigest` no se prohíbe:** el escritorio muestra una huella de su
  identidad, y eso no es criptografía de protocolo.

**Verificación**: `:km-desktop` = **192 tests en 22 clases, 0 fallos**. 13
mutaciones aplicadas, 12 detectadas por el test que las declara y revertidas por
md5; 1 resultada **INVÁLIDA** y se declara abajo.

### Lo que NO se cubre aquí, y por qué

- **Failover de endpoint en caliente.** `EndpointSequence` recorre la lista en
  orden y `BootstrapAllFailed` lista los probados, pero
  `JvmRelayTransport` toma el endpoint en su **constructor** (`private val
  endpoint`), no como `var`. `Session.switchEndpoint` devuelve por tanto un
  `UnsupportedOperationException` observable para todo destino distinto del de
  arranque, en vez de fingir un reintento. Cerrarlo exige que `:km-transport`
  exponga el endpoint: es una decisión de ese módulo, no de este.
- **Sesión E2E entre DOS procesos `km-desktop`.** El establecimiento, el
  `SecureFrame` cruzando el backend y la entrega con el mismo `messageId` y el
  mismo payload sí están cerrados en `:km-desktop` sobre `RelayProbe`, que es un
  `TransportBackend` real con cable de salida y de entrada. Lo que no está es la
  unión a través de un **socket** entre dos procesos: eso es la etapa ADB.
- **Empaquetado del `shadowJar`.** `CryptoProviderRuntimeTest` prueba CARGA DE
  CLASE de los tres providers de BouncyCastle en el runtime de `:km-desktop`, no
  que el jar final linkee sin clase ausente. Eso solo lo dice el arranque real.
- **El checksum de los `SecureFrame` y los vectores criptográficos** viven en
  `km-core` y no se duplican aquí.

### Mutaciones INVÁLIDAS, declaradas

La mutación prevista para `CryptoProviderRuntimeTest` era **quitar `bcprov` de
`km-desktop/build.gradle.kts`**. Es **INVÁLIDA**: `IdentityKeystore` importa
`org.bouncycastle` al COMPILAR, así que quitarla rompe `compileKotlin` y no llega
a producir XML —`INVALIDA(sin XML)`, no una asercion roja. La sustituta attacka
el mismo invariante (BouncyCastle ausente en runtime) excluyendo el grupo del
`runtimeClasspath` y filtrando el classpath de `test`, y **sí** falla en la
asercion.


### Cambiado — `app/`: la app movil pasa a ser un CLIENTE DELGADO

La app movil se reescribe como consumidor de `km-core` y `km-transport`. Todo lo
que la app hacia por su cuenta se ha borrado, y se declara aqui lo que queda
dentro, lo que queda fuera y lo que no se puede demostrar sin un dispositivo.

**Borrado** (`app/src/main/java/com/example/keymessage/`): `crypto/Crypto.kt`
(AES-GCM casero), `network/dht/{DhtNode,DhtProtocol,PeerConnection}.kt` (DHT
propio con bootstrap `http://13.140.155.230:8080`), `network/WebRtcManager.kt`
(ICE/STUN/TURN con secreto TURN incrustado), `network/ChatManager.kt`
(senalizacion `ws://13.140.155.230:3001/ws` **y fallback a texto plano**),
`network/RelayClient.kt` (importaba `com.keymessage.core.*`, paquete que no
existe en km-core) y `model/Identity.kt` (RSA 2048). Con ellos caen los dos tests
que importaban ese mismo paquete inexistente.

**Añadido**: `identity/{IdentityMaterial,IdentityStore,LocalKeyMaterial}.kt`
(Ed25519 del framework, `KmIds.identityId`, almacen cifrado, material de sesion
del dispositivo), `contact/ContactImporter.kt` (`ContactBundle.decodeAndVerify`
como UNICA entrada), `session/SessionCoordinator.kt`,
`core/CoreFactory.kt`, `net/{RelayStackFactory,EndpointSequence}.kt`,
`config/RelayEndpointConfig.kt`, `util/KMLog.kt`, `storage/room/**` reescrito
contra `com.km.storage.*`, y `ui/**` recableado a `com.km.api.KeyMessageCore`.

**Decisiones que quedan escritas**:

- **El motor de relé no se reescribe.** `RelayStackFactory` compone
  `JvmRelayTransport` + `PeerTransportManager` + `RelayClientStack.compose` de
  p4 y le inyecta el motor Ktor de Android. `RelayStackFactoryTest` ata que
  `stack.backend === stack.transport`: dos motores de wire serian el CRITICAL #1
  que cerro p4.
- **Sin IPs incrustadas y con multiples bootstraps.** El endpoint sale de
  `Settings.Global(km_relay_ws_url)` > `BuildConfig` >
  `JvmRelayTransport.DEFAULT_ENDPOINT`, en lista ORDENADA, con failover
  observable y `BOOTSTRAP_ALL_FAILED` con los endpoints probados.
- **No hay camino en claro.** El nucleo se construye con el registro de sesiones
  presente y el opt-in de recepcion en `false`: sin sesion el envio queda
  `FAILED` con **cero bytes** fuera, y un `MESSAGE` en claro entrante no llega al
  handler y se cuenta en `legacyInbound.rejectedCleartext`.
- **`app/` es un build AUTONOMO** (`app/settings.gradle.kts`, raiz propia, sin
  `include`) y consume `com.km:km-core` y `com.km:km-transport` POR ARTEFACTO
  desde `mavenLocal`. El `settings.gradle.kts` raiz del repositorio no se toca.

**Verificacion**: 111 tests de `:app` en 19 clases, 0 fallos, sobre `compileDebugKotlin`
y `compileDebugUnitTestKotlin` en verde. 17 mutaciones aplicadas, todas compiladas,
todas detectadas por el test que las declara, y revertidas por md5.

**Lo que NO se demuestra aqui, y se dice en vez de disimular**:

1. **Nada de esto se ha ejecutado en un dispositivo.** La etapa ADB (arranque,
   `adb logcat -s KeyMessage` con los ocho canales, failover real entre dos
   bootstraps, handshake `AUTH` contra un `km-daemon`, sesion entre DOS
   dispositivos, consultas Room y navegacion de Compose) sigue pendiente: es la
   etapa final de la fase, no de este corte.
2. **Que el APK linkea en dex no esta demostrado.** `compileDebugKotlin` y
   `testDebugUnitTest` son lo que se ha medido; `assembleDebug` no se ha corrido.
   En concreto, `km-core` arrastra `bcprov-jdk18on` y `net.i2p.crypto:eddsa`, que
   son JARs de escritorio, y `km-transport` trae `ktor-client-cio` por defecto
   (la app inyecta OkHttp, pero CIO sigue en el grafo). Si `assembleDebug` falla
   por clase ausente, el arreglo es un artefacto Android del framework, no codigo
   de la app.
3. **`SecureSendRejectedException` no sale por `KeyMessageCore.sendMessage`.**
   `sendMessage` envuelve el fallo del emisor y devuelve
   `SendResult(state = FAILED, failureCode = INTERNAL_ERROR)`: el estado y el
   cero-bytes son observables, el MOTIVO tipado no. La pantalla solo puede decir
   "NO enviado". Exponer el motivo es un cambio de framework.
4. **Compose y Room no tienen prueba unitaria.** Sin Robolectric ni instrumentado,
   la UI y las consultas reales se cubren por compilacion y por ADB.
5. **`AndroidIdentityStore`, `AndroidLocalKeyStore` y `AppContainer` no se prueban
   en unit tests**: necesitan `Context`, `EncryptedSharedPreferences` y
   `Settings.Global`. Lo que si se prueba es la logica pura que contienen
   (`IdentityCodec`, `LocalKeyCodec`, `RelayEndpointConfig`).
6. **`/app/` esta en el `.gitignore` del repositorio.** Nada de esta fase esta
   versionado: el trabajo existe en el arbol de trabajo y no en un commit.


Estado del árbol a **2026-09-30**. No hay releases; esto es lo que hay en
`main`.

### Añadido — `km-transport`: el transporte de red del relé sale de `km-desktop`

El motor cliente WebSocket del relé (`JvmRelayTransport`, 1090 líneas) y el codec
del dialecto KM-WIRE-RELAY (`RelayDataCodec`, 349 líneas) se **movieron** de
`km-desktop` a un módulo hermano nuevo, `:km-transport`, que se consume **por
artefacto** (`com.km:km-transport:0.1.0-SNAPSHOT`). No quedan dos clientes de
wire: la copia de `km-desktop` se eliminó y `DesktopMain` compila contra
`com.km.transport.*`.

El motivo es la frontera arquitectónica del proyecto: `km-core` no gana
dependencias de red. El framework expone el contrato `TransportBackend` y lo
implementa el hermano que trae Ktor CIO, no el núcleo. `km-transport` usa
`jvmToolchain(11)`, el mismo que `km-core`, para que su bytecode siga siendo
consumible.

### Añadido — recepción cableada y routing del motor, con pruebas

- `PeerTransportManager` compone (`copy()`) el cable de recepción sobre
  `TransportEventCallbacks.onDataReceived`: componer, nunca sustituir.
- `TransportEventCallbacks.onLegacyInboundFrame` + `LegacyInboundFrame` dan una
  entrada propia al camino legado, sin reusar el slot de pares.
- `JvmRelayTransport` clasifica `MESSAGE` entrante **una vez**: carga legible en
  el dialecto de `km-core` al camino legado, carga opaca a la cara de par.
- `RealRelayTransport` despacha por `eventCallbacks.onDataReceived`. `onDataFrom`
  se conserva como camino de diagnóstico, no de recepción.

### Añadido — aceptación de `RelayPeerChannelDriver` (tarea 3.3) y E2E por socket real (tarea 3.5)

Las tareas 3.3 y 5 del plan de `p4-relay-backend` estaban marcadas `[x]` sin su
test de aceptación. Aquí están los dos, escritos y medidos. Ningún fichero de
`km-core/src/main` cambió: `git status --short -- '*/src/main'` sale vacío.

**`RelayPeerChannelDriverTest`** (7 casos, `km-transport`). Levanta el
`RelayWebSocketServer` real de `km-daemon` en un puerto libre, porque
`intentarConectar` consulta `engine.isPeerPresent`, que solo el `AUTH_OK` y el
`PEER_ONLINE` de un relé real pueden poner: un doble probaría el doble.

- **PC-01/PC-02** — los dos órdenes de `presencia ↔ binding` alcanzan
  `CONNECTED`. Un solo orden dejaría el otro sin cubrir, y el fallo del orden
  equivocado es el más silencioso: el enlace simplemente nunca se abre.
- **PC-03** — doble presencia y doble binding (más el alias `onPeerOnline`)
  conectan **una sola vez**: un `CONNECTED` en los eventos del manager y un solo
  binding. "Una vez" es un número, no una sensación.
- **PC-04** — `onPeerOffline` olvida presencia y enlace, medido por su
  consecuencia: un aviso de binding nuevo **no** reconecta.
- **PC-05** — un par ajeno no conecta ni genera binding.
- **PC-06** — con binding pero sin presencia, `sendData` falla
  `INVALID_STATE_TRANSITION` (estado), no `BINDING_NOT_FOUND`: son fallos
  distintos y confundirlos ocultaría qué falta.
- **PC-07** — una presencia que **no** es la del par declarado no cierra un enlace
  a medio ciclo.

**`RelaySocketSecureE2ETest`** (1 caso, `km-transport`). `E2E-01`: dos
`KeyMessageCoreImpl` con `secureSessionRegistry` + `peerTransportManager` reales,
sesión E2E establecida por `SecureSessionEstablishment.initiate`/`accept` sobre
bundles verificados firmados de verdad, y el mensaje saliendo por un WebSocket
real contra el relé. En el receptor: `delivered == 1`, `rejectedFrames == 0`,
`unknownPeerFrames == 0`, `messageId` y `payload` intactos. El byte que baja por
el socket se decodifica con `BinarySecureFrameCodec` y se comprueba
`SecureFrameSpec.HEADER_LENGTH == 44` y `type == MESSAGE`. En el emisor:
`delivered == 0` y `rejectedFrames >= 1` (el eco local, **medido**, no presumido).
Ninguno recibe de vuelta su propio mensaje: el relé es un fan-out a
`envelope.to`, no un hub.

El enlace lo abre `RelayPeerChannelDriver`, no el test: `markConnected` exige
haber pasado por `OFFER_SENT`/`ANSWER_RECEIVED` y el relé no negocia nada, así
que recorrer el ciclo a mano sería probar el camino del último en vez del del
framework.

#### Mutaciones medidas (cada una con su control negativo compilado y ejecutado antes)

| Mutación | Detectada por | Válida | md5 revertido |
|---|---|---|---|
| M3-c · conductor sin `markConnected` | PC-01, PC-02, PC-03, PC-04, E2E-01 | sí | `2b4dcebfe742a48307d316ccf4247e30` |
| M3-c-bis · conductor sin la guarda `remotePeerId != peerContext.remotePeerId` | PC-07 | sí | `2b4dcebfe742a48307d316ccf4247e30` |
| M1-a · `sendData` del motor como no-op | E2E-01 | sí | `80f24f8733f764cf2ff9441b1a427475` |
| M1-b-bis · el motor pierde el `from` autenticado | E2E-01 | sí | `80f24f8733f764cf2ff9441b1a427475` |

`M3-c-bis` y `M1-b-bis` son nuevas (`tools/mutaciones_p4/p4_m3_c_bis.py`,
`p4_m1_b_bis.py`): las existentes `p4_m3_c.py` y `p4_m1_b.py` atacan la otra
mitad —el `markConnected` y el `RealRelayTransport` de proceso—, no el motor que
habla por socket.

**Lo que la primera medición de M3-c-bis dio y por qué** (`NO DETECTADA`,
7/7 verdes contra la mutación, y el motivo está leído, no supuesto): en `NEW`,
`getBinding(local, ajeno)` sale `null`, `createBinding(local, peerContext)` crea el
binding del par **declarado**, y `sendOffer(local, ctx, "", ajeno)` falla con
`PEER_ID_MISMATCH`. Si el par ajeno ya tiene binding activo, `createBinding`
devuelve `BINDING_ALREADY_EXISTS` y el conductor sale antes de tocar nada. La
guarda del conductor es **redundante en esos dos caminos**: solo se nota cuando
el par ajeno tiene su **propio** binding en `ANSWER_RECEIVED`, que es lo que
monta `PC-07`. Está escrito en el propio test para que nadie vuelva a atribuirle
dientes a `PC-05`.

#### Lo que queda FUERA, declarado y no fingido

- **La costura raíz/relé no está resuelta por el framework.** El `from` que el
  receptor ve es la identidad que el relé autenticó —la del motor—, y la sesión
  filtra por `remotePeerId`, que `PeerContext.forContact` deriva de la **raíz**
  del contacto. `E2E-01` hace que ambas coincidan porque el motor se autentica
  con el par de claves de la raíz. Es una decisión de la **fixture**, escrita en
  el KDoc del test con su motivo: `E2E-01` demuestra la **cadena** (cifrado,
  cable, socket, descifrado, entrega), **no** que la costura esté resuelta. Qué
  identidad se autentica en el relé sigue siendo decisión de p3. Con identidades
  distintas, el mismo camino acaba en `unknownPeerFrames`, y eso es observable.
- **`onPeerOffline` y su reentrada tienen un defecto abierto** `[LEÍDO, sin
  medir]`. `RelayPeerChannelDriver.onPeerOffline` saca al par de `conectados` pero
  deja el binding en `CONNECTED`. Si la presencia vuelve, `intentarConectar` cae
  en la rama `CONNECTED` y llama a `manager.markConnected`, y
  `PeerTransportBinding.withConnected()` **lanza** `IllegalArgumentException`
  porque exige `OFFER_SENT`/`ANSWER_RECEIVED`/`NEGOTIATING`. El `try/catch` de
  `JvmRelayTransport.onFrame` lo absorbería, con lo que `isConnected` mentiría
  (`false`) con el binding realmente conectado. `PC-04` comprueba el contrato que
  sí está escrito —que la presencia se olvida— y **no** fija como invariante este
  fallo. La corrección sería una rama que no vuelva a llamar `markConnected` si el
  binding ya está `CONNECTED`. No se ha tocado `src/main` en esta entrada.
- **Nada se ve en una pantalla.** Un mensaje descifrado en UI sigue sin
  demostrarse: no hay dispositivo ni ADB, y Ktor CIO en Android sigue sin
  verificar.
- **`RelayPeerChannelDriver` no está suscrito a nada.** `RelayClientStack` crea
  los drivers pero no los engancha a `onPeerOnline`/`onPeerOffline` del motor: eso
  es trabajo del consumidor (p3). `E2E-01` lo suscribe a mano porque el test es
  el consumidor, lo que además deja escrito cuál es el cable que falta.

#### Cifras

Línea base medida antes de este cambio y suite completa después, con
`*/build/test-results` borrado antes de cada corrida y `exit 0` en ambas:

| Módulo | Línea base | Final | Δ |
|---|---|---|---|
| `km-core` | 1388 / 0 | 1388 / 0 | 0 |
| `km-transport` | 46 / 0 | 54 / 0 | **+8** |
| `km-desktop` | 60 / 0 | 60 / 0 | 0 |
| `km-daemon` | 49 / 0 | 49 / 0 | 0 |
| `km-webrtc` | 92 / 0 | 92 / 0 | 0 |
| **Total** | **1635 / 0** | **1643 / 0** | **+8** |

Evidencia sustitutiva declarada, como en R-4 y R-6: `git diff` sobre
`frame/`, `ratchet/`, `x3dh/` y `SecureOutbound.kt` sale **vacío**, y
`git diff km-core/build.gradle.kts` sale **vacío**.

### Changed — `lastWireByMessageId` se limpia solo tras `Acked`

Antes se borraba al fallar y al cancelar, y nunca tras el ACK, con lo que el
mapa crecía sin límite. Ahora `SenderImpl.onAckConfirmed(messageId)` lo limpia
desde `ackManager.registerAckHandler`, que solo dispara con ACK autenticado. Se
declara que **no** se limpia en `queueOffline`: sin caché no hay idempotencia y
`retrySecure` devolvería `NO_SESSION`.

### Security — alcance de esta entrada, declarado

Lo que esta entrada **sí** deja cerrado, y lo que deja fuera, se dice aquí en
vez de taparlo:

- **Cerrado y con mutación que lo ataca**: UN solo cliente de wire (R-8.a, la
  copia de `km-desktop` eliminada y `DesktopMain` compilado contra
  `com.km.transport`); el byte entrante llega al manager con el `from`
  autenticado, no con la identidad local; `sendData` no es un no-op silencioso;
  la clasificación legado/opaco de `onMessage` no se puede desactivar en
  silencio; `RealRelayTransport.onDataReceived` entrega al manager; el relé no
  hace eco al emisor.
- **Cerrado solo por evidencia declarada, NO por test**: `km-core` sigue sin
  dependencias de RED. El guard existente (`KmCoreNoWebRtcDependencyTest`) solo
  cubre `com.km.webrtc`, así que la evidencia es `git diff
  km-core/build.gradle.kts` = 0. Un test que fingiera cubrir «no hay red» sería
  peor que la evidencia declarada. Lo mismo con «el diff no toca criptografía»:
  `git diff` vacío sobre `frame/`, `ratchet/`, `x3dh/` y `SecureOutbound.kt`.
- **La frontera criptográfica se sostiene por lectura, no por un invariante
  ejecutable en toda la superficie**: dentro de `com.km.node` la única
  criptografía es `Ed25519` para firmar el transcript `AUTH` (mismo precedente que
  `NodeRuntimeImpl` y `RelayServer`, con excepción justificada en
  `RelayTransportCryptoSeparationTest`); `SecureFrame` no aparece en código, solo
  en documentación.
- **Lo que esta entrada NO cubre**: el escenario `inbound.to != identity` de la
  tarea 3.2, que es inalcanzable a través del relé porque este solo entrega a
  `envelope.to` y probarlo exigiría llamar a un método `private` del motor. La
  costura raíz/relé tampoco queda resuelta por el framework (ver más arriba).
- **Corrección de alcance**: este párrafo decía antes que `RelaySocketSecureE2ETest`
  (3.5) y `RelayPeerChannelDriverTest` (3.3) **no existían**. Era cierto cuando lo
  escribió el cierre de los 3 CRITICAL y dejó de serlo al escribirse las tareas
  3.3 y 3.5, que los crean. Ahora sí existen, con 1 caso `E2E-01` y 7 casos
  `PC-01..07`, y ambos están atacados por mutación propia. La cadena completa de
  dos núcleos cifrados sobre un socket real **sí** tiene test propio; lo que
  sigue sin cubrir de un solo trazo es el escenario `inbound.to != identity`.
- **La política de reconexión** (`ReconnectPolicy`) está cubierta por pruebas
  unitarias del módulo, no por un corte de red inducido.
- Todo lo anterior corre contra el `km-daemon` levantado en proceso sobre un
  puerto libre, no contra infraestructura desplegada. El `DEFAULT_ENDPOINT` del
  motor es `wss://`; las pruebas usan `ws://127.0.0.1` a propósito, y no se ha
  validado un despliegue real.

### Añadido — estructura del repositorio

- Separación del cliente Android: `app/` (Compose) sale del repositorio y pasa a
  `km-android`. `/app/` está en `.gitignore` y fuera del índice de git.
  **km-framework** se queda con `km-core`, `km-webrtc`, `km-id-reference`,
  `docs/` y `tools/`.
- Documentación de publicación: `README.md`, `CONTRIBUTING.md`, `SECURITY.md`,
  `NOTICE`, `THIRD-PARTY-LICENSES/`.
- `LICENSE` (MIT, `Copyright (c) 2026 KemaMada`).

### Added — renombrado de paquetes (ROMPIENTE)

Los paquetes pasan de `com.keymessage.core.*` a `com.km.*`, con nombres más
cortos tomados del vocabulario del protocolo. El detalle completo está en el
[README](README.md#mapeo-de-renombrado-de-paquetes).

| Antes | Ahora |
|-------|-------|
| `com.keymessage.core.kmid` | `com.km.identity` |
| `com.keymessage.core.km7` | `com.km.negotiation` |
| `com.keymessage.core.sf` | `com.km.frame` |
| `com.keymessage.core.km52` | `com.km.unit` |
| `com.keymessage.core.*` (resto) | `com.km.*` |
| `com.keymessage.webrtc` | `com.km.webrtc` |

- **ROMPE COMPATIBILIDAD**: cambia la API pública completa. No hay shim, ni
  alias, ni `@Deprecated`. No hay compatibilidad que preservar porque no hay
  versión publicada. Los **nombres de tipo** no cambian: `Km7Negotiation`,
  `Km52Codec`, `KmIds` y `KmSchemas` conservan el identificador de
  especificación.

### Added — protocolo e implementación

- **SecureFrame v1** (`com.km.frame`): header fijo de 44 bytes, los 44 bytes
  íntegros como AAD, nonce derivado por HKDF y **no** transmitido. `length`
  acota el ciphertext a 65535 bytes; la fragmentación queda pendiente.
- **Double Ratchet** (`com.km.ratchet`): ratchet de raíz y de cadena, con
  dominios separados para `RootKey` y `ChainKey`. Establishment desde `F` sin
  mezclarlo con el bootstrap ni con la mensajería.
- **X3DH** (`com.km.x3dh`, KM-0006): `DH1..DH4`, `SK` y `K_prekey`. Ni `initiate`
  ni `respond` mutan estado; el consumo de la one-time prekey ocurre en el
  commit. Solo claves X25519 de acuerdo: no hay conversión Ed25519→X25519 en la
  API.
- **Unidad persistida KM52 v2** (`com.km.unit`): `unitVersion = 2`, y aparece el
  bloque `PENDING_INBOUND`. Los frames entrantes que la sesión todavía no ha
  podido usar se guardan **en la misma unidad atómica** que el estado
  criptográfico; antes vivían en una cola en memoria que nada vaciaba.
- **Medio físico con publicación atómica** (`com.km.transmit`): escribir un
  temporal → sincronizar → `rename` sobre el hueco → sincronizar el directorio.
  El invariante que gobierna la clase: *un fallo físico no puede convertirse en
  un nuevo estado criptográfico*. Se conserva la asimetría al escribir y al
  leer: podar al escribir, rechazar entero al leer.
- **Identidad KM-ID-0001** (`com.km.identity`): `identityId`, `deviceId` y
  `nodeId` con separación de dominio; `DeviceRoster` con cadena de hashes y
  revocación; `ContactBundle` con binding dispositivo↔identidad; Canonical
  Encoding (KCE) como perfil cerrado de CBOR.
- **Autenticación KM-0002** (`com.km.auth`): `AuthVerifier` con las reglas V1–V6
  —derivación de `identityId`, firma del transcript de 152 bytes, firma del
  transcript de servidor de 120 bytes, ventana de skew, guarda de replay de
  nonce— con `Clock` y `NonceReplayGuard` inyectados para mantener el verificador
  sin estado. `Base64URL` estricto, sin padding en el wire.
- **Negociación KM-0007** (`com.km.negotiation`): intersección por categoría,
  selección por preferencia **local**, determinista. Una capacidad desconocida se
  ignora, no se rechaza.
- **Transporte WebRTC** (`com.km.webrtc`): `ManagedWebRtcPeer`,
  `RealWebRtcTransport`, `RelaySignaling`, `WebRtcEstablishment` y
  `WebRtcPlatformDetector`, con selección del clasificador nativo a partir de
  `os.name` y `os.arch`.
- **km-id-reference**: implementación de referencia normativa de KM-ID-0001 en
  Python, con vectores congelados (`FROZEN.sha256`) y fixtures negativos que
  fallan por una sola razón cada uno.

### Added — envío y recepción E2E cifrados en el camino de aplicación (km-core)

El camino de envío de km-core puede salir ahora como `SecureFrame` en vez de
JSON en claro, y el camino de recepción sabe descifrarlo. Todo es **opt-in**:
sin inyección, el comportamiento es exactamente el de antes.

- `isSecureFrameWire` (`com.km.messaging`): discriminante D-1. Todo `SecureFrame`
  v1 empieza por su campo `version` (`0x01` en el byte 0) y ningún valor JSON
  válido puede empezar así, así que la clasificación no tiene colisión.
- `SecureOutbound` / `SecureOutboundImpl`: envuelve el `Message` en un
  `MessageEnvelope` y lo entrega a la `SecureMessagingSession` del destinatario.
  El `messageId` va **dentro** del sobre, que a su vez va dentro del plaintext
  cifrado: el header de 44 bytes está congelado y su AAD es el header completo.
- `SecureInbound`: entrega al `ReceiverImpl` los `Message` descifrados. **No
  descifra**: la sesión ya lo hizo en su propia suscripción, y un segundo paso
  por el ratchet sería un replay que perdería el mensaje. Se observa **por
  sesión**, no una vez por par: cuando el registro sustituye la sesión de un par
  por otra (un re-key), esa otra se observa también y la sustituida deja de
  hablar. Antes, los mensajes posteriores a un re-key se perdían en silencio.
- `SecureSessionRegistry.onInstall`: observa cada sesión que entra. Existe por
  una garantía de **orden** de suscripciones, no por comodidad.
- `SecureMessagingSession.sendTranscript`: reenvía bytes **ya cifrados** sin
  volver a cifrar, para que el reintento sea idempotente (`MAX_RESEND`).
- `SecureMessagingSession` filtra ahora `onData` por `remotePeerId`: con dos
  sesiones sobre el mismo `PeerTransportManager`, el frame de un par entraba en
  la sesión del otro.
- `KeyMessageCoreImpl` acepta `secureSessionRegistry`, `allowLegacyCleartextReceive`
  y `peerTransportManager` como parámetros opcionales al final, con valor por
  defecto `null`, `false` y `null`.
- `LegacyInboundObservation`: contadores y estado del camino legado de recepción,
  por API pública. Es lo que hace observable el rechazo en claro con el registro
  activo.

**Regla de decisión (R1), que hay que leer antes de usar nada de esto:**

- **Registro ausente → el mensaje sale EN CLARO** por el camino legado. Es
  compatibilidad, no cifrado, y así se declara aquí y en el KDoc. Ningún
  consumidor actual construye el registro.
- **Registro presente y sin sesión con el destinatario → RECHAZO observable**:
  estado `FAILED` con código de fallo, sin encolar y sin llamar a
  `Transport.send`. Nunca un reintento en claro como plan B.

**El espejo de esa regla en RECEPCIÓN, que es lo que faltaba:**

Con el registro presente, un `MESSAGE` **en claro** por el camino legado ya no
llega al handler de la aplicación. Antes sí llegaba, con la misma forma y el mismo
`from` que un mensaje E2E y sin ninguna marca de origen, así que un consumidor que
hubiera inyectado el registro creyendo estar cifrado no tenía forma de saberlo.

- **Registro ausente → el camino legado de recepción se conserva tal cual**, en
  claro y sin marcar. Es el camino de compatibilidad y no hay a quién engañar con
  el: nadie ha construido el registro todavía.
- **Registro presente y `allowLegacyCleartextReceive = false` (el valor por
  defecto) → el camino legado de recepción está DESACTIVADO.** El rechazo es
  observable por API pública en `KeyMessageCoreImpl.legacyInbound`
  (`rejectedCleartext`, `legacyReceiveEnabled`), igual que el rechazo del envío se
  ve en el estado del mensaje. No es un log.
- **Registro presente y `allowLegacyCleartextReceive = true` → el claro vuelve**,
  y **eso no es E2E**. Queda marcado en `legacyInbound.deliveredCleartext`, que
  es la forma de distinguirlo de un mensaje descifrado: un `Message` que suma ahí
  y **no** suma en `secureInbound.delivered` vino por el camino legado.

Lo que **no** cambia: el `ACK` sigue saliendo por `Transport.sendAck`, firmado y
en claro. Cerrar el `MESSAGE` en claro no cierra el canal `ACK`.

**Lo que este cambio NO cubre**, y sigue igual que antes:

- **km-desktop es TEST-ONLY para este camino.** No tiene origen de
  `VerifiedContactBundle`, luego no puede establecer sesión y su envío sigue
  saliendo en claro. No se ha escrito ningún test que fije ese comportamiento
  como correcto.
- **El `ACK` sigue firmado y EN CLARO** por `Transport.sendAck`. Cifrarlo exige
  `EnvelopeType.DELIVERY_ACK` más `SecureMessageReceiver`, y es otro slice.
- **El payload sigue persistido en claro** en el `MessageStore`. El cifrado
  at-rest es otra fase: que el cable vaya cifrado no dice nada del disco.
- **`MessageEnvelope` v1 no lleva `timestamp`.** El `Message` entrante se
  reconstruye con `0`, y eso se declara en vez de inventar una hora.
- **`SecureMessagingSession.send` sigue exigiendo
  `PeerContext.authSession == AUTHENTICATED`**, y no se ha relajado. Es un
  checkpoint de superficie con la criptografía del ratchet detrás, y el hueco que
  deja para un contacto E2E en producción está pendiente del rediseño de
  app/desktop.
- **La disponibilidad mezcla dos nociones.** `SenderImpl` sigue preguntando al
  `Transport` legado (`isOnline()`), mientras que la rama cifrada decide por el
  estado del binding de `PeerTransportManager`. Un destino offline **se encola**
  y se vacia por la rama **cifrada**, o **no se vacia**: si el binding no está
  `CONNECTED` el envío se rechaza de forma observable y el mensaje sigue en la
  cola. **No** se drena por el camino legado, porque esa rama solo existe sin
  registro. La brecha es de **disponibilidad** —lo que dispara el vaciado es la
  presencia que declara el `Transport` legado, no el estado del binding—, no de
  confidencialidad. Declarado, no arreglado.
- **El drenado de `onPeerOnline` con el registro presente y sin sesión está
  pinado, y no solo declarado.** Es la misma fuga que se cerró en el reintento
  (C-1), pero por la otra rama: el vaciado de la cola vuelve a pasar por `send`,
  y `send` rechaza por `NO_SESSION`. Lo que ahora está probado es que ese
  rechazo **no se convierte en un plan B**: con la cola llena, el par de vuelta
  en línea y el registro presente pero sin sesión con ese par, no sale nada por
  `Transport.send` —los dos observadores del transporte falso, `sendCount == 0` y
  `sent` vacío— y el mensaje **sigue en la cola** con su `messageId` intacto. Sin
  ese test la afirmación eraStructural: un drenado en claro del fallback
  `if (send falla) transport.send(json)` era invisible para toda la suite, porque
  la rama feliz del vaciado nunca llega al fallo.

### Added — andamiaje de pruebas

- `tools/mutprobe.py` y `tools/fallos.py`: arnés de medición de mutaciones y
  atribución de fallos por XML.
- `tools/mutaciones/m1..m6`: seis mutaciones que borran, cada una, una garantía
  distinta del medio físico (escritura directa en el hueco, `force` del canal,
  `ATOMIC_MOVE`, `copyTo`+`delete`, lectura parcial, verificación de integridad
  ignorada).

### Seguridad

- **1363 tests, 0 fallos** en `km-core` tras el cierre de C-1, C-2 y W-A del
  slice 4 (antes de estos arreglos: 1355; +7 nuevos: 4 de recepción heredada,
  1 de sesión de reemplazo, 1 de `ACK` del camino cifrado, 1 del drenado de la
  cola sin sesión). Baseline del slice: 1331.
  Mutaciones aplicadas y revertidas: las 9 del slice más 6 de estos arreglos
  (fuga en claro aditiva en el reintento, discriminante de recepción invertido,
  opt-in apagado, observación solo de la primera sesión por par, `ACK` sin
  correlacionar, y **plan B en claro al drenar la cola sin sesión**), **todas
  válidas**: cada una compiló y falló en la aserción esperada.
  La del drenado es de las que importan por lo que **no** detectaba: con ella
  puesta y el test nuevo desactivado, la suite seguía en 1362/0 y `exit 0`.
  Activado el test, 1363 con 1 fallo y en la aserción nueva y solo en ella.
- **1187 tests, 0 fallos** a 2026-09-30: 1095 en `km-core`, 92 en `km-webrtc`
  (esta última suite levanta `PeerConnection` reales de libwebrtc).
- Corrección del bug donde `identityId` se usaba como clave de verificación
  Ed25519: `identityId` y `deviceId` son identificadores y **nunca** material
  criptográfico. `SignedMessage` y `SignedAck` transportan la clave pública por
  separado, y `Ed25519Impl.verify()` rechaza claves de tamaño distinto de 32.
- `identityId` en el wire pasa de 40 a 64 hex, con la derivación de KM-ID-0001
  (`SHA-256("KM-ID-IDENTITY" || publicKey)`). El transcript de autenticación
  cambia de 104 a 152 bytes, lo que invalida las firmas anteriores. Es un límite
  de versión del protocolo.
- El `deviceId` opaco de KM-0002 queda retirado: ahora siempre es derivado
  (`SHA-256("KM-ID-DEVICE" || deviceSigningKey)`).

### Decisiones que conviene tener presentes

- **MIT no incluye concesión explícita de patentes.** Es una decisión
  consciente, no un descuido. La concesión de patentes que Apache-2.0 incorpora se
  añadió precisamente porque el proyecto que la inspiró (WebRTC) la necesitaba, y
  km-framework depende de él; aquí se ha preferido la simplicidad de la MIT.
  **Consecuencia:** km-framework no promete de forma explícita derechos de patente
  a quienes lo contribuya. Quien necesite esa protección debe declarar sus
  invenciones en el PR (ver [CONTRIBUTING.md](CONTRIBUTING.md)) y asumir el
  riesgo. Si esta decisión cambia, es un cambio de licencia y debe pasar por un
  proceso explícito, no por un commit.
- **Bouncy Castle está confinado** a `com.km.crypto.provider`. El resto de
  `km-core` depende solo de interfaces propias (`X25519`, `Kdf`, `Aead`,
  `Hash`), lo que permite cambiar de proveedor sin tocar el resto del framework.
- **Todas las dependencias se declaran con `implementation`, no con `api`.** El
  tipo de una dependencia de terceros no forma parte de la API pública de
  km-framework.
- **`km-core` no depende de Android ni de `dev.onvoid`**, y compila con toolchain
  JVM 11.

### Added — sesión E2E con un contacto, sin handshake de nodo (km-core)

Un `ContactBundle` verificado ya basta para abrir una sesión de mensajería
cifrada y usarla. Antes, el camino de aplicación exigía un `AuthSession` en
`AUTHENTICATED` aunque el par fuese una persona y no un nodo, así que el
"hablar con un contacto" solo se podía probar en el guion de pruebas, que
fabricaba en nombre del par el `AuthSession`, el `AuthOk` y el `NodeAnnouncement`
que en producción exigen el handshake completo.

- **`PeerAuthority`** (`com.km.node`, nuevo): tipo sumo cerrado, dentro de
  `PeerContext`, con dos casos y solo dos — `NodeAuthenticated` (handshake
  KM-0002 completo, con anuncio firmado) y `ContactVerified` (bundle que pasó
  `ContactBundle.verifyBundle`). `PeerContext` pierde `authSession`,
  `announcement` y `contactBundle` como campos: pasan a lecturas derivadas de la
  autoridad, y por eso son **nullables**, porque un contacto no tiene sesión de
  autenticación ni anuncio. `PeerContext.authenticated(...)` conserva firma y
  orden; `withContactBundle` se **elimina** (cero llamantes de producción) y su
  sustituto es `PeerContext.forContact(bundle)`.
- **El guard cambió de forma explícita y tipada**, no en silencio. Los cuatro
  sitios que leían `authSession.state` preguntan ahora
  `authority.authorizesPeerSession()`: `SecureMessagingSession.send`,
  `sendTranscript`, `PeerTransportManager.createBinding` y el `init` de
  `PeerTransportBinding`. Los rechazos observables no cambian
  (`SendReject.NOT_AUTHENTICATED`,
  `TransportError.PEER_CONTEXT_NOT_AUTHENTICATED`).
- **`authorizesPeerSession()` consulta el estado VIVO**, no una foto: un
  `AuthSession` que recibe `AUTH_FAIL` después de construir el contexto deja de
  autorizar en el instante. No existe ningún predicado que responda "está
  autenticado", ni `isNode`, ni `capabilities`: el único booleano se llama por
  lo que **autoriza**, que es la sesión 1:1 y su binding, nunca por lo que el
  par "es".
- **La no-negociabilidad es estructural.** Las superficies de rol
  (`PeerRoleDirectory.record`) consumen `NodeAnnouncement`, y `ContactVerified`
  ni lo contiene ni lo produce: `directory.record(autoridadDeContacto)` no
  compila. No hay conversión de `ContactVerified` a `NodeAuthenticated`.
- **`forContact` deriva el `remotePeerId`** del `identityRoot` con
  `KmIds.identityId` y no lo acepta como parámetro, así que el llamante no
  puede colar un `subjectDeviceId` como clave de enrutado. La granularidad es la
  **conversación**: una raíz por par, nunca compartida.
- **El grano de raíz gobierna los dos indexadores de la superficie 1:1**, no
  solo la rama de contacto: `SecureSessionRegistry` y `PeerTransportBinding`
  indexan por `remotePeerId`, y ese `remotePeerId` es la raíz en todo lo que este
  cambio construye con `forContact`. Lo que la fábrica de contacto **garantiza**
  es que *su* puerta produce siempre clave de raíz; `PeerContext.authenticated`
  no restringe la granularidad en absoluto y recibe la que le pase el llamante,
  que puede ser de dispositivo. Que las dos ramas coincidedan hoy en grano de
  raíz es consecuencia de dónde sale cada contexto, no de un invariante del tipo.
- **Cambio de comportamiento del `ACK` en el camino de núcleo.** El `ACK` lo firma
  `ReceiverImpl` con el `localIdentity` **del núcleo que lo recibe**, y
  `AckManagerImpl` lo correlaciona con `ack.from != message.to`, descartando en
  silencio si no coinciden. Como `message.to` es la raíz con la que se indexó la
  sesión, un consumidor que alimente su `KeyMessageCoreImpl` con un
  `localIdentity` de **dispositivo** deja de cerrar el ciclo de entrega: el `ACK`
  se descarta y no hay error. El mundo de pruebas (`SecureWorld`) pasa ahora a
  `localIdentity = root.identityId` —antes `device.identityId`— y por eso
  `EncryptedSendReceiveIntegrationTest` afirma que `ack.from` es la raíz del
  receptor. Es un cambio de comportamiento **del fixture y del contrato que
  exige**, no un parche cosmético: el emparejamiento raíz → clave de firma del
  dispositivo sigue siendo del consumidor.
- **`publicKey` de un contexto de contacto no está fijado por ninguna
  aserción.** `forContact` lo toma de `bundle.subjectSigningKey`, pero nada en la
  suite impide que sea otro valor: es dato *write-only*. El camino de nodo sí
  está fijado (`EndToEndRuntimeTest` afirma la clave del anuncio). Un consumidor
  que lo lea como "la clave del par" no tiene garantía.

Fuera de alcance, declarado: rediseño y cableado de la app Android, TUI y CLI;
federación relé-a-relé; DHT nuevo; cifrado at-rest; ACK cifrado (el ACK sigue
firmado **y en claro**).

#### Limitaciones activas que introduce este cambio

No son detalles de implementación: son el alcance real de lo que existe.

1. **Una sesión por raíz significa un dispositivo por conversación.** La clave
   de registro y la del binding son el `remotePeerId` del `PeerContext`, que con
   `forContact` es el `identityId` de la raíz y no el del dispositivo. Hablar con
   dos dispositivos de la misma persona desde un mismo proceso no está resuelto.
2. **Dos dispositivos de la misma raíz sustituyen la sesión anterior.**
   `SecureSessionRegistry.install` guarda una sesión por clave, luego la segunda
   sustituye a la primera; y el binding del segundo falla con
   `BINDING_ALREADY_EXISTS` mientras el del primero siga activo.
3. **El filtro `from == remotePeerId` admite el frame del otro dispositivo.**
   Con dos dispositivos de una misma raíz, la sesión de A admite el frame
   emitido para B. El ratchet lo rechaza, pero ese rechazo puede **avanzar
   estado**: es justamente el "estado de dos pares mezclado" contra el que avisa
   el KDoc de `SecureMessagingSession`.
4. **La selección de dispositivo queda pendiente.** Resolverla exige una clave
   compuesta `(identityRoot, subjectDeviceId)` en el registro y en el binding.
   `BootstrapPrekeys.fromVerified` ya ata la sesión a un `subjectDeviceId`
   concreto, luego la clave por raíz descarta información que el material sí
   distingue.
5. **`VerifiedContactBundle` no comprueba vigencia temporal.**
   `ContactBundle.verifyBundle` valida estructura, invariantes de binding y
   firma, pero no mira el reloj. La expiración de un bundle es una decisión de
   protocolo que todavía no está tomada.

Caveat preexistente que no se toca: `NodeIdentity.fromKeyPair` deriva `nodeId`
con `identityId(pubKey)` en vez de `KmIds.nodeId`, así que un par que sea nodo
y persona a la vez coincide por construcción solo si comparte clave de identidad.

### Limitaciones conocidas

Declaradas en el [README](README.md) y en [SECURITY.md](SECURITY.md), y en el
propio código donde aplican:

- Sin grupos, sin DHT, sin voz, aunque tengan RFC.
- Sin fragmentación de `SecureFrame`: el ciphertext se acota a 65535 bytes.
- `com.km.storage` es solo en memoria. Lo durable es la unidad criptográfica.
- `RelayServer` es una implementación en proceso para pruebas, no un servicio
  desplegable.
- `com.km.api.KeyMessageCore` es una fachada mínima de 9 métodos.
- `com.km.model.Attachment` está declarado y no está referenciado por nada.
- Sin CI: no hay workflows, y `.github/` no existe. Los tests se ejecutan en
  local.
- Los RFC están en `Draft` o `Draft (Frozen)`. Solo KM-0002 y KM-0004 están
  `Stable`.

### Sin auditorías

Ninguna auditoría de seguridad externa o interna revisada por terceros. Ninguna
afirmación de cumplimiento. No está en producción ni lo usa nadie conocido.
En una implementación criptográfica, la ausencia de auditoría es el primer
dato que hay que dar, no una nota al pie.
