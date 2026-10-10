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
| Publicación de artefactos | Solo `km-core`, y **solo a `mavenLocal()`**. Ni Maven Central ni releases firmadas. `km-webrtc`, `km-daemon` y `km-desktop` no publican. Ver [Cómo consumirlo](#cómo-consumirlo). |
| CI | No hay workflows. No existe directorio `.github/`. Los tests se ejecutan en local. |

---

## Requisitos

Lo mínimo para clonar este repositorio y construir el cliente de escritorio.

### Para ejecutar Gradle

| Requisito | Versión | Cómo se selecciona |
|-----------|---------|--------------------|
| JDK para **ejecutar** Gradle | **27** | `JAVA_HOME`, o `-Dorg.gradle.java.home=<ruta>` en la invocación. |
| Gradle | **9.5.0** | `gradle/wrapper/gradle-wrapper.properties`. **Solo el wrapper**: `./gradlew`. No hay que instalar nada. |
| RAM libre | **≥4 GB** (recomendación) | Empírica, de **una** máquina de prueba; no es un mínimo universal. Ver [RAM y máquinas ajustadas](#ram-y-máquinas-ajustadas). |
| Espacio libre (solo escritorio) | **~1,45 GB** | Medido en el portátil de validación con `publishToMavenLocal` + `installDist` + `shadowJar` y **sin** tests: `~/.gradle` 1,36 GB + repositorio y builds ~50 MB. |
| Espacio libre (build completo) | **~5 GB** (estimado) | Estimación para `./gradlew build`, que además compila `km-webrtc` (descarga de `libwebrtc` nativo) y ejecuta ~1187 tests. **No medido.** |
| Red de salida | HTTPS | `services.gradle.org` (el wrapper descarga la distribución), Maven Central y Google Maven. |

```sh
# Opción A: exportar el JDK una vez por terminal
export JAVA_HOME=/usr/lib/jvm/java-27-openjdk
./gradlew build

# Opción B: pasar el JDK solo en esa invocación
./gradlew -Dorg.gradle.java.home=/usr/lib/jvm/java-27-openjdk build
```

Los dos números de versión que más se confunden son **el JDK que ejecuta Gradle
(27)** y **el toolchain que compila el bytecode (11)**. No son el mismo requisito
y no se sustituyen:

| | Qué es | De dónde sale |
|---|--------|--------------|
| JDK 27 | El intérprete que corre el daemon de Gradle y las tareas de build. | Versión del JDK instalado en la máquina. |
| Toolchain 11 | El `javac`/`kotlinc` que **produce** el bytecode, y el nivel de compatibilidad del resultado. | `jvmToolchain(11)` en `km-core`, `km-webrtc`, `km-daemon` y `km-desktop`, más `sourceCompatibility/targetCompatibility = 11`. El build usa `foojay-resolver-convention`, que **descarga** el toolchain 11 si la máquina no lo tiene. |

Es decir: no hace falta instalar un JDK 11 para compilar. Solo hace falta el 27.

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
setsid nohup ./gradlew \
    -Dorg.gradle.java.home=/usr/lib/jvm/java-27-openjdk \
    -Pkotlin.compiler.execution.strategy=in-process \
    --max-workers=1 \
    -Dorg.gradle.jvmargs="-Xmx1300m -Dfile.encoding=UTF-8" \
    :km-core:publishToMavenLocal :km-desktop:shadowJar \
    > /tmp/km-build.log 2>&1 &
```

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

## Inicio rápido

De un clon limpio a un cliente en marcha, en Linux. Todo con `./gradlew` desde la
raíz del repositorio.

```sh
# 1. Clonar. El cliente de escritorio está DENTRO de este repositorio, en
#    km-desktop/. No hay un repositorio de GitHub aparte para él.
git clone https://github.com/KemaMada/km-framework.git
cd km-framework

# 2. Publicar km-core en mavenLocal. NO es opcional: km-desktop lo consume como
#    artefacto publicado (com.km:km-core:0.1.0-SNAPSHOT), no como proyecto del
#    build. Sin este paso, el paso 3 falla al resolver la dependencia.
./gradlew -Dorg.gradle.java.home=/usr/lib/jvm/java-27-openjdk \
          :km-core:publishToMavenLocal

# 3. Compilar el cliente de escritorio: un único jar con todo dentro.
./gradlew -Dorg.gradle.java.home=/usr/lib/jvm/java-27-openjdk \
          :km-desktop:shadowJar

# 4. Arrancar un relé propio. installDist deja un launcher con su classpath.
./gradlew -Dorg.gradle.java.home=/usr/lib/jvm/java-27-openjdk \
          :km-daemon:installDist
./km-daemon/build/install/km-daemon/bin/km-daemon

# 5. Arrancar la TUI, en otra terminal, apuntada a ese relé local.
java -jar km-desktop/build/libs/km-desktop.jar --relay ws://127.0.0.1:8080/km
```

Sin `--relay`, la TUI usa el relé desplegado, `wss://keymessage.xyz/km`, que es su
endpoint por defecto. El `km-daemon` del paso 4 **no enruta por ruta**: acepta el
handshake WebSocket en cualquier path, así que `/km` y `/ws` funcionan igual.

#### `installDist` o fat jar

Las dos vías funcionan y dan el mismo cliente; elige según cómo lo vayas a
ejecutar. (Antes este README llamaba a `installDist` «la vía recomendada frente a
un fat jar» y acto seguido lanzaba el fat jar: era contradictorio.) Verificado en
el portátil de validación:

| Vía | Comando | Salida | Tamaño | Cuándo usarla |
|-----|---------|--------|--------|---------------|
| `installDist` | `./gradlew :km-desktop:installDist` | `km-desktop/build/install/km-desktop/bin/km-desktop` | **~22 MB** repartidos en **34 jars** | Ejecutar en local o en desarrollo: es un launcher con su classpath y los jars resueltos por Gradle. |
| `shadowJar` | `./gradlew :km-desktop:shadowJar` | `km-desktop/build/libs/km-desktop.jar` | **~21 MB** en un solo fichero | Distribuir o copiar: un único jar con todo dentro, `Main-Class: com.km.desktop.DesktopMainKt`, bytecode major **55** = Java 11. |

Los dos scripts arrancan el mismo `main`, así que `--relay` y el resto de opciones
valen igual en ambos casos.

Al arrancar por primera vez la TUI no encuentra identidad: **crea una** y pide una
frase de paso nueva, dos veces. En los arranques siguientes pide la que la abre.
La copia de la identidad es la que los contactos necesitan para poder escribirte,
así que ese paso no es opcional en la práctica.

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

> **Estado de publicación: parcial.** `km-core` **sí** publica, pero **solo en
> `mavenLocal()`**: lleva el plugin `maven-publish`, con `group = "com.km"` y
> `version = "0.1.0-SNAPSHOT"`, y un destino `mavenLocal()`. No está en Maven
> Central ni en ningún registro público, así que **no existe un repositorio del
> que dependa alguien que no tenga km-core en su `~/.m2`**. `km-webrtc` no
> publica. Para consumir hoy, construye desde el fuente (`includeBuild`), usa un
> subtree, o publica primero con `:km-core:publishToMavenLocal`.

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

Un estado honesto, porque un clon limpio compila pero no envía mensajes cifrados,
y conviene saberlo antes de perder una tarde.

| Capacidad | Estado |
|-----------|--------|
| Autenticar contra el relé (`AUTH_*` completo, `sessionId` de 40 hex) | **FUNCIONA** |
| Enviar `MESSAGE` por el relé y que lo reciba el destinatario, con su mismo `messageId` | **FUNCIONA** |
| Recibir el `ACK` de vuelta y **autenticarlo** contra la clave del peer, con `MESSAGE` confirmado | **FUNCIONA** |
| Reconexión automática con backoff y jitter | **CÓDIGO LISTO, NO PROBADO** contra el relé real: el backoff está cubierto por tests deterministas, pero nadie ha tumbado el socket del relé para ver el ciclo entero |
| Que la carga del mensaje vaya **cifrada** | **NO EMPEZADO.** Las primitivas existen (`SecureFrame`, `DoubleRatchetSession`, `X3dh`) pero **no están cableadas** a este transporte. Lo que viaja por el cable es exactamente lo que `km-core` produce hoy: el `Message` va **firmado, pero en claro**. Ver [Estado y límites honestos](km-desktop/README.md#estado-y-límites-honestos) |
| Anonimato / soporte de Tor | **NO EXISTE.** No hay ninguna implementación; los RFC lo declaran fuera de alcance (§22 de KM-0005) |
| Fragmentación de `SecureFrame` | **NO.** Tope de 65535 B por carga |
| Store-and-forward (`STORED`, `RELAY_EXPIRED`) | **NO.** El codec está y el cliente los maneja; la lógica que los dispararía no existe |

La distinción que importa: **las primitivas criptográficas están escritas y
testeadas; el cableado E2E no está hecho.** Firmar un mensaje no es cifrarlo, y
confundir los dos es el error más caro que se puede cometer con este código.

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

## Bootstrap de contactos — DISEÑADO, no implementado

> **Estado: DISEÑADO.** No hay ningún comando, ninguna opción y ningún formato de
> fichero que haga esto hoy. Está aquí para que quede escrito antes de que se
> implemente, y para que nadie asuma que ya existe.

Un protocolo sin servidor de descubrimiento necesita una manera de que dos
personas se queden con la clave pública de la otra. El diseño previsto tiene dos
caminos, deliberadamente uno reducido y otro completo.

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
pública con la que luego establecer la sesión (X3DH + Double Ratchet).

### El camino reducido: una clave pública a pelo

Para un contacto técnico, o para probar, importar una clave Ed25519 desnuda es
suficiente — y si no hay nombre, **el nombre por defecto es `user`**. Es
deliberado: un contacto sin nombre es un dato que existe, y mostrar `user` es
mejor que mostrar una cadena vacía o un `identityId` hexadecimal.

Esto tiene una consecuencia práctica, y es a propósito: **importar una clave a
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
| `PeerKeyDirectory` en `km-desktop`: registro de una clave de firma pública, con recálculo de coherencia contra `KmIds.identityId`, y persistencia en `peers.json` | **IMPLEMENTADO** |
| Comando de TUI `peer <identityIdHex> <claveBase64>` | **IMPLEMENTADO**, y es el camino reducido sin nombre |
| Nombre visible por contacto | **NO EMPEZADO.** `peers.json` guarda `identityId` y `publicKey`, y nada más |
| Fichero `.km`, importación y exportación de contactos | **NO EMPEZADO** |
| Nombre por defecto `user` al importar una clave a pelo | **NO EMPEZADO** |
| QR de contacto en el escritorio | **NO EMPEZADO** (y el escaneo, por diseño, tampoco) |
| QR de identidad en Android | **IMPLEMENTADO**: `MainActivity.kt` genera y escanea con ZXing, en el formato `nombre\|clavePública`. Sin nombre, esta app usa `"User"` como reserva. El escritorio no tiene QR |

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
(`SecureFrame`, X3DH, Double Ratchet) está escrito y cubierto por tests pero
**no cableado al transporte**, y hay RFCs escritos más una campaña de mutaciones
sobre la persistencia atómica.

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
