# km-framework

**km-framework** es la implementación de referencia del protocolo **KeyMessage**:
la infraestructura criptográfica y de transporte P2P que otras aplicaciones
consumen para hablar entre sí de forma segura.

No es una aplicación de mensajería. No tiene interfaz de usuario, ni bandeja
de entrada, ni lista de contactos, ni notificaciones. Es la capa de la que se
sirve un cliente.

```
km-framework   =  protocolo + criptografía + transporte
un cliente     =  km-framework  +  interfaz de usuario  +  almacenamiento
```

Los cuatro módulos del repositorio son:

| Módulo | Qué es | Dependencias |
|--------|--------|--------------|
| `km-core` | Identidad, autenticación, negociación, X3DH, Double Ratchet, SecureFrame, persistencia. | Sin Android. Sin WebRTC. |
| `km-webrtc` | Transporte WebRTC: establecimiento de `PeerConnection`, señalización contra el relé, detección de plataforma nativa. | `km-core` + `webrtc-java` (JNI + libwebrtc nativo). |
| `km-daemon` | El lado del **relé**: aplicación headless que habla KM-WIRE-RELAY sobre WebSocket. Es el proceso que actúa de nodo de señalización y de relay. | `km-core` + Java-WebSocket. |
| `km-desktop` | Cliente **TUI** de escritorio: Kotlin/JVM, un solo binario, sin GUI. Es el que se instala clonando este repositorio. | `km-core` como artefacto publicado. |

**`km-desktop` vive dentro de este repositorio**, en `km-desktop/`. No hay un
repositorio de GitHub aparte para él: se clona **este** repo y se compila el
módulo. Ver [`km-desktop/README.md`](km-desktop/README.md).

El cliente Android es lo único que vive en otro repositorio
([`KemaMada/km-android`](https://github.com/KemaMada/km-android)); ver su
[`README.md`](https://github.com/KemaMada/km-android) para los requisitos
propios.

---

## Qué NO es

- **No es un cliente de mensajería.** No envía ni muestra mensajes. `com.km.api.KeyMessageCore`
  es una fachada mínima; la interfaz de usuario la pone el consumidor.
- **No es un servidor de relé para producción.** `com.km.node.RelayServer` es una
  implementación en proceso usada por las pruebas y los tests de integración. No
  tiene autenticación de operador, ni limitación de tasa global, ni persistencia
  entre reinicios, ni métricas. El proceso de relé que sí habla el cable es
  `km-daemon` (ver [`km-daemon/README.md`](km-daemon/README.md)), y arrastra
  estas mismas carencias.
- **No está auditado.** No ha pasado ninguna revisión de seguridad externa,
  ni certificación, ni evaluación formal de los algoritmos. Ver
  [Estado del proyecto](#estado-del-proyecto).
- **No está en producción.** No está publicado en Maven Central ni en ningún
  registro, y no hay releases firmadas.
- **No es estable.** No hay versión `1.0`. La API y el formato de wire pueden
  cambiar sin aviso (ver [Renombrado de paquetes](#mapeo-de-renombrado-de-paquetes)).

---

## Mapeo de renombrado de paquetes

Los paquetes se renombraron de `com.keymessage.core.*` a `com.km.*`. El segmento
`core` desaparece y los nombres se acortan: los identificadores de especificación
(`kmid`, `km7`, `km52`, `sf`) ceden el sitio a palabras del vocabulario del
protocolo.

Este renombrado es un **cambio incompatible**. La API pública, los imports y los
nombres de clase que los referencian han cambiado. No hay versión publicada, así
que no hay compatibilidad que preservar: no hay shim, no hay `@Deprecated`, no
hay alias.

| Antes | Ahora | Qué contiene |
|-------|-------|--------------|
| `com.keymessage.core.kmid` | `com.km.identity` | `KmIds`, `DeviceRoster`, `ContactBundle`, `Kce`, `KmSchemas` — identidad, dispositivos, ContactBundle, Canonical Encoding. |
| `com.keymessage.core.km7` | `com.km.negotiation` | `Km7Negotiation`, `Km7Types`, `Km7Codec` — negociación de capacidades, serialización, compresión y suites (KM-0007). |
| `com.keymessage.core.sf` | `com.km.frame` | `SecureFrame`, `SecureFrameCodec`, `SecureFrameProtector` — contenedor cifrado del camino caliente (KM-0004). |
| `com.keymessage.core.km52` | `com.km.unit` | `Km52Types`, `Km52Codec` — formato de la unidad de transmisión persistida, versión 2. |
| `com.keymessage.core.crypto` | `com.km.crypto` | `Ed25519`, `X25519`, `Kdf`, `Aead`, `Hash`, `SignatureVerifier`, `AgreementKeyGuard`. |
| `com.keymessage.core.crypto.provider` | `com.km.crypto.provider` | `BcX25519`, `BcHkdfSha256`, `BcChaCha20Poly1305` — **único** punto donde entra Bouncy Castle. |
| `com.keymessage.core.ratchet` | `com.km.ratchet` | `DoubleRatchetSession`, `DoubleRatchetSnapshot`, `SymmetricRatchet`, `RatchetSessionBootstrap`. |
| `com.keymessage.core.x3dh` | `com.km.x3dh` | `X3dh`, `X3dhSpec` — bootstrap de sesión (KM-0006). |
| `com.keymessage.core.node` | `com.km.node` | `NodeRuntime`, `RelayService`, `RealRelayTransport`, `TransportChain`, `PeerContext`. |
| `com.keymessage.core.protocol` | `com.km.protocol` | `Sender`, `Receiver`, `AckManager`, `RetryManager`, `OrderingManager`, `Transport`. |
| `com.keymessage.core.messaging` | `com.km.messaging` | `SecureMessagingSession`, `SecureMessageReceiver`, `MessageEnvelope`, `PendingInbox`. |
| `com.keymessage.core.transmit` | `com.km.transmit` | `FileTransmitUnitStore`, `SecureTransmitJournal`, `TransmitLedger`, `ChainRetentionBook`. |
| `com.keymessage.core.storage` | `com.km.storage` | `MessageStore`, `OfflineQueue`, `RelayStore`, `DuplicateStore` y sus implementaciones en memoria. |
| `com.keymessage.core.codec` | `com.km.codec` | `JsonEncoder`, `JsonDecoder`, `JsonRelayControlCodec`, `Validator`. |
| `com.keymessage.core.model` | `com.km.model` | `Message`, `Ack`, `IdentityId`, `MessageId`, `NodeIdentity`, `NodeAnnouncement`, `StoredMessage`, … |
| `com.keymessage.core.api` | `com.km.api` | `KeyMessageCore`, `KeyMessageCoreImpl` — la fachada. |
| `com.keymessage.core.auth` | `com.km.auth` | `AuthVerifier`, `AuthSession`, `Transcripts`, `Base64Url`, `RelayAuthHandler`. |
| `com.keymessage.webrtc` | `com.km.webrtc` | `ManagedWebRtcPeer`, `RealWebRtcTransport`, `RelaySignaling`, `WebRtcEstablishment`, `WebRtcPlatformDetector`. |

**Lo que el renombrado NO tocó.** Los *nombres de tipo* siguen llevando el
identificador de especificación: `Km7Negotiation`, `Km52Codec`, `KmIds`,
`KmIdConstants`, `KmSchemas`. Solo cambiaron los paquetes. `km-id-reference/`
sigue llamándose así, y las rutas de especificación siguen siendo
`docs/rfc/KM-000N-*.md`. Si esperabas `com.km.negotiation.CapabilitySet` en lugar
de `com.km.negotiation.Km7Types`, todavía no está.

---

## Arquitectura

```
  ┌──────────────────────────────────────────────────────────────┐
  │                    Consumidores                              │
  │                                                              │
  │   km-desktop (TUI)   km-android (Compose)     tu cliente     │
  │   Linux + Windows    repo propio               propio        │
  │   ESTE repositorio   separado                 separado       │
  └──────────────────────┼─────────────────────────┼─────────────┘
                         │                             │
                         ▼                             ▼
  ┌──────────────────────────────────────────────────────────────┐
  │                    km-webrtc   (com.km.webrtc)               │
  │   ManagedWebRtcPeer · RealWebRtcTransport                   │
  │   RelaySignaling · WebRtcEstablishment                       │
  │   └─ webrtc-java 0.19.0  →  libwebrtc nativo (classifier)   │
  ├──────────────────────────────────────────────────────────────┤
  │                    km-core    (com.km.*)                     │
  │                                                              │
  │  node       · relé, transporte, binding peer↔transporte     │
  │  protocol   · sender, receiver, ACK, retry, orden            │
  │  messaging  · sesión de mensajería segura, PendingInbox     │
  │  frame      · SecureFrame v1 (44 B, header íntegro como AAD) │
  │  ratchet    · Double Ratchet + establishment                 │
  │  x3dh       · bootstrap de sesión                            │
  │  identity   · identityId, deviceId, ContactBundle, KCE      │
  │  unit       · unidad persistida KM52 v2                      │
  │  transmit   · medio físico con publicación atómica           │
  │  auth       · KM-0002: verifier, transcripts, Base64URL     │
  │  crypto     · interfaces propias + provider Bouncy Castle   │
  │  negotiation· KM-0007: intersección de capacidades          │
  │  codec      · JSON / KCE                                     │
  │  storage    · MessageStore, OfflineQueue, DuplicateStore    │
  │  model      · Message, Ack, IdentityId, NodeIdentity…       │
  │  api        · KeyMessageCore (fachada)                       │
  ├──────────────────────────────────────────────────────────────┤
  │  km-daemon   ·  el relé como proceso: KM-WIRE-RELAY sobre    │
  │                WebSocket, auth, camino de datos, señalización│
  ├──────────────────────────────────────────────────────────────┤
  │  km-id-reference/  ·  oráculo Python de KM-ID-0001          │
  │                      (normativo; vectores congelados)         │
  └──────────────────────────────────────────────────────────────┘
                         │
                         ▼
              red  ·  PeerConnection / relé
```

`km-core` no depende de `km-webrtc`: la dependencia va en el sentido contrario.
`km-core` no tiene dependencia alguna de Android ni de `dev.onvoid`, y compila
para un toolchain JVM 11.

`km-daemon` depende **solo** de `km-core`, y en un solo sentido: arrastrar
`km-webrtc` al proceso del relé traería ~25 MB de libwebrtc nativo que el relé
no usa. `km-desktop`, en cambio, consume `km-core` como **artefacto publicado**
(`com.km:km-core:0.1.0-SNAPSHOT` resuelto desde `mavenLocal()`), no como
`project(":km-core")`: es un consumidor del framework, no una parte de él. Por eso
el primer paso de cualquier build de `km-desktop` es
`:km-core:publishToMavenLocal`.

---

## Estado real

Todo lo de esta tabla está comprobado leyendo el código de este repositorio a
**2026-09-30**. Lo que no está en la tabla de "Implementado" **no está
implementado**, por mucho que exista en `docs/rfc/`.

### Implementado y cubierto por tests

| Capacidad | Dónde | Notas |
|-----------|-------|-------|
| Identidad: `identityId`, `deviceId`, `nodeId` | `com.km.identity` | Derivación con separación de dominio (`KM-ID-IDENTITY`, `KM-ID-DEVICE`, `KM-NODE-NODE`). |
| `DeviceRoster`, cadena de hashes, revocación | `com.km.identity` | Validación de mundo cerrado contra `KmSchemas`. |
| `ContactBundle` y binding dispositivo↔identidad | `com.km.identity` | Un bundle que dice "móvil" y lleva la clave del PC se rechaza. |
| Canonical Encoding (KCE) | `com.km.identity.Kce` | Perfil de CBOR: rechaza flotantes, etiquetas, `null`, longitudes indefinidas, claves duplicadas y texto no NFC. |
| Autenticación KM-0002 | `com.km.auth` | `AuthVerifier` con V1–V6 (derivación de `identityId`, firma del transcript, ventana de skew, guarda de replay de nonce). Transcripts de 152 y 120 bytes. |
| Base64URL estricto (RFC 4648 §5, sin padding) | `com.km.auth.Base64Url` | El padding en el wire se rechaza. |
| Negociación de capacidades KM-0007 | `com.km.negotiation` | Intersección por categoría, selección por preferencia **local**, determinista. Capacidad desconocida se ignora, no se rechaza. |
| `SecureFrame` v1 | `com.km.frame` | Header fijo de 44 bytes, los 44 bytes íntegros son el AAD. `length` acota el ciphertext a 65535 bytes. El nonce **no viaja**: se deriva por HKDF. |
| Double Ratchet | `com.km.ratchet` | Ratchet de raíz + cadenas, con dominios separados para RootKey y ChainKey. |
| X3DH (KM-0006) | `com.km.x3dh` | `DH1..DH4`, `SK`, `K_prekey`. Transaccional: ni `initiate` ni `respond` mutan estado. Separación de claves: solo X25519 de acuerdo, nunca Ed25519. |
| Unidad persistida **KM52 v2** | `com.km.unit`, `com.km.transmit` | `unitVersion = 2`. Bloque `PENDING_INBOUND`: los frames entrantes que la sesión aún no pudo usar se guardan **en la misma unidad atómica** que el estado criptográfico. |
| Medio físico con publicación atómica | `com.km.transmit.FileTransmitUnitStore` | Escribir temporal → sincronizar → `rename` sobre el hueco → sincronizar directorio. Un corte nunca convierte una escritura a medias en una unidad aparentemente entera. |
| Recuperación de la unidad | `com.km.transmit.SecureTransmitJournal` | El medio persiste bytes, sin interpretarlos. Recuperación y verificación leen. |
| Transporte WebRTC | `com.km.webrtc` | Establecimiento de `PeerConnection`, señalización contra el relé, selección de clasificador nativo por plataforma. |
| Ciclo de vida de mensajes | `com.km.protocol`, `com.km.messaging` | ACK, reintento, orden, detección de duplicados, `OfflineQueue`. |
| Codecs JSON y KCE | `com.km.codec` | `Message`, `Ack` y control de relé. |

**Tests: 1187, 0 fallos** (1095 en `km-core`, 92 en `km-webrtc`) a 2026-09-30.
JUnit 5 vía `useJUnitPlatform()`.

### Declarado pero no cableado

| Cosa | Estado real |
|------|-------------|
| `com.km.api.KeyMessageCore` | Interfaz de 9 métodos (`start`, `stop`, `createMessage`, `sendMessage`, `getMessageState`, `getConversation`, tres `register*Handler`). Es una fachada mínima, no una API de aplicación. |
| `com.km.model.Attachment` | Clase de datos declarada. **No hay transporte de adjuntos**: no está referenciada por ningún otro fichero de `km-core/src/main`. |
| `com.km.node.RelayServer` | Servidor de relé en proceso, para pruebas e integración. No es un servicio desplegable. |
| `com.km.node.FakeTransportBackend` | Backend de transporte falso, para pruebas. |

### No implementado

Esto **no** existe en el código, aunque tenga RFC en `docs/rfc/`:

| Capacidad | Nota |
|-----------|------|
| Grupos / mensajería grupal | Sin una sola referencia a grupos en `km-core` ni `km-webrtc`. |
| DHT | Sin implementación. |
| Voz / llamadas | Sin implementación. |
| Fragmentación de `SecureFrame` | El propio código lo dice: cargas mayores de 65535 bytes "requieren fragmentacion (incremento posterior)". |
| Transports distintos de relé y WebRTC | `TransportBackend` es la extensión; solo hay relé real y un backend falso. |
| Persistencia durable de mensajes | `com.km.storage` solo tiene implementaciones **en memoria** (`InMemoryMessageStore`, `InMemoryOfflineQueue`, `InMemoryRelayStore`, `InMemoryDuplicateStore`). Lo que sí es durable es la unidad criptográfica, en `com.km.transmit`. |
| Publicación de artefactos | Solo `km-core` y `km-transport`, y **solo a `mavenLocal()`**. Ni Maven Central ni releases firmadas. `km-webrtc`, `km-daemon` y `km-desktop` no publican. Ver [Cómo consumirlo](#cómo-consumirlo). |
| CI | No hay workflows. No existe directorio `.github/`. Los tests se ejecutan en local. |

---

## Requisitos

Lo mínimo para clonar este repositorio y construir el cliente de escritorio.

### Para ejecutar Gradle

| Requisito | Versión | Cómo se selecciona |
|-----------|---------|--------------------|
| JDK para **ejecutar** Gradle | **17 o superior** | `JAVA_HOME`, o `-Dorg.gradle.java.home=<ruta>` en la invocación. Validado con 17.0.2 y con 27 (ver [la nota del JDK](#sobre-el-jdk-dato-no-suposición)). |
| Gradle | **9.5.0** | `gradle/wrapper/gradle-wrapper.properties`. **Solo el wrapper**: `./gradlew`. No hay que instalar nada. |
| RAM libre | **≥4 GB** (recomendación) | Empírica, de **una** máquina de prueba; no es un mínimo universal. Ver [RAM y máquinas ajustadas](#ram-y-máquinas-ajustadas). |
| Espacio libre (solo escritorio) | **~1,45 GB** | Medido en el portátil de validación con `publishToMavenLocal` + `installDist` + `shadowJar` y **sin** tests: `~/.gradle` 1,36 GB + repositorio y builds ~50 MB. |
| Espacio libre (build completo) | **~5 GB** (estimado) | Estimación para `./gradlew build`, que además compila `km-webrtc` (descarga de `libwebrtc` nativo) y ejecuta ~1187 tests. **No medido.** |
| Red de salida | HTTPS | `services.gradle.org` (el wrapper descarga la distribución), Maven Central y Google Maven. |

```sh
# Opción A: exportar el JDK una vez por terminal
export JAVA_HOME=/ruta/a/tu/jdk-17-o-superior
./gradlew build

# Opción B: pasar el JDK solo en esa invocación
./gradlew -Dorg.gradle.java.home=/ruta/a/tu/jdk-17-o-superior build
```

> Los ejemplos de este README usan `/ruta/a/tu/jdk-17-o-superior` como marcador.
> En la máquina de validación ese JDK estaba en `/usr/lib/jvm/java-27-openjdk`,
> pero **27 no es obligatorio**: 17 también compila y arranca (ver la tabla).

Los dos números de versión que más se confunden son **el JDK que ejecuta Gradle
(17 o superior)** y **el toolchain que compila el bytecode (11)**. No son el mismo
requisito y no se sustituyen:

| | Qué es | De dónde sale |
|---|--------|--------------|
| JDK 17+ | El intérprete que corre el daemon de Gradle y las tareas de build. | Versión del JDK instalado en la máquina. **17 es el mínimo medido**; el 27 también sirve. |
| Toolchain 11 | El `javac`/`kotlinc` que **produce** el bytecode, y el nivel de compatibilidad del resultado. | `jvmToolchain(11)` en `km-core`, `km-webrtc`, `km-daemon` y `km-desktop`, más `sourceCompatibility/targetCompatibility = 11`. El build usa `foojay-resolver-convention`, que **descarga** el toolchain 11 si la máquina no lo tiene. |

Es decir: no hace falta instalar un JDK 11 para compilar (el toolchain lo baja el
build). Hace falta un JDK **17 o superior** para lanzar Gradle; el 27 también vale.

`gradle.properties` **no** fija `org.gradle.java.home`, y es deliberado: es una
ruta absoluta que no viaja entre máquinas, así que hardcodearla rompería el clon
de cualquiera que tenga el JDK en otro sitio. Si prefieres no pasar `-D` en cada
comando, ponla en `~/.gradle/gradle.properties`, que tiene precedencia sobre el
fichero del repositorio.

### RAM y máquinas ajustadas

> **Recomendación empírica, no requisito.** El número de abajo sale de **una**
> máquina de prueba (un portátil Arch Linux con **3,6 GB** de RAM y JDK 27). No es
> un mínimo universal: puede que a ti te baste menos, o que necesites más, según
> la carga del sistema y la versión del toolchain.

En esa máquina, un `./gradlew` normal agotó la memoria: `gradle.properties` fija
`org.gradle.jvmargs=-Xmx2048m`, y el compilador de Kotlin arranca un **segundo**
JVM. El swap llegó a 1824/1824 MB con load 14,6 y el host se reinició a mitad de
`compileKotlin`. Por eso, para este build, **se recomiendan ≥4 GB de RAM libres**;
si vas justo, la compilación puede necesitar ajustes.

En una máquina ajustada, lo que funcionó fue:

- `-Pkotlin.compiler.execution.strategy=in-process`, que ejecuta el compilador de
  Kotlin **dentro** del daemon de Gradle y **elimina el segundo JVM** — es la
  bandera decisiva;
- `--max-workers=1`, para no compilar módulos en paralelo;
- bajar el heap del daemon, p. ej. `-Dorg.gradle.jvmargs="-Xmx1300m ..."`;
- lanzarlo **desacoplado** (`setsid nohup … &`), para que una caída de la sesión
  SSH no mate el build a medias.

```sh
# Una invocacion por tarea, en este orden: publicar core, publicar transport,
# compilar desktop. Ver "Clonar y arrancar" para el porque.
for tarea in :km-core:publishToMavenLocal :km-transport:publishToMavenLocal :km-desktop:shadowJar; do
    ./gradlew \
        -Dorg.gradle.java.home=/ruta/a/tu/jdk-17-o-superior \
        -Pkotlin.compiler.execution.strategy=in-process \
        --max-workers=1 \
        -Dorg.gradle.jvmargs="-Xmx1300m -Dfile.encoding=UTF-8" \
        "$tarea" || break
done
```

(Para lanzarlo desacoplado, envuelve el bucle con `setsid nohup sh -c '...' > /tmp/km-build.log 2>&1 &`.)

`gradle.properties` **no** se toca: su `-Xmx2048m` se mantiene, porque en una
máquina con RAM de sobra es la configuración correcta.

### Otros

| Requisito | Versión | Nota |
|-----------|---------|------|
| Python (opcional) | 3.x | Solo para `km-id-reference/` y el andamiaje de mutaciones de `tools/`. |
| Android SDK | — | **No hace falta** para `km-core`, `km-webrtc`, `km-daemon` ni `km-desktop`. El `local.properties` de la raíz solo lo leen los plugins de Android, que aquí no se aplican. |

### Red y puertos

| Qué | Detalle |
|-----|---------|
| Salida HTTPS | `services.gradle.org`, `repo.maven.apache.org`, `dl.google.com`. Sin salida a internet el wrapper no descarga la distribución, y `mavenLocal()` no resuelve `com.km:km-core`. |
| TCP **8080** | Puerto del `km-daemon` por defecto (`listenHost = "0.0.0.0"`, `listenPort = 8080`). Configurable con `listenPort` (rango 1–65535) o con `KM_DAEMON_LISTEN_PORT`. |
| Salida **443** | Solo si usas el relé desplegado, que es el endpoint por defecto de la TUI: `wss://keymessage.xyz/km`. Con un `km-daemon` propio en `ws://` no hace falta. |
| Privilegios | **Ninguno.** No hay que abrir puertos privilegiados ni ejecutar nada como root. Solo tiene que ser alcanzable el relé. |

**Pruebas entre varias máquinas.** La vía cómoda es una red privada como
**Tailscale**: el relé corre en una máquina y los clientes apuntan a su dirección
`100.x`, sin abrir nada en el router. La alternativa es alcanzar el relé
públicamente, que para entonces necesita un `listenHost` alcanzable y el firewall
de la máquina permitiendo ese puerto. No hace falta ningún privilegio especial en
ninguno de los dos casos: la condición es que el cliente llegue al relé.

---

## Install and run the TUI (instalación y ejecución)

Un solo recorrido: de un clon a la TUI en marcha y, de ahí, a la APK Android. Es
la vía práctica; el detalle de cada pieza está en [Inicio rápido](#inicio-rápido),
en [`km-desktop/README.md`](km-desktop/README.md) y en
[`docs/developer/p2p.md`](docs/developer/p2p.md).

### Requisitos

| Qué | Mínimo | Nota |
|-----|--------|------|
| Sistema operativo | Linux, macOS, Windows 10+ | `./km` es `sh` POSIX (Linux/macOS); en Windows se usa `km.cmd`. **macOS no se ha ejecutado**: el script es POSIX y el jar es Java puro, pero no está medido. `km.cmd` tampoco se ha ejecutado en un runner Windows; su equivalencia está revisada leyendo el script. |
| Terminal | Color, **≥60x20** | La TUI de pantallas necesita direccionamiento de cursor y ese tamaño. Por debajo, sin tty o con `TERM=dumb`, se avisa y se usa el modo líneas. El **QR** pide además **≥85x43** (85x43..88x46 sin marco; 89x47 y 100x50 con el chrome completo). Sin color **no** se pinta el QR: se degrada a texto y `.km`. |
| JDK para **compilar** | **17 o superior** | Medido con 17.0.2 y con 27: los dos compilan y arrancan la TUI. Ver [la nota del JDK](#sobre-el-jdk-dato-no-suposición). |
| JRE para **ejecutar** | **11 o superior** | El fat jar es bytecode major **55** = Java 11. |
| Gradle | **9.5.0** | Wrapper incluido (`./gradlew`): no hay que instalar Gradle. |
| Toolchain de compilación | **11** | `jvmToolchain(11)`; el build lo descarga solo (`foojay-resolver-convention`) si falta. No hay que instalarlo a mano. |
| RAM libre | ≥4 GB (recomendación) | Empírico, de una máquina de 3,6 GB. Ver [RAM y máquinas ajustadas](#ram-y-máquinas-ajustadas). |

#### Sobre el JDK (dato, no suposición)

Este repositorio decía que Gradle «exige JDK 27». Se comprobó el 2026-10-30 con
el JDK 17.0.2 del portátil de validación:

```sh
$ /home/kemamada/jdk-17.0.2/bin/java -version
openjdk version "17.0.2" 2022-01-18
$ KM_GRADLE_JAVA_HOME=/home/kemamada/jdk-17.0.2 KM_REBUILD=1 ./km --help
km: construyendo km-desktop (publishToMavenLocal de km-core/km-webrtc/km-transport + shadowJar)...
km-desktop — cliente TUI de KeyMessage
...                                   # exit 0
```

Sale con **exit 0** y sin warnings de Gradle ni del compilador de Kotlin. El build
que lo produjo:

| Dato | Valor |
|------|-------|
| Gradle | **9.5.0** (wrapper) |
| Kotlin del proyecto | **2.2.10** (catálogo); Gradle embebe Kotlin 2.3.20 para sus scripts |
| Daemon JVM | `/home/kemamada/jdk-17.0.2` (de `-Dorg.gradle.java.home`) |
| Toolchain / bytecode | 11 / major **55** |

El mensaje del lanzador que decía «Gradle necesita JDK 27» era una suposición del
script y ya está corregido. La recomendación es, por tanto:

> **JDK 17+ para compilar; JRE 11+ para ejecutar. Validado también con 27.**

El 17 sirve igual para la app Android (`app/`); el APK de abajo se validó con el
27 porque es el JDK instalado en esta máquina. El **27 no es obligatorio**.

### Clonar y arrancar

| Shell | Comandos |
|-------|----------|
| Linux / macOS | `git clone https://github.com/KemaMada/km-framework.git`<br>`cd km-framework`<br>`./km` |
| Windows 10+ | `git clone https://github.com/KemaMada/km-framework.git`<br>`cd km-framework`<br>`km.cmd` |

La **primera vez** no hay jar, y el lanzador hace cuatro cosas, **cada una en su
propio proceso de Gradle y en este orden**:

1. publica `com.km:km-core` `0.1.0-SNAPSHOT` en `mavenLocal()`
   (`:km-core:publishToMavenLocal`);
2. publica `com.km:km-webrtc` (`:km-webrtc:publishToMavenLocal`), el tier P2P que
   `km-desktop` consume **por artefacto**;
3. publica `com.km:km-transport` (`:km-transport:publishToMavenLocal`);
4. ejecuta `:km-desktop:shadowJar` y deja **un** fichero,
   `km-desktop/build/libs/km-desktop.jar` (~36 MB, `Main-Class:
   com.km.desktop.DesktopMainKt`),
5. arranca con `exec java -jar` y tus argumentos tal cual.

Las cuatro invocaciones son deliberadas. `km-webrtc` y `km-transport` declaran
`api("com.km:km-core:…")` y `km-desktop` consume los **tres** artefactos, pero
`publishToMavenLocal` **no** es dependencia de los `compileKotlin` del mismo
build: pedidas juntas, Gradle puede compilar un consumidor antes de que
`km-core` exista en `mavenLocal` y falla con `Could not find com.km:km-core`
(reproducido con `mavenLocal` vacío). Publicar solo core y transport **tampoco
basta** en un `mavenLocal` vacío: `km-desktop` declara `com.km:km-webrtc` y el
build falla con `Could not find com.km:km-webrtc`. Compartir invocación además
deja al compilador de Kotlin leer un jar a medio escribir
(`newPosition > limit`). Publicar core, **luego** webrtc y transport, y compilar
después, quita las dos carreras.

Después de eso solo reconstruye si falta el jar, si `KM_REBUILD=1`, o si hay
fuentes o ficheros de build más nuevos que el jar. La salida del build va a
**stderr**: stdout es de la TUI.

| Variable | Efecto |
|----------|--------|
| `KM_JAVA=<ruta>` | JVM que **ejecuta** el jar. Por defecto, `java` del `PATH`. |
| `KM_GRADLE_JAVA_HOME=<ruta>` | JDK que ejecuta **Gradle**. Por defecto, `JAVA_HOME`. Tiene que ser 17+. |
| `KM_REBUILD=1` | Fuerza el paso de build aunque el jar exista (Gradle decide qué recompilar). |

### Flags

| Flag | Efecto |
|------|--------|
| `--tui` | TUI de pantallas. Es el **defecto**; se pide para ser explícito. |
| `--accessible` | Salida lineal append-only, sin repintado ni direccionamiento de cursor. Para lectores de pantalla. |
| `--no-tui` | REPL de líneas de G1 (scripts y emergencia). |
| `--ascii` | Sin glifos Unicode ni color. |
| `--theme <color\|mono>` | `color` (defecto, respeta `NO_COLOR`/`TERM`) o `mono`, sin SGR: el QR se degrada a texto/`.km`. |
| `--mouse <auto\|on\|off>` | Ratón en la TUI (clic izquierdo y rueda). `auto` (defecto) solo con TUI viable y terminal con soporte; `off` devuelve la selección nativa; `on` con `--accessible`/`--no-tui` es error de uso (exit 2). |
| `--wheel-rows <1..10>` | Filas por muesca de rueda (defecto 3). |
| `--bootstrap <url>` | Bootstrap, **repetible** y en orden: se prueban uno tras otro hasta que uno responda. También `KM_BOOTSTRAPS` (separados por coma) o `KM_BOOTSTRAP_URL`. |

El resto (`--relay`, `--relay-identity`, `--keystore`, `--peers`, `--reset`,
`--verbose`, `-h`) está en `./km --help`.

### P2P opcional (WebRTC)

Por defecto **no** hay P2P: el transporte es relay-only, byte a byte.

| Variable | Valores | Nota |
|----------|---------|------|
| `KM_DESKTOP_P2P` | `off` (defecto), `auto`, `on` | `auto` **exige TURN**: sin TURN se queda en relay (`p2p_no_turn`). `on` exige P2P y falla si no hay ICE (`p2p_on_no_ice`) o si solo hay STUN (`p2p_on_no_turn`, exit 2). Un valor desconocido cae a `off`. |
| `KM_ICE_STUN_URLS` | lista por comas, `stun:`/`stuns:` | Solo descubre la IP pública; no basta para atravesar NAT. |
| `KM_ICE_TURN_URLS` | lista por comas, `turn:`/`turns:` | Imprescindible para `auto`/`on`. |
| `KM_ICE_TURN_SECRET` | secreto compartido con coturn | Preferido: deriva una credencial efímera. **Nunca** aparece en logs, barra ni export. |
| `KM_ICE_TURN_USERNAME` / `KM_ICE_TURN_CREDENTIAL` | usuario / credencial estática | Alternativa al secreto. La credencial **nunca** se imprime. |
| `KM_ICE_TURN_TTL_SECONDS` | segundos | Vida de la credencial efímera (defecto 86400). |

Con `KM_DESKTOP_P2P=off` las `KM_ICE_*` **ni se parsean**: una URL inválida no
rompe el arranque. El ACK viaja siempre por el relay. Detalle, presupuestos de 8 s
y rollback en [`docs/developer/p2p.md`](docs/developer/p2p.md).

#### Android

El movil usa la MISMA politica (`P2pMode`/`P2pPlan` viven en `km-core`) y un
backend distinto (`km-webrtc-android`, sobre `org.webrtc`). El binario arranca en
`off`; el ajuste y los servidores ICE viven en preferencias, no en el APK:

```bash
adb shell run-as com.example.keymessage cat shared_prefs/km_p2p.xml
# mode = off|auto|on
# turn = turn:host:3479      user = kmdesktop
# secret = <TURN REST>       (nunca se versiona)
```

- `auto` exige TURN, igual que escritorio.
- Solo DataChannel: **sin permisos de cámara ni micrófono**.
- Release: `arm64-v8a` + `armeabi-v7a`. Debug añade `x86_64` para el emulador.
- `DependencyOriginTest` prohíbe `dev.onvoid` siempre y permite `org.webrtc` solo
  con `com.km:km-webrtc-android` en el classpath.
- Los intentos se rearman con jitter (ANSWERER ~1 s, OFFERER ~5 s) para que dos
  sesiones establecidas con desfase coincidan.

### APK Android

La app vive en `app/` y es un **build aparte** (su propio `settings.gradle.kts`,
que no está incluido en el de la raíz).

#### 1. SDK de Android

Hace falta `ANDROID_HOME` o un `app/local.properties` (**no versionado**) con la
ruta del SDK:

```sh
export ANDROID_HOME="$HOME/Android/Sdk"                 # opcion A
# opcion B: app/local.properties (lo ignora git)
printf 'sdk.dir=%s\n' "$HOME/Android/Sdk" > app/local.properties
```

Sin ninguno de los dos el build de la app falla con `SDK location not found`; el
del framework **no** lo necesita.

#### 2. Build de desarrollo (sin firma, sin keystore)

```sh
./gradlew -p app -Dorg.gradle.java.home=/ruta/a/tu/jdk-17-o-superior :assembleDebug
adb install -r app/build/outputs/apk/debug/KeyMessageApp-debug.apk
```

`:assembleDebug` **no** pide keystore ni credenciales: es la vía para cualquier
clon.

#### 3. Release firmado (keystore propio, no distribuido)

El release se firma con `app/keymessage-release.jks`, que **no se versiona ni se
distribuye** (PKCS12, alias `keymessage`). Sin ese fichero no hay APK de release.

```sh
# 1) keystore en su sitio (solo quien lo tenga)
cp /ruta/privada/keymessage-release.jks app/keymessage-release.jks
chmod 600 app/keymessage-release.jks

# 2) credenciales FUERA del repo: ~/.gradle/gradle.properties (modo 600) o
#    variables de entorno. NUNCA en app/build.gradle.kts.
#      KM_RELEASE_STORE_PASSWORD=...
#      KM_RELEASE_KEY_PASSWORD=...      (PKCS12: el mismo valor)
#      KM_RELEASE_KEY_ALIAS=keymessage  (opcional; es el default)

# 3) publicar core y transport ANTES de la app: en ese orden y en invocaciones
#    separadas, por el mismo motivo de orden que el lanzador
./gradlew -Dorg.gradle.java.home=/ruta/a/tu/jdk-17-o-superior :km-core:publishToMavenLocal
./gradlew -Dorg.gradle.java.home=/ruta/a/tu/jdk-17-o-superior :km-transport:publishToMavenLocal

# 4) APK firmada
./gradlew -p app -Dorg.gradle.java.home=/ruta/a/tu/jdk-17-o-superior :assembleRelease
adb install -r app/build/outputs/apk/release/KeyMessageApp-release.apk
```

- **`:assembleRelease` no funciona en un clon ajeno**: sin el `.jks` (y sin las
  credenciales) falla en `validateSigningRelease` con un mensaje que dice cómo
  definirlos, y **no imprime valores**. Es esperado, no un bug: para probar la
  app desde un clon usa `:assembleDebug`.
- `versionCode 3`, `versionName "1.2"`, `minSdk 28`, `targetSdk 36`.
- Certificado esperado, SHA-256
  `af12fd06fc0945b7e8c778714f7742eb7616cbcb09ea8fae0faec581f0d712d1`. Rotar la
  contraseña (`keytool -storepasswd`) **no** cambia la clave ni el certificado,
  así que las actualizaciones siguen instalándose encima.
- `adb install -r` conserva los datos. Instalar un `versionCode` **menor** que el
  instalado falla con `INSTALL_FAILED_VERSION_DOWNGRADE`: hay que desinstalar
  antes, y eso borra el almacén cifrado de identidad del teléfono.

### Troubleshooting

| Síntoma | Qué pasa | Qué hacer |
|---------|----------|-----------|
| `Unable to resolve host "keymessage.xyz"` en la barra o en `--verbose` | No hay DNS o no hay salida 443. El relé por defecto es `wss://keymessage.xyz/km`. | Comprobar la red; o usar un relé propio: `./km --relay ws://127.0.0.1:8080/km`. |
| `PASSPHRASE_WRONG` | La frase escrita no abre el almacén de **este** equipo. La frase de paso no es la de recuperación. | Repetir la frase de paso; si no se recuerda, `R` restaura desde las 24 palabras (**sobrescribe** el almacén: la identidad vuelve, contactos y mensajes no). |
| El anillo del icono Android no se ve entero | El aro negro es parte del dibujo (88 de 108 px), mayor que la zona segura de 72/108 del icono adaptativo, así que el launcher lo recorta con su máscara. Con iconos temáticos (Android 13+) se usa la capa monocroma y desaparecen el color y el aro. | Nada: no es un fallo de la APK. Es cómo funciona el icono adaptativo. |
| Terminal sin color y no hay QR | Con `--theme mono`, `--ascii` o `NO_COLOR` el QR saldría invertido (ilegible), así que **no se pinta**. | Abrir un terminal con color (`--theme color`); si no lo hay, usar el `.km` o el texto que ofrece la pantalla. |
| El jar no se reconstruye tras editar | Solo se compara con fuentes y ficheros de build conocidos. | `KM_REBUILD=1 ./km ...`. |

---

## Inicio rápido

De un clon limpio a un cliente en marcha. El atajo es el lanzador de la raíz:

```sh
# 1. Clonar. El cliente de escritorio está DENTRO de este repositorio, en
#    km-desktop/. No hay un repositorio de GitHub aparte para él.
git clone https://github.com/KemaMada/km-framework.git
cd km-framework

# 2. Arrancar. La primera vez no hay jar: el lanzador publica km-core y
#    km-transport en mavenLocal, compila el fat jar y arranca.
./km
```

`./km` (Linux/macOS) y `km.cmd` (Windows) compilan solo si falta el jar,
ejecutan `java -jar` con todos los argumentos y propagan el código de salida.
Gradle necesita **JDK 17 o superior** (validado con 17.0.2 y con 27); el jar corre
en JRE 11 o superior. Ver [Lanzadores `km` y `km.cmd`](#lanzadores-km-y-kmcmd).

Los mismos pasos a mano, sin el lanzador:

```sh
# 1. Clonar. El cliente de escritorio está DENTRO de este repositorio, en
#    km-desktop/. No hay un repositorio de GitHub aparte para él.
git clone https://github.com/KemaMada/km-framework.git
cd km-framework

# 2. Publicar km-core, km-webrtc y km-transport en mavenLocal. NO es opcional:
#    km-desktop los consume como artefactos publicados (0.1.0-SNAPSHOT), no como
#    proyectos del build. En TRES invocaciones y en este orden: webrtc y transport
#    dependen de core, y publicar juntos deja al scheduler compilar un consumidor
#    antes de que core exista en mavenLocal.
./gradlew -Dorg.gradle.java.home=/ruta/a/tu/jdk-17-o-superior :km-core:publishToMavenLocal
./gradlew -Dorg.gradle.java.home=/ruta/a/tu/jdk-17-o-superior :km-webrtc:publishToMavenLocal
./gradlew -Dorg.gradle.java.home=/ruta/a/tu/jdk-17-o-superior :km-transport:publishToMavenLocal

# 3. Compilar el cliente de escritorio: un único jar con todo dentro.
./gradlew -Dorg.gradle.java.home=/ruta/a/tu/jdk-17-o-superior \
          :km-desktop:shadowJar

# 4. Arrancar un relé propio. installDist deja un launcher con su classpath.
./gradlew -Dorg.gradle.java.home=/ruta/a/tu/jdk-17-o-superior \
          :km-daemon:installDist
./km-daemon/build/install/km-daemon/bin/km-daemon

# 5. Arrancar la TUI, en otra terminal, apuntada a ese relé local.
java -jar km-desktop/build/libs/km-desktop.jar --relay ws://127.0.0.1:8080/km
```

Sin `--relay`, la TUI usa el relé desplegado, `wss://keymessage.xyz/km`, que es su
endpoint por defecto. El `km-daemon` del paso 4 **no enruta por ruta**: acepta el
handshake WebSocket en cualquier path, así que `/km` y `/ws` funcionan igual.

#### Lanzadores `km` y `km.cmd`

| Shell | Lanzador | Requisito |
|-------|----------|-----------|
| Linux / macOS | `./km` (POSIX `sh`, LF, ejecutable) | JDK 17+ para compilar; JRE 11+ para ejecutar |
| Windows 10+ | `km.cmd` (batch, CRLF) | JDK 17+ para compilar; JRE 11+ para ejecutar |

Los dos hacen lo mismo: si `km-desktop/build/libs/km-desktop.jar` no existe (o si
`KM_REBUILD=1`), publican `km-core`, `km-webrtc`, `km-transport` y ejecutan
`:km-desktop:shadowJar` —**cuatro invocaciones separadas, en ese orden**—;
después lanzan `exec java -jar` con los argumentos tal cual y devuelven el mismo
código de salida. La salida del build va a **stderr**, para que stdout siga
siendo de la interfaz.

```sh
./km --help                                  # ayuda completa, exit 0
./km --relay ws://127.0.0.1:8080/km          # relé propio
./km --no-tui                                # REPL de líneas (scripts)
./km --mouse=off                             # sin tracking: selección nativa del terminal
KM_REBUILD=1 ./km --ascii                    # fuerza el build y arranca sin glifos
```

| Opción | Efecto |
|--------|--------|
| `--tui` | TUI de pantallas (defecto). Exige terminal de al menos 60x20 y con direccionamiento de cursor; si no, se avisa y se usa el modo líneas. |
| `--accessible` | Salida lineal append-only, sin repintado ni direccionamiento de cursor. |
| `--no-tui` | REPL de líneas de G1, con los comandos de la tabla de `help`. |
| `--ascii` | Sin glifos Unicode ni color. |
| `--theme <color\|mono>` | `color` (defecto) respeta `NO_COLOR`/`TERM`; `mono` apaga todo SGR (el QR se degrada a texto/`.km`). |
| `--mouse <auto\|on\|off>` | Ratón en la TUI: clic izquierdo y rueda. `auto` (defecto) lo activa solo con TUI viable y terminal con soporte; `off` lo apaga y devuelve la selección nativa; `on` con `--accessible`/`--no-tui` o sin soporte es error de uso (exit 2). |
| `--wheel-rows <1..10>` | Filas por muesca de rueda (defecto 3). |
| `--relay`, `--bootstrap`, `--relay-identity`, `--keystore`, `--peers`, `--reset`, `--verbose` | Igual que en el jar: ver `./km --help`. |
| `-h`, `--help` | Ayuda, sin abrir la red. |

| Variable | Efecto |
|----------|--------|
| `KM_REBUILD=1` | Fuerza el paso de build aunque el jar exista (Gradle decide qué recompilar). |
| `KM_JAVA=<ruta>` | JVM que ejecuta el jar. Por defecto, `java` del `PATH`. |
| `KM_GRADLE_JAVA_HOME=<ruta>` | JDK que ejecuta Gradle. Si no se define, se usa `JAVA_HOME`. |

**La TUI en una pantalla.** Tres pestañas raíz (`Alt-1..3`: INICIO, FRASE,
DIAGNOSTICO) con su propia pila de navegación, barra de estado (identidad corta,
relé, presencia del par, contadores `S`/`K`, aviso VOLÁTIL) y pie con las teclas
de la pantalla. INICIO, CONVERSACION y DIAGNOSTICO usan **todo el ancho**, como
WhatsApp de escritorio: una fila por contacto (presencia, alias, último mensaje,
hora y `S`/`K`) con separador fino y, en el hilo, burbujas alineadas por autor
(`[ ... ]` propias a la derecha, `{ ... }` del par a la izquierda). `Tab`/`Shift-Tab`
recorren los campos enfocables (pestañas, lista, filtro, compositor) y `?` abre la
ayuda, ventaneada. Las listas se desplazan con `↑`/`↓`/`j`/`k`, `PgUp`/`PgDn`,
`Home`/`End`, con indicador textual `[n/m]` que solo aparece si la lista no cabe;
en la conversación el hilo sigue el final y, si subes, los mensajes nuevos se
anuncian como `[N nuevos ↓]` en vez de moverte la vista. `--theme mono` (o
`NO_COLOR`/`--ascii`) deja la jerarquía en manos de `▌`, `>`, `┃`, etiquetas y
mayúsculas: **sin color no se pinta el QR**, porque saldría invertido, y la
pantalla ofrece texto y `.km`. Con color, el QR se pinta **centrado** y su fondo
blanco cubre solo sus celdas, no una banda hasta el borde del terminal.

**Ratón.** Con `--mouse=auto` (defecto) o `--mouse=on`, la TUI acepta **clic
izquierdo** y **rueda** (SGR 1006). Un clic press+release en la misma zona abre
el chat de una fila, cambia de pestaña, pulsa un botón del diálogo, ejecuta la
acción del pie (copiar, `.km`, texto, exportar…) o da el foco al compositor/filtro.
La rueda desplaza **±`--wheel-rows`** (3 por defecto) sobre la lista bajo el
cursor, con el foco siempre visible y `followEnd` intacto; fuera de una lista o
con el foco en un campo de texto no mueve. **Ninguna acción es solo-ratón**: todas
tienen su tecla y `?` las repite. Hover, doble clic, arrastre, redimensionar
arrastrando, menús contextuales y toque **no** están implementados. Un evento
SGR de movimiento (bit 32) se descarta en el decodificador: si un multiplexor
lo reenvía, no puede convertirse en clic.

El tracking **captura el arrastre**: mientras está activo, la selección nativa
del terminal no funciona (no hay copiar-y-pegar con el ratón). Para recuperarla,
`--mouse=off`; el bypass del **terminal** (no de la app) es `Shift`+arrastre en
la mayoría de terminales. En tmux hay que activar su propio ratón
(`set -g mouse on`); en la consola de Windows (conhost) el SGR 1006 puede no
llegar y se degrada a teclado (declarado, `--mouse=off`).

Límites conocidos: no hay **hover, doble clic ni arrastre de ratón**, no hay
temas configurables más allá de `color`/`mono`, los contactos y mensajes son
**volátiles**, en MONO el backdrop de los diálogos no se pinta y el chrome del QR
baja con el tamaño —85x43..88x46 sin marco, 89x47 con marco compacto sin
pestañas, 100x50 con bandas completas— para no recortarlo nunca. El detalle está
en [`km-desktop/README.md`](km-desktop/README.md#teclas-pestañas-y-scroll).

`km.cmd` no se ha ejecutado en un runner Windows en este repositorio: su
equivalencia con `km` está revisada leyendo el script, no medida. Se declara.

#### `installDist` o fat jar

Las dos vías funcionan y dan el mismo cliente; elige según cómo lo vayas a
ejecutar. (Antes este README llamaba a `installDist` «la vía recomendada frente a
un fat jar» y acto seguido lanzaba el fat jar: era contradictorio.) Verificado en
el portátil de validación:

| Vía | Comando | Salida | Tamaño | Cuándo usarla |
|-----|---------|--------|--------|---------------|
| `installDist` | `./gradlew :km-desktop:installDist` | `km-desktop/build/install/km-desktop/bin/km-desktop` | **~22 MB** repartidos en **34 jars** | Ejecutar en local o en desarrollo: es un launcher con su classpath y los jars resueltos por Gradle. |
| `shadowJar` | `./gradlew :km-desktop:shadowJar` | `km-desktop/build/libs/km-desktop.jar` | **~36 MB** (medido el 2026-10-30) en un solo fichero | Distribuir o copiar: un único jar con todo dentro, `Main-Class: com.km.desktop.DesktopMainKt`, bytecode major **55** = Java 11. |

Los dos scripts arrancan el mismo `main`, así que `--relay` y el resto de opciones
valen igual en ambos casos.

Al arrancar por primera vez la TUI no encuentra identidad: ofrece **crear una**
(frase de paso nueva, dos veces) o **recuperar la identidad** desde la frase de
recuperación de 24 palabras que muestra Android. En los arranques siguientes pide
la frase de paso que abre el almacén, con `[R]` disponible para restaurar y
sobrescribir. La copia de la identidad es la que los contactos necesitan para
poder escribirte, así que ese paso no es opcional en la práctica.

**Frase de paso** y **frase de recuperación** no son lo mismo: la primera cifra el
almacén de *este* equipo; la segunda deriva la *identidad* (la misma que Android).
Restaurar recupera la identidad, **no** contactos ni mensajes, y **regenera** las
claves de dispositivo: el peer verá un dispositivo nuevo. Los detalles están en
[`km-desktop/README.md`](km-desktop/README.md#frase-de-paso-y-frase-de-recuperación).

### Tests

```sh
./gradlew :km-core:test        # el núcleo del protocolo
./gradlew :km-daemon:test      # el relé: codec del cable y reenvío de ACK
./gradlew :km-desktop:test     # incluye un E2E que levanta el km-daemon real
./gradlew :km-webrtc:test      # levanta PeerConnections nativas de libwebrtc
```

`:km-desktop:test` es el que más tarda y el único que abre un socket de verdad:
levanta el `km-daemon` en proceso (con un puerto libre reservado antes) y conecta
dos transportes reales contra él para cerrar `MESSAGE(id=M)` → `ACK` →
confirmación.

---

## Cómo consumirlo

> **Estado de publicación: parcial.** `km-core` y `km-transport` **sí** publican,
> pero **solo en `mavenLocal()`**: llevan el plugin `maven-publish`, con
> `group = "com.km"` y `version = "0.1.0-SNAPSHOT"`, y un destino `mavenLocal()`.
> No están en Maven Central ni en ningún registro público, así que **no existe un
> repositorio del que dependa alguien que no tenga esos artefactos en su
> `~/.m2`**. `km-webrtc` no publica. Para consumir hoy, construye desde el fuente
> (`includeBuild`), usa un subtree, o publica primero con
> `:km-core:publishToMavenLocal :km-transport:publishToMavenLocal`.

El grupo previsto es `com.km`:

```kotlin
// settings.gradle.kts
dependencyResolutionManagement {
    repositories {
        mavenCentral()
    }
}
```

```kotlin
// build.gradle.kts
dependencies {
    // Solo el protocolo: identidad, autenticación, X3DH, ratchet, SecureFrame,
    // persistencia. Sin Android, sin WebRTC, sin código nativo.
    implementation("com.km:km-core:<version>")

    // Añade el transporte WebRTC. Implica libwebrtc nativo: el artefacto con
    // clasificador se resuelve en tiempo de ejecución según la plataforma.
    implementation("com.km:km-webrtc:<version>")
}
```

Elegir solo `km-core` es una decisión real, no cosmética: es el único módulo que
no arrastra un `.so`/`.dll`/`.dylib` de ~25 MB. `km-core` por sí solo no puede
establecer una `PeerConnection`; necesita un `Transport` que tú inyectes.

Ambos módulos declaran sus dependencias con `implementation`, no con `api`. Es
decir: **el tipo de una dependencia de terceros no forma parte de la API pública
de km-framework**. Si expones un tipo de Jackson o de `webrtc-java` en tu propia
firma, Tus consumidores necesitarán esa dependencia por su cuenta.

---

## Modelo de roles — DISEÑADO, no implementado entero

> **Estado: DISEÑADO.** La arquitectura es esta; lo que hay hoy es un
> subconjunto. Nada de lo que sigue es una afirmación sobre el código actual
> salvo donde se marca explícitamente.

El modelo previsto es que **no hay un servidor privilegiado**. Cualquier cliente
de escritorio puede subir y bajar del rol de nodo de señalización y de relay, y
el rol es por sesión, no por identidad.

| Rol | Qué hace | Qué necesita |
|-----|----------|--------------|
| **Cliente** | Envía y recibe mensajes. Es el rol por defecto de cualquier cliente de escritorio. | Un `km-desktop` en marcha. Nada más. |
| **Nodo de señalización** | Aloja el intercambio de SDP/ICE entre dos peers para que puedan abrir la `PeerConnection`. | `km-daemon` con `signalingEnabled: true`. |
| **Relay** | Mueve `MESSAGE` y `ACK` entre dos peers que no se alcanzan directamente, y los mantiene en línea aunque el otro esté dormido. | `km-daemon` con `relayEnabled: true`. |

Los dos últimos roles son el **mismo proceso**. `km-daemon` es a la vez nodo de
señalización y relay, y ambos caminos se encienden y apagan por separado
(`signalingEnabled`, `relayEnabled`). Eso es lo que hace que «subir» un cliente a
relay sea **arrancar un proceso**, nada más: **cualquiera que clone este
repositorio puede ser el relé** para sus propios contactos, sin pedirle permiso a
nadie y sin servidor central.

El **móvil es un cliente fino**. No aloja lógica de protocolo: `km-android`
implementa las interfaces de almacenamiento (`com.km.storage.*`) sobre Room y los
contratos de transporte (`com.km.protocol.Transport`,
`com.km.node.RelayTransport`) sobre WebSocket, y todo lo criptográfico lo pide a
`km-core`. El móvil puede subir a signalling/relay **más adelante**, pero no es
necesario para que el modelo se sostenga: el diseño no depende de que exista.

Qué hay hoy de esto:

| Pieza | Estado |
|-------|--------|
| `km-daemon` hablando el dialecto del cable, con `MESSAGE`, `ACK`, `PING`/`PONG`, `PEER_ONLINE`/`PEER_OFFLINE` y el handshake `AUTH_*` completo | **IMPLEMENTADO** |
| Relay moviendo datos entre dos clientes de escritorio de verdad, con `ACK` autenticado en el emisor | **IMPLEMENTADO** (`RelayAckEndToEndTest`, `km-desktop`) |
| Señalización SDP/ICE expuesta en el cable | **DISEÑADO.** El dialecto la tiene como extensión explícita con `kind` ∈ {`sdp_offer`, `sdp_answer`, `ice_candidate`, `contact_exchange`}; el catálogo normativo no define un tipo `SIGNAL`, y esa divergencia está abierta |
| Un cliente de escritorio que **suba** a relay sin arrancar un proceso aparte | **NO EMPEZADO** |
| Móvil como relay | **NO EMPEZADO**, y fuera del camino crítico |

---

## Construir y testear

```sh
./gradlew build                # compila y testea todo
./gradlew :km-core:test        # solo km-core
./gradlew :km-daemon:test      # solo km-daemon
./gradlew :km-desktop:test     # solo km-desktop (incluye el E2E con el daemon real)
./gradlew :km-webrtc:test      # solo km-webrtc (levanta PeerConnections nativas)
./gradlew :km-webrtc:test --tests 'com.km.webrtc.*Test'   # una clase
```

Con `-Dorg.gradle.java.home=/ruta/al/jdk-27` delante si no has exportado
`JAVA_HOME`. Ver [Inicio rápido](#inicio-rápido) para el recorrido completo desde
un clon limpio.

`km-webrtc` es una suite de integración real: levanta `PeerConnection` nativas de
libwebrtc. Necesita el artefacto con clasificador de tu plataforma
(`linux-x86_64`, `linux-aarch64`, `linux-aarch32`, `macos-x86_64`,
`macos-aarch64`, `windows-x86_64`, `windows-aarch64`), que el propio build
detecta a partir de `os.name` y `os.arch`.

Ver [CONTRIBUTING.md](CONTRIBUTING.md) para estilo, commits convencionales y el
andamiaje de mutaciones.

---

## Lo que funciona y lo que no, en una línea

Un estado honesto: un clon limpio compila, envía cifrado y tiene TUI de pantallas,
pero los contactos y los mensajes viven **solo en memoria**, el relé no guarda y
la interfaz **no está auditada**.

| Capacidad | Estado |
|-----------|--------|
| Autenticar contra el relé (`AUTH_*` completo, `sessionId` de 40 hex) | **FUNCIONA** |
| Enviar `MESSAGE` por el relé y que lo reciba el destinatario, con su mismo `messageId` | **FUNCIONA** |
| Recibir el `ACK` de vuelta y **autenticarlo** contra la clave del peer, con `MESSAGE` confirmado | **FUNCIONA** |
| Reconexión automática con backoff y jitter | **CÓDIGO LISTO, NO PROBADO** contra el relé real: el backoff está cubierto por tests deterministas, pero nadie ha tumbado el socket del relé para ver el ciclo entero |
| Que la carga del mensaje vaya **cifrada** | **FUNCIONA** en el camino de la TUI: `DesktopMain` compone el núcleo con `secureSessionRegistry` y `PeerTransportManager`, y sin sesión `send` rechaza con **cero bytes** (`SecureSendRejectionTest`). El `Message` viaja dentro de `SecureFrame`; la recepción en claro está cerrada (`allowLegacyCleartextReceive = false`) y `DesktopSendCiphertextTest` comprueba que el texto no aparece en el cable. Ver [Estado y límites honestos](km-desktop/README.md#estado-y-límites-honestos) |
| TUI de pantallas (contactos, conversación, importar/compartir, frase, diagnóstico) | **FUNCIONAL, NO AUDITADA.** Cubierta por tests puros de reducer, renderer y saneo, y por tests de bytecode; no se ha probado en un `pty` real ni con revisión externa |
| Contactos | **VOLÁTILES.** `InMemoryContactBook`: se pierden al reiniciar. `persist-contact-book` está especificado y **no aplicado** |
| Anonimato / soporte de Tor | **NO EXISTE.** No hay ninguna implementación; los RFC lo declaran fuera de alcance (§22 de KM-0005) |
| Fragmentación de `SecureFrame` | **NO.** Tope de 65535 B por carga |
| Store-and-forward (`STORED`, `RELAY_EXPIRED`) | **NO.** El codec está y el cliente los maneja; la lógica que los dispararía no existe. El relé descarta si el par no está |

La distinción que importa: **enviar cifrado no es persistir ni entregar.** El
camino P2P cifrado funciona y el `ACK` se autentica; lo que no existe es
almacenamiento (contactos, historial, store-and-forward) y no hay auditoría
externa de la interfaz. `SENT` significa que el socket local aceptó bytes, no que
el par los recibiera: solo un `ACK` verificado pinta `ENTREGADO`.

---

## Especificaciones

Las especificaciones del protocolo están en [`docs/`](docs/) y son la
autoridad normativa; el código las implementa.

| Ruta | Contenido |
|------|-----------|
| `docs/rfc/KM-0000-terminology.md` | **Terminología oficial.** Empieza por aquí: `Identity`, `Device`, `Peer`, `Relay`, `Session`, `Capability`… |
| `docs/rfc/KM-0001-protocol-architecture-overview.md` | Objetivos, no-objetivos, principios (P0–P7), capas, ciclo de vida, invariantes. |
| `docs/rfc/KM-0002-authentication.md` | Autenticación. *Stable.* |
| `docs/rfc/KM-0003-relay-protocol.md` | Relé. *Draft (Frozen).* |
| `docs/rfc/KM-0004-message-protocol.md` | SecureFrame, ciclo de vida, ACK, duplicados, orden, `OfflineQueue`. *Stable (Frozen).* |
| `docs/rfc/KM-0005-node-and-relay-protocol.md` | Nodo y relé. |
| `docs/rfc/KM-0006-session-bootstrap.md` | X3DH. |
| `docs/tests/KM-TEST-0001-conformance.md` | 29 casos de prueba de conformidad e integración, con matriz de cobertura. |
| `docs/architecture/` | Revisiones de arquitectura. |
| `docs/api/`, `docs/developer/` | Reservados. |

**Los RFC están en `Draft` o `Draft (Frozen)`.** Solo KM-0002 y KM-0004 están
`Stable`. Un RFC en `Draft` puede cambiar.

---

## Bootstrap de contactos — implementado en G1, con límites

> **Estado: IMPLEMENTADO en G1.** `import`, `export`, `announce` y `session`
> existen en el REPL y la TUI tiene pantallas de IMPORTAR y COMPARTIR. Límites
> abiertos: el escritorio **no escanea** QR, los contactos son **volátiles** y el
> reenvío tras reconexión es acotado (un `contact_exchange`, 25 s), con su prueba
> en red real pendiente.

Un protocolo sin servidor de descubrimiento necesita una manera de que dos
personas se queden con la clave pública de la otra. El diseño tiene dos caminos,
deliberadamente uno reducido y otro completo.

### El camino completo: un fichero `.km`

Un contacto es un fichero, no un enlace. La primera vez, el cliente exporta su
propio contacto; el otro lo recibe por el medio que sea (correo, pendrive,
mensaje) y lo importa.

- **Contenido**: un `ContactBundle` **firmado** (`com.km.identity.ContactBundle`)
  más un nombre visible para que la lista de contactos no muestre 64 hex.
- El bundle lleva un snapshot firmado del `DeviceRoster` y de las prekeys
  vigentes, de modo que es **autónomo**: permite iniciar la relación sin ningún
  servicio. Es un bootstrap, no una fuente de verdad.
- El mismo contenido criptográfico sirve para el fichero y para el QR. **No
  existen dos protocolos distintos**, solo dos formas de transportar lo mismo.
- El bundle está sujeto al **invariante de binding**: no puede afirmar que
  pertenece al dispositivo X mientras lleva las claves de Y. El verificador
  tiene que comprobar que `subjectSigningKey` coincide con la del
  `DeviceEntry(subjectDeviceId)` y que el `deviceId` derivado encaja.

Lo que **no** puede hacer un `.km` es cifrar el mensaje: solo te da la clave
pública con la que luego establecer la sesión (X3DH + Double Ratchet). El camino
criptográfico de la sesión sí está cableado en el escritorio; el portador
(deflate + ISO-8859-1 + `Kce.validate` + `ContactBundle.verified`) vive en
`km-core` y lo usan los dos clientes.

### El camino reducido: una clave pública a pelo

Para un contacto técnico, o para probar, el comando `peer <identityIdHex>
<claveBase64>` registra una clave Ed25519 desnuda. Es deliberado que exista:
sirve para autenticar los `ACK` de un par sin haber intercambiado un bundle.

Esto tiene una consecuencia práctica, y es a propósito: **registrar una clave a
pelo no establece ninguna relación criptográfica.** Sirve para poder *responder*
a alguien que ya te tiene a ti, y para verificar sus `ACK`. Para escribirle la
primera vez hace falta un `ContactBundle` con prekeys.

### El QR: se muestra, no se escanea

El cliente de escritorio **muestra** el QR de su contacto en pantalla y **no
tiene escáner**. Es una decisión de diseño, no una carencia pendiente: un
escritorio no tiene cámara que ofrecer, así que el QR es una **salida** — se
pasa el móvil por delante de la pantalla del PC, o se lee a mano. El escaneo de QR
es del cliente móvil, que sí tiene cámara.

Qué hay hoy de todo esto:

| Pieza | Estado |
|-------|--------|
| `ContactBundle` en `km-core`: estructura, firma, invariante de binding, vectores dorados | **IMPLEMENTADO** |
| Codec del portador compartido (`CarrierCodec`, `CarrierFileCodec`) y fábrica del bundle propio (`SelfBundleFactory`) | **IMPLEMENTADO** en `km-core` (G1) |
| `PeerKeyDirectory` en `km-desktop`: registro de una clave de firma pública, con recálculo de coherencia contra `KmIds.identityId`, y persistencia en `peers.json` | **IMPLEMENTADO** |
| Comando `peer <identityIdHex> <claveBase64>` | **IMPLEMENTADO**, y es el camino reducido sin nombre |
| Fichero `.km`, importación y exportación de contactos | **IMPLEMENTADO** (G1): `import`/`export` y pantallas IMPORTAR/COMPARTIR |
| SIGNAL `contact_exchange` y PREKEY solo tras `Delivered` | **IMPLEMENTADO** en `km-transport` (G1), cableado en el escritorio |
| Nombre visible por contacto | **NO EMPEZADO.** La lista muestra la huella del `identityId`, no un alias |
| QR de contacto en el escritorio | **IMPLEMENTADO como SALIDA** (zxing-core). El escaneo no aplica: no hay cámara |
| QR de identidad en Android | **IMPLEMENTADO**: `MainActivity.kt` genera y escanea con ZXing, en el formato `nombre\|clavePública`. Sin nombre, esta app usa `"User"` como reserva. El escritorio no escanea |

---

## km-id-reference

`km-id-reference/` es una implementación de referencia de **KM-ID-0001**
(identidad, dispositivos y `ContactBundle`) escrita en **Python**. Es
**normativa**: los vectores de `vectors/` e `invalid/` son la referencia contra la
que se comprueba el port a Kotlin.

Su razón de existir es demostrar que la especificación produce **bytes
deterministas y reproducibles**, de forma que sirva de oráculo para cualquier
implementación, sea en Kotlin, Rust o Go. Los vectores no están escritos a mano:
los calcula esta implementación, y `FROZEN.sha256` fija su contenido.

```sh
cd km-id-reference
python3 generate.py    # regenera vectors/, invalid/ y MANIFEST.json
python3 selfcheck.py   # verifica los fixtures sin escribir nada
```

Por alcance, esta implementación **no** contiene y no debe contener nunca
WebRTC, DHT, relé, sockets, base de datos, mensajería, sesiones, Double Ratchet
ni X25519.

---

## Estado del proyecto

Pre-1.0, en desarrollo activo, sin auditoría. En concreto:

- **No se ha hecho ninguna auditoría de seguridad.** Ni externa, ni interna
  revisada por terceros. No hay informe, ni threat model publicado, ni
  penetration test.
- **No hay afirmaciones de cumplimiento** (SOC 2, ISO 27001, GDPR, FIPS 140-3…).
- **No se usa en producción** por nadie conocido. No hay notificaciones de
  vulnerabilidades, no hay usuarios, no hay SLA.
- **No es *production-ready* ni *battle-tested*.** No uses km-framework con datos
  que no puedas perder. Las primitivas criptográficas se apoyan en Bouncy Castle
  y en Ed25519 de `net.i2p.crypto`, que son implementaciones usadas en otros
  sitios, pero **el diseño del protocolo y su implementación no han sido
  revisados por nadie**.

Lo que sí se puede afirmar: el camino de relé y de `ACK` funciona de extremo a
extremo entre dos clientes de escritorio, el camino criptográfico completo
(`SecureFrame`, X3DH, Double Ratchet) está escrito, cubierto por tests y
**cableado al transporte del escritorio**; y hay RFCs escritos más una campaña de
mutaciones sobre la persistencia atómica. Lo que no existe es persistencia de
contactos/mensajes, store-and-forward ni auditoría de la TUI.

El recuento de tests que aparece arriba está fechado el 2026-09-30 y no se ha
re-medido: córrase `./gradlew test` para tener el número de hoy.

---

## Licencia

[MIT](LICENSE) — `Copyright (c) 2026 KemaMada`.

**MIT no incluye concesión explícita de patentes.** Es una decisión consciente,
no un descuido: la concesión de patentes de Apache-2.0 se añadió precisamente
porque el proyecto que la inspiró (WebRTC) la necesitaba, y este framework
depende de él. Aquí se ha preferido la simplicidad de MIT. Quien necesite esa
protección debe declarar sus propias invenciones en las contribuciones y asumir
el riesgo correspondiente. Ver [CHANGELOG.md](CHANGELOG.md).

Las dependencias de terceros y sus licencias están en
[`THIRD-PARTY-LICENSES/`](THIRD-PARTY-LICENSES/) y en [NOTICE](NOTICE).

---

## Contribución

Lee [CONTRIBUTING.md](CONTRIBUTING.md). Resumen: `./gradlew build` antes de abrir
un PR, commits convencionales, estilo oficial de Kotlin.

## Seguridad

Lee [SECURITY.md](SECURITY.md). **No abras issues públicos para reportar un
fallo de seguridad.** Este proyecto implementa criptografía; un hallazgo
criptográfico se maneja con discreción, no en un hilo público.
