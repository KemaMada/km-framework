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
| JDK para **ejecutar** Gradle | **17 o superior** | `JAVA_HOME`, o `-Dorg.gradle.java.home=<ruta>` en la invocación. Validado con 17.0.2 y con 27: ver [JDK: lo que se midió](#jdk-lo-que-se-midió). |
| Gradle | **9.5.0** | `gradle/wrapper/gradle-wrapper.properties` de la raíz del mono-repo. **Solo el wrapper**: `./gradlew`. No hay que instalar nada. |
| Toolchain de **compilación** | **11** | `jvmToolchain(11)`. El build lo descarga solo si la máquina no lo tiene (`foojay-resolver-convention`). |
| RAM libre | **≥4 GB** (recomendación) | Empírica, de **una** máquina de prueba (3,6 GB, JDK 27); no es un mínimo universal. Ver [RAM y máquinas ajustadas](#ram-y-máquinas-ajustadas). |
| Espacio libre (solo escritorio) | **~1,45 GB** | Medido con `publishToMavenLocal` + `installDist` + `shadowJar` y **sin** tests: `~/.gradle` 1,36 GB + repo y builds ~50 MB. |
| Espacio libre (build completo) | **~5 GB** (estimado) | Para `./gradlew build`, que además compila `km-webrtc` (nativo) y corre ~1187 tests. **No medido.** |
| Red de salida | HTTPS | `services.gradle.org`, Maven Central, Google Maven. |
| Android SDK | — | **No hace falta.** |

```sh
# Opción A: exportar el JDK una vez por terminal
export JAVA_HOME=/ruta/a/tu/jdk-17-o-superior
./gradlew :km-core:publishToMavenLocal

# Opción B: pasar el JDK solo en esa invocación
./gradlew -Dorg.gradle.java.home=/ruta/a/tu/jdk-17-o-superior \
          :km-core:publishToMavenLocal
```

El JDK que **ejecuta** Gradle (**17 o superior**; en la máquina de validación era
el 27, pero 17 también sirve) y el toolchain que **produce** el bytecode (11) son
dos requisitos distintos. El jar que sale corre sobre un JRE 11 o superior; el 27
no es obligatorio para lanzar el build.

`gradle.properties` **no** fija `org.gradle.java.home`, y es a propósito: es una
ruta absoluta que no viaja entre máquinas. Para no pasar `-D` en cada comando,
ponla en `~/.gradle/gradle.properties`, que tiene precedencia.

### RAM y máquinas ajustadas

> **Recomendación empírica, no requisito.** En **una** máquina de prueba (portátil
> Arch Linux, **3,6 GB** de RAM, JDK 27), un `./gradlew` normal agotó la memoria:
> el segundo JVM del compilador de Kotlin llevó el swap a 1824/1824 MB con load
> 14,6 y el host se reinició a mitad de `compileKotlin`. Por eso se recomiendan
> **≥4 GB** de RAM libres para este build. No es un mínimo universal.

En una máquina ajustada, lo que funcionó: `-Pkotlin.compiler.execution.strategy=in-process`
(elimina el segundo JVM — la bandera decisiva), `--max-workers=1`, un heap menor
para el daemon (`-Dorg.gradle.jvmargs="-Xmx1300m …"`) y lanzarlo desacoplado
(`setsid nohup … &`) para que una caída de SSH no lo mate. El detalle completo
está en el [README de la raíz](../README.md#ram-y-máquinas-ajustadas).
`gradle.properties` **no** se toca: su `-Xmx2048m` se mantiene.

### Para ejecutar

| Requisito | Detalle |
|-----------|---------|
| JRE | **11 o superior.** El jar es bytecode Java 11. |
| Terminal | TUI: terminal real de al menos **60x20** con direccionamiento de cursor. Por debajo de eso, `TERM=dumb` o salida no-tty, se avisa y se usa el modo líneas. `NO_COLOR` y `--ascii` desactivan color y glifos. |
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
cd km-framework

# 2. Arrancar con el lanzador de la RAÍZ. La primera vez compila: publica
#    km-core y km-transport en mavenLocal y ejecuta :km-desktop:shadowJar.
./km
```

`./km` no usa `gradlew run`: compila si falta el jar y ejecuta
`exec java -jar`, así que los argumentos, la señal y el código de salida son los
del cliente. En Windows, el equivalente es `km.cmd`. Gradle necesita **JDK 17 o
superior** (validado con 17 y con 27); el jar corre en JRE 11 o superior.

Los mismos pasos a mano, si prefieres controlar cada fase:

```sh
# 2. Publicar km-core, km-webrtc y km-transport en mavenLocal. NO es opcional:
#    este módulo los consume como artefactos publicados (0.1.0-SNAPSHOT), no como
#    proyectos del build. En TRES invocaciones y en este orden: webrtc y transport
#    dependen de core, y publicar juntos deja compilar un consumidor antes de que
#    core exista en mavenLocal.
./gradlew -Dorg.gradle.java.home=/ruta/a/tu/jdk-17-o-superior :km-core:publishToMavenLocal
./gradlew -Dorg.gradle.java.home=/ruta/a/tu/jdk-17-o-superior :km-webrtc:publishToMavenLocal
./gradlew -Dorg.gradle.java.home=/ruta/a/tu/jdk-17-o-superior :km-transport:publishToMavenLocal

# 3. Compilar el fat jar de un solo fichero
./gradlew -Dorg.gradle.java.home=/ruta/a/tu/jdk-17-o-superior \
          :km-desktop:shadowJar

# 4. Arrancar la TUI. Sin argumentos usa el relé desplegado.
java -jar km-desktop/build/libs/km-desktop.jar
```

Contra un relé propio, en vez del desplegado:

```sh
# En una terminal: el relé
./gradlew -Dorg.gradle.java.home=/ruta/a/tu/jdk-17-o-superior \
          :km-daemon:installDist
./km-daemon/build/install/km-daemon/bin/km-daemon

# En otra: la TUI apuntada a él (con el lanzador o con el jar)
./km --relay ws://127.0.0.1:8080/km
# o: java -jar km-desktop/build/libs/km-desktop.jar --relay ws://127.0.0.1:8080/km
```

El `km-daemon` **no enruta por ruta**: acepta el handshake WebSocket en
cualquier path, así que `/km` y `/ws` funcionan igual. Y `--relay` acepta
cualquier esquema que Ktor resuelva, `ws://` incluido.

Al arrancar por primera vez la TUI no encuentra identidad: ofrece **crear una**
(frase de paso nueva, dos veces, para confirmar) o **recuperar la identidad**
desde la frase de recuperación de 24 palabras. En los arranques siguientes pide
la frase de paso que abre el almacén. La identidad es lo que los contactos
necesitan para poder escribirte, así que ese paso no es opcional en la práctica.

### Frase de paso y frase de recuperación

No son lo mismo, y confundirlas es fácil:

- **Frase de paso**: cifra el almacén de **este** equipo (Argon2id + AES-GCM).
  Si se pierde, el almacén local no se puede abrir, pero la identidad no depende
  de ella para existir.
- **Frase de recuperación**: 24 palabras BIP39 que **derivan la identidad**; es
  la misma frase que muestra Android. Restaurarla reproduce la misma clave
  pública y el mismo `identityId`, pero **no** recupera contactos, sesiones ni
  mensajes, y **regenera las claves de dispositivo**: el peer verá un
  dispositivo nuevo y las sesiones se vuelven a establecer.

Sin almacén, la TUI elige primero el camino, antes de pedir nada:

```
[C] crear una identidad nueva en este equipo
[R] recuperar desde la frase de recuperación (24 palabras)
```

`Esc` vuelve a la elección mientras no se haya enviado nada. Al recuperar se pide
la frase de 24 palabras (solo se pinta el contador `n/24`, nunca las palabras),
una frase de paso **nueva** dos veces y, si ya había almacén, la confirmación
literal `SOBRESOBRESCRIBIR`. Con almacén existente, `R` (con el campo de frase de
paso vacío) abre la restauración y sobrescribe. Tras validar, la identidad se
escribe y la sesión se abre **en el acto** con esa frase de paso, sin reiniciar.

En modo líneas (`--no-tui`/`--accessible`) también se ofrece `[C]`/`[R]` cuando
**no** hay almacén. Restaurar **sobrescribiendo** un almacén existente solo está
en la TUI: el modo líneas es append-only y para scripts, y no se pone una
escritura destructiva detrás de un prompt sin pantalla. Si
`KM_KEYSTORE_PASSPHRASE` está puesta, se conserva el comportamiento de G1 (crear
sin preguntar), pensado para scripts y contenedores.

El directorio de claves de peers (`peers.json`) **tampoco existe al principio**,
y la TUI avisa por `stderr`:

```
sin claves de peers registradas (.../peers.json); los ACK entrantes no se podran autenticar. usa 'peer <identityIdHex> <claveBase64>'
```

Eso no es un error: sin la clave de firma de un peer, sus `ACK` llegan y se
descartan sin verificar. El comando `peer` es lo que la resuelve. Ver
[Bootstrap de contactos](#bootstrap-de-contactos--implementado-en-g1-con-limites).

---

## Install and run the TUI (instalación y ejecución)

De cero a la TUI, y de ahí a la APK Android. Esta sección es la vía práctica y
autocontenida; el detalle de teclas, pantallas y protocolo está en el resto del
documento y en el [README de la raíz](../README.md#install-and-run-the-tui-instalación-y-ejecución).

### Requisitos

| Qué | Mínimo | Nota |
|-----|--------|------|
| Sistema operativo | Linux, macOS, Windows 10+ | `./km` es `sh` POSIX (Linux/macOS); en Windows, `km.cmd`. **macOS no se ha ejecutado** en la validación: el script es POSIX y el jar es Java puro, pero no está medido. `km.cmd` tampoco se ha ejecutado en un runner Windows. |
| Terminal | Color, **≥60x20** | La TUI necesita direccionamiento de cursor y ese tamaño. Por debajo, sin tty o con `TERM=dumb`, se avisa y se usa el modo líneas. El **QR** pide **≥85x43** (85x43..88x46 sin marco; 89x47 y 100x50 con chrome completo). **Sin color no hay QR**: se degrada a texto y `.km`. |
| JDK para compilar | **17 o superior** | Medido con 17.0.2 y con 27. Ver [JDK: lo que se midió](#jdk-lo-que-se-midió). |
| JRE para ejecutar | **11 o superior** | El fat jar es bytecode major **55** = Java 11. |
| Gradle | **9.5.0** | Wrapper incluido en la raíz: no hay que instalar Gradle. |
| RAM libre | ≥4 GB (recomendación) | Ver [RAM y máquinas ajustadas](#ram-y-máquinas-ajustadas). |

#### JDK: lo que se midió

Este documento decía que Gradle «exige JDK 27». Comprobado el 2026-10-30, no es
así: con `KM_GRADLE_JAVA_HOME=/home/kemamada/jdk-17.0.2 KM_REBUILD=1 ./km --help`
el build termina con **exit 0**, sin warnings de Gradle ni de Kotlin, y arranca la
TUI. Gradle **9.5.0**, Kotlin **2.2.10**, toolchain 11, daemon JVM 17, bytecode
major 55. El mensaje del lanzador que pedía «JDK 27» era una suposición del script
y ya está corregido.

> **JDK 17+ para compilar; JRE 11+ para ejecutar. Validado también con 27.**

El JDK 17 sirve igual para la app Android; el APK se validó con el 27 porque es el
que está instalado en la máquina de validación.

### Clonar y arrancar

```sh
# Linux / macOS
git clone https://github.com/KemaMada/km-framework.git
cd km-framework
./km
```

```bat
:: Windows 10+
git clone https://github.com/KemaMada/km-framework.git
cd km-framework
km.cmd
```

La **primera vez** el lanzador publica `km-core`, `km-webrtc` y `km-transport` en
`mavenLocal()` (tres invocaciones a propósito, en ese orden), ejecuta
`:km-desktop:shadowJar` y arranca `java -jar` con tus argumentos. Después solo
reconstruye si falta el jar, si `KM_REBUILD=1`, o si hay fuentes o ficheros de
build más nuevos. La salida del build va a **stderr**; stdout es de la TUI.

| Variable | Efecto |
|----------|--------|
| `KM_JAVA=<ruta>` | JVM que ejecuta el jar (por defecto `java` del `PATH`). |
| `KM_GRADLE_JAVA_HOME=<ruta>` | JDK que ejecuta Gradle (por defecto `JAVA_HOME`); 17+. |
| `KM_REBUILD=1` | Fuerza el build aunque el jar exista. |

### Flags

| Flag | Efecto |
|------|--------|
| `--tui` | TUI de pantallas (defecto). |
| `--accessible` | Salida lineal append-only, sin repintado: lectores de pantalla. |
| `--no-tui` | REPL de líneas de G1 (scripts y emergencia). |
| `--ascii` | Sin glifos Unicode ni color. |
| `--theme <color\|mono>` | `color` (defecto, respeta `NO_COLOR`/`TERM`) o `mono`, sin SGR: el QR se degrada a texto/`.km`. |
| `--mouse <auto\|on\|off>` | Ratón (clic izquierdo y rueda). `auto` (defecto) solo con TUI viable y terminal con soporte; `off` devuelve la selección nativa; `on` con `--accessible`/`--no-tui` es error de uso (exit 2). |
| `--wheel-rows <1..10>` | Filas por muesca de rueda (defecto 3). |
| `--bootstrap <url>` | Bootstrap, **repetible** y en orden. También `KM_BOOTSTRAPS` o `KM_BOOTSTRAP_URL`. |

El detalle de cada opción, y el resto (`--relay`, `--relay-identity`,
`--keystore`, `--peers`, `--reset`, `--verbose`), en [Opciones](#opciones) y en
`./km --help`.

### P2P opcional

Relay-only por defecto. `KM_DESKTOP_P2P=off|auto|on`; **`auto` exige TURN** (con
solo STUN o sin ICE se queda en relay). Las `KM_ICE_*`
(`KM_ICE_STUN_URLS`, `KM_ICE_TURN_URLS`, `KM_ICE_TURN_SECRET`,
`KM_ICE_TURN_USERNAME`, `KM_ICE_TURN_CREDENTIAL`, `KM_ICE_TURN_TTL_SECONDS`) y el
presupuesto de 8 s están en [`docs/developer/p2p.md`](../docs/developer/p2p.md).
Con `off` no se parsean. Los secretos **nunca** se imprimen.

### APK Android

Desde la raíz del repositorio (`app/` es un build aparte). Primero el SDK, con
`ANDROID_HOME` o un `app/local.properties` **no versionado**:

```sh
export ANDROID_HOME="$HOME/Android/Sdk"
# o: printf 'sdk.dir=%s\n' "$HOME/Android/Sdk" > app/local.properties
```

**Desarrollo (sin firma)**: no necesita keystore ni credenciales.

```sh
./gradlew -p app -Dorg.gradle.java.home=/ruta/a/tu/jdk-17-o-superior :assembleDebug
adb install -r app/build/outputs/apk/debug/KeyMessageApp-debug.apk
```

**Release firmado**: necesita el keystore `app/keymessage-release.jks` (PKCS12,
alias `keymessage`), que **no se versiona ni se distribuye**, y las credenciales
fuera del repo (`~/.gradle/gradle.properties` con modo 600, o variables de
entorno): `KM_RELEASE_STORE_PASSWORD`, `KM_RELEASE_KEY_PASSWORD` y, opcional,
`KM_RELEASE_KEY_ALIAS`.

```sh
cp /ruta/privada/keymessage-release.jks app/keymessage-release.jks && chmod 600 app/keymessage-release.jks

# publicar core y transport ANTES de la app, en orden e invocaciones separadas
./gradlew -Dorg.gradle.java.home=/ruta/a/tu/jdk-17-o-superior :km-core:publishToMavenLocal
./gradlew -Dorg.gradle.java.home=/ruta/a/tu/jdk-17-o-superior :km-transport:publishToMavenLocal

./gradlew -p app -Dorg.gradle.java.home=/ruta/a/tu/jdk-17-o-superior :assembleRelease
adb install -r app/build/outputs/apk/release/KeyMessageApp-release.apk
```

**`:assembleRelease` no funciona en un clon ajeno**: sin el `.jks` ni las
credenciales falla en `validateSigningRelease` con un mensaje que explica cómo
definirlas (sin imprimir valores). Es esperado; para probar usa `:assembleDebug`.

`versionCode 3`, `versionName "1.2"`, `minSdk 28`, `targetSdk 36`. Certificado
esperado, SHA-256 `af12fd06fc0945b7e8c778714f7742eb7616cbcb09ea8fae0faec581f0d712d1`
(rotar la contraseña no cambia la clave ni el certificado). `adb install -r`
conserva datos; un `versionCode` menor falla con `INSTALL_FAILED_VERSION_DOWNGRADE`
y obliga a desinstalar (se pierde el almacén de identidad del teléfono).

### Troubleshooting

| Síntoma | Qué pasa | Qué hacer |
|---------|----------|-----------|
| `Unable to resolve host "keymessage.xyz"` | Sin DNS o sin salida 443; el relé por defecto es `wss://keymessage.xyz/km`. | Revisar la red o usar un relé propio: `./km --relay ws://127.0.0.1:8080/km`. |
| `PASSPHRASE_WRONG` | La frase no abre el almacén de **este** equipo; no es la frase de recuperación. | Repetir la frase de paso; si no se recuerda, `R` restaura con las 24 palabras (**sobrescribe**; contactos y mensajes no vuelven). |
| El anillo del icono Android sale recortado | El aro es parte del dibujo (88 de 108 px), mayor que la zona segura de 72/108 del icono adaptativo; el launcher lo recorta con su máscara. Con iconos temáticos (Android 13+) se pinta la capa monocroma y el aro desaparece. | Nada: es el comportamiento del icono adaptativo, no un fallo. |
| Terminal sin color y no hay QR | Con `--theme mono`, `--ascii` o `NO_COLOR` el QR saldría invertido. | Terminal con color; si no, `.km` o el texto que ofrece la pantalla. |

---

## Construir

`km-core` y `km-transport` se consumen como **artefactos publicados**, no como
proyectos del build. Por eso el primer paso es publicarlos:

```bash
# desde la raiz del mono-repo; Gradle necesita JDK 17+ (en la maquina de
# validacion era el 27, pero 17 tambien sirve). TRES invocaciones, en este orden:
# webrtc y transport dependen de core; publicar juntos (o saltarse webrtc) rompe
# el arranque en frio.
./gradlew -Dorg.gradle.java.home=/ruta/a/tu/jdk-17-o-superior :km-core:publishToMavenLocal
./gradlew -Dorg.gradle.java.home=/ruta/a/tu/jdk-17-o-superior :km-webrtc:publishToMavenLocal
./gradlew -Dorg.gradle.java.home=/ruta/a/tu/jdk-17-o-superior :km-transport:publishToMavenLocal

# luego, el cliente
./gradlew -Dorg.gradle.java.home=/ruta/a/tu/jdk-17-o-superior \
          :km-desktop:shadowJar
```

El resultado es un único jar con todo dentro:

```
km-desktop/build/libs/km-desktop.jar
java -jar km-desktop/build/libs/km-desktop.jar
```

Desde la raíz, `./km` hace esos pasos (publicar core, webrtc, transport;
compilar) solo si falta el jar y siempre en ese orden. El módulo también
se compila **aislado**, sin el resto del mono-repo, gracias a su propio
`settings.gradle.kts`, pero **solo después** de publicar los tres artefactos:

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

#### `installDist` o fat jar

Las dos vías funcionan y arrancan el mismo `main`; elige según el uso. Verificado
en el portátil de validación:

| Vía | Salida | Tamaño | Cuándo usarla |
|-----|--------|--------|---------------|
| `shadowJar` | `km-desktop/build/libs/km-desktop.jar` | **~36 MB** (medido el 2026-10-30), un solo fichero | Distribuir o copiar: `Main-Class: com.km.desktop.DesktopMainKt`, bytecode major **55** = Java 11. |
| `installDist` | `km-desktop/build/install/km-desktop/bin/km-desktop` | **~22 MB** en **34 jars** | Ejecutar en local o en desarrollo: launcher con su classpath, jars resueltos por Gradle. |

Ninguna es «la recomendada» de forma absoluta: el fat jar es más cómodo de mover,
y el launcher de `installDist` evita reempaquetar durante el desarrollo.

Las versiones de plugin de ese `settings.gradle.kts` son las mismas que el
catálogo de la raíz. Si divergieran, Gradle fallaría al incluir el módulo en el
build integrado por conflicto de plugin.

## Ejecutar

```bash
# recomendado: lanzador de la raíz del repo (compila si falta el jar)
./km
# Windows
km.cmd

# o el jar ya construido
java -jar km-desktop/build/libs/km-desktop.jar
# o, instalado
./km-desktop/build/install/km-desktop/bin/km-desktop
```

`./km` y `km.cmd` solo construyen cuando `km-desktop/build/libs/km-desktop.jar`
no existe; `KM_REBUILD=1` fuerza el paso de build aunque exista. El build escribe
en **stderr** y el cliente conserva stdout. Variables del lanzador:

| Variable | Efecto |
|---|---|
| `KM_REBUILD=1` | Fuerza el paso de build aunque el jar exista (Gradle decide qué recompilar). |
| `KM_JAVA=<ruta>` | JVM que ejecuta el jar; por defecto `java` del `PATH`. |
| `KM_GRADLE_JAVA_HOME=<ruta>` | JDK que ejecuta Gradle; por defecto `JAVA_HOME`. Debe ser **17+**. |

Al arrancar, si no hay identidad, la TUI ofrece crearla (frase de paso nueva,
dos veces) o recuperarla desde la frase de 24 palabras. Si ya existe, pide la
frase de paso que la abre; `R` con el campo vacío abre la restauración. Ver
[Frase de paso y frase de recuperación](#frase-de-paso-y-frase-de-recuperación).

Por defecto se abre la **TUI de pantallas**. Exige una terminal real de al menos
**60x20** con direccionamiento de cursor; si no la hay, se avisa y se usa el modo
líneas. Los tres modos comparten modelo, servicios y comandos:

| Modo | Cómo se pide | Para qué |
|---|---|---|
| TUI de pantallas | `./km` o `--tui` | Uso normal: contactos, conversación, importar/compartir, frase, diagnóstico. |
| Accesible | `--accessible` | Salida lineal append-only, sin repintado: lectores de pantalla y terminales sin cursor. |
| Líneas (REPL G1) | `--no-tui` | Scripts, emergencia y terminales que no soportan TUI. Los comandos son los de la tabla de `help`. |

### Opciones

Argumentos de línea de comandos. Son pocos a propósito: esto es una TUI, no un
CLI, y lo que se usa a menudo son las teclas y los comandos de dentro de la
sesión.

| Opción | Efecto |
|---|---|
| `--relay <endpoint>` | Relé alternativo. También se lee de `KM_RELAY_URL`. Sin él, `wss://keymessage.xyz/km`. |
| `--relay-identity <64hex>` | Identidad del relé que se anuncia en el `AUTH_REQUEST`. También se lee de `KM_RELAY_IDENTITY`. |
| `--keystore <ruta>` | Ruta del almacén de identidad. Por defecto, `$XDG_CONFIG_HOME/keymessage/identity.kmkey`, `%APPDATA%\keymessage\identity.kmkey` en Windows o `~/Library/Application Support/keymessage/identity.kmkey` en macOS. |
| `--bootstrap <url>` | Bootstrap del relé, **repetible** y en orden: se prueban uno detrás de otro hasta que uno responda. Por defecto, los de `KM_BOOTSTRAPS` (separados por coma) o `KM_BOOTSTRAP_URL`, y en su defecto `JvmRelayTransport.DEFAULT_ENDPOINT`. |
| `--peers <ruta>` | Ruta del directorio de claves de firma de los peers, del que sale el resolver que autentica los `ACK`. Por defecto `peers.json`, **junto** al almacén de identidad. |
| `--reset` | Borra el almacén **y** el directorio de peers antes de arrancar. |
| `--tui` | TUI de pantallas (defecto). |
| `--accessible` | Salida lineal, sin direccionamiento de cursor. |
| `--no-tui` | REPL de líneas de G1. |
| `--ascii` | Sin glifos Unicode ni color. |
| `--theme <color\|mono>` | `color` (defecto) deja decidir a `NO_COLOR`/`TERM`/`COLORTERM`; `mono` fuerza la capacidad mínima: ni un SGR, y el QR se degrada a texto/`.km`. |
| `--mouse <auto\|on\|off>` | Ratón: clic izquierdo y rueda. `auto` (defecto) = ON solo con TUI viable y terminal con soporte; `off` lo apaga (selección nativa); `on` con `--accessible`/`--no-tui` o sin soporte es **error de uso** (exit 2). |
| `--wheel-rows <1..10>` | Filas que desplaza una muesca de rueda. Defecto 3. |
| `--verbose` | Traza del transporte por **stderr**. |
| `-h`, `--help` | Ayuda, sin arrancar ni abrir la red. |

`--tui`, `--accessible` y `--no-tui` son **excluyentes**: pedir dos a la vez es un
error de uso, no «gana el último».

Un `identityId` mal escrito falla **al parsear**, con un mensaje de línea de
comandos, no veinte segundos después con un `AUTH_FAIL`. Un argumento
desconocido también: sale con código `2`.

### Teclas, pestañas y scroll

La TUI es **teclado primero**: toda acción tiene tecla y la barra inferior
recuerda las globales. Desde el change B también acepta **clic izquierdo y rueda**
opcionales (`--mouse auto|on|off`), que son un atajo de las mismas acciones, no
una capacidad aparte. `?` abre la ayuda de la pantalla en la que estés y declara
el valor **efectivo** del ratón (`ON`/`OFF`).

| Tecla | Qué hace |
|---|---|
| `Alt-1`, `Alt-2`, `Alt-3` | Cambian de **pestaña raíz**: INICIO, FRASE, DIAGNOSTICO. Cambiar de pestaña **no borra** el borrador de importación, el compositor, el portador compartido ni la frase. |
| `Tab` / `Shift-Tab` | Recorren los campos enfocables: barra de pestañas, lista, filtro, compositor. La pestaña activa se marca con `▌`/`>` además de color. |
| `Enter` | Activa la pestaña apuntada, abre el contacto seleccionado o envía el mensaje del compositor. |
| `Esc` | Retrocede dentro de la pestaña (CONVERSACION → INICIO, RESTAURAR → FRASE). En la raíz de INICIO pide confirmación de salida. Nunca borra un borrador. |
| `↑`/`↓` o `j`/`k` | Mueven el foco de la lista; el scroll lo sigue (`ensureVisible`). |
| `PgUp` / `PgDn` | Desplazan la lista una página. |
| `Home` / `End` | Primera / última fila. En CONVERSACION, `End` vuelve a seguir el final del hilo. |
| `?` | Abre la ayuda del keymap, ventaneada; `PgUp`/`PgDn` la recorren y `?`/`Esc` la cierran. |
| `Ctrl-C` | La primera pulsación **arma** la salida; la segunda sale. |

**Scroll.** El desplazamiento es por lista (contactos, mensajes, logs) y tiene
indicador **textual** `[n/m]` —«estás viendo hasta la fila n de m»—, que solo
aparece cuando la lista **no cabe entera**. En CONVERSACION se sigue el final por
defecto; si subes, el hilo deja de saltar y los mensajes que llegan se anuncian
como `[N nuevos ↓]`. `End` restaura el seguimiento. Nada de esto depende del
color: se lee igual en MONO y con `--ascii`.

**Color y jerarquía.** Los colores son **roles** (marca, acento, error, aviso,
borde, selección…) resueltos según la capacidad del terminal: truecolor, 256,
16 o **MONO**. En MONO no se emite ni un SGR: la jerarquía la hacen `▌`, `>`,
`┃`, `─`, las mayúsculas de sección, la sangría y las etiquetas (`!`,
`[EXPIRADO]`, `P2P[x]`). Un error nunca se distingue solo por el tono. `NO_COLOR`,
`TERM=dumb`, una salida que no sea terminal, `--ascii` y `--theme mono` llevan a
MONO. **El QR exige color**: sin él se ofrecen `[T]` texto y `[W]` `.km` en vez de
pintar bloques que saldrían invertidos.

### Ratón (change B)

`--mouse auto|on|off` (defecto `auto`) activa el tracking **SGR 1006** (`?1000h`
press/release + `?1006h`) al entrar en la TUI y lo apaga (`?1000l?1006l`) **antes**
de salir de la pantalla alternativa y de devolver el cursor. Se apaga igual en
salida normal, EOF, `Ctrl-C`/SIGINT y excepción (el `shutdown hook` llama al mismo
`exit()`, que es idempotente).

| Zona | Clic izquierdo | Rueda |
|---|---|---|
| Ficha de INICIO (y su regla) | abre la conversación (como `↓`+`Enter`) | desplaza ±`--wheel-rows` |
| Pestaña (`INICIO`/`FRASE`/`DIAGNOSTICO`) | cambia de raíz (`TabTo`) | — |
| Burbuja de CONVERSACION | mueve el foco al mensaje | desplaza el hilo |
| Log de DIAGNOSTICO | mueve el foco a la línea | desplaza los logs |
| Compositor / filtro | da el foco al campo | no mueve |
| Botón `[ Sí ]` / `[ No ]` del diálogo | marca y activa esa opción | — |
| Pie (`[C] compartir`, `[W] .km`, `[X] exportar`…) | ejecuta esa acción | — |
| Fuera de la caja modal | cierra la capa superior (`Dismiss`) | — |

Un clic es **press y release en la misma región**: press en una fila y release en
otra no hace nada (arrastre descartado). El botón derecho y el medio no actúan.
La rueda **no cambia de campo** y respeta `ensureVisible` y `followEnd` (`[N
nuevos ↓]`); `End` reactiva el seguimiento.

**Selección nativa.** Mientras el tracking está activo, el terminal entrega el
arrastre a la app y **no** selecciona texto. `--mouse=off` la restaura; en la
mayoría de terminales `Shift`+arrastre es un bypass **del terminal** (no de la
app). La app no implementa selección propia.

**Compatibilidad.** Terminales sin SGR 1006 (`TERM=dumb`, consola Linux, sin TTY)
degradan a **OFF** sin bloquear el arranque; `--mouse=on` allí es error de uso
(exit 2). Windows Terminal está soportado; en **tmux** hace falta activar su
ratón (`set -g mouse on`), y en **conhost** el SGR puede no llegar: se declara y
se usa el teclado. Esta comprobación manual **no** está automatizada (no hay pty
real ni Windows en la suite).

**Fuera de alcance (declarado):** hover, doble clic, arrastre de filas,
redimensionar ventanas arrastrando, menús contextuales, toque/touch y selección
propia. No se activan `?1002`/`?1003`; ningún control depende de pasar el ratón
por encima. Además, un evento SGR de **movimiento** (bit 32) se descarta en el
decodificador: si un multiplexor lo reenvía, no se convierte en clic ni en
rueda. Un arrastre dentro de una MISMA región tampoco es distinguible con
`?1000` (se comporta como un clic, limitación del protocolo elegido).

### Límites conocidos de la TUI

- **Ratón sin hover, doble clic ni arrastre.** Hay clic izquierdo y rueda
  (`--mouse auto|on|off`); pasar el ratón no produce nada, el doble clic son dos
  clics (el primero ya abre el chat) y el arrastre no actúa. Tampoco hay menús
  contextuales, redimensionado por arrastre ni toque. Todo lo demás tiene tecla.
- **El tracking impide la selección nativa.** Es el precio de `?1000`; con
  `--mouse=off` vuelve, y `Shift`+arrastre es bypass del terminal. No hay
  selección propia: derecho y medio no actúan.
- **Sin temas configurables.** Hay una paleta por capacidad de color y `--theme
  mono`; no hay fichero de tema ni colores por pantalla.
- **El QR manda sobre las bandas.** De 85x43 a 88x46 el QR se pinta **completo y
  sin marco** (las 43 filas intactas). A 89x47 vuelve el **marco compacto**
  (borde, portador y acciones) aunque sin pestañas ni barra de estado; desde
  100x50 caben también las bandas completas. Por debajo de 85x43 se declara el
  mínimo y se ofrece texto/`.km`. En ningún caso se recorta el código ni se
  sustituyen filas. El código se pinta **centrado** y su fondo blanco cubre solo
  sus celdas (con el margen de silencio): no hay banda blanca hasta el borde.
- **Las listas son de ancho completo.** INICIO, CONVERSACION y DIAGNOSTICO usan
  todo el ancho de la terminal, como WhatsApp de escritorio: una fila por
  contacto (presencia, alias, último mensaje, hora y S/K) y burbujas alineadas
  por autor (`[ ... ]` a la derecha, `{ ... }` a la izquierda) con hora y estado
  fuera del marco. El `maxWidth` de 88/75/60 queda solo para los formularios
  (FRASE, RESTAURAR) y para los diálogos.
- **El backdrop de los diálogos no existe en MONO.** En color el modal oscurece
  el fondo; en MONO no se altera ni un atributo y la modalidad la dicen el marco,
  el título `CONFIRMAR` y los botones. Es una limitación del modo, no un fallo.
- **Los contactos y los mensajes son volátiles.** Viven en memoria: se pierden al
  reiniciar y el relé no guarda. La barra de estado lo avisa de forma permanente
  (`VOLÁTIL`) y la lista vacía lo repite en INICIO.
- **La conversación no pagina el hilo entero.** Se formatea solo la ventana
  visible (unos cientos de mensajes no cuestan más que quince); lo que queda
  fuera se alcanza desplazando, no se resume ni se corta por el centro.

### Variables de entorno

| Variable | Efecto |
|---|---|
| `KM_KEYSTORE_PASSPHRASE` | Frase de paso, sin preguntar. Comodidad con riesgo: queda en el entorno del proceso y a menudo en la historia del shell. Cuando está puesta, la TUI lo **avisa**. |
| `KM_RELAY_URL` | Endpoint por defecto (nombre histórico, **uno** solo). |
| `KM_BOOTSTRAPS` | **Varios** bootstraps separados por coma, en orden de preferencia. Gana a `KM_RELAY_URL`. Una entrada en blanco es un error de arranque, no un destino que se salta. |
| `KM_BOOTSTRAP_URL` | Alias en singular de `KM_BOOTSTRAPS`, para un destino. |
| `KM_RELAY_IDENTITY` | `identityId` del relé que se anuncia. |
| `NO_COLOR` | Desactiva el color, igual que `TERM=dumb` o una salida que no sea terminal. |

## Comandos

La tabla es el **modo líneas** (`--no-tui`), que se escribe en el prompt `km>`.
En la TUI de pantallas se navega con teclas (Tab/Shift-Tab, flechas o `j`/`k`,
Enter, Esc, `?` para la ayuda del keymap); las acciones equivalentes —importar,
compartir, abrir sesión, enviar, reintentar, descartar— se hacen desde las
pantallas.

| Comando | Qué hace |
|---|---|
| `connect [endpoint]` | Abre el WebSocket y hace el handshake `AUTH_REQUEST` → `AUTH_CHALLENGE` → `AUTH_RESPONSE` → `AUTH_OK`. Sin argumento usa `wss://keymessage.xyz/km`. |
| `identity` | `identityId`, huellas de las claves públicas y ruta del almacén. |
| `status` | Estado del transporte, `sessionId`, relé, intento y cuenta atrás de reconexión, último error, peers vistos, **pares con sesión cifrada** y cuántas claves de firma hay registradas. Las dos listas son distintas a propósito: «está en línea» no es «puedo escribirle». |
| `send <identityIdHex> <texto>` | Crea y envía un mensaje por la API de `KeyMessageCore`, **por el camino cifrado**. Sin sesión con ese par el resultado es `FAILED` y **no sale ningún byte**; la línea lo dice como `NO ENVIADO`, nunca como «pendiente». |
| `peer <identityIdHex> <claveBase64>` | Registra la clave de firma de un peer y la guarda en `peers.json`. Es lo que permite autenticar sus `ACK`. Rechaza una clave que no derive ese `identityId`. |
| `import <portador\|@ruta>` | Importa un contacto desde el portador comprimido o desde un fichero `.km`. El portador se verifica con el codec compartido de `km-core`. |
| `export [ruta]` | Escribe **tu** bundle `.km` (0 OPK) para compartirlo. |
| `announce <identityIdHex>` | Publica tu bundle por SIGNAL y, solo tras `Delivered`, abre sesión y entrega PREKEY. |
| `session <identityIdHex>` | Abre la sesión cifrada con un contacto ya importado. |
| `contacts` | Lista los contactos verificados (**solo en memoria**). |
| `history <identityIdHex>` | Historial conocido del par. |
| `resend <uuid>` | Reintenta un mensaje `EXPIRED` conservando su `messageId`. |
| `discard <uuid>` | Descarta un mensaje `EXPIRED`. |
| `help` | Ayuda. |
| `quit` | Desconecta y sale. |

Alias: `conectar`, `enviar`, `importar`, `exportar`, `anunciar`, `sesion`,
`contactos`, `historial`, `reintentar`, `descartar`, `id`, `q`, `exit`, `?`, `h`.

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
└───────────────┬──────────────────────────────────────────────┘
                │ implementa RelayTransport
                ▼
┌──────────────────────────────────────────────────────────────┐
│  km-transport  (artefacto, no submodule)                     │
│    JvmRelayTransport  ── KM-WIRE-RELAY sobre Ktor WS         │
│    RelayDataCodec     ── MESSAGE / ACK (secciones 10/11)     │
└───────────────┬──────────────────────────────────────────────┘
                │ lo compone RelayStackFactory
                ▼
┌──────────────────────────────────────────────────────────────┐
│  km-desktop                                                  │
│    DesktopMain · ui/ · shell/  ── TUI y modo líneas          │
│    client/  ── contacto, chat, recovery, diagnóstico         │
│    IdentityKeystore   ── Argon2id + AES-256-GCM              │
└──────────────────────────────────────────────────────────────┘
                                    │
                                    ▼
                    wss://keymessage.xyz/km  →  km-daemon
```

### Cómo queda cableado al framework

Desde p5 el escritorio es un **cliente delgado**: compone piezas que ya existen
y no implementa ninguna por su cuenta.

| Pieza | Dónde vive | Qué hace aquí |
|---|---|---|
| `RelayClientStack` | `:km-transport` | `RelayStackFactory.stackFor(motor)` compone motor + manager + drivers. `stack.backend === stack.transport`, comprobado. |
| `SecureSessionRegistry` + `PeerTransportManager` | `:km-core` | `CoreFactory.create(...)` los inyecta **juntos**, con `allowLegacyCleartextReceive = false`. Sin esa pareja el envío sale en claro. |
| `SecureSessionEstablishment` | `:km-core` | `SessionCoordinator.iniciar/aceptar` sobre `PeerContext.forContact`, que deriva la clave de ruteo de la **raíz** del bundle. |
| `ContactBundle.decodeAndVerify` | `:km-core` | Única entrada de un contacto. Lo que no verifica, no entra. |
| `DiagLog` | aquí | Ocho canales a **stderr**. `stdout` es el prompt de la TUI. |

La línea que resume la puerta anti-texto-plano: **sin sesión, el envío queda
`FAILED` y salen cero bytes** por los dos caminos que existen —el cifrado
(`TransportBackend.sendData`) y el legado (`Transport.send`).

### Lo que solo se cubre en ADB

Nada de lo siguiente tiene un test de JVM que lo demuestre, y no se presenta como
si lo tuviera:

| Qué | Por qué no hay test de JVM | Cómo se cierra |
|---|---|---|
| **Sesión E2E entre dos procesos `km-desktop`** por un socket real | El arnés de `:km-desktop` (`RelayProbe`) es un `TransportBackend` con cables de salida y de entrada reales, pero sin socket. El establecimiento, el `SecureFrame` cruzando el cable y la entrega con el mismo `messageId` y payload sí están cubiertos; la unión entre dos procesos, no. | Dos procesos reales + `km-daemon` |
| **Failover de endpoint en caliente** | `JvmRelayTransport` toma el endpoint en el **constructor** (`private val endpoint`), no como `var`. `Session.switchEndpoint` devuelve un `UnsupportedOperationException` observable para todo destino distinto del de arranque, en vez de fingir un reintento. `EndpointSequence` (recorrido ordenado y `BootstrapAllFailed`) sí está probado sobre un doble. | Exponer el endpoint en `:km-transport` |
| **`shadowJar` que linkea BouncyCastle sin clase ausente** | `CryptoProviderRuntimeTest` prueba **carga de clase** de los tres providers en el runtime de `:km-desktop`, no el enlace del jar final. | `shadowJar` + arranque real |
| **`BOOTSTRAP_ALL_FAILED` con DNS y TLS reales** | El arnés unitario no tiene red. La lógica de recorrido y el mensaje que lista los probados sí están cubiertos. | Arrancar contra hosts inalcanzables |
| **`AUTH` contra `km-daemon` desplegado, `PeerOnline`/`PeerOffline`** | Requiere el relé levantado; `RelayLegacyEchoTest` ya sube un `km-daemon` real, pero el `AUTH` del dialecto desplegado es otro proceso. | Log del proceso (`tee` / `adb logcat`) |


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
[Bootstrap de contactos](#bootstrap-de-contactos--implementado-en-g1-con-limites) para
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

### `JvmRelayTransport` (en `km-transport`) — el dialecto sobre el cable

Implementa `com.km.node.RelayTransport`. **Vive en `:km-transport`** desde p4
(`com.km.transport.JvmRelayTransport`); `km-desktop` no aloja copia: lo compone
con `RelayStackFactory` y `RelayClientStack`. El ciclo de vida del socket corre
en su propio `CoroutineScope`; `connect()` es síncrono porque `Transport` lo
declara así, y espera al `AUTH_OK` con un límite de 20 s.

| Frame | Dirección | Dónde se implementa |
|---|---|---|
| `AUTH_REQUEST` | cliente → relé | `km-transport` |
| `AUTH_CHALLENGE` | relé → cliente | `km-transport` |
| `AUTH_RESPONSE` | cliente → relé | `km-transport`, firmando con `Ed25519` de `km-core` el transcript de 152 B de `TranscriptBuilder` |
| `AUTH_OK` / `AUTH_FAIL` | relé → cliente | `km-transport` |
| `MESSAGE` | ambos | `RelayDataCodec` de `km-transport`, campo `data` en Base64 estándar |
| `ACK` | ambos | `RelayDataCodec` de `km-transport`; el relé **no** lo reenvía |
| `PING` / `PONG` | ambos | `km-transport`, correlacionados por `originalMessageId` |
| `PEER_ONLINE` / `PEER_OFFLINE` | relé → cliente | `km-transport` |
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

- **La carga del `Message` SÍ va cifrada en este camino.** Desde p5,
  `DesktopMain` compone `KeyMessageCoreImpl` con `secureSessionRegistry` y
  `PeerTransportManager` (`CoreFactory`), y `send` pasa por
  `SecureOutbound`/`SecureInbound` con ratchet y `SecureFrame`. Sin sesión con el
  par, `send` devuelve `FAILED` y **no sale ni un byte**
  (`SecureSendRejectionTest`); `DesktopSendCiphertextTest` comprueba que el texto
  no aparece en el cable, y la recepción en claro está cerrada
  (`allowLegacyCleartextReceive = false`). Lo que **no** existe es entrega
  garantizada: `SENT` solo significa que el socket local aceptó bytes, y sin
  `ACK` verificado no se pinta `ENTREGADO`.
- **Contactos y mensajes viven SOLO en memoria.** `InMemoryContactBook` e
  `InMemoryMessageStore`: se pierden al reiniciar y el relé no guarda nada. La
  TUI lo avisa de forma permanente en INICIO y en el modo líneas. La persistencia
  de contactos (`persist-contact-book`) está especificada y **no aplicada**; no
  se promete.
- **La TUI de pantallas no está auditada.** Está cubierta por tests puros
  (reducer, renderer, saneo, QR), por tests de bytecode (un solo escritor, sin
  crypto ni portador en `ui/`) y por la suite de `km-desktop`, pero **no** se ha
  probado en un `pty` real, ni en un terminal Windows, ni con revisión externa.
  El arranque interactivo, el resize real y la restauración por señal quedan
  como prueba manual.
- **La TUI repinta por filas, no el frame entero.** `ScreenWriter` guarda el
  último frame y escribe solo las filas que cambiaron (`ESC[<n>;1H` + texto +
  `ESC[K`); un frame idéntico no emite **ni un byte**. La comparación incluye
  texto, estilo, foco y paleta, de modo que un cambio solo de color repinta. Los
  casos que invalidan el diff —primer frame, resize, cambio de alto, tema o
  capacidad, `--ascii`, entrar o salir de la pantalla alternativa— fuerzan frame
  completo. Lo mide `ScreenWriterDiffTest`; el coste con 500 mensajes, un test
  que cuenta filas leídas y bytes escritos, no milisegundos.
- **Windows no se ha ejecutado.** `km.cmd`, las rutas de `ConfigPaths` y las ACL
  de `OwnerPermissions` están implementadas y declaradas, pero no hay runner
  Windows en este repositorio: se revisan por lectura, no por ejecución.
- **El QR se muestra, no se escanea.** El escritorio no tiene cámara por diseño.
  A 80x24 un portador de ~469 B no cabe (necesita 85x43): si el terminal es más
  pequeño, la pantalla lo declara y ofrece texto y `.km`. Entre 85x43 y 88x46 el
  QR se pinta **sin el marco de caja** (ocupa la pantalla entero), porque el
  marco se come 4 columnas y 4 filas y lo dejaría ilegible. A 89x47 vuelve el
  **marco compacto** (borde, portador y acciones; sin pestañas ni barra de
  estado), y desde 100x50 se suman las bandas completas. El código se pinta
  siempre entero: lo que se sacrifica es el chrome, nunca el QR.
- **El QR se pinta negro sobre blanco, no con el tema del terminal.** Los glifos
  `█ ▀ ▄` representan módulos **oscuros**: si el escritor los pintara con el
  primer plano claro y el fondo oscuro habituales, el QR saldría **invertido** y
  ninguna cámara lo detectaría. Por eso las filas del QR llevan una marca de
  paleta y sus celdas se pintan con `ESC[30;47m` (negro sobre blanco). El código
  va **centrado** en el ancho disponible y el `ESC[K` de fin de línea se emite con
  los atributos **ya reseteados**, de modo que el blanco cubre solo las celdas del
  QR —margen de silencio incluido— y no una banda hasta el borde de la terminal.
  Esto exige un **terminal con color**: con `--ascii`, `NO_COLOR`, `TERM=dumb` o
  una salida que no sea tty no se pintan bloques de QR (volvería a salir
  invertido) y la pantalla lo dice, ofreciendo `[T]` texto o `[W]` guardar `.km`.
  La geometría no cambia: 85x43..88x46 sin marco, 89x47 con marco compacto y
  100x50 con bandas completas.
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

## Bootstrap de contactos — implementado en G1, con limites

> **Estado: IMPLEMENTADO en G1.** `import`, `export`, `announce` y `session`
> existen en el REPL, y la TUI tiene las pantallas IMPORTAR (texto, `.km`, ver mi
> QR) y COMPARTIR. Límites que siguen abiertos: el escritorio **no escanea** QR
> (no tiene cámara); los contactos son **volátiles**; y el reenvío tras
> reconexión es acotado (un solo `contact_exchange`, 25 s) y su prueba en red real
> sigue pendiente. Lo que ya no es cierto es que falte el camino: el portador, la
> fábrica del bundle propio y la secuencia SIGNAL → PREKEY viven en el framework
> desde G1.

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
que luego se establece la sesión (X3DH + Double Ratchet). Esa parte **sí está
cableada** en el camino actual del escritorio (`SecureOutbound`/`SecureInbound`);
ver [Estado y límites honestos](#estado-y-límites-honestos).

### El camino reducido: una clave pública a pelo

Para un contacto técnico, o para probar, el comando `peer <identityIdHex>
<claveBase64>` registra una clave Ed25519 desnuda. Es deliberado que exista: sirve
para autenticar los `ACK` de un par sin haber intercambiado un bundle.

Esto tiene una consecuencia práctica y es a propósito: **registrar una clave a
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
| Codec del portador compartido (`CarrierCodec`: deflate + ISO-8859-1 + topes) y `CarrierFileCodec` | **IMPLEMENTADO** en `km-core` (G1) |
| Fábrica del bundle propio (`SelfBundleFactory`, 0 OPK para QR) | **IMPLEMENTADO** en `km-core` (G1) |
| Fichero `.km`, importación y exportación desde el escritorio | **IMPLEMENTADO** (G1): comandos `import`/`export` y pantallas IMPORTAR/COMPARTIR |
| `announce`/`session`: SIGNAL `contact_exchange` y PREKEY solo tras `Delivered` | **IMPLEMENTADO** en `km-transport` (G1), cableado en el escritorio |
| QR de contacto en el escritorio | **IMPLEMENTADO como SALIDA** con zxing-core; el escaneo no aplica (no hay cámara). Se pinta **negro sobre blanco** (contraste fijo, no el tema del terminal) y necesita color: con `--ascii`/`NO_COLOR` se degrada a texto/`.km`. Con viewport ≥ 85x43 el QR se pinta completo (sin marco a 85x43, con marco desde 89x47); por debajo se declara el mínimo y se ofrece texto/`.km` |
| Nombre visible por contacto | **NO EMPEZADO.** La lista muestra la huella del `identityId`, no un alias |
| Persistencia de contactos | **NO EMPEZADO.** `InMemoryContactBook`; `persist-contact-book` está especificado y no aplicado |
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
| `com.km:km-core` | 0.1.0-SNAPSHOT | MIT | framework: core, crypto, codec, modelo, portador |
| `com.km:km-transport` | 0.1.0-SNAPSHOT | MIT | motor del relé, `RelayClientStack` y orquestación del intercambio |
| `com.fasterxml.jackson.module:jackson-module-kotlin` | 2.15.2 | Apache-2.0 | la ejecuta `JsonRelayControlCodec` de `km-core` |
| `org.bouncycastle:bcprov-jdk18on` | 1.86 | MIT | Argon2id del almacén cifrado |
| `io.ktor:ktor-client-core` / `-cio` / `-websockets` | 3.1.3 | Apache-2.0 | transporte WebSocket |
| `org.jline:jline` | 3.30.17 | BSD-3-Clause / EPL-2.0 | terminal y edición de línea |
| `org.jline:jline-terminal-jna` | 3.30.17 | BSD-3-Clause | proveedor de terminal para Windows |
| `net.java.dev.jna:jna` | 5.19.1 | LGPL-2.1-or-later **o** Apache-2.0 (se elige Apache-2.0) | binding nativo que necesita JLine en Windows |
| `com.google.zxing:core` | 3.5.3 | Apache-2.0 | codificar el QR de salida del portador propio (no escanear) |
| `org.jetbrains.kotlinx:kotlinx-serialization-json` | 1.8.1 | Apache-2.0 | JSON del cable (solo API de árbol) |
| `org.jetbrains.kotlinx:kotlinx-coroutines-core` | 1.10.2 | Apache-2.0 | ciclo de vida del socket |
| `org.slf4j:slf4j-simple` | 2.0.17 | MIT | binding de SLF4J |

Las licencias de `zxing-core`, `jna` y `jline-terminal-jna` están verificadas en
su POM y registradas, con su texto cuando aplica, en
[`THIRD-PARTY-LICENSES/`](../THIRD-PARTY-LICENSES/).

`kotlinx-serialization` se usa **solo por su API de árbol**
(`buildJsonObject`, `Json.parseToJsonElement`), así que el módulo no necesita el
plugin del compilador de serialization y no arrastra un cuarto plugin.

El binding de SLF4J escribe en **stderr** y a nivel `warn`: stdout es el prompt
de la TUI, y una línea de log ahí parte la edición de la línea que el usuario
está escribiendo. La traza fina se activa con `--verbose`, que escribe por
stderr explícitamente.

## Licencia

MIT, como el resto del repositorio.
